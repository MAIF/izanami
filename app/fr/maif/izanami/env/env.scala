package fr.maif.izanami.env

import akka.actor.{ActorSystem, Scheduler}
import akka.stream.Materializer
import com.typesafe.config.ConfigFactory
import fr.maif.izanami.datastores._
import fr.maif.izanami.events.EventService
import fr.maif.izanami.jobs.WebhookListener
import fr.maif.izanami.mail.Mails
import fr.maif.izanami.security.JwtService
import fr.maif.izanami.wasm.IzanamiWasmIntegrationContext
import fr.maif.izanami.{AppConf, PlayRoot}
import io.otoroshi.wasm4s.scaladsl.WasmIntegration
import play.api.libs.ws.WSClient
import play.api.{Configuration, Environment, Logger}

import javax.crypto.spec.SecretKeySpec
import scala.concurrent._

class Datastores(env: Env) {

  private implicit val ec: ExecutionContext = env.executionContext

  val features: FeaturesDatastore                         = new FeaturesDatastore(env)
  val featureCalls: FeatureCallsDatastore                 = new FeatureCallsDatastore(env)
  val tenants: TenantsDatastore                           = new TenantsDatastore(env)
  val projects: ProjectsDatastore                         = new ProjectsDatastore(env)
  val tags: TagsDatastore                                 = new TagsDatastore(env)
  val apiKeys: ApiKeyDatastore                            = new ApiKeyDatastore(env)
  val featureContext: FeatureContextDatastore             = new FeatureContextDatastore(env)
  val users: UsersDatastore                               = new UsersDatastore(env)
  val configuration: ConfigurationDatastore               = new ConfigurationDatastore(env)
  val webhook: WebhooksDatastore                          = new WebhooksDatastore(env)
  val stats: StatsDatastore                               = new StatsDatastore(env)
  val exportDatastore: ImportExportDatastore              = new ImportExportDatastore(env)
  val search: SearchDatastore                             = new SearchDatastore(env)
  val personnalAccessToken: PersonnalAccessTokenDatastore = new PersonnalAccessTokenDatastore(env)
  val events: EventDatastore                              = new EventDatastore(env)

  def onStart(): Future[Unit] = {
    for {
      _ <- users.onStart()
      _ <- events.onStart()
      _ <- featureCalls.onStart()
    } yield ()
  }

  def onStop(): Future[Unit] = {
    for {
      _ <- users.onStop()
      _ <- events.onStop()
      _ <- featureCalls.onStop()
    } yield ()
  }
}

class Env(
    val environment: Environment,
    val Ws: WSClient,
    val typedConfiguration: AppConf,
    val rawConfiguration: Configuration,
    playConfiguration: PlayRoot
) {
  // TODO variablize with izanami
  val logger: Logger                        = Logger("izanami")
  private val defaultSecret                 = typedConfiguration.defaultSecret
  val secret: String                        = typedConfiguration.secret
  val extensionsSchema: String              = typedConfiguration.pg.extensionsSchema
  val houseKeepingStartDelayInSeconds: Long = typedConfiguration.housekeeping.startDelayInSeconds
  val houseKeepingIntervalInSeconds: Long   = typedConfiguration.housekeeping.intervalInSeconds

  if (defaultSecret == secret) {
    logger.warn(
      "You're using Izanami default secret, which is not safe for production. Please generate a new secret and provide it to Izanami (see https://maif.github.io/izanami/docs/guides/configuration#secret for details)."
    )
  }

  lazy val encryptionKey = new SecretKeySpec(
    typedConfiguration.authentication.tokenBodySecret.padTo(16, "0").mkString("").take(16).getBytes,
    "AES"
  )

  lazy val expositionUrl = typedConfiguration.exposition.url
    .map(_.toString)
    .getOrElse(s"http://localhost:${playConfiguration.server.http.port}")

  val actorSystem: ActorSystem = ActorSystem(
    "app-actor-system",
    ConfigFactory.empty
  )

  implicit val executionContext: ExecutionContext = actorSystem.dispatcher
  val scheduler: Scheduler                        = actorSystem.scheduler
  val materializer: Materializer                  = Materializer(actorSystem)

  // init subsystems
  val eventService    = new EventService(this)
  val postgresql      = new Postgresql(this)
  val datastores      = new Datastores(this)
  val webhookListener = new WebhookListener(this, eventService)
  val mails           = new Mails(this)
  val jwtService      = new JwtService(this)
  val wasmIntegration = WasmIntegration(new IzanamiWasmIntegrationContext(this))
  val jobs            = new Jobs(this)

  val maybeOidcConfig = typedConfiguration.openid

  def isOIDCConfigurationEditable: Boolean = {
    (for (
      oidcConfig <- maybeOidcConfig;
      _          <- oidcConfig.clientId;
      _          <- oidcConfig.clientSecret;
      _          <- oidcConfig.authorizeUrl;
      _          <- oidcConfig.tokenUrl
    ) yield false)
      .getOrElse(true)
  }

  def oidcConfigurationMigration() = {
    datastores.configuration
      .readFullConfiguration()
      .map {
        case Left(err)            =>
        case Right(configuration) =>
          val maybeOauth = maybeOidcConfig.flatMap(o => o.toIzanamiOAuth2Configuration)
          maybeOauth.map(oauth => {
            datastores.configuration
              .updateConfiguration(configuration.copy(oidcConfiguration = Some(oauth)))
              .map(res => {
                logger.info("The OIDC configuration has been register in database from environments variables")
                res
              })
          })
      }
  }

  def onStart(): Future[Unit] = {
    for {
      _ <- postgresql.onStart()
      _ <- datastores.onStart()
      _ <- jobs.onStart()
      _ <- wasmIntegration.startF()
      _ <- webhookListener.onStart()
      _ <- oidcConfigurationMigration()
    } yield ()
  }

  def onStop(): Future[Unit] = {
    for {
      _ <- wasmIntegration.stopF()
      _ <- datastores.onStop()
      _ <- postgresql.onStop()
      _ <- jobs.onStop()
      _ <- eventService.killAllSources(excludeIzanamiChannel = false)
    } yield ()
  }
}
