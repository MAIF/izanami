package fr.maif.izanami.web

import fr.maif.izanami.datastores.PersonnalAccessTokenDatastore.{
  TokenCheckFailure,
  TokenCheckSuccess
}
import fr.maif.izanami.env.Env
import fr.maif.izanami.errors.{
  MissingPersonalAccessToken,
  NotEnoughRights,
  UserNotFound
}
import fr.maif.izanami.events.EventAuthentication
import fr.maif.izanami.events.EventAuthentication.{
  BackOfficeAuthentication,
  TokenAuthentication
}
import fr.maif.izanami.models.*
import fr.maif.izanami.models.IzanamiMode.{Leader, Worker}
import fr.maif.izanami.security.JwtService.decodeJWT
import fr.maif.izanami.utils.FutureEither
import fr.maif.izanami.utils.syntax.implicits.{
  BetterFuture,
  BetterFutureEither,
  BetterSyntax
}
import fr.maif.izanami.web.AuthAction.{
  extractAndCheckPersonnalAccessToken,
  extractClaims
}
import pdi.jwt.JwtClaim
import play.api.libs.json.*
import play.api.mvc.*
import play.api.mvc.Results.{BadRequest, Forbidden, Unauthorized}

import java.time.Duration
import java.util.concurrent.{Executors, TimeUnit}
import java.util.{Base64, UUID}
import javax.crypto.spec.SecretKeySpec
import scala.concurrent.{ExecutionContext, Future, Promise}

case class UserInformation(
    username: String,
    authentication: EventAuthentication
)

case class ClientKeyRequest[A](
    request: Request[A],
    key: ApiKeyWithCompleteRights
) extends WrappedRequest[A](request)
case class UserRequestWithCompleteRights[A](
    request: Request[A],
    user: UserWithRights,
    authentication: EventAuthentication
) extends WrappedRequest[A](request)
case class UserRequestWithTenantRights[A](
    request: Request[A],
    user: UserWithTenantRights,
    authentication: EventAuthentication
) extends WrappedRequest[A](request)
case class UserRequestWithCompleteRightForOneTenant[A](
    request: Request[A],
    user: UserWithCompleteRightForOneTenant,
    authentication: EventAuthentication
) extends WrappedRequest[A](request) {
  def userInformation: UserInformation =
    UserInformation(username = user.username, authentication = authentication)
}
case class UserNameRequest[A](request: Request[A], user: UserInformation)
    extends WrappedRequest[A](request)
case class ProjectIdUserNameRequest[A](
    request: Request[A],
    user: UserInformation,
    projectId: UUID
) extends WrappedRequest[A](request)

case class HookAndUserNameRequest[A](
    request: Request[A],
    user: UserInformation,
    hookName: String
) extends WrappedRequest[A](request)
case class SessionIdRequest[A](request: Request[A], sessionId: String)
    extends WrappedRequest[A](request)

class ClientApiKeyAction(bodyParser: BodyParser[AnyContent], env: Env)(implicit
    ec: ExecutionContext
) extends ActionBuilder[ClientKeyRequest, AnyContent] {
  override def parser: BodyParser[AnyContent] = bodyParser

  override def invokeBlock[A](
      request: Request[A],
      block: ClientKeyRequest[A] => Future[Result]
  ): Future[Result] = {
    val maybeFutureKey =
      for (
        clientId <- request.headers.get("Izanami-Client-Id");
        clientSecret <- request.headers.get("Izanami-Client-Secret")
      ) yield env.datastores.apiKeys.readAndCheckApiKey(clientId, clientSecret)

    maybeFutureKey
      .map(futureKey =>
        futureKey.flatMap(eitherKey => {
          eitherKey.fold(
            error =>
              Future.successful(
                Unauthorized(Json.obj("message" -> "Invalid key"))
              ),
            key => block(ClientKeyRequest(request, key))
          )
        })
      )
      .getOrElse(
        Future.successful(Unauthorized(Json.obj("message" -> "Invalid key")))
      )
  }

  override protected def executionContext: ExecutionContext = ec
}

class TenantRightsAction(
    bodyParser: BodyParser[AnyContent],
    override val env: Env
)(implicit
    ec: ExecutionContext
) extends LeaderActionBuilder[UserRequestWithTenantRights] {
  override def parser: BodyParser[AnyContent] = bodyParser

  override def invokeBlockImpl[A](
      request: Request[A],
      block: UserRequestWithTenantRights[A] => Future[Result]
  ): Future[Result] = {
    extractClaims(
      request,
      env.typedConfiguration.authentication.secret,
      env.encryptionKey
    )
      .flatMap(claims => claims.subject)
      .fold(Future.successful(Unauthorized("")))(subject => {
        env.datastores.users
          .findSessionWithTenantRights(subject)
          .flatMap {
            case None =>
              Unauthorized(
                Json.obj("message" -> "User is not connected")
              ).future
            case Some(user) =>
              block(
                UserRequestWithTenantRights(
                  request,
                  user,
                  BackOfficeAuthentication
                )
              )
          }
      })
  }

  override protected def executionContext: ExecutionContext = ec
}

