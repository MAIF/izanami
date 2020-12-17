package test

import akka.http.scaladsl.util.FastFuture
import controllers.actions.{AuthContext, SecuredAuthContext}
import domains.AuthorizedPatterns
import domains.user.{IzanamiUser, User}
import modules.IzanamiComponentsInstances
import org.scalactic.Prettifier
import org.scalatest.concurrent.ScalaFutures
import org.scalatest.matchers.{MatchResult, Matcher}
import org.scalatest._
import org.scalatestplus.play.components._
import play.api.ApplicationLoader.Context
import play.api._
import play.api.inject.ApplicationLifecycle
import play.api.libs.json.JsValue
import play.api.libs.ws.WSResponse
import play.api.mvc.{ActionBuilder, _}
import akka.{Done, NotUsed}
import akka.stream.scaladsl.Source
import domains.Domain.Domain
import domains.events.Events.IzanamiEvent
import domains.events.{EventStore, Events}
import domains.errors.IzanamiErrors
import zio.{ULayer, ZIO, ZLayer}

import scala.collection.mutable
import scala.collection.mutable.ArrayBuffer
import scala.concurrent.{ExecutionContext, Future}
import play.api.libs.ws.ahc.AhcWSComponents
import _root_.controllers.AssetsComponents
import akka.actor.ActorSystem
import akka.stream.Materializer
import domains.configuration.PlayModule
import domains.configuration.PlayModule.PlayModuleProd
import env.{
  ApiKeyHeaders,
  ApikeyConfig,
  ConfigConfig,
  DbConfig,
  DbDomainConfig,
  DbDomainConfigDetails,
  Default,
  DefaultFilter,
  ExperimentConfig,
  ExperimentEventConfig,
  FeaturesConfig,
  GlobalScriptConfig,
  InMemory,
  InMemoryEvents,
  InMemoryEventsConfig,
  InitialUserConfig,
  InitializeApiKey,
  IzanamiConfig,
  LogoutConfig,
  MetricsConfig,
  MetricsConsoleConfig,
  MetricsElasticConfig,
  MetricsHttpConfig,
  MetricsKafkaConfig,
  MetricsLogConfig,
  PatchConfig,
  UserConfig,
  WebhookConfig,
  WebhookEventsConfig
}
import play.api.cache.AsyncCacheApi
import play.libs.ws.ahc.AhcWSClient
import play.shaded.ahc.org.asynchttpclient.AsyncHttpClient
import play.api.libs.json.Json

import java.time.ZoneId
import scala.concurrent.duration._
import scala.reflect.ClassTag

trait IzanamiSpec extends WordSpec with MustMatchers with OptionValues with Inside {
  def run[Ctx <: zio.Has[_], E, A](ctx: ZLayer[Any, Throwable, Ctx])(toRun: ZIO[Ctx, E, A]): A =
    zio.Runtime.default.unsafeRun(toRun.provideLayer(ctx))
}

object IzanamiSpecObj extends IzanamiSpec

object FakeConfig {

  case class FakeAhcWSComponents(
      environment: Environment,
      applicationLifecycle: play.api.inject.ApplicationLifecycle,
      configuration: play.api.Configuration,
      executionContext: scala.concurrent.ExecutionContext,
      materializer: akka.stream.Materializer
  ) extends AhcWSComponents

  def playModule(actorSystem: ActorSystem, environment: Environment = Environment.simple()): ULayer[PlayModule] = {
    val configuration                                       = Configuration.load(environment)
    val executionContext: scala.concurrent.ExecutionContext = actorSystem.dispatcher
    val applicationLifecycle                                = FakeApplicationLifecycle()
    val materializer: Materializer                          = Materializer(actorSystem)
    val javaClient                                          = play.test.WSTestClient.newClient(-1)
    val ahcComponent: AhcWSComponents =
      FakeAhcWSComponents(environment, applicationLifecycle, configuration, executionContext, materializer)
    PlayModule.live(
      PlayModuleProd(
        actorSystem,
        materializer,
        new AsyncCacheApi {
          override def set(key: String, value: Any, expiration: Duration): Future[Done] = ???
          override def remove(key: String): Future[Done]                                = ???
          override def getOrElseUpdate[A](key: String, expiration: Duration)(orElse: => Future[A])(
              implicit evidence$1: ClassTag[A]
          ): Future[A]                                                                          = ???
          override def get[T](key: String)(implicit evidence$2: ClassTag[T]): Future[Option[T]] = ???
          override def removeAll(): Future[Done]                                                = ???
        },
        configuration,
        environment,
        ahcComponent.wsClient,
        javaClient,
        executionContext,
        applicationLifecycle
      )
    )
  }

