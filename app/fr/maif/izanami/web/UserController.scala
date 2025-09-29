package fr.maif.izanami.web

import fr.maif.izanami.env.Env
import fr.maif.izanami.errors.{BadBodyFormat, EmailAlreadyUsed, IzanamiError}
import fr.maif.izanami.models.Rights._
import fr.maif.izanami.models.User._
import fr.maif.izanami.models._
import fr.maif.izanami.services.RightService
import fr.maif.izanami.utils.syntax.implicits.BetterSyntax
import fr.maif.izanami.web.ImportController.Skip
import play.api.data.validation.{Constraints, Valid}
import play.api.libs.json._
import play.api.mvc._

import java.util.Objects
import scala.concurrent.{ExecutionContext, Future}
import scala.util.Try

class UserController(
    val env: Env,
    val controllerComponents: ControllerComponents,
    val authAction: AuthenticatedAction,
    val adminAction: AdminAuthAction,
    val detailledAuthAction: DetailledAuthAction,
    val tenantRightsAction: TenantRightsAction,
    val tenantRightFilterAction: TenantAuthActionFactory,
    val projectAuthAction: ProjectAuthActionFactory,
    val webhookAuthAction: WebhookAuthActionFactory,
    val keyAuthAction: KeyAuthActionFactory,
    val rightService: RightService
) extends BaseController {
  implicit val ec: ExecutionContext = env.executionContext;

  def hasRight(loggedInUser: UserWithTenantRights, admin: Boolean, rights: Rights): Boolean = {
    val loggedInUserTenantsAdmin = loggedInUser.tenantRights.filter { case (_, right) =>
      right == RightLevel.Admin
    }.keySet
    if (!loggedInUser.admin && loggedInUserTenantsAdmin.isEmpty) {
      false
    } else if (admin) {
      loggedInUser.admin
    } else {
      val tenants = rights.tenants.keySet
      loggedInUser.admin || tenants.subsetOf(loggedInUserTenantsAdmin)
    }
  }

  def sendInvitation(): Action[JsValue] = tenantRightsAction.async(parse.json) { implicit request =>
    {
      def handleInvitation(email: String, id: String) = {
        val token = env.jwtService.generateToken(
          id,
          Json.obj("invitation" -> id)
        )

        env.datastores.configuration
          .readFullConfiguration()
          .flatMapF(conf => {
            if (conf.invitationMode == InvitationMode.Response) {
              Created(Json.obj("invitationUrl" -> s"""${env.expositionUrl}/invitation?token=${token}""")).future
            } else if (conf.invitationMode == InvitationMode.Mail) {
              env.mails
                .sendInvitationMail(email, token)
                .toResult(_ => NoContent)
            } else {
              throw new RuntimeException("Unknown invitation mode " + conf.invitationMode)
            }
          })
      }

      User.userInvitationReads
        .reads(request.body)
        .fold(
          _ => Future.successful(Left(BadRequest("Invalid Payload"))),
          invitation =>
            env.datastores.users
              .findUserByMail(invitation.email)
              .map(maybeUser =>
                maybeUser.map(_ => EmailAlreadyUsed(invitation.email).toHttpResponse).toLeft(invitation)
              )
        )
        .map {
          case Right(invitation) if hasRight(request.user, invitation.admin, invitation.rights) => Right(invitation)
          case Right(_)                                                                         => Left(Forbidden(Json.obj("message" -> "Not enough rights")))
          case left                                                                             => left
        }
        .flatMap(e => {
          e.fold(
            r => r.future,
            invitation =>
              env.datastores.users
                .createInvitation(invitation.email, invitation.admin, invitation.rights, request.user.username)
                .flatMap(either =>
                  either.fold(err => err.toHttpResponse.future, id => handleInvitation(invitation.email, id).toResult(r => r))
                )
          )
        })
    }
  }

  def updateUser(user: String): Action[JsValue] = authAction.async(parse.json) { implicit request =>
    if (!request.user.username.equalsIgnoreCase(user)) {
      Forbidden(Json.obj("message" -> "Modification of other users information is not allowed")).future
    } else {
      // TODO make special action that check password ?
      User.userUpdateReads.reads(request.body) match {
        case JsSuccess(updateRequest, _) => {
          env.datastores.users
            .isUserValid(user, updateRequest.password)
            .flatMap {
              case Some(user) => {
                env.datastores.users.updateUserInformation(user.username, updateRequest).map {
                  case Left(err) => err.toHttpResponse
                  case Right(_)  => NoContent
                }
              }
              case None       => Unauthorized(Json.obj("message" -> "Wrong username / password")).future
            }
        }
        case JsError(_)                  => BadBodyFormat().toHttpResponse.future
      }
    }
  }

  def updateUserRightsForWebhook(tenant: String, webhook: String, user: String): Action[JsValue] =
    webhookAuthAction(tenant, webhook, RightLevel.Admin).async(parse.json) { implicit request =>
      request.body
        .asOpt[JsObject]
        .fold(BadBodyFormat().toHttpResponse.future) {
          case obj if obj.fields.isEmpty =>
            rightService
              .updateUserRightsForTenant(
                user,
                tenant,
                UpsertTenantRights(removedWebhookRights = Set(request.hookName))
              )
              .map(_ => NoContent)
          case obj                       => {
            (obj \ "level").asOpt[RightLevel] match {
              case None        => BadBodyFormat().toHttpResponse.future
              case Some(level) => {
                val baseDiff = UpsertTenantRights(
                  removedWebhookRights = Set(request.hookName),
                  addedWebhookRights = Set(UnscopedFlattenWebhookRight(name = request.hookName, level = level))
                )

                env.datastores.users.findUser(user).flatMap {
                  case Some(userWithTenantRights) => {
                    val tenantRightDiff = userWithTenantRights.tenantRights
                      .get(tenant)
                      .fold(baseDiff.copy(addedTenantRight = Some(UnscopedFlattenTenantRight(RightLevel.Read))))(_ =>
                        baseDiff
                      )
                    rightService
                      .updateUserRightsForTenant(user, tenant, tenantRightDiff)
                      .map(_ => NoContent)
                  }
                  case None                       => NotFound(Json.obj("message" -> "user not found")).future
                }
              }

            }
          }
        }
    }

  def updateUserRightsForKey(tenant: String, name: String, user: String): Action[JsValue] =
    keyAuthAction(tenant, name, RightLevel.Admin).async(parse.json) { implicit request =>
      request.body
        .asOpt[JsObject]
        .fold(BadBodyFormat().toHttpResponse.future) {
          case obj if obj.fields.isEmpty =>
            rightService
              .updateUserRightsForTenant(
                user,
                tenant,
                UpsertTenantRights(removedKeyRights = Set(name))
              )
              .map(_ => NoContent)
          case obj                       => {
            (obj \ "level").asOpt[RightLevel] match {
              case None        => BadBodyFormat().toHttpResponse.future
              case Some(level) => {
                val baseDiff = UpsertTenantRights(
                  removedKeyRights = Set(name),
                  addedKeyRights = Set(UnscopedFlattenKeyRight(name = name, level = level))
                )

                env.datastores.users.findUser(user).flatMap {
                  case Some(userWithTenantRights) => {
                    val tenantRightDiff = userWithTenantRights.tenantRights
                      .get(tenant)
                      .fold(baseDiff.copy(addedTenantRight = Some(UnscopedFlattenTenantRight(RightLevel.Read))))(_ =>
                        baseDiff
                      )
                    rightService
                      .updateUserRightsForTenant(user, tenant, tenantRightDiff)
                      .map(_ => NoContent)
                  }
                  case None                       => NotFound(Json.obj("message" -> "user not found")).future
                }
              }

            }
          }
        }
    }

  def updateUserRightsForProject(tenant: String, project: String, user: String): Action[JsValue] =
    projectAuthAction(tenant, project, ProjectRightLevel.Admin).async(parse.json) { implicit request =>
      request.body
        .asOpt[JsObject]
        .fold(BadBodyFormat().toHttpResponse.future)(obj => {
          if (obj.fields.isEmpty) {
            rightService
              .updateUserRightsForTenant(user, tenant, UpsertTenantRights(removedProjectRights = Set(project)))
              .map(e => e.fold(err => err.toHttpResponse, _ => NoContent))
          } else {
            val newLevel = (obj \ "level").as[ProjectRightLevel]

            env.datastores.users.findUser(user).flatMap {
              case Some(userWithTenantRights) =>
                {
                  userWithTenantRights.tenantRights.get(tenant) match {
                    case Some(_) =>
                      rightService.updateUserRightsForTenant(
                        user,
                        tenant,
                        UpsertTenantRights(addedProjectRights =
                          Set(Rights.UnscopedFlattenProjectRight(project, level = newLevel))
                        )
                      )
                    case None    =>
                      rightService.updateUserRightsForTenant(
                        user,
                        tenant,
                        UpsertTenantRights(
                          addedTenantRight = Some(Rights.UnscopedFlattenTenantRight(RightLevel.Read)),
                          addedProjectRights = Set(Rights.UnscopedFlattenProjectRight(project, level = newLevel))
                        )
                      )
                  }
                }.map(e => e.fold(err => err.toHttpResponse, _ => NoContent))
              case None                       => NotFound(Json.obj("message" -> "user not found")).future
            }
          }
        })
    }

  def updateUserRights(user: String): Action[JsValue] = adminAction.async(parse.json) { implicit request =>
    User.userRightsUpdateReads.reads(request.body) match {
      case JsSuccess(modificationRequest, _) =>
        rightService.updateUserRights(user, modificationRequest).map {
          case Left(err) => err.toHttpResponse
          case Right(_)  => NoContent
        }
      case JsError(_)                        => BadBodyFormat().toHttpResponse.future
    }
  }

  def updateUserRightsForTenant(tenant: String, user: String): Action[JsValue] = {
    // TODO use tenantActionRight ?
    detailledAuthAction.async(parse.json) { implicit request =>
      {

        val futureRightChange: Future[Either[Result, TenantRightDiff]] =
          if ((request.body.as[JsObject]).fields.isEmpty) {
            Future.successful(Right(DeleteTenantRights))
          } else {
            User.tenantRightReads.reads(request.body) match {
              case JsError(_)          => Left(BadBodyFormat().toHttpResponse).future
              case JsSuccess(value, _) => {
                env.datastores.users.findUserWithCompleteRights(user).map {
                  case Some(user) => {
                    val currentRights: TenantRight = user.rights.tenants.getOrElse(tenant, TenantRight(null))
                    Rights
                      .compare(base = Option(currentRights), modified = Option(value))
                      .toRight(NoContent)
                  }
                  case None       => Left(BadRequest(Json.obj("message" -> s"User ${user} does not exist")))
                }

              }
            }
          }

        futureRightChange.flatMap {
          case Left(value) => value.future
          case Right(diff) => {
            val authorized = diff match {
              case Rights.DeleteTenantRights => request.user.hasAdminRightForTenant(tenant)
              case UpsertTenantRights(
                    addedTenantRight,
                    addedProjectRights,
                    removedProjectRights,
                    addedKeyRights,
                    removedKeyRights,
                    addedWebhookRights,
                    removedWebhookRights
                  ) => {
                removedProjectRights
                  .concat(addedProjectRights.map(_.name))
                  .forall(project => request.user.hasAdminRightForProject(project, tenant)) &&
                removedKeyRights
                  .concat(addedKeyRights.map(_.name))
                  .forall(key => request.user.hasAdminRightForKey(key, tenant)) &&
                addedTenantRight
                  .forall(_ => request.user.hasAdminRightForTenant(tenant)) &&
                removedWebhookRights
                  .concat(addedWebhookRights.map(_.name))
                  .forall(webhook => request.user.hasAdminRightForWebhook(webhook, tenant))
              }
            }

            if (authorized) {
              rightService
                .updateUserRightsForTenant(user, tenant, diff)
                .map(e => e.fold(err => err.toHttpResponse, _ => NoContent))
            } else {
              Forbidden(Json.obj("message" -> "Not enough rights")).future
            }
          }
        }

      }
    }
  }

  def updateUserPassword(user: String): Action[JsValue] = authAction.async(parse.json) { implicit request =>
    if (!request.user.username.equalsIgnoreCase(user)) {
      Forbidden("Modification of other users information is not allowed").future
    } else {
      // TODO check password during update
      User.userPasswordUpdateReads.reads(request.body) match {
        case JsSuccess(updateRequest, _) => {
          env.datastores.users
            .isUserValid(user, updateRequest.oldPassword)
            .flatMap {
              case Some(user) => {
                env.datastores.users.updateUserPassword(user.username, updateRequest.password).map {
                  case Left(err)    => err.toHttpResponse
                  case Right(value) => NoContent
                }
              }
              case None       => Unauthorized(Json.obj("message" -> "Wrong username / password")).future
            }
        }
        case JsError(errors)             => BadBodyFormat().toHttpResponse.future
      }
    }
  }

  def resetPassword(): Action[JsValue] = Action.async(parse.json) { implicit request =>
    (request.body \ "email")
      .asOpt[String]
      .filter(Constraints.emailAddress.apply(_) == Valid)
      .map(email => {
        env.datastores.users
          .findUserByMail(email)
          .filter(_.forall(_.userType == INTERNAL))
          .flatMap {
            case Some(user) => {
              env.datastores.users
                .savePasswordResetRequest(user.username)
                .flatMap(id => {
                  val token = env.jwtService.generateToken(
                    id,
                    Json.obj("reset" -> id)
                  )
                  env.mails.sendPasswordResetEmail(email, token).toResult(_ => NoContent)
                })
            }
            case None       => NoContent.future
          }
      })
      .getOrElse(BadRequest("Bad body request").future)
  }

  def createUser(): Action[JsValue] = Action.async(parse.json) { implicit request =>
    val result =
      for (
        username    <- (request.body \ "username").asOpt[String].filter(name => USERNAME_REGEXP.pattern.matcher(name).matches());
        password    <- (request.body \ "password").asOpt[String].filter(name => PASSWORD_REGEXP.pattern.matcher(name).matches());
        token       <- (request.body \ "token").asOpt[String];
        parsedToken <- env.jwtService.parseJWT(token).toOption;
        content     <- Option(parsedToken.content);
        jsonContent <- Try {
                         Json.parse(content)
                       }.toOption;
        invitation  <- (jsonContent \ "invitation").asOpt[String]
      ) yield {
        env.datastores.users.readInvitation(invitation).flatMap {
          case Some(invitation) => {
            val user = UserWithRights(
              username = username,
              email = invitation.email,
              password = password,
              rights = invitation.rights,
              admin = invitation.admin,
              userType = INTERNAL
            )
            env.datastores.users
              .createUser(user)
              .flatMap(eitherUser => {
                eitherUser
                  .map(user => {
                    env.datastores.users.deleteInvitation(invitation.id).map {
                      _.map(_ => user).toRight(fr.maif.izanami.errors.InternalServerError())
                    }
                  })
                  .fold(err => Left(err).future, foo => foo)
              })
              .map {
                case Right(_)    => Created(Json.toJson(user))
                case Left(error) => error.toHttpResponse
              }
          }
          case None             => NotFound(Json.obj("message" -> "Invitation not found")).future
        }
      }
    result.getOrElse(BadBodyFormat().toHttpResponse.future)
  }

  def readUsers(): Action[AnyContent] = authAction.async { implicit request =>
    env.datastores.users
      .findVisibleUsers(request.user.username)
      .map(users => {
        Ok(Json.toJson(users))
      })
  }

  def searchUsers(query: String, count: Integer): Action[AnyContent] = authAction.async { implicit request =>
    var effectiveCount: Integer = Objects.requireNonNullElse(count, 10)
    if (effectiveCount > 100) effectiveCount = 100
    env.datastores.users
      .searchUsers(query, effectiveCount)
      .map(usernames => Ok(Json.toJson(usernames)))
  }

  def inviteUsersToProject(tenant: String, project: String): Action[JsValue] =
    projectAuthAction(tenant, project, ProjectRightLevel.Admin).async(parse.json) { implicit request =>
      request.body
        .asOpt[JsArray]
        .map(arr =>
          arr.value
            .map(value => {
              for (
                username <- (value \ "username").asOpt[String];
                right    <- (value \ "level").asOpt[ProjectRightLevel]
              ) yield (username, right)
            })
            .filter(_.isDefined)
            .map(_.get)
            .toSeq
        ) match {
        case Some(seq) => {
          val userByLevel = seq.groupMap(_._2)(_._1)
          env.postgresql.executeInTransaction(conn => {
            userByLevel
              .foldLeft(Future.successful(Right(())): Future[Either[IzanamiError, Unit]])((future, t) => {
                future.flatMap {
                  case Left(error)  => Future.successful(Left(error))
                  case Right(value) => {
                    val rightDiff = UpsertTenantRights(addedProjectRights =
                      Set(UnscopedFlattenProjectRight(name = project, level = t._1))
                    )
                    rightService.updateUsersRightsForTenant(
                      targetUsers = t._2.toSet,
                      tenant = tenant,
                      diff = rightDiff,
                      conn = Some(conn),
                      conflictStrategy = Skip
                    )
                  }
                }

              })
              .map {
                case Left(error) => error.toHttpResponse
                case Right(_)    => NoContent
              }
          })

        }
        case None      => BadBodyFormat().toHttpResponse.future
      }
    }

  def inviteUsersToTenant(tenant: String): Action[JsValue] =
    tenantRightFilterAction(tenant, RightLevel.Admin).async(parse.json) { implicit request =>
      request.body
        .asOpt[JsArray]
        .map(arr =>
          arr.value
            .map(value => {
              for (
                username <- (value \ "username").asOpt[String];
                right    <- (value \ "level").asOpt[RightLevel]
              ) yield (username, right)
            })
            .filter(_.isDefined)
            .map(_.get)
            .toSeq
        ) match {
        case Some(seq) => {
          val userByLevel = seq.groupMap(_._2)(_._1)
          env.postgresql.executeInTransaction(conn => {
            userByLevel
              .foldLeft(Future.successful(Right(())): Future[Either[IzanamiError, Unit]])((future, t) => {
                future.flatMap {
                  case Left(error) => Future.successful(Left(error))
                  case Right(_)    => {
                    val rightDiff =
                      UpsertTenantRights(addedTenantRight = Some(UnscopedFlattenTenantRight(level = t._1)))
                    rightService.updateUsersRightsForTenant(
                      targetUsers = t._2.toSet,
                      tenant = tenant,
                      diff = rightDiff,
                      conn = Some(conn),
                      conflictStrategy = Skip
                    )
                  }
                }

              })
              .map {
                case Left(error) => error.toHttpResponse
                case Right(_)    => NoContent
              }
          })
        }
        case None      => BadBodyFormat().toHttpResponse.future
      }
    }

  def readUser(user: String): Action[AnyContent] = adminAction.async { implicit request =>
    env.datastores.users
      .findUserWithCompleteRights(user)
      .map {
        case Some(user) => Ok(Json.toJson(user))
        case None       => NotFound(Json.obj("message" -> "user does not exist"))
      }
  }

  def readUserForTenant(tenant: String, user: String): Action[AnyContent] =
    tenantRightFilterAction(tenant, RightLevel.Admin).async { implicit request =>
      env.datastores.users
        .findCompleteRightsFromTenant(user, Set(tenant))
        .map {
          case Some(user) => Ok(Json.toJson(user))
          case None       => NotFound(Json.obj("message" -> "user does not exist"))
        }
    }

  def readUsersForTenant(tenant: String): Action[AnyContent] =
    tenantRightFilterAction(tenant, RightLevel.Admin).async { implicit request =>
      env.datastores.users
        .findUsersForTenant(tenant)
        .map(users => Ok(Json.toJson(users)))
    }

  def readUsersForProject(tenant: String, project: String): Action[AnyContent] =
    projectAuthAction(tenant, project, ProjectRightLevel.Admin).async { implicit request =>
      env.datastores.users
        .findUsersForProject(tenant, project)
        .map(users => Ok(Json.toJson(users)))
    }

  def readUsersForWebhook(tenant: String, id: String): Action[AnyContent] = {
    webhookAuthAction(tenant = tenant, webhook = id, minimumLevel = RightLevel.Admin).async { implicit request =>
      env.datastores.users.findUsersForWebhook(tenant, id).map(ws => Ok(Json.toJson(ws)))
    }
  }

  def readUsersForKey(tenant: String, name: String): Action[AnyContent] = {
    keyAuthAction(tenant = tenant, key = name, minimumLevel = RightLevel.Admin).async { implicit request =>
      env.datastores.users.findUsersForKey(tenant, name).map(ws => Ok(Json.toJson(ws)))
    }
  }

  def deleteUser(user: String): Action[AnyContent] = adminAction.async { implicit request =>
    if (request.user.username.equals(user)) {
      Future.successful(BadRequest(Json.obj("message" -> "User can't delete itself !")))
    } else {
      env.datastores.users.deleteUser(user).map(_ => NoContent)
    }
  }

  def readRights(): Action[AnyContent] = authAction.async { implicit request =>
    env.datastores.users
      .findUserWithCompleteRights(request.user.username)
      .map {
        case Some(user) => Ok(Json.toJson(user)(User.userRightsWrites))
        case None       => NotFound(Json.obj("message" -> "User does not exist"))
      }
  }

  def reinitializePassword(): Action[JsValue] = Action.async(parse.json) { implicit request =>
    val result =
      for (
        password    <- (request.body \ "password").asOpt[String].filter(name => PASSWORD_REGEXP.pattern.matcher(name).matches());
        token       <- (request.body \ "token").asOpt[String];
        parsedToken <- env.jwtService.parseJWT(token).toOption;
        content     <- Option(parsedToken.content);
        jsonContent <- Try {
                         Json.parse(content)
                       }.toOption;
        reset       <- (jsonContent \ "reset").asOpt[String]
      ) yield {
        env.datastores.users
          .findPasswordResetRequest(reset)
          .flatMap {
            case Some(username) => {
              env.datastores.users
                .updateUserPassword(username, password)
                .flatMap(_ => env.datastores.users.deletePasswordResetRequest(reset))
                .map(_ => NoContent)
            }
            case None           => NotFound(Json.obj("message" -> "No password reset pending for this user")).future
          }
      }

    result.getOrElse(BadBodyFormat().toHttpResponse.future)
  }

}
