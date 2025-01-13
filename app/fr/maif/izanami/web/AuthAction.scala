package fr.maif.izanami.web

import fr.maif.izanami.datastores.PersonnalAccessTokenDatastore.{TokenCheckFailure, TokenCheckSuccess}
import fr.maif.izanami.env.Env
import fr.maif.izanami.errors.UserNotFound
import fr.maif.izanami.events.EventAuthentication
import fr.maif.izanami.events.EventAuthentication.{BackOfficeAuthentication, TokenAuthentication}
import fr.maif.izanami.models.RightLevels.RightLevel
import fr.maif.izanami.models.{ApiKey, ApiKeyWithCompleteRights, RightLevels, TenantTokenRights, UserWithCompleteRightForOneTenant, UserWithRights, UserWithTenantRights}
import fr.maif.izanami.security.JwtService.decodeJWT
import fr.maif.izanami.utils.syntax.implicits.BetterSyntax
import fr.maif.izanami.web.AuthAction.{extractAndCheckPersonnalAccessToken, extractClaims}
import pdi.jwt.JwtClaim
import play.api.libs.json._
import play.api.mvc.Results.{BadRequest, Forbidden, Unauthorized}
import play.api.mvc._

import java.time.Duration
import java.util.concurrent.{Executors, TimeUnit}
import java.util.{Base64, UUID}
import javax.crypto.spec.SecretKeySpec
import scala.concurrent.{ExecutionContext, Future, Promise}

case class UserInformation(username: String, authentication: EventAuthentication)


case class ClientKeyRequest[A](request: Request[A], key: ApiKeyWithCompleteRights) extends WrappedRequest[A](request)
case class UserRequestWithCompleteRights[A](request: Request[A], user: UserWithRights, authentication: EventAuthentication)
    extends WrappedRequest[A](request)
case class UserRequestWithTenantRights[A](request: Request[A], user: UserWithTenantRights, authentication: EventAuthentication)
    extends WrappedRequest[A](request)
case class UserRequestWithCompleteRightForOneTenant[A](request: Request[A], user: UserWithCompleteRightForOneTenant, authentication: EventAuthentication)
    extends WrappedRequest[A](request)
case class UserNameRequest[A](request: Request[A], user: UserInformation)                   extends WrappedRequest[A](request)
case class ProjectIdUserNameRequest[A](request: Request[A], user: UserInformation, projectId: UUID) extends WrappedRequest[A](request)

case class HookAndUserNameRequest[A](request: Request[A], user: UserInformation, hookName: String)
    extends WrappedRequest[A](request)
case class SessionIdRequest[A](request: Request[A], sessionId: String)             extends WrappedRequest[A](request)

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
          eitherKey.fold(
            error => Future.successful(Unauthorized(Json.obj("message" -> "Invalid key"))),
            key => block(ClientKeyRequest(request, key))
          )
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
            case Some(user) => block(UserRequestWithTenantRights(request, user, BackOfficeAuthentication))
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
            case Some(user) => block(UserRequestWithCompleteRights(request, user, BackOfficeAuthentication))
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
            case Some(username) => block(UserNameRequest(request = request, UserInformation(username = username, authentication = BackOfficeAuthentication)))
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
          case Some(username) => block(UserNameRequest(request = request, UserInformation(username = username, authentication = BackOfficeAuthentication)))
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
            case Right(user) => block(UserRequestWithCompleteRightForOneTenant(request = request, user = user, authentication = BackOfficeAuthentication))
          }
      })
  }
}