  val dbConfig: DbDomainConfig = DbDomainConfig(InMemory, DbDomainConfigDetails("", None), None)

  val config: IzanamiConfig = IzanamiConfig(
    Some("dev"),
    "/",
    "/",
    false,
    false,
    "X-Forwarded-For",
    Some(ZoneId.systemDefault().getId),
    Default(DefaultFilter(Seq(), "", "", "", ApiKeyHeaders("", ""))),
    None,
    DbConfig(""),
    LogoutConfig(""),
    ConfigConfig(dbConfig),
    FeaturesConfig(dbConfig),
    GlobalScriptConfig(dbConfig),
    ExperimentConfig(dbConfig),
    ExperimentEventConfig(dbConfig),
    WebhookConfig(dbConfig, WebhookEventsConfig(5, 1.second, 1, 1.second)),
    UserConfig(dbConfig, InitialUserConfig("", "")),
    ApikeyConfig(dbConfig, InitializeApiKey(None, None, "*")),
    InMemoryEvents(InMemoryEventsConfig(500)),
    PatchConfig(dbConfig),
    MetricsConfig(
      false,
      false,
      refresh = 1.second,
      MetricsConsoleConfig(false, 1.second),
      MetricsLogConfig(false, 1.second),
      MetricsHttpConfig("json"),
      MetricsKafkaConfig(false, "", "", 1.second),
      MetricsElasticConfig(false, "", 1.second)
    )
  )
}

class TestAuthAction(user: => User, val parser: BodyParser[AnyContent])(implicit val executionContext: ExecutionContext)
    extends ActionBuilder[AuthContext, AnyContent]
    with ActionFunction[Request, AuthContext] {

  override def invokeBlock[A](request: Request[A], block: (AuthContext[A]) => Future[Result]): Future[Result] =
    block(AuthContext(request, Some(user)))
}

class TestSecuredAuthAction(user: => User, val parser: BodyParser[AnyContent])(
    implicit val executionContext: ExecutionContext
) extends ActionBuilder[SecuredAuthContext, AnyContent]
    with ActionFunction[Request, SecuredAuthContext] {

  override def invokeBlock[A](request: Request[A], block: (SecuredAuthContext[A]) => Future[Result]): Future[Result] =
    block(SecuredAuthContext(request, user))
}

class IzanamiTestComponentsInstances(context: Context, user: => User, conf: Configuration => Configuration)
    extends IzanamiComponentsInstances(context) {
  override def configuration = conf(super.configuration)

  override val authAction: ActionBuilder[AuthContext, AnyContent] =
    new TestAuthAction(user, defaultBodyParser)

  override val securedSecuredAuthContext: ActionBuilder[SecuredAuthContext, AnyContent] =
    new TestSecuredAuthAction(user, defaultBodyParser)
}

trait AddConfiguration {
  def getConfiguration(configuration: Configuration) = configuration
}

trait OneAppPerTestWithMyComponents extends OneAppPerTestWithComponents with ScalaFutures with AddConfiguration {
  this: TestSuite =>

  def user =
    IzanamiUser(
      id = "id",
      name = "Ragnar Lodbrok",
      email = "ragnar.lodbrok@gmail.com",
      admin = true,
      password = None,
      authorizedPatterns = AuthorizedPatterns.fromString("*")
    )

  def izanamiComponents =
    new IzanamiTestComponentsInstances(context, user, getConfiguration)

  override def components: BuiltInComponents = izanamiComponents

}

trait OneAppPerSuiteWithMyComponents extends OneAppPerSuiteWithComponents with ScalaFutures with AddConfiguration {
  this: TestSuite =>