class DetailledAuthAction(
    bodyParser: BodyParser[AnyContent],
    override val env: Env
)(implicit
    ec: ExecutionContext
) extends LeaderActionBuilder[UserRequestWithCompleteRights] {
  override def parser: BodyParser[AnyContent] = bodyParser

  override def invokeBlockImpl[A](
      request: Request[A],
      block: UserRequestWithCompleteRights[A] => Future[Result]
  ): Future[Result] = {
    extractClaims(
      request,
      env.typedConfiguration.authentication.secret,
      env.encryptionKey
    )
      .flatMap(claims => claims.subject)
      .fold(
        Future.successful(Unauthorized(Json.obj("message" -> "Invalid token")))
      )(subject => {
        env.datastores.users
          .findSessionWithCompleteRights(subject)
          .flatMap {
            case None       => UserNotFound(subject).toHttpResponse.future
            case Some(user) =>
              block(
                UserRequestWithCompleteRights(
                  request,
                  user,
                  BackOfficeAuthentication
                )
              )
          }
      })
  }

  override protected def executionContext: ExecutionContext = ec
}

class AdminAuthAction(
    bodyParser: BodyParser[AnyContent],
    override val env: Env
)(implicit
    ec: ExecutionContext
) extends LeaderActionBuilder[UserNameRequest] {

  override def parser: BodyParser[AnyContent] = bodyParser

  override def invokeBlockImpl[A](
      request: Request[A],
      block: UserNameRequest[A] => Future[Result]
  ): Future[Result] = {
    extractClaims(
      request,
      env.typedConfiguration.authentication.secret,
      env.encryptionKey
    )
      .flatMap(claims => claims.subject)
      .fold(
        Future.successful(Unauthorized(Json.obj("message" -> "Invalid token")))
      )(subject => {
        env.datastores.users
          .findAdminSession(subject)
          .flatMap {
            case Some(username) =>
              block(
                UserNameRequest(
                  request = request,
                  UserInformation(
                    username = username,
                    authentication = BackOfficeAuthentication
                  )
                )
              )
            case None =>
              Future.successful(
                Forbidden(Json.obj("message" -> "User is not connected"))
              )
          }
      })
  }

  override protected def executionContext: ExecutionContext = ec
}

class AuthenticatedAction(
    bodyParser: BodyParser[AnyContent],
    override val env: Env
)(implicit
    ec: ExecutionContext
) extends LeaderActionBuilder[UserNameRequest] {

  override def parser: BodyParser[AnyContent] = bodyParser

  override def invokeBlockImpl[A](
      request: Request[A],
      block: UserNameRequest[A] => Future[Result]
  ): Future[Result] = {
    extractClaims(
      request,
      env.typedConfiguration.authentication.secret,
      env.encryptionKey
    )
      .flatMap(claims => claims.subject)
      .fold(
        Future.successful(Unauthorized(Json.obj("message" -> "Invalid token")))
      )(sessionId =>
        env.datastores.users.findSession(sessionId).flatMap {
          case Some(username) =>
            block(
              UserNameRequest(
                request = request,
                UserInformation(
                  username = username,
                  authentication = BackOfficeAuthentication
                )
              )
            )
          case None =>
            Future.successful(
              Unauthorized(Json.obj("message" -> "User is not connected"))
            )
        }
      )
  }

  override protected def executionContext: ExecutionContext = ec
}

class AuthenticatedSessionAction(
    bodyParser: BodyParser[AnyContent],
    override val env: Env
)(implicit
    ec: ExecutionContext
) extends LeaderActionBuilder[SessionIdRequest] {

  override def parser: BodyParser[AnyContent] = bodyParser

  override def invokeBlockImpl[A](
      request: Request[A],
      block: SessionIdRequest[A] => Future[Result]
  ): Future[Result] = {
    extractClaims(
      request,
      env.typedConfiguration.authentication.secret,
      env.encryptionKey
    )
      .flatMap(claims => claims.subject)
      .fold(
        Future.successful(Unauthorized(Json.obj("message" -> "Invalid token")))
      )(sessionId =>
        block(SessionIdRequest(request = request, sessionId = sessionId))
      )
  }

  override protected def executionContext: ExecutionContext = ec
}

class DetailledRightForTenantAction(
    bodyParser: BodyParser[AnyContent],
    override val env: Env,
    tenant: String
)(implicit
    ec: ExecutionContext
) extends LeaderActionBuilder[UserRequestWithCompleteRightForOneTenant] {

  override def parser: BodyParser[AnyContent] = bodyParser

  override def invokeBlockImpl[A](
      request: Request[A],
      block: UserRequestWithCompleteRightForOneTenant[A] => Future[Result]
  ): Future[Result] = {
    extractClaims(
      request,
      env.typedConfiguration.authentication.secret,
      env.encryptionKey
    )
      .flatMap(claims => claims.subject)
      .fold(
        Future.successful(Unauthorized(Json.obj("message" -> "Invalid token")))
      )(subject => {
        env.datastores.users
          .findSessionWithRightForTenant(subject, tenant)
          .flatMap {
            case Left(err)   => err.toHttpResponse.toFuture
            case Right(user) =>
              block(
                UserRequestWithCompleteRightForOneTenant(
                  request = request,
                  user = user,
                  authentication = BackOfficeAuthentication
                )
              )
          }
      })
  }

  override protected def executionContext: ExecutionContext = ec
}

