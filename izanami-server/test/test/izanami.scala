package test

import akka.http.scaladsl.util.FastFuture
import controllers.actions.{AuthContext, SecuredAuthContext}
import domains.AuthorizedPattern
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
import zio.{IO, Task}

import scala.collection.mutable
import scala.collection.mutable.ArrayBuffer
import scala.concurrent.{ExecutionContext, Future}
import zio.internal.PlatformLive
import zio.ZIO
import play.api.libs.ws.ahc.AhcWSComponents
import _root_.controllers.AssetsComponents
import play.libs.ws.ahc.AhcWSClient
import play.shaded.ahc.org.asynchttpclient.AsyncHttpClient
import play.api.libs.json.Json

trait IzanamiSpec extends WordSpec with MustMatchers with OptionValues with Inside {
  def run[Ctx, E, A](ctx: Ctx)(toRun: ZIO[Ctx, E, A]): A = zio.Runtime(ctx, PlatformLive.Default).unsafeRun(toRun)
}

object IzanamiSpecObj extends IzanamiSpec

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
    IzanamiUser(id = "id",
                name = "Ragnar Lodbrok",
                email = "ragnar.lodbrok@gmail.com",
                admin = true,
                password = "",
                authorizedPattern = AuthorizedPattern("*"))

  def izanamiComponents =
    new IzanamiTestComponentsInstances(context, user, getConfiguration)

  override def components: BuiltInComponents = izanamiComponents

}

trait OneAppPerSuiteWithMyComponents extends OneAppPerSuiteWithComponents with ScalaFutures with AddConfiguration {
  this: TestSuite =>

  def user =
    IzanamiUser(id = "id",
                name = "Ragnar Lodbrok",
                email = "ragnar.lodbrok@gmail.com",
                admin = true,
                password = "",
                authorizedPattern = AuthorizedPattern("*"))

  def izanamiComponents =
    new IzanamiTestComponentsInstances(context, user, getConfiguration)

  override def components: BuiltInComponents = izanamiComponents

}

trait OneServerPerTestWithMyComponents extends OneServerPerTestWithComponents with ScalaFutures with AddConfiguration {
  this: TestSuite =>

  def user =
    IzanamiUser(id = "id",
                name = "Ragnar Lodbrok",
                email = "ragnar.lodbrok@gmail.com",
                admin = true,
                password = "",
                authorizedPattern = AuthorizedPattern("*"))

  def izanamiComponents =
    new IzanamiTestComponentsInstances(context, user, getConfiguration)

  override def components: BuiltInComponents = izanamiComponents

}

trait OneServerPerSuiteWithMyComponents
    extends OneServerPerSuiteWithComponents
    with ScalaFutures
    with AddConfiguration { this: TestSuite =>

  def user =
    IzanamiUser(id = "id",
                name = "Ragnar Lodbrok",
                email = "ragnar.lodbrok@gmail.com",
                admin = true,
                password = "",
                authorizedPattern = AuthorizedPattern("*"))

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

class TestEventStore(val events: ArrayBuffer[Events.IzanamiEvent] = mutable.ArrayBuffer.empty) extends EventStore {
  import zio._

  def publish(event: IzanamiEvent): IO[IzanamiErrors, Done] = {
    events += event
    IO.succeed(Done)
  }

  def events(domains: Seq[Domain] = Seq.empty[Domain],
             patterns: Seq[String] = Seq.empty[String],
             lastEventId: Option[Long] = None): Source[IzanamiEvent, NotUsed] =
    Source(events.toList)

  def check(): Task[Unit] = Task.succeed(())

}
