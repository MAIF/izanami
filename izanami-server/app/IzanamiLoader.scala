import akka.actor.ActorSystem
import com.softwaremill.macwire._
import controllers._
import controllers.actions.{AuthAction, AuthContext, SecuredAction, SecuredAuthContext}
import domains.Import
import domains.abtesting._
import domains.abtesting.events.ExperimentVariantEventService
import domains.apikey.{ApikeyDataStore, ApikeyService}
import domains.config.{ConfigDataStore, ConfigService}
import domains.configuration.GlobalContext
import domains.feature.{FeatureDataStore, FeatureService}
import domains.lock.{LockDataStore, LockService}
import domains.script.{GlobalScriptDataStore, GlobalScriptService}
import domains.user.{UserDataStore, UserService}
import domains.webhook.{WebhookDataStore, WebhookService}
import env._
import filters.{ZioIzanamiDefaultFilter, ZioOtoroshiFilter}
import handlers.ErrorHandler
import libs.logs.IzanamiLogger
import metrics.MetricsService
import play.api.ApplicationLoader.Context
import play.api._
import play.api.cache.AsyncCacheApi
import play.api.cache.ehcache.EhCacheComponents
import play.api.http.HttpErrorHandler
import play.api.libs.ws.ahc.AhcWSComponents
import play.api.mvc.{ActionBuilder, AnyContent, EssentialFilter}
import play.api.routing.Router
import router.Routes
import zio.{Runtime, ZEnv, ZIO, ZLayer}

class IzanamiLoader extends ApplicationLoader {

  def load(context: Context): Application = {
    LoggerConfigurator(context.environment.classLoader).foreach {
      _.configure(context.environment, context.initialConfiguration, Map.empty)
    }
    new modules.IzanamiComponentsInstances(context).application
  }
}

package object modules {

  class IzanamiComponentsInstances(context: Context)
      extends BuiltInComponentsFromContext(context)
      with AssetsComponents
      with EhCacheComponents
      with AhcWSComponents {

    IzanamiLogger.info(s"Starting Izanami with java ${System.getProperty("java.version")}")

    lazy val izanamiConfig: IzanamiConfig            = IzanamiConfig.fromConfig(configuration)
    lazy val mayBeOauth2Config: Option[Oauth2Config] = izanamiConfig.oauth2.filter(_.enabled)

    implicit val system: ActorSystem    = actorSystem
    implicit val runtime: Runtime[ZEnv] = Runtime.default
    import zio.interop.catz._

    IzanamiLogger.info(s"Configuration: \n${IzanamiConfig.toStringWithoutSecrets(izanamiConfig)}")
    IzanamiLogger.debug(s"Configuration with secrets: \n${izanamiConfig}")

    lazy val _env: Env = izanamiConfig.baseURL match {
      case "/" => wire[Env]
      case c =>
        val aFinder: AssetsFinder = assetsFinder
          .withUrlPrefix(s"$c/assets")
        wire[Env].copy(assetsFinder = aFinder)
    }

    val authAction: ActionBuilder[AuthContext, AnyContent]                       = wire[AuthAction]
    val securedSecuredAuthContext: ActionBuilder[SecuredAuthContext, AnyContent] = wire[SecuredAction]

    private val izanamiCache: AsyncCacheApi = environment.mode match {
      case Mode.Test => cacheApi("izanami" + System.nanoTime())
      case _         => cacheApi("izanami")
    }

    private implicit val (
      globalContextLayer: ZLayer[zio.ZEnv, Throwable, GlobalContext],
      release: ZIO[zio.ZEnv, Throwable, Unit]
    ) =
      Runtime.default.unsafeRun(
        GlobalContext
          .live(
            actorSystem,
            materializer,
            izanamiCache,
            configuration,
            environment,
            wsClient,
            system.dispatcher,
            izanamiConfig,
            applicationLifecycle
          )
          .memoize
          .toResource
          .allocated
      )

    applicationLifecycle.addStopHook(() => runtime.unsafeRunToFuture(release))

    // Start stores
    val globalScriptStart: ZIO[GlobalContext, Throwable, Unit] = GlobalScriptDataStore.>.start
    val initIzanami: ZIO[GlobalContext, Throwable, Unit] = (globalScriptStart
      *> ConfigDataStore.>.start *> FeatureDataStore.>.start
      *> UserDataStore.>.start *> ApikeyDataStore.>.start
      *> WebhookDataStore.>.start *> ExperimentDataStore.>.start
      *> ExperimentVariantEventService.start *> WebhookService.startHooks(wsClient, izanamiConfig.webhook).unit
      *> MetricsService.start *> LockDataStore.>.start
      // Import files
      *> Import.importFile(_.globalScript.db, GlobalScriptService.importData())
      *> Import.importFile(_.config.db, ConfigService.importData())
      *> Import.importFile(_.features.db, FeatureService.importData())
      *> Import.importFile(_.apikey.db, ApikeyService.importData())
      *> Import.importFile(_.user.db, UserService.importData())
      *> Import.importFile(_.webhook.db, WebhookService.importData())
      *> Import.importFile(_.experiment.db, ExperimentService.importData())
      *> Import.importFile(_.experimentEvent.db, ExperimentVariantEventService.importData())
      *> Import.importFile(_.lock.db, LockService.importData()))

    runtime.unsafeRun(initIzanami.provideLayer(globalContextLayer))

    lazy val homeController: HomeController                 = wire[HomeController]
    lazy val globalScripController: GlobalScriptController  = wire[GlobalScriptController]
    lazy val configController: ConfigController             = wire[ConfigController]
    lazy val springConfigController: SpringConfigController = wire[SpringConfigController]
    lazy val featureController: FeatureController           = wire[FeatureController]
    lazy val apikeyController: ApikeyController             = wire[ApikeyController]
    lazy val userController: UserController                 = wire[UserController]
    lazy val authController: AuthController                 = wire[AuthController]
    lazy val webhookController: WebhookController           = wire[WebhookController]
    lazy val experimentController: ExperimentController     = wire[ExperimentController]
    lazy val healthCheckController: HealthCheckController   = wire[HealthCheckController]
    lazy val eventsController: EventsController             = wire[EventsController]
    lazy val searchController: SearchController             = wire[SearchController]
    lazy val backOfficeController: BackOfficeController     = wire[BackOfficeController]
    lazy val metricsController: MetricController            = wire[MetricController]
    lazy val oicController: OAuthController                 = wire[OAuthController]
    lazy val lockController: LockController                 = wire[LockController]

    lazy val httpFilters: Seq[EssentialFilter] = izanamiConfig.filter match {
      case env.Otoroshi(config) =>
        IzanamiLogger.info("Using otoroshi filter")
        Seq(new ZioOtoroshiFilter(_env.env, config))
      case env.Default(config) =>
        IzanamiLogger.info("Using default filter")
        Seq(
          new ZioIzanamiDefaultFilter(
            _env.env,
            izanamiConfig.contextPath,
            izanamiConfig.metrics,
            config,
            izanamiConfig.apikey
          )
        )
    }

    lazy val router: Router = {
      lazy val prefix: String = izanamiConfig.contextPath
      IzanamiLogger.info(s"Initializing play router with prefix $prefix")
      wire[Routes].withPrefix(prefix)
    }

    override lazy val httpErrorHandler: HttpErrorHandler =
      new ErrorHandler(environment, configuration, None, Some(router))
  }
}