class PersonnalAccessTokenTenantAuthAction(
    bodyParser: BodyParser[AnyContent],
    env: Env,
    tenant: String,
    minimumLevel: RightLevel,
    operation: TenantTokenRights
)(implicit
    ec: ExecutionContext
) extends ActionBuilder[UserNameRequest, AnyContent] {
  override def parser: BodyParser[AnyContent]               = bodyParser
  override protected def executionContext: ExecutionContext = ec

  override def invokeBlock[A](
      request: Request[A],
      block: UserNameRequest[A] => Future[Result]
  ): Future[Result] = {

    def maybeTokenAuth: Future[Either[Result, UserInformation]] = {
      extractAndCheckPersonnalAccessToken(request, env, tenant, operation)
        .flatMap {
          case Some((username, tokenId)) =>
            env.datastores.users
              .findUser(username)
              .map {
                case Some(user)
                    if user.admin || user.tenantRights
                      .get(tenant)
                      .exists(r => RightLevels.superiorOrEqualLevels(minimumLevel).contains(r)) =>
                  Right(UserInformation(username = username, TokenAuthentication(tokenId = tokenId)))
                case Some(user) =>
                  Left(Forbidden(Json.obj("message" -> "User does not have enough rights for this operation")))
                case None       => Left(Unauthorized(Json.obj("message" -> "User not found")))
              }
          case None           => Future.successful(Left(Unauthorized(Json.obj("message" -> "Invalid access token"))))
        }
    }

    def maybeCookieAuth: Future[Either[Result, String]] = {
      extractClaims(request, env.configuration.get[String]("app.authentication.secret"), env.encryptionKey)
        .flatMap(claims => claims.subject)
        .fold(Future.successful(Left(Unauthorized(Json.obj("message" -> "Invalid token"))): Either[Result, String]))(
          subject => {
            env.datastores.users
              .hasRightForTenant(subject, tenant, minimumLevel)
              .map {
                case Some(username) => Right(username)
                case None           =>
                  Left(Forbidden(Json.obj("message" -> "User does not have enough rights for this operation")))
              }
          }
        )
    }

    maybeCookieAuth.flatMap {
      case Right(username) => block(UserNameRequest(request = request, UserInformation(username = username, authentication = BackOfficeAuthentication)))
      case Left(result)    =>
        maybeTokenAuth.flatMap {
          case Right(userInformation) => block(UserNameRequest(request = request, userInformation))
          case Left(result)    => Future.successful(result)
        }
    }
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
            case Some(username) => block(UserNameRequest(request = request, UserInformation(username = username, authentication = BackOfficeAuthentication)))
            case None           =>
              Future.successful(Forbidden(Json.obj("message" -> "User does not have enough rights for this operation")))
          }
      })
  }
}

class ValidatePasswordAction(bodyParser: BodyParser[AnyContent], env: Env)(implicit
    ec: ExecutionContext
) extends ActionBuilder[UserNameRequest, AnyContent] {
  override protected def executionContext: ExecutionContext = ec
  override def parser: BodyParser[AnyContent]               = bodyParser

  override def invokeBlock[A](request: Request[A], block: UserNameRequest[A] => Future[Result]): Future[Result] = {
    request.body match {
      case json: JsObject =>
        (json \ "password").asOpt[String] match {
          case None           =>
            Future.successful(BadRequest(Json.obj("message" -> "Missing password")))
          case Some(password) =>
            val userRequest = request match {
              case r: HookAndUserNameRequest[A]        => Some(r.user.username)
              case r: UserNameRequest[A]               => Some(r.user.username)
              case r: UserRequestWithCompleteRights[A] => Some(r.user.username)
              case r: UserRequestWithTenantRights[A]   => Some(r.user.username)
              case r: ProjectIdUserNameRequest[A]      => Some(r.user.username)
              case _                                   => None
            }
            userRequest match {
              case Some(user) =>
                env.datastores.users.isUserValid(user, password).flatMap {
                  case Some(_) => block(UserNameRequest(request, user=UserInformation(username = user, authentication = BackOfficeAuthentication)))
                  case None    => Future.successful(Unauthorized(Json.obj("message" -> "Your password is invalid.")))
                }
              case _          => Future.successful(BadRequest(Json.obj("message" -> "Invalid request")))

            }

        }
      case _              =>
        Future.successful(BadRequest(Json.obj("message" -> "Invalid request body")))
    }
  }
}

