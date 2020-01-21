package controllers.actions

import akka.http.scaladsl.util.FastFuture
import domains.AuthInfo
import env.Env
import filters.{FilterAttrs}
import libs.logs.IzanamiLogger
import play.api.mvc.Results._
import play.api.mvc._

import scala.concurrent.{ExecutionContext, Future}

case class AuthContext[A](request: Request[A], auth: Option[AuthInfo]) extends WrappedRequest[A](request) {
  def authorizedPatterns: Seq[String] =
    auth.toList.flatMap(_._authorizedPattern.split(","))
}

case class SecuredAuthContext[A](request: Request[A], authInfo: AuthInfo) extends WrappedRequest[A](request) {

  val auth: Option[AuthInfo] = Some(authInfo)

  def authorizedPatterns: Seq[String] =
    authInfo._authorizedPattern.split(",").toIndexedSeq
}

class AuthAction(val env: Env, val parser: BodyParser[AnyContent])(
    implicit val executionContext: ExecutionContext
) extends ActionBuilder[AuthContext, AnyContent]
    with ActionFunction[Request, AuthContext] {

  override def invokeBlock[A](request: Request[A], block: AuthContext[A] => Future[Result]): Future[Result] = {
    val maybeMaybeInfo: Option[Option[AuthInfo]] =
      request.attrs.get(FilterAttrs.Attrs.AuthInfo)
    maybeMaybeInfo.map { auth =>
      AuthContext(request, auth)
    } match {
      case Some(ctx) => block(ctx)
      case None =>
        IzanamiLogger.info("Auth info is missing => Unauthorized")
        FastFuture.successful(Unauthorized)
    }
  }
}

class SecuredAction(val env: Env, val parser: BodyParser[AnyContent])(
    implicit val executionContext: ExecutionContext
) extends ActionBuilder[SecuredAuthContext, AnyContent]
    with ActionFunction[Request, SecuredAuthContext] {

  override def invokeBlock[A](request: Request[A], block: SecuredAuthContext[A] => Future[Result]): Future[Result] = {
    val maybeMaybeInfo: Option[Option[AuthInfo]] =
      request.attrs.get(FilterAttrs.Attrs.AuthInfo)

    maybeMaybeInfo match {
      case Some(Some(info)) => block(SecuredAuthContext(request, info))
      case Some(None) =>
        IzanamiLogger.debug("Auth info is empty => Forbidden")
        FastFuture.successful(Forbidden)
      case _ =>
        IzanamiLogger.debug("Auth info is missing => Unauthorized")
        FastFuture.successful(Unauthorized)
    }
  }
}