class PersonnalAccessTokenProjectAuthAction(
    bodyParser: BodyParser[AnyContent],
    override val env: Env,
    tenant: String,
    project: String,
    minimumLevel: ProjectRightLevel,
    operation: TenantTokenRights
)(implicit
    ec: ExecutionContext
) extends LeaderActionBuilder[UserNameRequest] {
  override def parser: BodyParser[AnyContent] = bodyParser

  override def invokeBlockImpl[A](
      request: Request[A],
      block: UserNameRequest[A] => Future[Result]
  ): Future[Result] = {

    def maybeTokenAuth: Future[Either[Result, UserInformation]] = {
      extractAndCheckPersonnalAccessToken(request, env, tenant, operation)
        .flatMap {
          case Some((username, tokenId)) =>
            env.datastores.users
              .findCompleteRightsFromTenant(
                username = username,
                tenants = Set(tenant)
              )
              .map {
                case Some(user)
                    if user.hasRightForProject(
                      tenant = tenant,
                      project = project,
                      rightLevel = minimumLevel
                    ) =>
                  Right(
                    UserInformation(
                      username = username,
                      TokenAuthentication(tokenId = tokenId)
                    )
                  )
                case Some(user) =>
                  Left(
                    Forbidden(
                      Json.obj(
                        "message" -> "User does not have enough rights for this operation"
                      )
                    )
                  )
                case None =>
                  Left(Unauthorized(Json.obj("message" -> "User not found")))
              }
          case None =>
            Future.successful(
              Left(Unauthorized(Json.obj("message" -> "Invalid access token")))
            )
        }
    }

    def maybeCookieAuth: Future[Option[Either[Result, String]]] = {
      extractClaims(
        request,
        env.typedConfiguration.authentication.secret,
        env.encryptionKey
      )
        .flatMap(claims => claims.subject)
        .fold(
          Future
            .successful(
              None
            )
        )(subject => {
          env.datastores.users
            .hasRightForProject(
              session = subject,
              tenant = tenant,
              projectIdOrName = Right(project),
              level = minimumLevel
            )
            .map {
              case Right(Some((username, _))) => Some(Right(username))
              case _                          =>
                Some(
                  Left(
                    Forbidden(
                      Json.obj(
                        "message" -> "User does not have enough rights for this operation"
                      )
                    )
                  )
                )
            }
        })
    }

    maybeCookieAuth.flatMap {

      case Some(Right(username)) =>
        block(
          UserNameRequest(
            request = request,
            UserInformation(
              username = username,
              authentication = BackOfficeAuthentication
            )
          )
        )
      case Some(Left(result)) => Future.successful(result)
      case None               =>
        maybeTokenAuth.flatMap {
          case Right(userInformation) =>
            block(UserNameRequest(request = request, userInformation))
          case Left(result) => Future.successful(result)
        }
    }
  }

  override protected def executionContext: ExecutionContext = ec
}

trait IzanamiActionBuilder[R[_] <: Request[_]]
    extends ActionBuilder[R, AnyContent] {
  def disabledOn: IzanamiMode
  def env: Env
  def invokeBlockImpl[A](
      request: Request[A],
      block: R[A] => Future[Result]
  ): Future[Result]

  private val actualMode = env.typedConfiguration.mode

  override def invokeBlock[A](
      request: Request[A],
      block: R[A] => Future[Result]
  ): Future[Result] = {

    if (actualMode == disabledOn) {
      Future.successful(Results.NotFound("Page not found"))
    } else {
      invokeBlockImpl(request, block)
    }
  }
}

trait LeaderActionBuilder[R[_] <: Request[_]] extends IzanamiActionBuilder[R] {
  override def disabledOn: IzanamiMode = Worker
}

class LeaderActionBuilderImpl(
                               override val parser: BodyParser[AnyContent],
                               override val env: Env
                             ) extends LeaderActionBuilder[Request] {
  override def disabledOn: IzanamiMode = Worker

  override def invokeBlockImpl[A](
                                   request: Request[A],
                                   block: Request[A] => Future[Result]
                                 ): Future[Result] = block(request)

  override protected def executionContext: ExecutionContext = env.executionContext
}

class WorkerActionBuilder(
    override val parser: BodyParser[AnyContent],
    override val env: Env
) extends IzanamiActionBuilder[Request] {
  override def disabledOn: IzanamiMode = Leader

  override def invokeBlockImpl[A](
      request: Request[A],
      block: Request[A] => Future[Result]
  ): Future[Result] = block(request)

  override protected def executionContext: ExecutionContext = env.executionContext
}

