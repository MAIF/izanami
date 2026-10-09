package fr.maif.izanami

import com.softwaremill.macwire.wire
import controllers.Assets
import controllers.AssetsComponents
import fr.maif.izanami.errors.IzanamiHttpErrorHandler
import fr.maif.izanami.services.{APIKeyService, AuthService, FeatureService, FeatureUsageService, PostgresTransactionProvider, RightService, TagService, TenantService, WebhookService}
import fr.maif.izanami.v1.WasmManagerClient
import fr.maif.izanami.web.*
import play.api.*
import play.api.ApplicationLoader.Context
import play.api.http.DefaultHttpFilters
import play.api.http.HttpErrorHandler
import play.api.libs.ws.ahc.AhcWSComponents
import play.api.mvc.EssentialFilter
import play.api.routing.Router
import play.filters.HttpFiltersComponents
import play.filters.cors.CORSConfig
import play.filters.cors.CORSFilter
import play.filters.csp.CSPComponents
import play.filters.csrf.CSRFFilter
import play.filters.gzip.GzipFilterComponents
import play.filters.https.RedirectHttpsComponents

import scala.concurrent.Await
import scala.concurrent.Future
import scala.concurrent.duration.DurationInt
import scala.concurrent.ExecutionContext
import fr.maif.izanami.datastores.ApiKeyDatastore
import fr.maif.izanami.datastores.WebhooksDatastore
import fr.maif.izanami.datastores.TenantsDatastore
import fr.maif.izanami.datastores.ProjectsDatastore
import fr.maif.izanami.datastores.TagsDatastore
import org.apache.pekko.actor.ActorSystem
import com.typesafe.config.ConfigFactory
import fr.maif.izanami.datastores.FeaturesDatastore
import fr.maif.izanami.env.Postgresql
import fr.maif.izanami.datastores.FeatureCallsDatastore
import fr.maif.izanami.datastores.FeatureContextDatastore
import fr.maif.izanami.datastores.UsersDatastore
import fr.maif.izanami.datastores.ConfigurationDatastore
import fr.maif.izanami.datastores.StatsDatastore
import fr.maif.izanami.datastores.ImportExportDatastore
import fr.maif.izanami.datastores.SearchDatastore
import fr.maif.izanami.datastores.PersonnalAccessTokenDatastore
import fr.maif.izanami.datastores.EventDatastore
import fr.maif.izanami.events.EventService
import fr.maif.izanami.jobs.WebhookListener
import fr.maif.izanami.mail.Mails
import fr.maif.izanami.security.JwtService

import javax.crypto.spec.SecretKeySpec
import fr.maif.izanami.wasm.{IzanamiWasmIntegrationContext, WasmRelatedStuff}
import io.otoroshi.wasm4s.scaladsl.WasmIntegration
import router.Routes

import java.time.Duration


case class ExpositionUrls(expositionUrl: String, backendUrl: Option[String])

class IzanamiLoader extends ApplicationLoader {
  Logger("IzanamiLoader")

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

  override val httpFilters: Seq[EssentialFilter] =
    super.httpFilters.filter {
      case _: CSRFFilter => false
      case _             => false
    } :+ corsFilter :+ /*cspFilter :+ redirectHttpsFilter :*/ gzipFilter
  override lazy val httpErrorHandler: HttpErrorHandler =
    wire[IzanamiHttpErrorHandler]

  implicit val typedConfig: IzanamiTypedConfiguration =
    IzanamiTypedConfiguration.from(
      ConfigUtil.fixIzanamiConfigIfNeeded(configuration.underlying)
    )

  override implicit lazy val actorSystem: ActorSystem = ActorSystem(
    "app-actor-system",
    ConfigFactory.empty
  );
  //implicit val ec: ExecutionContext = actorSystem.dispatcher

  val expositionUrl: String = typedConfig.app.exposition.url
    .getOrElse(s"http://localhost:${typedConfig.play.server.http.port}")
  val exposition = ExpositionUrls(expositionUrl=expositionUrl, backendUrl=typedConfig.app.exposition.url)

