package fr.maif.izanami.web

import fr.maif.izanami.env.Env
import fr.maif.izanami.errors.UserNotFound
import fr.maif.izanami.models.RightLevels.RightLevel
import fr.maif.izanami.models.{ApiKey, ApiKeyWithCompleteRights, UserWithCompleteRightForOneTenant, UserWithRights, UserWithTenantRights}
import fr.maif.izanami.security.JwtService.decodeJWT
import fr.maif.izanami.utils.syntax.implicits.BetterSyntax
import fr.maif.izanami.web.AuthAction.extractClaims
import pdi.jwt.JwtClaim
import play.api.libs.json.Json
import play.api.mvc.Results.{Forbidden, Unauthorized}
import play.api.mvc._

import javax.crypto.spec.SecretKeySpec
import scala.concurrent.{ExecutionContext, Future}

case class ClientKeyRequest[A](request: Request[A], key: ApiKeyWithCompleteRights)       extends WrappedRequest[A](request)
case class UserRequestWithCompleteRights[A](request: Request[A], user: UserWithRights)
    extends WrappedRequest[A](request)
case class UserRequestWithTenantRights[A](request: Request[A], user: UserWithTenantRights)
    extends WrappedRequest[A](request)
case class UserRequestWithCompleteRightForOneTenant[A](request: Request[A], user: UserWithCompleteRightForOneTenant)
    extends WrappedRequest[A](request)
case class UserNameRequest[A](request: Request[A], user: String)       extends WrappedRequest[A](request)
case class HookAndUserNameRequest[A](request: Request[A], user: String, hookName: String) extends WrappedRequest[A](request)
case class SessionIdRequest[A](request: Request[A], sessionId: String) extends WrappedRequest[A](request)


class ClientApiKeyAction(bodyParser: BodyParser[AnyContent], env: Env)(implicit ec: ExecutionContext)
  extends ActionBuilder[ClientKeyRequest, AnyContent] {
  override def parser: BodyParser[AnyContent]               = bodyParser
  override protected def executionContext: ExecutionContext = ec

  override def invokeBlock[A](request: Request[A], block: ClientKeyRequest[A] => Future[Result]): Future[Result] = {
    val maybeFutureKey =
      for (
        clientId     <- request.headers.get("Izanami-Client-Id");
        clientSecret <- request.headers.get("Izanami-Client-Secret")
      ) yield env.datastores.apiKeys.readAndCheckApiKey(clientId, clientSecret)

    maybeFutureKey
      .map(futureKey =>
        futureKey.flatMap(eitherKey => {
          eitherKey.fold(error => Future.successful(Unauthorized(Json.obj("message" -> "Invalid key"))), key => block(ClientKeyRequest(request, key)))
        })
      )
      .getOrElse(Future.successful(Unauthorized(Json.obj("message" -> "Invalid key"))))
  }
}

class TenantRightsAction(bodyParser: BodyParser[AnyContent], env: Env)(implicit
    ec: ExecutionContext
) extends ActionBuilder[UserRequestWithTenantRights, AnyContent] {
  override def parser: BodyParser[AnyContent]               = bodyParser
  override protected def executionContext: ExecutionContext = ec

  override def invokeBlock[A](
      request: Request[A],
      block: UserRequestWithTenantRights[A] => Future[Result]
  ): Future[Result] = {
    extractClaims(request, env.configuration.get[String]("app.authentication.secret"), env.encryptionKey)
      .flatMap(claims => claims.subject)
      .fold(Future.successful(Unauthorized("")))(subject => {
        env.datastores.users
          .findSessionWithTenantRights(subject)
          .flatMap {
            case None       => Unauthorized(Json.obj("message" -> "User is not connected")).future
            case Some(user) => block(UserRequestWithTenantRights(request, user))
          }
      })
  }
}

class DetailledAuthAction(bodyParser: BodyParser[AnyContent], env: Env)(implicit
    ec: ExecutionContext
) extends ActionBuilder[UserRequestWithCompleteRights, AnyContent] {
  override def parser: BodyParser[AnyContent]               = bodyParser
  override protected def executionContext: ExecutionContext = ec

  override def invokeBlock[A](
      request: Request[A],
      block: UserRequestWithCompleteRights[A] => Future[Result]
  ): Future[Result] = {
    extractClaims(request, env.configuration.get[String]("app.authentication.secret"), env.encryptionKey)
      .flatMap(claims => claims.subject)
      .fold(Future.successful(Unauthorized(Json.obj("message" -> "Invalid token"))))(subject => {
        env.datastores.users
          .findSessionWithCompleteRights(subject)
          .flatMap {
            case None       => UserNotFound(subject).toHttpResponse.future
            case Some(user) => block(UserRequestWithCompleteRights(request, user))
          }
      })
  }
}