/*class IzanamiActionBuilder[R[_] <: Request[_]](
    bodyParser: BodyParser[AnyContent],
    env: Env,
    disabledOn: IzanamiMode
)(implicit ec: ExecutionContext)
    extends ActionBuilder[R, AnyContent] {

  override def parser: BodyParser[AnyContent] = parser
  override protected def executionContext: ExecutionContext = ec

  private val blockedValue = "disabled"
  private val mode = env.typedConfiguration.mode

  override def invokeBlock[A](
      request: R[A],
      block: R[A] => Future[Result]
  ): Future[Result] = if (mode == disabledOn) {
    Future.successful(Results.NotFound("Page not found"))
  } else {
    block(request)
  }

  override def invokeBlock[A](request: Request[A], block: R[A] => Future[Result]): Future[Result] =

}*/

/*class LeaderActionBuilder(
    bodyParser: BodyParser[AnyContent],
    env: Env
)(implicit ec: ExecutionContext)
    extends IzanamiActionBuilder(
      bodyParser = bodyParser,
      env = env,
      disabledOn = Worker
    )

class WorkerActionBuilder(
    bodyParser: BodyParser[AnyContent],
    env: Env
)(implicit ec: ExecutionContext)
    extends IzanamiActionBuilder(
      bodyParser = bodyParser,
      env = env,
      disabledOn = Leader
    )*/

class PersonnalAccessTokenFeatureAuthAction(
    bodyParser: BodyParser[AnyContent],
    override val env: Env,
    tenant: String,
    featureId: String,
    minimumLevel: ProjectRightLevel,
    operation: TenantTokenRights
)(implicit
    ec: ExecutionContext
) extends LeaderActionBuilder[UserRequestWithCompleteRightForOneTenant] {
  override def parser: BodyParser[AnyContent] = bodyParser

  override def invokeBlockImpl[A](
      request: Request[A],
      block: UserRequestWithCompleteRightForOneTenant[A] => Future[Result]
  ): Future[Result] = {

    def maybeTokenAuth: FutureEither[
      (UserWithCompleteRightForOneTenant, EventAuthentication)
    ] = {
      extractAndCheckPersonnalAccessToken(
        request,
        env,
        tenant,
        operation
      ).mapToFEither
        .flatMap {
          case Some((username, tokenId)) =>
            for (
              user <- env.datastores.users
                .findUserWithRightForTenant(
                  username = username,
                  tenant = tenant
                )
                .toFEither;
              projectByFeatures <- env.datastores.features.findFeaturesProjects(
                tenant = tenant,
                featureIds = Set(featureId)
              );
              project = projectByFeatures(featureId);
              res <- if (
                user.hasRightForProject(
                  project = project,
                  level = minimumLevel
                )
              ) {
                FutureEither.success(
                  (user, TokenAuthentication(tokenId = tokenId))
                )
              } else {
                FutureEither.failure(
                  NotEnoughRights(
                    "User associated with this token does not have enough rights"
                  )
                )
              }
            ) yield {
              res
            }
          case None =>
            FutureEither.failure(
              MissingPersonalAccessToken
            )
        }
    }

    def maybeCookieAuth
        : FutureEither[Option[UserWithCompleteRightForOneTenant]] = {
      extractClaims(
        request,
        env.typedConfiguration.authentication.secret,
        env.encryptionKey
      )
        .flatMap(claims => claims.subject) match {
        case Some(subject) => {
          for (
            projectByfeature <- env.datastores.features.findFeaturesProjects(
              tenant = tenant,
              featureIds = Set(featureId)
            );
            project = projectByfeature(featureId);
            user <- env.datastores.users
              .findSessionWithRightForTenant(
                session = subject,
                tenant = tenant
              )
              .toFEither
          ) yield {
            Some(user)
          }
        }
        case None => FutureEither.success(None)
      }
    }

    maybeCookieAuth.value.flatMap {
      case Right(Some(user)) =>
        block(
          UserRequestWithCompleteRightForOneTenant(
            request = request,
            user = user,
            authentication = BackOfficeAuthentication
          )
        )
      case Left(result) => Future.successful(result.toHttpResponse)
      case Right(None)  =>
        maybeTokenAuth.value.flatMap {
          case Right((user, authentication)) =>
            block(
              UserRequestWithCompleteRightForOneTenant(
                request = request,
                user = user,
                authentication = authentication
              )
            )
          case Left(result) => Future.successful(result.toHttpResponse)
        }
    }
  }

  override protected def executionContext: ExecutionContext = ec
}