    val encryptionKey = new SecretKeySpec(
    typedConfig.app.authentication.tokenBodySecret
      .padTo(16, "0")
      .mkString("")
      .take(16)
      .getBytes,
    "AES"
  )
  val postgresql = Postgresql(typedConfig.app)

  // Datastores
  lazy val tenantDatastore: TenantsDatastore = new TenantsDatastore(postgresql = postgresql, eventService = eventService, extensionsSchema = typedConfig.app.pg.extensionsSchema)
  lazy val projectDatastore: ProjectsDatastore = new ProjectsDatastore(postgresql = postgresql, eventService = eventService)
  lazy val featureDatstore: FeaturesDatastore = new FeaturesDatastore(postgresql = postgresql,  extensionSchema = typedConfig.app.pg.extensionsSchema, projectDatastore = projectDatastore, tenantDatastore = tenantDatastore, featureContextDatastore = featureContextDatastore, eventService = eventService, wasmIntegration = wasmIntegration)
  lazy val featureCallDatastore: FeatureCallsDatastore = new FeatureCallsDatastore(postgresql = postgresql, tenantDatastore = tenantDatastore, callRetentionTimeInHours = typedConfig.app.feature.callRecords.callRetentionTimeInHours,houseKeepingStartDelayInSeconds = typedConfig.app.housekeeping.startDelayInSeconds,houseKeepingIntervalInSeconds = typedConfig.app.housekeeping.startDelayInSeconds, extensionsSchema = typedConfig.app.pg.extensionsSchema, actorSystem = actorSystem)
  lazy val tagDatastore: TagsDatastore = new TagsDatastore(postgresql = postgresql)
  lazy val apiKeyDatastore: ApiKeyDatastore = new ApiKeyDatastore(postgresql = postgresql)
  lazy val featureContextDatastore: FeatureContextDatastore = new FeatureContextDatastore(postgresql = postgresql,  extensionSchema = typedConfig.app.pg.extensionsSchema, featureDatastore=featureDatstore, eventService = eventService)
  lazy val userDatastore: UsersDatastore = new UsersDatastore(postgresql = postgresql, actorSystem = actorSystem, houseKeepingStartDelayInSeconds = typedConfig.app.housekeeping.startDelayInSeconds, houseKeepingIntervalInSeconds = typedConfig.app.housekeeping.intervalInSeconds, sessionsTtl = typedConfig.app.sessions.ttl, invitationsTtl = typedConfig.app.invitations.ttl, passwordResetRequestsTtl = typedConfig.app.passwordResetRequests.ttl)
  lazy val configurationDatastore: ConfigurationDatastore = new ConfigurationDatastore(postgresql = postgresql, tenantDatastore = tenantDatastore, eventService = eventService, maybeOidcConfig = typedConfig.app.openid, wasmoConf = typedConfig.app.wasmo)
  lazy val webhookDatastore: WebhooksDatastore = new WebhooksDatastore(postgresql = postgresql)
  lazy val statDatastore: StatsDatastore = new StatsDatastore(postgresql = postgresql, configurationDatastore = configurationDatastore, httpClient = wsClient, reportingUrl = typedConfig.app.reporting.url.toString, containerized = typedConfig.app.containerized, actorSystem = actorSystem)
  lazy val exportDatastore: ImportExportDatastore = new ImportExportDatastore(postgresql = postgresql,  extensionSchema = typedConfig.app.pg.extensionsSchema, featureDatastore = featureDatstore, eventService = eventService)
  lazy val searchDatastore: SearchDatastore = new SearchDatastore(postgresql = postgresql, similarityThreshold =  typedConfig.app.search.similarityThreshold, extensionsSchema = typedConfig.app.pg.extensionsSchema)
  lazy val personnalAccessTokenDatastore: PersonnalAccessTokenDatastore = new PersonnalAccessTokenDatastore(postgresql = postgresql)
  lazy val eventDatastore: EventDatastore = new EventDatastore(postgresql = postgresql, tenantDatastore = tenantDatastore, eventsHoursTtl = typedConfig.app.audit.eventsHoursTtl, houseKeepingStartDelayInSeconds = typedConfig.app.housekeeping.startDelayInSeconds, houseKeepingIntervalInSeconds = typedConfig.app.housekeeping.startDelayInSeconds, actorSystem = actorSystem)
  lazy val transactionProvider: PostgresTransactionProvider = new PostgresTransactionProvider(pool = postgresql.pool)