  def user =
    IzanamiUser(
      id = "id",
      name = "Ragnar Lodbrok",
      email = "ragnar.lodbrok@gmail.com",
      admin = true,
      password = None,
      authorizedPatterns = AuthorizedPatterns.All
    )

  def izanamiComponents =
    new IzanamiTestComponentsInstances(context, user, getConfiguration)

  override def components: BuiltInComponents = izanamiComponents

}

trait OneServerPerTestWithMyComponents extends OneServerPerTestWithComponents with ScalaFutures with AddConfiguration {
  this: TestSuite =>

  def user =
    IzanamiUser(
      id = "id",
      name = "Ragnar Lodbrok",
      email = "ragnar.lodbrok@gmail.com",
      admin = true,
      password = None,
      authorizedPatterns = AuthorizedPatterns.All
    )

  def izanamiComponents =
    new IzanamiTestComponentsInstances(context, user, getConfiguration)

  override def components: BuiltInComponents = izanamiComponents

}

trait OneServerPerSuiteWithMyComponents
    extends OneServerPerSuiteWithComponents
    with ScalaFutures
    with AddConfiguration { this: TestSuite =>

  def user: User =
    IzanamiUser(
      id = "id",
      name = "Ragnar Lodbrok",
      email = "ragnar.lodbrok@gmail.com",
      admin = true,
      password = None,
      authorizedPatterns = AuthorizedPatterns.All
    )

  def izanamiComponents =
    new IzanamiTestComponentsInstances(context, user, getConfiguration)

  override def components: BuiltInComponents = izanamiComponents
}

trait IzanamiMatchers {

  def beAStatus(status: Int): Matcher[WSResponse] = new Matcher[WSResponse] {
    override def apply(left: WSResponse): MatchResult =
      MatchResult(
        left.status == status,
        s"${left.status} is not the same as $status (body is ${left.body})",
        s"${left.status} is the same as $status (body is ${left.body})",
        Vector()
      )
    override def toString: String = "be theStatus " + Prettifier.default(status)
  }

  def beAResponse(status: Int, value: JsValue): Matcher[WSResponse] = new Matcher[WSResponse] {
    override def apply(left: WSResponse): MatchResult =
      MatchResult(
        left.status == status && left.json == value,
        s"${left.status} is not the same as $status or the body ${left.body} is not the same as $value",
        s"${left.status} is the same as $status and the body ${left.body} is not the same as $value",
        Vector()
      )
    override def toString: String = "be theStatus " + Prettifier.default(status)
  }

}

case class FakeComponent(context: Context)
    extends BuiltInComponentsFromContext(context)
    with NoHttpFiltersComponents
    with AhcWSComponents
    with AssetsComponents {
  import play.api.mvc.Results
  import play.api.routing.Router
  import play.api.routing.sird._

  def wsJavaClient: play.libs.ws.WSClient =
    new AhcWSClient(wsClient.underlying[AsyncHttpClient], materializer)

  lazy val router: Router = Router.from({
    case GET(p"/") =>
      defaultActionBuilder {
        Results.Ok(Json.obj())
      }
  })
}

object FakeApplicationLifecycle {
  def apply(): ApplicationLifecycle = new FakeApplicationLifecycle()
}

class FakeApplicationLifecycle() extends ApplicationLifecycle {

  val hooks: mutable.Seq[() => Future[_]] = mutable.Seq.empty

  override def addStopHook(hook: () => Future[_]): Unit =
    hooks :+ hook

  override def stop(): Future[_] =
    FastFuture.successful(())
}

class TestEventStore(val events: ArrayBuffer[Events.IzanamiEvent] = mutable.ArrayBuffer.empty)
    extends EventStore.Service {
  import zio._

  def publish(event: IzanamiEvent): IO[IzanamiErrors, Done] = {
    events += event
    IO.succeed(Done)
  }

  def events(
      domains: Seq[Domain] = Seq.empty[Domain],
      patterns: Seq[String] = Seq.empty[String],
      lastEventId: Option[Long] = None
  ): Source[IzanamiEvent, NotUsed] =
    Source(events.toList)

  def check(): Task[Unit] = Task.succeed(())

}