class PersonnalAccessTokenKeyAuthAction(
    bodyParser: BodyParser[AnyContent],
    override val env: Env,
    tenant: String,
    key: String,
    minimumLevel: RightLevel,
    operation: TenantTokenRights
)(implicit
    ec: ExecutionContext
) extends LeaderActionBuilder[UserNameRequest] {
  override def parser: BodyParser[AnyContent] = bodyParser

  override def invokeBlockImpl[A](
      request: Request[A],
      block: UserNameRequest[A] => Future[Result]
  ): Future[Result] = {

    def maybeTokenAuth: Future[Either[Result, UserInformation]] = {
      extractAndCheckPersonnalAccessToken(request, env, tenant, operation)
        .flatMap {
          case Some((username, tokenId)) =>
            env.datastores.users
              .findCompleteRightsFromTenant(
                username = username,
                tenants = Set(tenant)
              )
              .map {
                case Some(user)
                    if user.hasRightForKey(
                      tenant = tenant,
                      key = key,
                      rightLevel = minimumLevel
                    ) =>
                  Right(
                    UserInformation(
                      username = username,
                      TokenAuthentication(tokenId = tokenId)
                    )
                  )
                case Some(user) =>
                  Left(
                    Forbidden(
                      Json.obj(
                        "message" -> "User does not have enough rights for this operation"
                      )
                    )
                  )
                case None =>
                  Left(Unauthorized(Json.obj("message" -> "User not found")))
              }
          case None =>
            Future.successful(
              Left(Unauthorized(Json.obj("message" -> "Invalid access token")))
            )
        }
    }

    def maybeCookieAuth: Future[Option[Either[Result, String]]] = {
      extractClaims(
        request,
        env.typedConfiguration.authentication.secret,
        env.encryptionKey
      )
        .flatMap(claims => claims.subject) match {
        case Some(subject) => {
          env.datastores.users
            .hasRightForKey(
              session = subject,
              tenant = tenant,
              key = key,
              level = minimumLevel
            )
            .map {
              case Right(Some(username)) => Some(Right(username))
              case _                     =>
                Some(
                  Left(
                    Forbidden(
                      Json.obj(
                        "message" -> "User does not have enough rights for this operation"
                      )
                    )
                  )
                )
            }
        }
        case None => Future.successful(None)
      }
    }

    maybeCookieAuth.flatMap {
      case Some(Right(username)) =>
        block(
          UserNameRequest(
            request = request,
            UserInformation(
              username = username,
              authentication = BackOfficeAuthentication
            )
          )
        )
      case Some(Left(result)) => Future.successful(result)
      case None               =>
        maybeTokenAuth.flatMap {
          case Right(userInformation) =>
            block(UserNameRequest(request = request, userInformation))
          case Left(result) => Future.successful(result)
        }
    }
  }

  override protected def executionContext: ExecutionContext = ec
}

class PersonnalAccessTokenTenantAuthAction(
    bodyParser: BodyParser[AnyContent],
    override val env: Env,
    tenant: String,
    minimumLevel: RightLevel,
    operation: TenantTokenRights
)(implicit
    ec: ExecutionContext
) extends LeaderActionBuilder[UserNameRequest] {
  override def parser: BodyParser[AnyContent] = bodyParser

  override def invokeBlockImpl[A](
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
                      .exists(r =>
                        RightLevel
                          .superiorOrEqualLevels(minimumLevel)
                          .contains(r)
                      ) =>
                  Right(
                    UserInformation(
                      username = username,
                      TokenAuthentication(tokenId = tokenId)
                    )
                  )
                case Some(user) =>
                  Left(
                    Forbidden(
                      Json.obj(
                        "message" -> "User does not have enough rights for this operation"
                      )
                    )
                  )
                case None =>
                  Left(Unauthorized(Json.obj("message" -> "User not found")))
              }
          case None =>
            Future.successful(
              Left(Unauthorized(Json.obj("message" -> "Invalid access token")))
            )
        }
    }

    def maybeCookieAuth: Future[Option[Either[Result, String]]] = {
      extractClaims(
        request,
        env.typedConfiguration.authentication.secret,
        env.encryptionKey
      )
        .flatMap(claims => claims.subject)
        .fold(
          Future
            .successful(
              None
            )
        )(subject => {
          env.datastores.users
            .hasRightForTenant(subject, tenant, minimumLevel)
            .map {
              case Some(username) => Some(Right(username))
              case None           =>
                Some(
                  Left(
                    Forbidden(
                      Json.obj(
                        "message" -> "User does not have enough rights for this operation"
                      )
                    )
                  )
                )
            }
        })
    }

    maybeCookieAuth.flatMap {
      case Some(Right(username)) =>
        block(
          UserNameRequest(
            request = request,
            UserInformation(
              username = username,
              authentication = BackOfficeAuthentication
            )
          )
        )
      case Some(Left(result)) => Future.successful(result)
      case None               =>
        maybeTokenAuth.flatMap {
          case Right(userInformation) =>
            block(UserNameRequest(request = request, userInformation))
          case Left(result) => Future.successful(result)
        }
    }
  }

  override protected def executionContext: ExecutionContext = ec
}

