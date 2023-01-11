import akka.actor.ActorSystem
import com.softwaremill.macwire._
import controllers._
import controllers.actions.{AuthAction, AuthContext, SecuredAction, SecuredAuthContext}
import domains.{AuthorizedPatterns, Import, Key}
import domains.abtesting._
import domains.abtesting.events.ExperimentVariantEventService
import domains.apikey.{ApikeyDataStore, ApikeyService}
import domains.config.{ConfigDataStore, ConfigService}
import domains.configuration.GlobalContext
import domains.feature.{FeatureDataStore, FeatureService}
import domains.lock.{LockDataStore, LockService}
import domains.script.{GlobalScriptDataStore, GlobalScriptService}
import domains.user.{IzanamiUser, User, UserDataStore, UserService}
import domains.webhook.{WebhookDataStore, WebhookService}
import env._
import filters.{ZioIzanamiDefaultFilter, ZioOtoroshiFilter}
import handlers.ErrorHandler
import libs.logs.IzanamiLogger
import metrics.MetricsService
import play.api.{Application, ApplicationLoader, BuiltInComponentsFromContext, LoggerConfigurator, Mode}
import play.api.ApplicationLoader.Context
import play.api.cache.AsyncCacheApi
import play.api.cache.ehcache.EhCacheComponents
import play.api.http.HttpErrorHandler
import play.api.libs.ws.ahc.AhcWSComponents
import play.api.mvc.{ActionBuilder, AnyContent, EssentialFilter}
import play.api.routing.Router
import router.Routes
import zio.{RIO, Runtime, ZEnv, ZIO, ZLayer}

class IzanamiLoader extends ApplicationLoader {

  def logBadDefaultValue(key: String, envVar: String, shutdown: Boolean): Unit = {
    IzanamiLogger.error("")
    IzanamiLogger.error("#########################################################")
    IzanamiLogger.error("")
    IzanamiLogger.error(s"You are using the default value for config. '${key}' (or env. variable '${envVar}') is using the default value")
    IzanamiLogger.error(s"You MUST change the value in production with a secure random value to avoid severe security issues !")
    if (shutdown) IzanamiLogger.error("Shutting down JVM right now !")
    IzanamiLogger.error("")
    IzanamiLogger.error("#########################################################")
    IzanamiLogger.error("")
  }

  def load(context: Context): Application = {
    LoggerConfigurator(context.environment.classLoader).foreach {
      _.configure(context.environment, context.initialConfiguration, Map.empty)
    }
    val instances = new modules.IzanamiComponentsInstances(context)
    instances.izanamiConfig.filter match {
      case Otoroshi(cfg) if cfg.sharedKey == "none" =>
        logBadDefaultValue("izanami.filter.otoroshi.sharedKey", "CLAIM_SHAREDKEY", false)
      case Default(cfg) if cfg.sharedKey == "none" =>
        val shutdown = !instances._env.isPlayDevMode && cfg.failOnDefaultValue
        logBadDefaultValue("izanami.filter.default.sharedKey", "FILTER_CLAIM_SHAREDKEY", shutdown)
        if (shutdown) System.exit(-1)
      case _ => ()
    }
    instances.application
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

    IzanamiLogger.info(s"Configuration: \n${IzanamiConfig.withoutSecrets(izanamiConfig)}")
    IzanamiLogger.debug(s"Configuration with secrets: \n${izanamiConfig}")

    val _env: Env = izanamiConfig.baseURL match {
      case "/" => wire[Env]
      case c =>
        val formattedURL =
          if (c.endsWith("/")) c.dropRight(1)
          else c
        val aFinder: AssetsFinder =
          assetsFinder
            .withUrlPrefix(s"$formattedURL/assets")
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
    val initIzanami: ZIO[GlobalContext, Throwable, Unit] = (
      globalScriptStart
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
        *> Import.importFile(_.lock.db, LockService.importData())
        *> ensureTempUser()
    )

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
        Seq(new ZioOtoroshiFilter(_env.env, _env.contextPath, config))
      case env.Default(config) =>
        IzanamiLogger.info("Using default filter")
        Seq(
          new ZioIzanamiDefaultFilter(
            _env.env,
            _env.contextPath,
            izanamiConfig,
            izanamiConfig.metrics,
            config,
            izanamiConfig.apikey
          )
        )
    }

    lazy val router: Router = {
      val prefix: String = izanamiConfig.contextPath match {
        case "/" => izanamiConfig.contextPath
        case path =>
          if (path.endsWith("/"))
            path.dropRight(1)
          else path
      }
      IzanamiLogger.info(s"Initializing play router with prefix $prefix")
      wire[Routes].withPrefix(prefix)
    }

    override lazy val httpErrorHandler: HttpErrorHandler =
      new ErrorHandler(environment, configuration, None, Some(router))

    def ensureTempUser[Ctx <: domains.user.UserContext](): RIO[Ctx, Unit] = {
      val oauth2Enabled = _env.izanamiConfig.oauth2.exists(_.enabled)
      val otoroshiEnabled = _env.izanamiConfig.filter match {
        case Otoroshi(_) => true
        case _ => false
      }
      if (!oauth2Enabled && !otoroshiEnabled) {
        (for {
          count <- UserService.countWithoutPermissions(store.Query.oneOf("*"))
          _     <- if (count == 0) {
            val password = _env.izanamiConfig.user.initialize.password.orElse(Some(libs.IdGenerator.token(32)))
            val userId = s"admin@izanami.io"
            val user: IzanamiUser = IzanamiUser(
              id = userId,
              name = userId,
              email = userId,
              password = password,
              admin = true,
              temporary = true,
              authorizedPatterns = AuthorizedPatterns.All
            )
            UserService.createWithoutPermission(Key(userId), user).map { _ =>
              IzanamiLogger.warn(s"A new admin user has been created as none existed. You can login with ${user.email} / ${user.password.get}")
              IzanamiLogger.warn(s"Be sure to remember these values as it's the only time it will be displayed.")
              ()
            }
          } else {
            ZIO.succeed(())
          }
        } yield ()).mapError(_ => new RuntimeException("an error occurred"))
      } else {
        RIO.succeed(())
      }
    }
  }
}