class AdminAuthAction(bodyParser: BodyParser[AnyContent], env: Env)(implicit ec: ExecutionContext)
    extends ActionBuilder[UserNameRequest, AnyContent] {

  override def parser: BodyParser[AnyContent]               = bodyParser
  override protected def executionContext: ExecutionContext = ec

  override def invokeBlock[A](request: Request[A], block: UserNameRequest[A] => Future[Result]): Future[Result] = {
    extractClaims(request, env.configuration.get[String]("app.authentication.secret"), env.encryptionKey)
      .flatMap(claims => claims.subject)
      .fold(Future.successful(Unauthorized(Json.obj("message" -> "Invalid token"))))(subject => {
        env.datastores.users
          .findAdminSession(subject)
          .flatMap {
            case Some(username) => block(UserNameRequest(request = request, user = username))
            case None           => Future.successful(Forbidden(Json.obj("message" -> "User is not connected")))
          }
      })
  }
}

class AuthenticatedAction(bodyParser: BodyParser[AnyContent], env: Env)(implicit ec: ExecutionContext)
    extends ActionBuilder[UserNameRequest, AnyContent] {

  override def parser: BodyParser[AnyContent]               = bodyParser
  override protected def executionContext: ExecutionContext = ec

  override def invokeBlock[A](request: Request[A], block: UserNameRequest[A] => Future[Result]): Future[Result] = {
    extractClaims(request, env.configuration.get[String]("app.authentication.secret"), env.encryptionKey)
      .flatMap(claims => claims.subject)
      .fold(Future.successful(Unauthorized(Json.obj("message" -> "Invalid token"))))(sessionId =>
        env.datastores.users.findSession(sessionId).flatMap {
          case Some(username) => block(UserNameRequest(request = request, user = username))
          case None           => Future.successful(Unauthorized(Json.obj("message" -> "User is not connected")))
        }
      )
  }
}

class AuthenticatedSessionAction(bodyParser: BodyParser[AnyContent], env: Env)(implicit ec: ExecutionContext)
    extends ActionBuilder[SessionIdRequest, AnyContent] {

  override def parser: BodyParser[AnyContent]               = bodyParser
  override protected def executionContext: ExecutionContext = ec

  override def invokeBlock[A](request: Request[A], block: SessionIdRequest[A] => Future[Result]): Future[Result] = {
    extractClaims(request, env.configuration.get[String]("app.authentication.secret"), env.encryptionKey)
      .flatMap(claims => claims.subject)
      .fold(Future.successful(Unauthorized(Json.obj("message" -> "Invalid token"))))(sessionId =>
        block(SessionIdRequest(request = request, sessionId = sessionId))
      )
  }
}

class DetailledRightForTenantAction(bodyParser: BodyParser[AnyContent], env: Env, tenant: String)(implicit
    ec: ExecutionContext
) extends ActionBuilder[UserRequestWithCompleteRightForOneTenant, AnyContent] {

  override def parser: BodyParser[AnyContent]               = bodyParser
  override protected def executionContext: ExecutionContext = ec

  override def invokeBlock[A](
      request: Request[A],
      block: UserRequestWithCompleteRightForOneTenant[A] => Future[Result]
  ): Future[Result] = {
    extractClaims(request, env.configuration.get[String]("app.authentication.secret"), env.encryptionKey)
      .flatMap(claims => claims.subject)
      .fold(Future.successful(Unauthorized(Json.obj("message" -> "Invalid token"))))(subject => {
        env.datastores.users
          .findSessionWithRightForTenant(subject, tenant)
          .flatMap {
            case Left(err)   => err.toHttpResponse.toFuture
            case Right(user) => block(UserRequestWithCompleteRightForOneTenant(request = request, user = user))
          }
      })
  }
}

class TenantAuthAction(bodyParser: BodyParser[AnyContent], env: Env, tenant: String, minimumLevel: RightLevel)(implicit
    ec: ExecutionContext
) extends ActionBuilder[UserNameRequest, AnyContent] {

  override def parser: BodyParser[AnyContent]               = bodyParser
  override protected def executionContext: ExecutionContext = ec

  override def invokeBlock[A](request: Request[A], block: UserNameRequest[A] => Future[Result]): Future[Result] = {
    extractClaims(request, env.configuration.get[String]("app.authentication.secret"), env.encryptionKey)
      .flatMap(claims => claims.subject)
      .fold(Future.successful(Unauthorized(Json.obj("message" -> "Invalid token"))))(subject => {
        env.datastores.users
          .hasRightForTenant(subject, tenant, minimumLevel)
          .flatMap {
            case Some(username) => block(UserNameRequest(request = request, user = username))
            case None           => Future.successful(Forbidden(Json.obj("message" -> "User does not have enough rights for this operation")))
          }
      })
  }
}