class PersonnalAccessTokenAdminAuthAction(
    bodyParser: BodyParser[AnyContent],
    override val env: Env,
    operation: GlobalTokenRight
)(implicit
    ec: ExecutionContext
) extends LeaderActionBuilder[UserNameRequest] {
  override def parser: BodyParser[AnyContent] = bodyParser

  override def invokeBlockImpl[A](
      request: Request[A],
      block: UserNameRequest[A] => Future[Result]
  ): Future[Result] = {

    def maybeTokenAuth: Future[Either[Result, UserInformation]] = {
      extractAndCheckPersonnalAccessToken(request, env, operation)
        .flatMap {
          case Some((username, tokenId)) =>
            env.datastores.users
              .findUser(username)
              .map {
                case Some(user) if user.admin =>
                  Right(
                    UserInformation(
                      username = username,
                      TokenAuthentication(tokenId = tokenId)
                    )
                  )
                case Some(user) =>
                  Left(
                    Forbidden(
                      Json.obj(
                        "message" -> "User does not have enough rights for this operation"
                      )
                    )
                  )
                case None =>
                  Left(Unauthorized(Json.obj("message" -> "User not found")))
              }
          case None =>
            Future.successful(
              Left(Unauthorized(Json.obj("message" -> "Invalid access token")))
            )
        }
    }

    def maybeCookieAuth: Future[Option[Either[Result, String]]] = {
      extractClaims(
        request,
        env.typedConfiguration.authentication.secret,
        env.encryptionKey
      )
        .flatMap(claims => claims.subject)
        .fold(
          Future
            .successful(
              None
            )
        )(subject => {
          env.datastores.users
            .findAdminSession(subject)
            .map {
              case Some(username) => Some(Right(username))
              case None           =>
                Some(
                  Left(
                    Forbidden(
                      Json.obj(
                        "message" -> "User does not have enough rights for this operation"
                      )
                    )
                  )
                )
            }
        })
    }

    maybeCookieAuth.flatMap {
      case Some(Right(username)) =>
        block(
          UserNameRequest(
            request = request,
            UserInformation(
              username = username,
              authentication = BackOfficeAuthentication
            )
          )
        )
      case Some(Left(result)) => Future.successful(result)
      case None               =>
        maybeTokenAuth.flatMap {
          case Right(userInformation) =>
            block(UserNameRequest(request = request, userInformation))
          case Left(result) => Future.successful(result)
        }
    }
  }

  override protected def executionContext: ExecutionContext = ec
}

class TenantAuthAction(
    bodyParser: BodyParser[AnyContent],
    override val env: Env,
    tenant: String,
    minimumLevel: RightLevel
)(implicit
    ec: ExecutionContext
) extends LeaderActionBuilder[UserNameRequest] {

  override def parser: BodyParser[AnyContent] = bodyParser

  override def invokeBlockImpl[A](
      request: Request[A],
      block: UserNameRequest[A] => Future[Result]
  ): Future[Result] = {
    extractClaims(
      request,
      env.typedConfiguration.authentication.secret,
      env.encryptionKey
    )
      .flatMap(claims => claims.subject)
      .fold(
        Future.successful(Unauthorized(Json.obj("message" -> "Invalid token")))
      )(subject => {
        env.datastores.users
          .hasRightForTenant(subject, tenant, minimumLevel)
          .flatMap {
            case Some(username) =>
              block(
                UserNameRequest(
                  request = request,
                  UserInformation(
                    username = username,
                    authentication = BackOfficeAuthentication
                  )
                )
              )
            case None =>
              Future.successful(
                Forbidden(
                  Json.obj(
                    "message" -> "User does not have enough rights for this operation"
                  )
                )
              )
          }
      })
  }

  override protected def executionContext: ExecutionContext = ec
}

class ValidatePasswordAction(
    bodyParser: BodyParser[AnyContent],
    override val env: Env
)(implicit
    ec: ExecutionContext
) extends LeaderActionBuilder[UserNameRequest] {
  override def parser: BodyParser[AnyContent] = bodyParser

  override def invokeBlockImpl[A](
      request: Request[A],
      block: UserNameRequest[A] => Future[Result]
  ): Future[Result] = {
    request.body match {
      case json: JsObject =>
        (json \ "password").asOpt[String] match {
          case None =>
            Future.successful(
              BadRequest(Json.obj("message" -> "Missing password"))
            )
          case Some(password) =>
            val userRequest = request match {
              case r: HookAndUserNameRequest[_]        => Some(r.user.username)
              case r: UserNameRequest[_]               => Some(r.user.username)
              case r: UserRequestWithCompleteRights[_] => Some(r.user.username)
              case r: UserRequestWithTenantRights[_]   => Some(r.user.username)
              case r: ProjectIdUserNameRequest[_]      => Some(r.user.username)
              case _                                   => None
            }
            userRequest match {
              case Some(user) =>
                env.datastores.users.isUserValid(user, password).flatMap {
                  case Some(_) =>
                    block(
                      UserNameRequest(
                        request,
                        user = UserInformation(
                          username = user,
                          authentication = BackOfficeAuthentication
                        )
                      )
                    )
                  case None =>
                    Future.successful(
                      Unauthorized(
                        Json.obj("message" -> "Your password is invalid.")
                      )
                    )
                }
              case _ =>
                Future.successful(
                  BadRequest(Json.obj("message" -> "Invalid request"))
                )

            }

        }
      case _ =>
        Future.successful(
          BadRequest(Json.obj("message" -> "Invalid request body"))
        )
    }
  }

  override protected def executionContext: ExecutionContext = ec
}

