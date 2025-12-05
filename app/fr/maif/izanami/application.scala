package fr.maif.izanami

import com.softwaremill.macwire.wire
import controllers.{Assets, AssetsComponents}
import fr.maif.izanami.env.Env
import fr.maif.izanami.errors.IzanamiHttpErrorHandler
import fr.maif.izanami.services.{
  FeatureService,
  FeatureUsageService,
  RightService
}
import fr.maif.izanami.v1.WasmManagerClient
import fr.maif.izanami.web.*
import play.api.ApplicationLoader.Context
import play.api.*
import play.api.http.{DefaultHttpFilters, HttpErrorHandler}
import play.api.libs.ws.ahc.AhcWSComponents
import play.api.mvc.EssentialFilter
import play.api.routing.Router
import play.filters.HttpFiltersComponents
import play.filters.cors.{CORSConfig, CORSFilter}
import play.filters.csp.CSPComponents
import play.filters.csrf.CSRFFilter
import play.filters.gzip.GzipFilterComponents
import play.filters.https.RedirectHttpsComponents
import pureconfig.{ConfigReader, ConfigSource}
import router.Routes
import pureconfig.generic.semiauto.deriveReader

import scala.concurrent.duration.DurationInt
import scala.concurrent.{Await, Future}

class IzanamiLoader extends ApplicationLoader {
  private val logger = Logger("IzanamiLoader")

  def load(context: ApplicationLoader.Context): Application = {
    LoggerConfigurator(context.environment.classLoader).foreach {
      _.configure(context.environment, context.initialConfiguration, Map.empty)
    }
    val components = new IzanamiComponentsInstances(context)
    Await.result(components.onStart(), 120.seconds)
    components.application
  }
}

class IzanamiComponentsInstances(
    context: Context
) extends BuiltInComponentsFromContext(context)
    with AssetsComponents
    with AhcWSComponents
    with HttpFiltersComponents
    with CSPComponents
    with RedirectHttpsComponents
    with GzipFilterComponents {

  override lazy val httpFilters: Seq[EssentialFilter] =
    super.httpFilters.filter {
      case _: CSRFFilter => false
      case _             => false
    } :+ corsFilter :+ /*cspFilter :+ redirectHttpsFilter :*/ gzipFilter
  override lazy val httpErrorHandler: HttpErrorHandler =
    wire[IzanamiHttpErrorHandler]

  implicit lazy val typedConfig: IzanamiTypedConfiguration =
    IzanamiTypedConfiguration.from(configuration.underlying)

  implicit lazy val env: Env = new Env(
    environment = environment,
    Ws = wsClient,
    typedConfiguration = typedConfig.app,
    playConfiguration = typedConfig.play,
    rawConfiguration = configuration
  )

  lazy val filters = new DefaultHttpFilters(httpFilters: _*)

  lazy val authAction: TenantAuthActionFactory = wire[TenantAuthActionFactory]
  lazy val tenantAuthAction: ProjectAuthActionFactory =
    wire[ProjectAuthActionFactory]
  lazy val projectAuthActionById: ProjectAuthActionByIdFactory =
    wire[ProjectAuthActionByIdFactory]
  lazy val adminAuthAction: AdminAuthAction = wire[AdminAuthAction]
  lazy val keyAuthAction: KeyAuthActionFactory = wire[KeyAuthActionFactory]
  lazy val authenticatedAction: AuthenticatedAction = wire[AuthenticatedAction]
  lazy val detailledAuthAction: DetailledAuthAction = wire[DetailledAuthAction]
  lazy val detailledRightForTenantFactory: DetailledRightForTenantFactory =
    wire[DetailledRightForTenantFactory]
  lazy val tenantRightsAction: TenantRightsAction = wire[TenantRightsAction]
  lazy val sessionAuthAction: AuthenticatedSessionAction =
    wire[AuthenticatedSessionAction]
  lazy val wasmManagerClient: WasmManagerClient = wire[WasmManagerClient]
  lazy val clientApiKeyAction: ClientApiKeyAction = wire[ClientApiKeyAction]
  lazy val webhookAuthAction: WebhookAuthActionFactory =
    wire[WebhookAuthActionFactory]
  lazy val tokenOrCookieAuthActionForTenant
      : PersonnalAccessTokenTenantAuthActionFactory =
    wire[PersonnalAccessTokenTenantAuthActionFactory]
  lazy val tokenOrCookieAuthActionForProject
      : PersonnalAccessTokenProjectAuthActionFactory =
    wire[PersonnalAccessTokenProjectAuthActionFactory]
  lazy val tokenOrCookieAuthActionForKey
      : PersonnalAccessTokenKeyAuthActionFactory =
    wire[PersonnalAccessTokenKeyAuthActionFactory]
  lazy val adminTokenAuthAction: PersonnalAccessTokenAdminAuthActionFactory =
      wire[PersonnalAccessTokenAdminAuthActionFactory]
  lazy val featureTokenAuthAction: PersonnalAccessTokenFeatureAuthActionFactory =
    wire[PersonnalAccessTokenFeatureAuthActionFactory]

  lazy val featureService: FeatureService = wire[FeatureService]
  lazy val staleFeatureService: FeatureUsageService = wire[FeatureUsageService]
  lazy val rightService: RightService = wire[RightService]

  lazy val featureController: FeatureController = wire[FeatureController]
  lazy val tenantController: TenantController = wire[TenantController]
  lazy val projectController: ProjectController = wire[ProjectController]
  lazy val tagController: TagController = wire[TagController]
  lazy val apiKeyController: ApiKeyController = wire[ApiKeyController]
  lazy val featureContextController: FeatureContextController =
    wire[FeatureContextController]
  lazy val userController: UserController = wire[UserController]
  lazy val loginController: LoginController = wire[LoginController]
  lazy val configurationController: ConfigurationController =
    wire[ConfigurationController]
  lazy val pluginController: PluginController = wire[PluginController]
  lazy val importController: ImportController = wire[ImportController]
  lazy val legacyController: LegacyController = wire[LegacyController]
  lazy val eventController: EventController = wire[EventController]
  lazy val webhookController: WebhookController = wire[WebhookController]
  lazy val frontendController: FrontendController = wire[FrontendController]
  lazy val exportController: ExportController = wire[ExportController]
  lazy val searchController: SearchController = wire[SearchController]
  lazy val personnalAccessTokenController: PersonnalAccessTokenController =
    wire[PersonnalAccessTokenController]

  override lazy val assets: Assets = wire[Assets]
  lazy val router: Router = {
    // add the prefix string in local scope for the Routes constructor
    val prefix: String = "/"
    wire[Routes]
  }

  def onStart(): Future[Unit] = {
    applicationLifecycle.addStopHook { () =>
      {
        for {
          _ <- env.onStop()
          _ <- staleFeatureService.onStop()
        } yield ()

      }
    }
    for {
      _ <- env.onStart()
      _ <- staleFeatureService.onStart()
    } yield ()
  }

  def corsFilter: CORSFilter = {
    new CORSFilter(CORSConfig.fromConfiguration(env.rawConfiguration))
  }

  /*def redirectHttpsFilter: RedirectHttpsFilter = {
    RedirectHttpsConfigurationProvider
    new RedirectHttpsFilter(RedirectHttpsConfiguration.(env.configuration))
  }*/

}
