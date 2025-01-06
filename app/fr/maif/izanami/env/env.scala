package fr.maif.izanami.env

import org.apache.pekko.actor.ActorSystem
import org.apache.pekko.actor.Scheduler
import org.apache.pekko.stream.Materializer
import com.typesafe.config.ConfigFactory
import fr.maif.izanami.datastores._
import fr.maif.izanami.events.EventService
import fr.maif.izanami.jobs.WebhookListener
import fr.maif.izanami.mail.Mails
import fr.maif.izanami.security.JwtService
import fr.maif.izanami.wasm.IzanamiWasmIntegrationContext
import io.otoroshi.wasm4s.scaladsl.WasmIntegration
import play.api.Configuration
import play.api.Environment
import play.api.Logger
import play.api.libs.ws.WSClient

import javax.crypto.spec.SecretKeySpec
import scala.concurrent._

class Datastores(env: Env) {

  private implicit val ec: ExecutionContext = env.executionContext

  val features: FeaturesDatastore             = new FeaturesDatastore(env)
  val tenants: TenantsDatastore               = new TenantsDatastore(env)
  val projects: ProjectsDatastore             = new ProjectsDatastore(env)
  val tags: TagsDatastore                     = new TagsDatastore(env)
  val apiKeys: ApiKeyDatastore                = new ApiKeyDatastore(env)
  val featureContext: FeatureContextDatastore = new FeatureContextDatastore(env)
  val users: UsersDatastore                   = new UsersDatastore(env)
  val configuration: ConfigurationDatastore   = new ConfigurationDatastore(env)
  val webhook: WebhooksDatastore   = new WebhooksDatastore(env)
  val stats: StatsDatastore   = new StatsDatastore(env)
  val exportDatastore: ImportExportDatastore = new ImportExportDatastore(env)
  val search : SearchDatastore = new SearchDatastore(env)
  val personnalAccessToken : PersonnalAccessTokenDatastore = new PersonnalAccessTokenDatastore(env)
  val events : EventDatastore = new EventDatastore(env)

  def onStart(): Future[Unit] = {
    for {
      _ <- users.onStart()
      _ <- events.onStart()
    } yield ()
  }

  def onStop(): Future[Unit] = {
    for {
      _ <- users.onStop()
      _ <- events.onStop()
    } yield ()
  }
}

class Env(val configuration: Configuration, val environment: Environment, val Ws: WSClient) {
  // TODO variablize with izanami
  lazy val wasmCacheTtl: Int  =
    configuration.getOptional[Int]("app.wasm.cache.ttl").filter(_ >= 5000).getOrElse(60000)
  lazy val wasmQueueBufferSize: Int =
    configuration.getOptional[Int]("app.wasm.queue.buffer.size").getOrElse(2048)

  val logger: Logger             = Logger("izanami")
  val defaultSecret: String = configuration.get[String]("app.default-secret")
  val secret: String = configuration.get[String]("app.secret")

  if(defaultSecret == secret) {
    logger.warn("You're using Izanami default secret, which is not safe for production. Please generate a new secret and provide it to Izanami (see https://maif.github.io/izanami/docs/guides/configuration#secret for details).")
  }

  lazy val encryptionKey = new SecretKeySpec(
    configuration.get[String]("app.authentication.token-body-secret").padTo(16, "0").mkString("").take(16).getBytes,
    "AES"
  )

  lazy val expositionUrl: String = configuration.getOptional[String]("app.exposition.url")
    .getOrElse(s"http://localhost:${configuration.getOptional[Int]("http.port").getOrElse(9000)}")

  val actorSystem: ActorSystem = ActorSystem(
    "app-actor-system",
    configuration
      .getOptional[Configuration]("app.actorsystem")
      .map(_.underlying)
      .getOrElse(ConfigFactory.empty)
  )

  implicit val executionContext: ExecutionContext = actorSystem.dispatcher
  val scheduler: Scheduler                        = actorSystem.scheduler
  val materializer: Materializer                  = Materializer(actorSystem)

  // init subsystems
  val eventService = new EventService(this)
  val webhookListener = new WebhookListener(this, eventService)
  val postgresql = new Postgresql(this)
  val datastores = new Datastores(this)
  val mails      = new Mails(this)
  val jwtService = new JwtService(this)
  val wasmIntegration: WasmIntegration = WasmIntegration(new IzanamiWasmIntegrationContext(this))
  val jobs = new Jobs(this)

  def onStart(): Future[Unit] = {
    for {
      _ <- postgresql.onStart()
      _ <- datastores.onStart()
      _ <- jobs.onStart()
      _ <- wasmIntegration.startF()
      _ <- webhookListener.onStart()
    } yield ()
  }

  def onStop(): Future[Unit] = {
    for {
      _ <- wasmIntegration.stopF()
      _ <- datastores.onStop()
      _ <- postgresql.onStop()
      _ <- jobs.onStop()
    } yield ()
  }

  def isDev: Boolean = configuration.getOptional[String]("app.config.mode").exists(mode => mode.equals("dev"))
}