  lazy val wasmIntegration: WasmIntegration = WasmIntegration(
    new IzanamiWasmIntegrationContext(
      configurationDatastore = configurationDatastore,
      featureDatastore = featureDatstore,
      wasmConfiguration = typedConfig.app.wasm,
      httpClient = wsClient
    )
  )
  lazy val eventService = new EventService(
    featureService=featureService,
    projectDatastore=projectDatastore,
    postgresql=postgresql,
    eventDatastore=eventDatastore,
    wasmIntegration=wasmIntegration,
    wasmAllowed = typedConfig.app.feature.allowWasm
  )
  lazy val webhookListener = new WebhookListener(
    datastore = webhookDatastore,
    eventService = eventService,
    webhookRetryConfig = typedConfig.app.webhooks.retry,
    tenantDatastore = tenantDatastore,
    httpClient = wsClient
  )
  lazy val mails = new Mails(configurationDatastore = configurationDatastore, httpClient = wsClient, expositionUrl = expositionUrl)
  lazy val jwtService = new JwtService(secret = typedConfig.app.authentication.secret, encryptionKey = encryptionKey,expositionUrl=expositionUrl)

  
  lazy val rightService = new RightService(
    eventService = eventService,
    usersDatastore = userDatastore,
    configurationDatastore = configurationDatastore,
    openidConfiguration = typedConfig.app.openid,
    postgresql = postgresql
  )
  
  lazy val wasmRelatedStuff: WasmRelatedStuff = WasmRelatedStuff(integration = wasmIntegration, isWasmAllowed = typedConfig.app.feature.allowWasm)
  
  lazy val AuthService = new AuthService(personalAccessTokenDatastore = personnalAccessTokenDatastore, userDatastore = userDatastore, tokenSecret = typedConfig.app.authentication.secret, encryptionKey = encryptionKey)
  val cluster: Cluster = typedConfig.app.cluster
  val featureConfiguration: FeatureConfiguration = typedConfig.app.feature
  val expositionConfig: Exposition = typedConfig.app.exposition
 

  lazy val filters = new DefaultHttpFilters(httpFilters: _*)
  lazy val personnalAccessTokenTenantRightsActionFactory
      : PersonnalAccessTokenTenantRightsActionFactory =
    wire[PersonnalAccessTokenTenantRightsActionFactory]
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
  lazy val personnalAccessTokenDetailledRightForTenantFactory
      : PersonnalAccessTokenDetailledRightForTenantFactory =
    wire[PersonnalAccessTokenDetailledRightForTenantFactory]
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
  lazy val featureTokenAuthAction
      : PersonnalAccessTokenFeatureAuthActionFactory =
    wire[PersonnalAccessTokenFeatureAuthActionFactory]
  lazy val workerActionBuilder: WorkerActionBuilder = wire[WorkerActionBuilder]
  lazy val leaderActionBuilder: LeaderActionBuilderImpl =
    wire[LeaderActionBuilderImpl]