class ProjectAuthAction(
    bodyParser: BodyParser[AnyContent],
    env: Env,
    tenant: String,
    project: String,
    minimumLevel: RightLevel
)(implicit ec: ExecutionContext)
    extends ActionBuilder[UserNameRequest, AnyContent] {

  override def parser: BodyParser[AnyContent]               = bodyParser
  override protected def executionContext: ExecutionContext = ec

  override def invokeBlock[A](request: Request[A], block: UserNameRequest[A] => Future[Result]): Future[Result] = {
    extractClaims(request, env.configuration.get[String]("app.authentication.secret"), env.encryptionKey)
      .flatMap(claims => claims.subject)
      .fold(Future.successful(Unauthorized(Json.obj("message" -> "Invalid token"))))(subject => {
        env.datastores.users
          .hasRightForProject(subject, tenant, project, minimumLevel)
          .flatMap(authorized =>
            authorized.fold(
              err => Future.successful(Results.Status(err.status)(Json.toJson(err))),
              maybeUser => {
                if (maybeUser.isDefined) {
                  block(UserNameRequest(request = request, user = maybeUser.get))
                } else {
                  Future.successful(Forbidden(Json.obj("message" -> "User does not have enough rights for this operation")))
                }
              }
            )
          )
      })
  }
}

class WebhookAuthAction(
                     bodyParser: BodyParser[AnyContent],
                     env: Env,
                     tenant: String,
                     webhook: String,
                     minimumLevel: RightLevel
                   )(implicit ec: ExecutionContext)
  extends ActionBuilder[HookAndUserNameRequest, AnyContent] {

  override def parser: BodyParser[AnyContent]               = bodyParser
  override protected def executionContext: ExecutionContext = ec

  override def invokeBlock[A](request: Request[A], block: HookAndUserNameRequest[A] => Future[Result]): Future[Result] = {
    extractClaims(request, env.configuration.get[String]("app.authentication.secret"), env.encryptionKey)
      .flatMap(claims => claims.subject)
      .fold(Future.successful(Unauthorized(Json.obj("message" -> "Invalid token"))))(subject => {
        env.datastores.users
          .hasRightForWebhook(subject, tenant, webhook, minimumLevel)
          .flatMap(authorized =>
            authorized.fold(
              err => Future.successful(Results.Status(err.status)(Json.toJson(err))),
              {
                case Some((username, hookName)) => block(HookAndUserNameRequest(request = request, user = username, hookName=hookName))
                case None           => Future.successful(Forbidden(Json.obj("message" -> "User does not have enough rights for this operation")))
              }
            )
          )
      })
  }
}

class KeyAuthAction(
    bodyParser: BodyParser[AnyContent],
    env: Env,
    tenant: String,
    key: String,
    minimumLevel: RightLevel
)(implicit ec: ExecutionContext)
    extends ActionBuilder[UserNameRequest, AnyContent] {

  override def parser: BodyParser[AnyContent]               = bodyParser
  override protected def executionContext: ExecutionContext = ec

  override def invokeBlock[A](request: Request[A], block: UserNameRequest[A] => Future[Result]): Future[Result] = {
    extractClaims(request, env.configuration.get[String]("app.authentication.secret"), env.encryptionKey)
      .flatMap(claims => claims.subject)
      .fold(Future.successful(Unauthorized(Json.obj("message" -> "Invalid token"))))(subject => {
        env.datastores.users
          .hasRightForKey(subject, tenant, key, minimumLevel)
          .flatMap(authorized =>
            authorized.fold(
              err => Future.successful(Results.Status(err.status)(Json.toJson(err))),
              {
                case Some(username) => block(UserNameRequest(request = request, user = username))
                case None           => Future.successful(Forbidden(Json.obj("message" -> "User does not have enough rights for this operation")))
              }
            )
          )
      })
  }
}

class DetailledRightForTenantFactory(bodyParser: BodyParser[AnyContent], env: Env)(implicit ec: ExecutionContext) {
  def apply(tenant: String): DetailledRightForTenantAction =
    new DetailledRightForTenantAction(bodyParser, env, tenant)
}

class KeyAuthActionFactory(bodyParser: BodyParser[AnyContent], env: Env)(implicit ec: ExecutionContext) {
  def apply(tenant: String, key: String, minimumLevel: RightLevel): KeyAuthAction =
    new KeyAuthAction(bodyParser, env, tenant, key, minimumLevel)
}

class WebhookAuthActionFactory(bodyParser: BodyParser[AnyContent], env: Env)(implicit ec: ExecutionContext) {
  def apply(tenant: String, webhook: String, minimumLevel: RightLevel): WebhookAuthAction =
    new WebhookAuthAction(bodyParser, env, tenant, webhook, minimumLevel)
}

class ProjectAuthActionFactory(bodyParser: BodyParser[AnyContent], env: Env)(implicit ec: ExecutionContext) {
  def apply(tenant: String, project: String, minimumLevel: RightLevel): ProjectAuthAction =
    new ProjectAuthAction(bodyParser, env, tenant, project, minimumLevel)
}

class TenantAuthActionFactory(bodyParser: BodyParser[AnyContent], env: Env)(implicit ec: ExecutionContext) {
  def apply(tenant: String, minimumLevel: RightLevel): TenantAuthAction =
    new TenantAuthAction(bodyParser, env, tenant, minimumLevel)
}

object AuthAction {
  def extractClaims[A](request: Request[A], secret: String, bodySecretKey: SecretKeySpec): Option[JwtClaim] = {
    request.cookies
      .get("token")
      .map(cookie => cookie.value)
      .map(token => decodeJWT(token, secret, bodySecretKey))
      .flatMap(maybeClaim => maybeClaim.toOption)
  }
}
