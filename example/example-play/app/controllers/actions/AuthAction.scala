package controllers.actions

import akka.http.scaladsl.util.FastFuture
import libs.AppLogger
import play.api.Logger
import play.api.mvc.Results.Unauthorized
import play.api.mvc._

import scala.concurrent.{ExecutionContext, Future}

case class SecuredContext[A](request: Request[A], userId: String) extends WrappedRequest[A](request)

class SecuredAction(val parser: BodyParser[AnyContent])(implicit val executionContext: ExecutionContext)
    extends ActionBuilder[SecuredContext, AnyContent]
    with ActionFunction[Request, SecuredContext] {

  override def invokeBlock[A](request: Request[A], block: (SecuredContext[A]) => Future[Result]): Future[Result] = {

    val maybeUserId = request.cookies.get("clientId")
    maybeUserId match {
      case Some(id) => block(SecuredContext(request, id.value))
      case None =>
        AppLogger.debug("Auth info is empty => Forbidden")
        FastFuture.successful(Unauthorized)
    }
  }
}