  lazy val tagService: TagService = wire[TagService]
  lazy val tenantService: TenantService = wire[TenantService]
  lazy val featureService: FeatureService = new FeatureService(datastore = featureDatstore, featureContextDatastore = featureContextDatastore, tagDatastore = tagDatastore, configuration = featureConfiguration, transactionProvider = transactionProvider, wasmRelatedStuff = wasmRelatedStuff)
  lazy val apiKeyService: APIKeyService = wire[APIKeyService]
  lazy val webhookService: WebhookService = wire[WebhookService]
  lazy val staleFeatureService: FeatureUsageService = new FeatureUsageService(featureCalls = featureCallDatastore, staleDelay = Duration.ofHours(typedConfig.app.feature.staleHoursDelay), isStatusTrackingActive = typedConfig.app.experimental.staleTracking.enabled, callRegistrationIntervalInSeconds = typedConfig.app.feature.callRecords.callRegisterIntervalInSeconds, actorSystem = actorSystem, ec = executionContext)

  lazy val featureController: FeatureController = wire[FeatureController]
  lazy val tenantController: TenantController = wire[TenantController]
  lazy val projectController: ProjectController = wire[ProjectController]
  lazy val tagController: TagController = wire[TagController]
  lazy val apiKeyController: ApiKeyController = wire[ApiKeyController]
  lazy val featureContextController: FeatureContextController =
    wire[FeatureContextController]
  lazy val userController: UserController = wire[UserController]
  lazy val loginController: LoginController = new LoginController(controllerComponents = controllerComponents, rightService = rightService, sessionAuthAction = sessionAuthAction, wsClient = wsClient, jwtService = jwtService, configurationDatastore = configurationDatastore, usersDatastore = userDatastore, postgresTransactionProvider = transactionProvider, openIdConfiguration = typedConfig.app.openid, sessions = typedConfig.app.sessions)
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
    wire[Routes]
  }

  def onStart(): Future[Unit] = {
    applicationLifecycle.addStopHook { () =>
      {
        for {
          _ <- featureDatstore.onStop()
          _ <- featureCallDatastore.onStop()
          _ <- tenantDatastore.onStop()
          _ <- projectDatastore.onStop()
          _ <- tagDatastore.onStop()
          _ <- apiKeyDatastore.onStop()
          _ <- featureContextDatastore.onStop()
          _ <- userDatastore.onStop()
          _ <- configurationDatastore.onStop()
          _ <- webhookDatastore.onStop()
          _ <- statDatastore.onStop()
          _ <- exportDatastore.onStop()
          _ <- searchDatastore.onStop()
          _ <- personnalAccessTokenDatastore.onStop()
          _ <- eventDatastore.onStop()
          _ <- postgresql.onStop()
          _ <- staleFeatureService.onStop()
          _ <- wasmIntegration.startF()
        } yield ()

      }
    }
    for {
      tenants <- tenantDatastore.readTenants().recover(_ => List())
      _ <- postgresql.onStart(tenants)
      _ <- featureDatstore.onStart()
      _ <- featureCallDatastore.onStart()
      _ <- tenantDatastore.onStart()
      _ <- projectDatastore.onStart()
      _ <- tagDatastore.onStart()
      _ <- apiKeyDatastore.onStart()
      _ <- featureContextDatastore.onStart()
      _ <- userDatastore.onStart()
      _ <- configurationDatastore.onStart()
      _ <- webhookDatastore.onStart()
      _ <- statDatastore.onStart()
      _ <- exportDatastore.onStart()
      _ <- searchDatastore.onStart()
      _ <- personnalAccessTokenDatastore.onStart()
      _ <- eventDatastore.onStart()
      _ <- staleFeatureService.onStart()
      _ <- wasmIntegration.stopF()
      _ = rightService.onStop()
      _ <- eventService.killAllSources(excludeIzanamiChannel = false)
    } yield ()
  }

  def corsFilter: CORSFilter = {
    new CORSFilter(CORSConfig.fromConfiguration(configuration))
  }

  /*def redirectHttpsFilter: RedirectHttpsFilter = {
    RedirectHttpsConfigurationProvider
    new RedirectHttpsFilter(RedirectHttpsConfiguration.(env.configuration))
  }*/

}