class ProjectAuthAction(
    bodyParser: BodyParser[AnyContent],
    env: Env,
    tenant: String,
    project: String,
    minimumLevel: RightLevel
)(implicit ec: ExecutionContext)
    extends ActionBuilder[ProjectIdUserNameRequest, AnyContent] {

  override def parser: BodyParser[AnyContent]               = bodyParser
  override protected def executionContext: ExecutionContext = ec

  override def invokeBlock[A](request: Request[A], block: ProjectIdUserNameRequest[A] => Future[Result]): Future[Result] = {
    extractClaims(request, env.configuration.get[String]("app.authentication.secret"), env.encryptionKey)
      .flatMap(claims => claims.subject)
      .fold(Future.successful(Unauthorized(Json.obj("message" -> "Invalid token"))))(subject => {
        env.datastores.users
          .hasRightForProject(subject, tenant, project, minimumLevel)
          .flatMap(authorized =>
            authorized.fold(
              err => Future.successful(Results.Status(err.status)(Json.toJson(err))),
              {
                case Some((username, projectId)) => block(ProjectIdUserNameRequest(request = request, user = UserInformation(username=username, authentication = BackOfficeAuthentication), projectId = projectId))
                case None => Future.successful(Forbidden(Json.obj("message" -> "User does not have enough rights for this operation")))
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

  override def invokeBlock[A](
      request: Request[A],
      block: HookAndUserNameRequest[A] => Future[Result]
  ): Future[Result] = {
    extractClaims(request, env.configuration.get[String]("app.authentication.secret"), env.encryptionKey)
      .flatMap(claims => claims.subject)
      .fold(Future.successful(Unauthorized(Json.obj("message" -> "Invalid token"))))(subject => {
        env.datastores.users
          .hasRightForWebhook(subject, tenant, webhook, minimumLevel)
          .flatMap(authorized =>
            authorized.fold(
              err => Future.successful(Results.Status(err.status)(Json.toJson(err))),
              {
                case Some((username, hookName)) =>
                  block(HookAndUserNameRequest(request = request, user = UserInformation(username=username, authentication = BackOfficeAuthentication), hookName = hookName))
                case None                       =>
                  Future
                    .successful(Forbidden(Json.obj("message" -> "User does not have enough rights for this operation")))
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
                case Some(username) => block(UserNameRequest(request = request, user = UserInformation(username=username, authentication = BackOfficeAuthentication)))
                case None           =>
                  Future
                    .successful(Forbidden(Json.obj("message" -> "User does not have enough rights for this operation")))
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

class PersonnalAccessTokenTenantAuthActionFactory(bodyParser: BodyParser[AnyContent], env: Env)(implicit
    ec: ExecutionContext
) {
  def apply(
      tenant: String,
      minimumLevel: RightLevel,
      operation: TenantTokenRights
  ): PersonnalAccessTokenTenantAuthAction =
    new PersonnalAccessTokenTenantAuthAction(bodyParser, env, tenant, minimumLevel, operation)
}

class ValidatePasswordActionFactory(bodyParser: BodyParser[AnyContent], env: Env)(implicit ec: ExecutionContext) {
  def apply(): ValidatePasswordAction = new ValidatePasswordAction(bodyParser, env)
}

object AuthAction {
  private val TIMER = Executors.newSingleThreadScheduledExecutor()

  def delayResponse(result: Result, duration: Duration = Duration.ofSeconds(3)): Future[Result] = {
    val promise = Promise[Result]()
    TIMER.schedule(() => promise.success(result), duration.toMillis, TimeUnit.MILLISECONDS)
    promise.future
  }


  def extractClaims[A](request: Request[A], secret: String, bodySecretKey: SecretKeySpec): Option[JwtClaim] = {
    request.cookies
      .get("token")
      .map(cookie => cookie.value)
      .map(token => decodeJWT(token, secret, bodySecretKey))
      .flatMap(maybeClaim => maybeClaim.toOption)
  }

  def extractAndCheckPersonnalAccessToken[A](
      request: Request[A],
      env: Env,
      tenant: String,
      operation: TenantTokenRights
  ): Future[Option[(String, UUID)]] = {
    request.headers
      .get("Authorization")
      .map(header => header.split("Basic "))
      .filter(splitted => splitted.length == 2)
      .map(splitted => splitted(1))
      .map(header => {
        Base64.getDecoder.decode(header.getBytes)
      })
      .map(bytes => new String(bytes))
      .map(header => header.split(":"))
      .filter(arr => arr.length == 2) match {
      case Some(Array(username, token, _*)) => {
        env.datastores.personnalAccessToken
          .checkAccessToken(username, token, tenant, operation)
          .map {
            case TokenCheckSuccess(tokenId)  => Some(username, tokenId)
            case TokenCheckFailure => None
          }(env.executionContext)

      }
      case None                             => Future.successful(None)
    }
  }
}
