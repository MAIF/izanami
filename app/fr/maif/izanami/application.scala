package fr.maif.izanami

import com.softwaremill.macwire.wire
import controllers.{Assets, AssetsComponents}
import fr.maif.izanami.env.Env
import fr.maif.izanami.errors.IzanamiHttpErrorHandler
import fr.maif.izanami.services.FeatureService
import fr.maif.izanami.v1.WasmManagerClient
import fr.maif.izanami.web._
import play.api.ApplicationLoader.Context
import play.api._
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
import router.Routes

import scala.concurrent.duration.DurationInt
import scala.concurrent.{Await, Future}

class IzanamiLoader extends ApplicationLoader {
  private val logger = Logger("IzanamiLoader")

  def load(context: ApplicationLoader.Context): Application = {
    LoggerConfigurator(context.environment.classLoader).foreach {
      _.configure(context.environment, context.initialConfiguration, Map.empty)
    }
    val components = new IzanamiComponentsInstances(context)
    Await.result(components.onStart(), 10.seconds)
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

  override lazy val httpFilters: Seq[EssentialFilter]  = super.httpFilters.filter {
    case _: CSRFFilter => false
    case _             => false
  } :+ corsFilter :+ /*cspFilter :+ redirectHttpsFilter :*/ gzipFilter
  override lazy val httpErrorHandler: HttpErrorHandler = wire[IzanamiHttpErrorHandler]

  implicit lazy val env: Env = new Env(
    configuration = configuration,
    environment = environment,
    Ws = wsClient
  )

  lazy val filters = new DefaultHttpFilters(httpFilters: _*)

  lazy val authAction                       = wire[TenantAuthActionFactory]
  lazy val tenantAuthAction                 = wire[ProjectAuthActionFactory]
  lazy val adminAuthAction                  = wire[AdminAuthAction]
  lazy val keyAuthAction                    = wire[KeyAuthActionFactory]
  lazy val authenticatedAction              = wire[AuthenticatedAction]
  lazy val detailledAuthAction              = wire[DetailledAuthAction]
  lazy val detailledRightForTenantFactory   = wire[DetailledRightForTenantFactory]
  lazy val tenantRightsAction               = wire[TenantRightsAction]
  lazy val sessionAuthAction                = wire[AuthenticatedSessionAction]
  lazy val wasmManagerClient                = wire[WasmManagerClient]
  lazy val clientApiKeyAction               = wire[ClientApiKeyAction]
  lazy val webhookAuthAction                = wire[WebhookAuthActionFactory]
  lazy val validatePasswordAction           = wire[ValidatePasswordActionFactory]
  lazy val tokenOrCookieAuthActionForTenant = wire[PersonnalAccessTokenTenantAuthActionFactory]

  lazy val featureService = wire[FeatureService]

  lazy val featureController              = wire[FeatureController]
  lazy val tenantController               = wire[TenantController]
  lazy val projectController              = wire[ProjectController]
  lazy val tagController                  = wire[TagController]
  lazy val apiKeyController               = wire[ApiKeyController]
  lazy val featureContextController       = wire[FeatureContextController]
  lazy val userController                 = wire[UserController]
  lazy val loginController                = wire[LoginController]
  lazy val configurationController        = wire[ConfigurationController]
  lazy val pluginController               = wire[PluginController]
  lazy val importController               = wire[ImportController]
  lazy val legacyController               = wire[LegacyController]
  lazy val eventController                = wire[EventController]
  lazy val webhookController              = wire[WebhookController]
  lazy val frontendController             = wire[FrontendController]
  lazy val exportController               = wire[ExportController]
  lazy val searchController               = wire[SearchController]
  lazy val personnalAccessTokenController = wire[PersonnalAccessTokenController]

  override lazy val assets: Assets = wire[Assets]
  lazy val router: Router = {
    // add the prefix string in local scope for the Routes constructor
    val prefix: String = "/"
    wire[Routes]
  }

  def onStart(): Future[Unit] = {
    applicationLifecycle.addStopHook { () =>
      env.onStop()
    }
    env
      .onStart()
  }

  def corsFilter: CORSFilter = {
    new CORSFilter(CORSConfig.fromConfiguration(env.configuration))
  }

  /*def redirectHttpsFilter: RedirectHttpsFilter = {
    RedirectHttpsConfigurationProvider
    new RedirectHttpsFilter(RedirectHttpsConfiguration.(env.configuration))
  }*/

}