class ProjectAuthAction(
    bodyParser: BodyParser[AnyContent],
    override val env: Env,
    tenant: String,
    projectIdOrName: Either[UUID, String],
    minimumLevel: ProjectRightLevel
)(implicit ec: ExecutionContext)
    extends LeaderActionBuilder[ProjectIdUserNameRequest] {

  override def parser: BodyParser[AnyContent] = bodyParser

  override def invokeBlockImpl[A](
      request: Request[A],
      block: ProjectIdUserNameRequest[A] => Future[Result]
  ): Future[Result] = {
    extractClaims(
      request,
      env.typedConfiguration.authentication.secret,
      env.encryptionKey
    )
      .flatMap(claims => claims.subject)
      .fold(
        Future.successful(Unauthorized(Json.obj("message" -> "Invalid token")))
      )(subject => {
        env.datastores.users
          .hasRightForProject(subject, tenant, projectIdOrName, minimumLevel)
          .flatMap(authorized =>
            authorized.fold(
              err =>
                Future.successful(Results.Status(err.status)(Json.toJson(err))),
              {
                case Some((username, projectId)) =>
                  block(
                    ProjectIdUserNameRequest(
                      request = request,
                      user = UserInformation(
                        username = username,
                        authentication = BackOfficeAuthentication
                      ),
                      projectId = projectId
                    )
                  )
                case None =>
                  Future
                    .successful(
                      Forbidden(
                        Json.obj(
                          "message" -> "User does not have enough rights for this operation"
                        )
                      )
                    )
              }
            )
          )
      })
  }

  override protected def executionContext: ExecutionContext = ec
}

class WebhookAuthAction(
    bodyParser: BodyParser[AnyContent],
    override val env: Env,
    tenant: String,
    webhook: String,
    minimumLevel: RightLevel
)(implicit ec: ExecutionContext)
    extends LeaderActionBuilder[HookAndUserNameRequest] {

  override def parser: BodyParser[AnyContent] = bodyParser

  override def invokeBlockImpl[A](
      request: Request[A],
      block: HookAndUserNameRequest[A] => Future[Result]
  ): Future[Result] = {
    extractClaims(
      request,
      env.typedConfiguration.authentication.secret,
      env.encryptionKey
    )
      .flatMap(claims => claims.subject)
      .fold(
        Future.successful(Unauthorized(Json.obj("message" -> "Invalid token")))
      )(subject => {
        env.datastores.users
          .hasRightForWebhook(subject, tenant, webhook, minimumLevel)
          .flatMap(authorized =>
            authorized.fold(
              err =>
                Future.successful(Results.Status(err.status)(Json.toJson(err))),
              {
                case Some((username, hookName)) =>
                  block(
                    HookAndUserNameRequest(
                      request = request,
                      user = UserInformation(
                        username = username,
                        authentication = BackOfficeAuthentication
                      ),
                      hookName = hookName
                    )
                  )
                case None =>
                  Future
                    .successful(
                      Forbidden(
                        Json.obj(
                          "message" -> "User does not have enough rights for this operation"
                        )
                      )
                    )
              }
            )
          )
      })
  }

  override protected def executionContext: ExecutionContext = ec
}

class KeyAuthAction(
    bodyParser: BodyParser[AnyContent],
    override val env: Env,
    tenant: String,
    key: String,
    minimumLevel: RightLevel
)(implicit ec: ExecutionContext)
    extends LeaderActionBuilder[UserNameRequest] {

  override def parser: BodyParser[AnyContent] = bodyParser

  override def invokeBlockImpl[A](
      request: Request[A],
      block: UserNameRequest[A] => Future[Result]
  ): Future[Result] = {
    extractClaims(
      request,
      env.typedConfiguration.authentication.secret,
      env.encryptionKey
    )
      .flatMap(claims => claims.subject)
      .fold(
        Future.successful(Unauthorized(Json.obj("message" -> "Invalid token")))
      )(subject => {
        env.datastores.users
          .hasRightForKey(subject, tenant, key, minimumLevel)
          .flatMap(authorized =>
            authorized.fold(
              err =>
                Future.successful(Results.Status(err.status)(Json.toJson(err))),
              {
                case Some(username) =>
                  block(
                    UserNameRequest(
                      request = request,
                      user = UserInformation(
                        username = username,
                        authentication = BackOfficeAuthentication
                      )
                    )
                  )
                case None =>
                  Future
                    .successful(
                      Forbidden(
                        Json.obj(
                          "message" -> "User does not have enough rights for this operation"
                        )
                      )
                    )
              }
            )
          )
      })
  }

  override protected def executionContext: ExecutionContext = ec
}

class DetailledRightForTenantFactory(
    bodyParser: BodyParser[AnyContent],
    env: Env
)(implicit ec: ExecutionContext) {
  def apply(tenant: String): DetailledRightForTenantAction =
    new DetailledRightForTenantAction(bodyParser, env, tenant)
}

class KeyAuthActionFactory(bodyParser: BodyParser[AnyContent], env: Env)(
    implicit ec: ExecutionContext
) {
  def apply(
      tenant: String,
      key: String,
      minimumLevel: RightLevel
  ): KeyAuthAction =
    new KeyAuthAction(bodyParser, env, tenant, key, minimumLevel)
}

