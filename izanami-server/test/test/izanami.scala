package test

import controllers.actions.{AuthContext, SecuredAuthContext}
import domains.user.User
import modules.IzanamiComponentsInstances
import org.scalatest.concurrent.ScalaFutures
import org.scalatest.{MustMatchers, OptionValues, TestSuite, WordSpec}
import org.scalatestplus.play.components._
import play.api.ApplicationLoader.Context
import play.api._
import play.api.mvc.{ActionBuilder, _}

import scala.concurrent.{ExecutionContext, Future}

trait IzanamiSpec extends WordSpec with MustMatchers with OptionValues

class TestAuthAction(user: => User, val parser: BodyParser[AnyContent])(implicit val executionContext: ExecutionContext)
    extends ActionBuilder[AuthContext, AnyContent]
    with ActionFunction[Request, AuthContext] {

  override def invokeBlock[A](request: Request[A], block: (AuthContext[A]) => Future[Result]): Future[Result] =
    block(AuthContext(request, Some(user)))
}

class TestSecuredAuthAction(user: => User, val parser: BodyParser[AnyContent])(implicit val executionContext: ExecutionContext)
  extends ActionBuilder[SecuredAuthContext, AnyContent]
    with ActionFunction[Request, AuthContext] {

  override def invokeBlock[A](request: Request[A], block: (SecuredAuthContext[A]) => Future[Result]): Future[Result] =
    block(SecuredAuthContext(request, user))
}

class IzanamiTestComponentsInstances(context: Context, user: => User, conf: Configuration => Configuration)
    extends IzanamiComponentsInstances(context) {
  override def configuration = conf(super.configuration)

  override def authAction: ActionBuilder[AuthContext, AnyContent] =
    new TestAuthAction(user, defaultBodyParser)

  override def securedSecuredAuthContext: ActionBuilder[SecuredAuthContext, AnyContent] =
    new TestSecuredAuthAction(user, defaultBodyParser)
}

trait AddConfiguration {
  def getConfiguration(configuration: Configuration) = configuration
}

trait OneAppPerTestWithMyComponents extends OneAppPerTestWithComponents with ScalaFutures with AddConfiguration {
  this: TestSuite =>

  def user =
    User(id = "id", name = "Ragnar Lodbrok", email = "ragnar.lodbrok@gmail.com", admin = true, authorizedPattern = "*")

  def izanamiComponents =
    new IzanamiTestComponentsInstances(context, user, getConfiguration)

  override def components: BuiltInComponents = izanamiComponents

}

trait OneAppPerSuiteWithMyComponents extends OneAppPerSuiteWithComponents with ScalaFutures with AddConfiguration {
  this: TestSuite =>

  def user =
    User(id = "id", name = "Ragnar Lodbrok", email = "ragnar.lodbrok@gmail.com", admin = true, authorizedPattern = "*")

  def izanamiComponents =
    new IzanamiTestComponentsInstances(context, user, getConfiguration)

  override def components: BuiltInComponents = izanamiComponents

}

trait OneServerPerTestWithMyComponents extends OneServerPerTestWithComponents with ScalaFutures with AddConfiguration {
  this: TestSuite =>

  def user =
    User(id = "id", name = "Ragnar Lodbrok", email = "ragnar.lodbrok@gmail.com", admin = true, authorizedPattern = "*")

  def izanamiComponents =
    new IzanamiTestComponentsInstances(context, user, getConfiguration)

  override def components: BuiltInComponents = izanamiComponents

}

trait OneServerPerSuiteWithMyComponents
    extends OneServerPerSuiteWithComponents
    with ScalaFutures
    with AddConfiguration { this: TestSuite =>

  def user =
    User(id = "id", name = "Ragnar Lodbrok", email = "ragnar.lodbrok@gmail.com", admin = true, authorizedPattern = "*")

  def izanamiComponents =
    new IzanamiTestComponentsInstances(context, user, getConfiguration)

  override def components: BuiltInComponents = izanamiComponents
}
