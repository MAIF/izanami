package controllers.actions

import akka.http.scaladsl.util.FastFuture
import domains.AuthInfo
import env.Env
import filters.OtoroshiFilter
import play.api.Logger
import play.api.mvc.Results._
import play.api.mvc._

import scala.concurrent.{ExecutionContext, Future}

case class AuthContext[A](request: Request[A], auth: Option[AuthInfo])
    extends WrappedRequest[A](request) {
  def authorizedPatterns: Seq[String] =
    auth.toList.flatMap(_.authorizedPattern.split(","))
}

class AuthAction(val env: Env, val parser: BodyParser[AnyContent])(
    implicit val executionContext: ExecutionContext)
    extends ActionBuilder[AuthContext, AnyContent]
    with ActionFunction[Request, AuthContext] {

  override def invokeBlock[A](
      request: Request[A],
      block: (AuthContext[A]) => Future[Result]): Future[Result] = {
    val maybeMaybeInfo: Option[Option[AuthInfo]] =
      request.attrs.get(OtoroshiFilter.Attrs.AuthInfo)
    maybeMaybeInfo.map { auth =>
      AuthContext(request, auth)
    } match {
      case Some(ctx) => block(ctx)
      case None =>
        Logger.info("Auth info is missing => Unauthorized")
        FastFuture.successful(Unauthorized)
    }
  }
}