class WebhookAuthActionFactory(bodyParser: BodyParser[AnyContent], env: Env)(
    implicit ec: ExecutionContext
) {
  def apply(
      tenant: String,
      webhook: String,
      minimumLevel: RightLevel
  ): WebhookAuthAction =
    new WebhookAuthAction(bodyParser, env, tenant, webhook, minimumLevel)
}

class ProjectAuthActionFactory(bodyParser: BodyParser[AnyContent], env: Env)(
    implicit ec: ExecutionContext
) {
  def apply(
      tenant: String,
      project: String,
      minimumLevel: ProjectRightLevel
  ): ProjectAuthAction =
    new ProjectAuthAction(bodyParser, env, tenant, Right(project), minimumLevel)
}

class ProjectAuthActionByIdFactory(
    bodyParser: BodyParser[AnyContent],
    env: Env
)(implicit ec: ExecutionContext) {
  def apply(
      tenant: String,
      project: UUID,
      minimumLevel: ProjectRightLevel
  ): ProjectAuthAction =
    new ProjectAuthAction(bodyParser, env, tenant, Left(project), minimumLevel)
}

class TenantAuthActionFactory(bodyParser: BodyParser[AnyContent], env: Env)(
    implicit ec: ExecutionContext
) {
  def apply(tenant: String, minimumLevel: RightLevel): TenantAuthAction =
    new TenantAuthAction(bodyParser, env, tenant, minimumLevel)
}

class PersonnalAccessTokenAdminAuthActionFactory(
    bodyParser: BodyParser[AnyContent],
    env: Env
)(implicit
    ec: ExecutionContext
) {
  def apply(
      operation: GlobalTokenRight
  ): PersonnalAccessTokenAdminAuthAction =
    new PersonnalAccessTokenAdminAuthAction(
      bodyParser,
      env,
      operation
    )
}

class PersonnalAccessTokenTenantAuthActionFactory(
    bodyParser: BodyParser[AnyContent],
    env: Env
)(implicit
    ec: ExecutionContext
) {
  def apply(
      tenant: String,
      minimumLevel: RightLevel,
      operation: TenantTokenRights
  ): PersonnalAccessTokenTenantAuthAction =
    new PersonnalAccessTokenTenantAuthAction(
      bodyParser,
      env,
      tenant,
      minimumLevel,
      operation
    )
}

class PersonnalAccessTokenFeatureAuthActionFactory(
    bodyParser: BodyParser[AnyContent],
    env: Env
)(implicit
    ec: ExecutionContext
) {
  def apply(
      tenant: String,
      featureId: String,
      minimumLevel: ProjectRightLevel,
      operation: TenantTokenRights
  ): PersonnalAccessTokenFeatureAuthAction =
    new PersonnalAccessTokenFeatureAuthAction(
      bodyParser,
      env,
      tenant,
      featureId,
      minimumLevel,
      operation
    )
}

class PersonnalAccessTokenProjectAuthActionFactory(
    bodyParser: BodyParser[AnyContent],
    env: Env
)(implicit
    ec: ExecutionContext
) {
  def apply(
      tenant: String,
      project: String,
      minimumLevel: ProjectRightLevel,
      operation: TenantTokenRights
  ): PersonnalAccessTokenProjectAuthAction =
    new PersonnalAccessTokenProjectAuthAction(
      bodyParser,
      env,
      tenant,
      project,
      minimumLevel,
      operation
    )
}

class PersonnalAccessTokenKeyAuthActionFactory(
    bodyParser: BodyParser[AnyContent],
    env: Env
)(implicit
    ec: ExecutionContext
) {
  def apply(
      tenant: String,
      key: String,
      minimumLevel: RightLevel,
      operation: TenantTokenRights
  ): PersonnalAccessTokenKeyAuthAction =
    new PersonnalAccessTokenKeyAuthAction(
      bodyParser,
      env,
      tenant,
      key,
      minimumLevel,
      operation
    )
}

object AuthAction {
  private val TIMER = Executors.newSingleThreadScheduledExecutor()

  def delayResponse(
      result: Result,
      duration: Duration = Duration.ofSeconds(3)
  ): Future[Result] = {
    val promise = Promise[Result]()
    TIMER.schedule(
      () => promise.success(result),
      duration.toMillis,
      TimeUnit.MILLISECONDS
    )
    promise.future
  }

  def extractClaims[A](
      request: Request[A],
      secret: String,
      bodySecretKey: SecretKeySpec
  ): Option[JwtClaim] = {
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
            case TokenCheckSuccess(tokenId) => Some(username, tokenId)
            case TokenCheckFailure          => None
          }(env.executionContext)

      }
      case _ => Future.successful(None)
    }
  }

  def extractAndCheckPersonnalAccessToken[A](
      request: Request[A],
      env: Env,
      operation: GlobalTokenRight
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
          .checkAccessToken(username, token, operation)
          .map {
            case TokenCheckSuccess(tokenId) => Some(username, tokenId)
            case TokenCheckFailure          => None
          }(env.executionContext)

      }
      case _ => Future.successful(None)
    }
  }
}
