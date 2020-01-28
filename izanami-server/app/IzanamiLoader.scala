import akka.actor.ActorSystem
import libs.logs.IzanamiLogger
import zio.{Runtime, Task}
import zio.interop.catz._
import com.codahale.metrics.MetricRegistry
import com.softwaremill.macwire._
import controllers._
import controllers.actions.{AuthAction, AuthContext, SecuredAction, SecuredAuthContext}
import domains.config.{ConfigDataStore, ConfigService}
import domains.{GlobalContext, Import}
import domains.abtesting.{ExperimentVariantEventService, _}
import domains.apikey.{ApiKeyDataStore, ApikeyService}
import domains.events._
import domains.feature.{FeatureDataStore, FeatureService}
import domains.script.Script.ScriptCache
import domains.script.{GlobalScriptDataStore, GlobalScriptService, PlayScriptCache}
import domains.user.{UserDataStore, UserService}
import domains.webhook.{WebhookDataStore, WebhookService}
import metrics.MetricsService
import libs.database.Drivers
import env._
import filters.{ZioIzanamiDefaultFilter, ZioOtoroshiFilter}
import handlers.ErrorHandler
import patches.Patchs
import play.api.ApplicationLoader.Context
import play.api._
import play.api.cache.ehcache.EhCacheComponents
import play.api.http.HttpErrorHandler
import play.api.libs.ws.ahc.AhcWSComponents
import play.api.mvc.{ActionBuilder, AnyContent, EssentialFilter}
import play.api.routing.Router
import play.libs.ws.ahc.AhcWSClient
import play.shaded.ahc.org.asynchttpclient.AsyncHttpClient
import router.Routes
import zio.internal.PlatformLive

class IzanamiLoader extends ApplicationLoader {

  def load(context: Context): Application = {
    LoggerConfigurator(context.environment.classLoader).foreach {
      _.configure(context.environment, context.initialConfiguration, Map.empty)
    }
    new modules.IzanamiComponentsInstances(context).application
  }
}

package object modules {
  import scala.concurrent.Future

  class IzanamiComponentsInstances(context: Context)
      extends BuiltInComponentsFromContext(context)
      with AssetsComponents
      with EhCacheComponents
      with AhcWSComponents {

    IzanamiLogger.info(s"Starting Izanami with java ${System.getProperty("java.version")}")

    lazy val izanamiConfig: IzanamiConfig            = IzanamiConfig(configuration)
    lazy val mayBeOauth2Config: Option[Oauth2Config] = izanamiConfig.oauth2.filter(_.enabled)

    implicit val system: ActorSystem = actorSystem

    IzanamiLogger.info(s"Configuration: \n$izanamiConfig")

    lazy val jwClient: play.libs.ws.WSClient = {
      new AhcWSClient(wsClient.underlying[AsyncHttpClient], materializer)
    }

    lazy val _env: Env = izanamiConfig.baseURL match {
      case "/" => wire[Env]
      case c =>
        val aFinder: AssetsFinder = assetsFinder
          .withUrlPrefix(s"$c/assets")
        wire[Env].copy(assetsFinder = aFinder)
    }

    val authAction: ActionBuilder[AuthContext, AnyContent]                       = wire[AuthAction]
    val securedSecuredAuthContext: ActionBuilder[SecuredAuthContext, AnyContent] = wire[SecuredAction]

    lazy val drivers: Drivers               = Drivers(izanamiConfig, configuration, applicationLifecycle)
    lazy val metricRegistry: MetricRegistry = wire[MetricRegistry]

    implicit lazy val playScriptCache: ScriptCache = new PlayScriptCache(defaultCacheApi)

    /* Event store */
    lazy val zioEventStore: EventStore =
      EventStore(izanamiConfig, drivers, configuration, applicationLifecycle)

    val globalContext: GlobalContext = GlobalContext(
      izanamiConfig,
      zioEventStore,
      actorSystem,
      materializer,
      environment,
      wsClient,
      jwClient,
      system.dispatcher,
      metricRegistry,
      drivers,
      playScriptCache,
      applicationLifecycle
    )

    implicit val runtime: Runtime[GlobalContext] = Runtime(globalContext, PlatformLive.Default)
    val patchs                                   = Patchs(izanamiConfig, drivers, applicationLifecycle)
    // Start stores
    runtime.unsafeRun(
      GlobalScriptDataStore.start
      *> ConfigDataStore.start *> FeatureDataStore.start
      *> UserDataStore.start *> ApiKeyDataStore.start
      *> WebhookDataStore.start *> ExperimentDataStore.start
      *> ExperimentVariantEventService.start *> WebhookService.startHooks(wsClient, izanamiConfig.webhook)
      *> MetricsService.start
      // Import files
      *> Import.importFile(izanamiConfig.globalScript.db, GlobalScriptService.importData())
      *> Import.importFile(izanamiConfig.config.db, ConfigService.importData())
      *> Import.importFile(izanamiConfig.features.db, FeatureService.importData())
      *> Import.importFile(izanamiConfig.apikey.db, ApikeyService.importData())
      *> Import.importFile(izanamiConfig.user.db, UserService.importData())
      *> Import.importFile(izanamiConfig.webhook.db, WebhookService.importData())
      *> Import.importFile(izanamiConfig.experiment.db, ExperimentService.importData())
      *> Import.importFile(izanamiConfig.experimentEvent.db, ExperimentVariantEventService.importData)
      *> patchs.run().ignore
    )

    applicationLifecycle.addStopHook { () =>
      Future(globalContext.prometheusRegistry.clear())
    }

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

    lazy val httpFilters: Seq[EssentialFilter] = izanamiConfig.filter match {
      case env.Otoroshi(config) =>
        IzanamiLogger.info("Using otoroshi filter")
        Seq(new ZioOtoroshiFilter(_env.environment.mode, config))
      case env.Default(config) =>
        IzanamiLogger.info("Using default filter")
        Seq(
          new ZioIzanamiDefaultFilter(_env.environment.mode,
                                      izanamiConfig.contextPath,
                                      izanamiConfig.metrics,
                                      config,
                                      izanamiConfig.apikey)
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
