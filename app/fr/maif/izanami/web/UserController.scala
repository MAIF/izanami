package fr.maif.izanami.web

import fr.maif.izanami.datastores.{ConfigurationDatastore, UsersDatastore}
import fr.maif.izanami.errors.BadBodyFormat
import fr.maif.izanami.errors.EmailAlreadyUsed
import fr.maif.izanami.mail.Mails
import fr.maif.izanami.models.*
import fr.maif.izanami.models.RightLevel.Read
import fr.maif.izanami.models.Rights.*
import fr.maif.izanami.models.User.*
import fr.maif.izanami.security.JwtService
import fr.maif.izanami.services.{PostgresTransactionProvider, RightService}
import fr.maif.izanami.utils.Done
import fr.maif.izanami.utils.FutureEither
import fr.maif.izanami.utils.syntax.implicits.BetterSyntax
import fr.maif.izanami.web.ImportController.Skip
import play.api.data.validation.Constraints
import play.api.data.validation.Valid
import play.api.libs.json.*
import play.api.mvc.*

import java.util.Objects
import scala.concurrent.ExecutionContext
import scala.concurrent.Future
import scala.util.Try

class UserController(
    val controllerComponents: ControllerComponents,
    authAction: =>AuthenticatedAction,
    adminAction: =>AdminAuthAction,
    detailledAuthAction: =>DetailledAuthAction,
    tenantRightsAction: =>TenantRightsAction,
    tenantRightFilterAction: =>TenantAuthActionFactory,
    projectAuthAction: =>ProjectAuthActionFactory,
    webhookAuthAction: =>WebhookAuthActionFactory,
    keyAuthAction: =>KeyAuthActionFactory,
    rightService: =>RightService,
    jwtService: =>JwtService,
    mails: =>Mails,
    transactionProvider: =>PostgresTransactionProvider,
    // FIXME below datastore(s) should be replaced by services
    configurationDatastore: =>ConfigurationDatastore,
    usersDatastore: =>UsersDatastore
)(implicit val ec: ExecutionContext) extends BaseController {
  def sendInvitation(): Action[JsValue] = tenantRightsAction.async(parse.json) {
    implicit request =>
      {
        def handleInvitation(email: String, id: String) = {
          val token = jwtService.generateToken(
            id,
            Json.obj("invitation" -> id)
          )

          configurationDatastore
            .readFullConfiguration()
            .flatMapF(conf => {
              if (conf.invitationMode == InvitationMode.Response) {
                Created(
                  Json.obj(
                    "invitationUrl" -> s"""${mails.expositionUrl}/invitation?token=${token}"""
                  )
                ).future
              } else if (conf.invitationMode == InvitationMode.Mail) {
                mails
                  .sendInvitationMail(email, token)
                  .toResult(_ => NoContent)
              } else {
                throw new RuntimeException(
                  "Unknown invitation mode " + conf.invitationMode
                )
              }
            })
        }

        User.userInvitationReads
          .reads(request.body)
          .fold(
            _ => Future.successful(Left(BadRequest("Invalid Payload"))),
            invitation =>
              usersDatastore
                .findUserByMail(invitation.email)
                .map(maybeUser =>
                  maybeUser
                    .map(_ => EmailAlreadyUsed(invitation.email).toHttpResponse)
                    .toLeft(invitation)
                )
          )
          .map {
            case Right(invitation)
                if hasRight(
                  request.user,
                  invitation.admin,
                  invitation.rights
                ) =>
              Right(invitation)
            case Right(_) =>
              Left(Forbidden(Json.obj("message" -> "Not enough rights")))
            case left => left
          }
          .flatMap(e => {
            e.fold(
              r => r.future,
              invitation =>
                usersDatastore
                  .createInvitation(
                    invitation.email,
                    invitation.admin,
                    invitation.rights,
                    request.user.username
                  )
                  .flatMap(either =>
                    either.fold(
                      err => err.toHttpResponse.future,
                      id =>
                        handleInvitation(invitation.email, id).toResult(r => r)
                    )
                  )
            )
          })
      }
  }

  def hasRight(
      loggedInUser: UserWithTenantRights,
      admin: Boolean,
      rights: Rights
  ): Boolean = {
    val loggedInUserTenantsAdmin = loggedInUser.tenantRights.filter {
      case (_, right) =>
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

  def updateUser(user: String): Action[JsValue] = authAction.async(parse.json) {
    implicit request =>
      if (!request.user.equalsIgnoreCase(user)) {
        Forbidden(
          Json.obj(
            "message" -> "Modification of other users information is not allowed"
          )
        ).future
      } else {
        // TODO make special action that check password ?
        User.userUpdateReads.reads(request.body) match {
          case JsSuccess(updateRequest, _) => {
            usersDatastore
              .isUserValid(user, updateRequest.password)
              .flatMap {
                case Some(user) => {
                  usersDatastore
                    .updateUserInformation(user.username, updateRequest)
                    .map {
                      case Left(err) => err.toHttpResponse
                      case Right(_)  => NoContent
                    }
                }
                case None =>
                  Unauthorized(
                    Json.obj("message" -> "Wrong username / password")
                  ).future
              }
          }
          case JsError(_) => BadBodyFormat().toHttpResponse.future
        }
      }
  }

  def updateUserRightsForWebhook(
      tenant: String,
      webhook: String,
      user: String
  ): Action[JsValue] =
    webhookAuthAction(tenant, webhook, RightLevel.Admin).async(parse.json) {
      implicit request => {
        val hookName = request.user._1
        request.body
          .asOpt[JsObject]
          .fold(BadBodyFormat().toHttpResponse.future) {
            case obj if obj.fields.isEmpty => {
              rightService
                .updateUserRightsForTenant(
                  user,
                  tenant,
                  UpsertTenantRights(removedWebhookRights =
                    Set(hookName)
                  )
                )
                .toResult(_ => NoContent)
            }
            case obj => {
              (obj \ "level").asOpt[RightLevel] match {
                case None        => BadBodyFormat().toHttpResponse.future
                case Some(level) => {
                  val baseDiff = UpsertTenantRights(
                    removedWebhookRights = Set(hookName),
                    addedWebhookRights = Set(
                      UnscopedFlattenWebhookRight(
                        name = hookName,
                        level = level
                      )
                    )
                  )

                  usersDatastore.findUser(user).flatMap {
                    case Some(userWithTenantRights) => {
                      val tenantRightDiff = userWithTenantRights.tenantRights
                        .get(tenant)
                        .fold(
                          baseDiff
                            .copy(tenantWideUpdate =
                              Some(
                                TenantWideRightUpdate(
                                  level = Read,
                                  defaultProjectRight =
                                    ProjectRightLevelIncludingNoRight.None,
                                  defaultKeyRight =
                                    RightLevelIncludingNoRight.None,
                                  defaultWebhookRight =
                                    RightLevelIncludingNoRight.None
                                )
                              )
                            )
                        )(_ => baseDiff)
                      rightService
                        .updateUserRightsForTenant(
                          user,
                          tenant,
                          tenantRightDiff
                        )
                        .toResult(_ => NoContent)
                    }
                    case None =>
                      NotFound(Json.obj("message" -> "user not found")).future
                  }
                }

              }
            }
          }
      }
    }

  def updateUserRightsForKey(
      tenant: String,
      name: String,
      user: String
  ): Action[JsValue] =
    keyAuthAction(tenant, name, RightLevel.Admin).async(parse.json) {
      implicit request =>
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
                .toResult(_ => NoContent)
            case obj => {
              (obj \ "level").asOpt[RightLevel] match {
                case None        => BadBodyFormat().toHttpResponse.future
                case Some(level) => {
                  val baseDiff = UpsertTenantRights(
                    removedKeyRights = Set(name),
                    addedKeyRights =
                      Set(UnscopedFlattenKeyRight(name = name, level = level))
                  )

                  usersDatastore.findUser(user).flatMap {
                    case Some(userWithTenantRights) => {
                      val tenantRightDiff = userWithTenantRights.tenantRights
                        .get(tenant)
                        .fold(
                          baseDiff
                            .copy(tenantWideUpdate =
                              Some(
                                TenantWideRightUpdate(
                                  level = Read,
                                  defaultProjectRight =
                                    ProjectRightLevelIncludingNoRight.None,
                                  defaultKeyRight =
                                    RightLevelIncludingNoRight.None,
                                  defaultWebhookRight =
                                    RightLevelIncludingNoRight.None
                                )
                              )
                            )
                        )(_ => baseDiff)
                      rightService
                        .updateUserRightsForTenant(
                          user,
                          tenant,
                          tenantRightDiff
                        )
                        .toResult(_ => NoContent)
                    }
                    case None =>
                      NotFound(Json.obj("message" -> "user not found")).future
                  }
                }

              }
            }
          }
    }

  def updateUserRightsForProject(
      tenant: String,
      project: String,
      user: String
  ): Action[JsValue] =
    projectAuthAction(tenant, project, ProjectRightLevel.Admin).async(
      parse.json
    ) { implicit request =>
      request.body
        .asOpt[JsObject]
        .fold(BadBodyFormat().toHttpResponse.future)(obj => {
          if (obj.fields.isEmpty) {
            rightService
              .updateUserRightsForTenant(
                user,
                tenant,
                UpsertTenantRights(removedProjectRights = Set(project))
              )
              .toResult(_ => NoContent)
          } else {
            val newLevel = (obj \ "level").as[ProjectRightLevel]

            usersDatastore.findUser(user).flatMap {
              case Some(userWithTenantRights) =>
                {
                  userWithTenantRights.tenantRights.get(tenant) match {
                    case Some(_) =>
                      rightService.updateUserRightsForTenant(
                        user,
                        tenant,
                        UpsertTenantRights(addedProjectRights =
                          Set(
                            Rights.UnscopedFlattenProjectRight(
                              project,
                              level = newLevel
                            )
                          )
                        )
                      )
                    case None =>
                      rightService.updateUserRightsForTenant(
                        user,
                        tenant,
                        UpsertTenantRights(
                          tenantWideUpdate = Some(
                            TenantWideRightUpdate(
                              level = Read,
                              defaultProjectRight =
                                ProjectRightLevelIncludingNoRight.None,
                              defaultKeyRight = RightLevelIncludingNoRight.None,
                              defaultWebhookRight =
                                RightLevelIncludingNoRight.None
                            )
                          ),
                          addedProjectRights = Set(
                            Rights.UnscopedFlattenProjectRight(
                              project,
                              level = newLevel
                            )
                          )
                        )
                      )
                  }
                }.toResult(_ => NoContent)
              case None =>
                NotFound(Json.obj("message" -> "user not found")).future
            }
          }
        })
    }

  def updateUserRights(user: String): Action[JsValue] =
    adminAction.async(parse.json) { implicit request =>
      User.userRightsUpdateReads.reads(request.body) match {
        case JsSuccess(modificationRequest, _) =>
          rightService
            .updateUserRights(user, modificationRequest)
            .toResult(_ => NoContent)
        case JsError(_) => BadBodyFormat().toHttpResponse.future
      }
    }

  def updateUserRightsForTenant(
      tenant: String,
      user: String
  ): Action[JsValue] = {
    // TODO use tenantActionRight ?
    detailledAuthAction.async(parse.json) { implicit request =>
      {
        val futureRightChange: Future[Either[Result, TenantRightDiff]] =
          if ((request.body.as[JsObject]).fields.isEmpty) {
            Future.successful(Right(DeleteTenantRights))
          } else {
            User.tenantRightReads.reads(request.body) match {
              case JsError(_) => Left(BadBodyFormat().toHttpResponse).future
              case JsSuccess(value, _) => {
                usersDatastore.findUserWithCompleteRights(user).map {
                  case Some(user) => {
                    val currentRights: TenantRight =
                      user.rights.tenants.getOrElse(tenant, TenantRight(null))
                    Rights
                      .compare(
                        base = Option(currentRights),
                        modified = Option(value)
                      )
                      .toRight(NoContent)
                  }
                  case None =>
                    Left(
                      BadRequest(
                        Json.obj("message" -> s"User ${user} does not exist")
                      )
                    )
                }

              }
            }
          }

        futureRightChange.flatMap {
          case Left(value) => value.future
          case Right(diff) => {
            val authorized = diff match {
              case Rights.DeleteTenantRights =>
                request.user.hasAdminRightForTenant(tenant)
              case UpsertTenantRights(
                    maybeTenantRightUpdate,
                    addedProjectRights,
                    removedProjectRights,
                    addedKeyRights,
                    removedKeyRights,
                    addedWebhookRights,
                    removedWebhookRights
                  ) => {
                removedProjectRights
                  .concat(addedProjectRights.map(_.name))
                  .forall(project =>
                    request.user.hasAdminRightForProject(project, tenant)
                  ) &&
                removedKeyRights
                  .concat(addedKeyRights.map(_.name))
                  .forall(key =>
                    request.user.hasAdminRightForKey(key, tenant)
                  ) &&
                maybeTenantRightUpdate
                  .forall(_ => request.user.hasAdminRightForTenant(tenant)) &&
                removedWebhookRights
                  .concat(addedWebhookRights.map(_.name))
                  .forall(webhook =>
                    request.user.hasAdminRightForWebhook(webhook, tenant)
                  )
              }
            }

            if (authorized) {
              rightService
                .updateUserRightsForTenant(user, tenant, diff)
                .toResult(_ => NoContent)
            } else {
              Forbidden(Json.obj("message" -> "Not enough rights")).future
            }
          }
        }

      }
    }
  }

  def updateUserPassword(user: String): Action[JsValue] =
    authAction.async(parse.json) { implicit request =>
      if (!request.user.equalsIgnoreCase(user)) {
        Forbidden(
          "Modification of other users information is not allowed"
        ).future
      } else {
        // TODO check password during update
        User.userPasswordUpdateReads.reads(request.body) match {
          case JsSuccess(updateRequest, _) => {
            usersDatastore
              .isUserValid(user, updateRequest.oldPassword)
              .flatMap {
                case Some(user) => {
                  usersDatastore
                    .updateUserPassword(user.username, updateRequest.password)
                    .map {
                      case Left(err)    => err.toHttpResponse
                      case Right(value) => NoContent
                    }
                }
                case None =>
                  Unauthorized(
                    Json.obj("message" -> "Wrong username / password")
                  ).future
              }
          }
          case JsError(errors) => BadBodyFormat().toHttpResponse.future
        }
      }
    }

  def resetPassword(): Action[JsValue] = Action.async(parse.json) {
    implicit request =>
      (request.body \ "email")
        .asOpt[String]
        .filter(Constraints.emailAddress.apply(_) == Valid)
        .map(email => {
          usersDatastore
            .findUserByMail(email)
            .filter(_.forall(_.userType == INTERNAL))
            .flatMap {
              case Some(user) => {
                usersDatastore
                  .savePasswordResetRequest(user.username)
                  .flatMap(id => {
                    val token = jwtService.generateToken(
                      id,
                      Json.obj("reset" -> id)
                    )
                    mails
                      .sendPasswordResetEmail(email, token)
                      .toResult(_ => NoContent)
                  })
              }
              case None => NoContent.future
            }
        })
        .getOrElse(BadRequest("Bad body request").future)
  }

  def createUser(): Action[JsValue] = Action.async(parse.json) {
    implicit request =>
      val result =
        for (
          username <- (request.body \ "username")
            .asOpt[String]
            .filter(name => USERNAME_REGEXP.pattern.matcher(name).matches());
          password <- (request.body \ "password")
            .asOpt[String]
            .filter(name => PASSWORD_REGEXP.pattern.matcher(name).matches());
          token <- (request.body \ "token").asOpt[String];
          parsedToken <- jwtService.parseJWT(token).toOption;
          content <- Option(parsedToken.content);
          jsonContent <- Try {
            Json.parse(content)
          }.toOption;
          invitation <- (jsonContent \ "invitation").asOpt[String]
        ) yield {
          usersDatastore.readInvitation(invitation).flatMap {
            case Some(invitation) => {
              val user = UserWithRights(
                username = username,
                email = invitation.email,
                password = password,
                rights = invitation.rights,
                admin = invitation.admin,
                userType = INTERNAL,
                roles = Set()
              )
              usersDatastore
                .createUser(user)
                .flatMap(eitherUser => {
                  eitherUser
                    .map(user => {
                      usersDatastore.deleteInvitation(invitation.id).map {
                        _.map(_ => user)
                          .toRight(fr.maif.izanami.errors.InternalServerError())
                      }
                    })
                    .fold(err => Left(err).future, foo => foo)
                })
                .map {
                  case Right(_)    => Created(Json.toJson(user))
                  case Left(error) => error.toHttpResponse
                }
            }
            case None =>
              NotFound(Json.obj("message" -> "Invitation not found")).future
          }
        }
      result.getOrElse(BadBodyFormat().toHttpResponse.future)
  }

  def readUsers(): Action[AnyContent] = authAction.async { implicit request =>
    rightService
      .findVisibleUsers(request.user)
      .map(users => {
        Ok(Json.toJson(users))
      })
  }

  def searchUsers(query: String, count: Integer): Action[AnyContent] =
    authAction.async { implicit request =>
      var effectiveCount: Integer = Objects.requireNonNullElse(count, 10)
      if (effectiveCount > 100) effectiveCount = 100
      usersDatastore
        .searchUsers(query, effectiveCount)
        .map(usernames => Ok(Json.toJson(usernames)))
    }

  def inviteUsersToProject(tenant: String, project: String): Action[JsValue] =
    projectAuthAction(tenant, project, ProjectRightLevel.Admin).async(
      parse.json
    ) { implicit request =>
      request.body
        .asOpt[JsArray]
        .map(arr =>
          arr.value
            .map(value => {
              for (
                username <- (value \ "username").asOpt[String];
                right <- (value \ "level").asOpt[ProjectRightLevel]
              ) yield (username, right)
            })
            .filter(_.isDefined)
            .map(_.get)
            .toSeq
        ) match {
        case Some(seq) => {
          val userByLevel = seq.groupMap(_._2)(_._1)
          transactionProvider.executeInTransaction(conn => {
            userByLevel
              .foldLeft(
                FutureEither.success(Done.done())
              )((future, t) => {
                future.flatMap(_ => {
                  val rightDiff = UpsertTenantRights(addedProjectRights =
                    Set(
                      UnscopedFlattenProjectRight(
                        name = project,
                        level = t._1
                      )
                    )
                  )
                  rightService.updateUsersRightsForTenant(
                    targetUsers = t._2.toSet,
                    tenant = tenant,
                    diff = rightDiff,
                    conn = Some(conn),
                    conflictStrategy = Skip
                  )
                })
              })
              .toResult(_ => NoContent)
          })

        }
        case None => BadBodyFormat().toHttpResponse.future
      }
    }

  def inviteUsersToTenant(tenant: String): Action[JsValue] =
    tenantRightFilterAction(tenant, RightLevel.Admin).async(parse.json) {
      implicit request =>
        request.body
          .asOpt[JsArray]
          .map(arr =>
            arr.value
              .map(value => {
                for (
                  username <- (value \ "username").asOpt[String];
                  right <- (value \ "level").asOpt[RightLevel]
                ) yield (username, right)
              })
              .filter(_.isDefined)
              .map(_.get)
              .toSeq
          ) match {
          case Some(seq) => {
            val userByLevel = seq.groupMap(_._2)(_._1)
            transactionProvider.executeInTransaction(conn => {
              userByLevel
                .foldLeft(
                  FutureEither.success(Done.done())
                )((future, t) => {
                  future.flatMap(_ => {
                    val rightDiff =
                      UpsertTenantRights(tenantWideUpdate =
                        Some(
                          TenantWideRightUpdate(
                            level = t._1,
                            defaultProjectRight =
                              ProjectRightLevelIncludingNoRight.None,
                            defaultKeyRight = RightLevelIncludingNoRight.None,
                            defaultWebhookRight =
                              RightLevelIncludingNoRight.None
                          )
                        )
                      )
                    rightService.updateUsersRightsForTenant(
                      targetUsers = t._2.toSet,
                      tenant = tenant,
                      diff = rightDiff,
                      conn = Some(conn),
                      conflictStrategy = Skip
                    )
                  })

                })
                .toResult(_ => NoContent)
            })
          }
          case None => BadBodyFormat().toHttpResponse.future
        }
    }

  def readUser(user: String): Action[AnyContent] = adminAction.async {
    implicit request =>
      rightService
        .findUserWithCompleteRights(user)
        .toResult(user => Ok(Json.toJson(user)))
  }

  def readUserForTenant(tenant: String, user: String): Action[AnyContent] =
    tenantRightFilterAction(tenant, RightLevel.Admin).async {
      implicit request =>
        rightService
          .findUserRightsForTenant(user, tenant)
          .toResult(user => Ok(Json.toJson(user)))
    }

  def readUsersForTenant(tenant: String): Action[AnyContent] =
    tenantRightFilterAction(tenant, RightLevel.Admin).async {
      implicit request =>
        rightService
          .findUsersForTenant(tenant)
          .map(users => Ok(Json.toJson(users)))
    }

  def readUsersForProject(tenant: String, project: String): Action[AnyContent] =
    projectAuthAction(tenant, project, ProjectRightLevel.Admin).async {
      implicit request =>
        rightService
          .findUsersForProject(tenant, project)
          .map(users => Ok(Json.toJson(users)))
    }

  def readUsersForWebhook(tenant: String, id: String): Action[AnyContent] = {
    webhookAuthAction(
      tenant = tenant,
      webhook = id,
      minimumLevel = RightLevel.Admin
    ).async { implicit request =>
      rightService
        .findUsersForWebhook(tenant, id)
        .map(ws => Ok(Json.toJson(ws)))
    }
  }

  def readUsersForKey(tenant: String, name: String): Action[AnyContent] = {
    keyAuthAction(tenant = tenant, key = name, minimumLevel = RightLevel.Admin)
      .async { implicit request =>
        rightService
          .findUsersForKey(tenant, name)
          .map(ws => Ok(Json.toJson(ws)))
      }
  }

  def deleteUser(user: String): Action[AnyContent] = adminAction.async {
    implicit request =>
      if (request.user.equals(user)) {
        Future.successful(
          BadRequest(Json.obj("message" -> "User can't delete itself !"))
        )
      } else {
        usersDatastore.deleteUser(user).map(_ => NoContent)
      }
  }

  def readRights(): Action[AnyContent] = authAction.async { implicit request =>
    usersDatastore
      .findUserWithCompleteRights(request.user)
      .map {
        case Some(user) => Ok(Json.toJson(user)(User.userRightsWrites))
        case None => NotFound(Json.obj("message" -> "User does not exist"))
      }
  }

  def reinitializePassword(): Action[JsValue] = Action.async(parse.json) {
    implicit request =>
      val result =
        for (
          password <- (request.body \ "password")
            .asOpt[String]
            .filter(name => PASSWORD_REGEXP.pattern.matcher(name).matches());
          token <- (request.body \ "token").asOpt[String];
          parsedToken <- jwtService.parseJWT(token).toOption;
          content <- Option(parsedToken.content);
          jsonContent <- Try {
            Json.parse(content)
          }.toOption;
          reset <- (jsonContent \ "reset").asOpt[String]
        ) yield {
          usersDatastore
            .findPasswordResetRequest(reset)
            .flatMap {
              case Some(username) => {
                usersDatastore
                  .updateUserPassword(username, password)
                  .flatMap(_ =>
                    usersDatastore.deletePasswordResetRequest(reset)
                  )
                  .map(_ => NoContent)
              }
              case None =>
                NotFound(
                  Json.obj(
                    "message" -> "No password reset pending for this user"
                  )
                ).future
            }
        }

      result.getOrElse(BadBodyFormat().toHttpResponse.future)
  }

}
