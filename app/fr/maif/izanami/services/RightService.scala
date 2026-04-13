package fr.maif.izanami.services

import fr.maif.izanami.RoleRightMode
import fr.maif.izanami.RoleRightMode.Initial
import fr.maif.izanami.RoleRightMode.Supervised
import fr.maif.izanami.datastores.UserIdentification
import fr.maif.izanami.env.Env
import fr.maif.izanami.errors.CantUpdateOIDCUser
import fr.maif.izanami.errors.IzanamiError
import fr.maif.izanami.errors.RightComplianceError
import fr.maif.izanami.errors.UserDoesNotExist
import fr.maif.izanami.errors.UserNotFound
import fr.maif.izanami.events.ConfigurationUpdated
import fr.maif.izanami.events.EventService
import fr.maif.izanami.models.GeneralAtomicRight
import fr.maif.izanami.models.KeyRightUnit
import fr.maif.izanami.models.OAuth2Configuration
import fr.maif.izanami.models.OIDC
import fr.maif.izanami.models.ProjectAtomicRight
import fr.maif.izanami.models.ProjectRightLevel
import fr.maif.izanami.models.ProjectRightLevel.ProjectRightOrdering
import fr.maif.izanami.models.ProjectRightLevelIncludingNoRight
import fr.maif.izanami.models.ProjectRightLevelIncludingNoRight.ProjectRightLevelIncludingNoRightOrdering
import fr.maif.izanami.models.ProjectRightUnit
import fr.maif.izanami.models.ProjectScopedUser
import fr.maif.izanami.models.RightLevel
import fr.maif.izanami.models.RightLevel.Admin
import fr.maif.izanami.models.RightLevel.RightOrdering
import fr.maif.izanami.models.RightLevelIncludingNoRight
import fr.maif.izanami.models.RightLevelIncludingNoRight.RightLevelIncludingNoRightOrdering
import fr.maif.izanami.models.RightUnit
import fr.maif.izanami.models.Rights
import fr.maif.izanami.models.Rights.DeleteTenantRights
import fr.maif.izanami.models.Rights.RightDiff
import fr.maif.izanami.models.Rights.TenantRightDiff
import fr.maif.izanami.models.Rights.TenantWideRightUpdate
import fr.maif.izanami.models.Rights.UnscopedFlattenKeyRight
import fr.maif.izanami.models.Rights.UnscopedFlattenProjectRight
import fr.maif.izanami.models.Rights.UnscopedFlattenWebhookRight
import fr.maif.izanami.models.Rights.UpsertTenantRights
import fr.maif.izanami.models.SingleItemScopedUser
import fr.maif.izanami.models.TenantRight
import fr.maif.izanami.models.TenantRightWithMaxRights
import fr.maif.izanami.models.User
import fr.maif.izanami.models.UserRightsUpdateRequest
import fr.maif.izanami.models.UserTrait
import fr.maif.izanami.models.UserWithCompleteRightForOneTenant
import fr.maif.izanami.models.UserWithRights
import fr.maif.izanami.models.UserWithSingleLevelRight
import fr.maif.izanami.models.UserWithTenantRights
import fr.maif.izanami.models.WebhookRightUnit
import fr.maif.izanami.services.RightService.DEFAULT_ROLE
import fr.maif.izanami.services.RightService.RightsByRole
import fr.maif.izanami.services.RightService.keepHigher
import fr.maif.izanami.utils.Done
import fr.maif.izanami.utils.FutureEither
import fr.maif.izanami.utils.syntax.implicits.BetterFuture
import fr.maif.izanami.utils.syntax.implicits.BetterFutureEither
import fr.maif.izanami.utils.syntax.implicits.BetterSyntax
import fr.maif.izanami.web.ImportController.ImportConflictStrategy
import fr.maif.izanami.web.ImportController.Replace
import io.vertx.sqlclient.SqlConnection
import org.apache.pekko.actor.ActorSystem
import org.apache.pekko.stream.Materializer.matFromSystem
import org.apache.pekko.stream.SharedKillSwitch
import play.api.Logger
import play.api.libs.json.JsError
import play.api.libs.json.JsSuccess
import play.api.libs.json.Json
import play.api.libs.json.Reads
import play.api.libs.json.Writes

import java.util.UUID
import java.util.concurrent.atomic.AtomicReference
import scala.collection.mutable.ArrayBuffer
import scala.concurrent.ExecutionContext
import scala.concurrent.Future

case class RightCheckConfirmation(
    user: User,
    projectNameById: Map[UUID, String] = Map(),
    webhookNameById: Map[UUID, String] = Map()
) {
  def projectName(id: UUID): Option[String] = projectNameById.get(id)
  def webhookName(id: UUID): Option[String] = webhookNameById.get(id)

  def webhookIdentifiers(
      webhook: WebhookIdentification
  ): Option[EntityIdentifiers] = webhook match {
    case WebhookNameIdentification(name) =>
      webhookNameById.find { (_, keyName) => name == keyName }.map {
        (id, name) => EntityIdentifiers(id = id, name = name)
      }
    case WebhookIdIdentification(id) =>
      webhookNameById.get(id).map { name =>
        EntityIdentifiers(id = id, name = name)
      }
  }
}

case class EntityIdentifiers(name: String, id: UUID)

class RightService(
    private val env: Env,
    private val eventService: EventService
) {
  private val logger = Logger("izanami.right-service")
  private implicit val executionContext: ExecutionContext = env.executionContext
  private implicit val actorSystem: ActorSystem = env.actorSystem
  private var sourceKillSwitch: Option[SharedKillSwitch] = Option.empty
  private val currentOidcConfiguration
      : AtomicReference[Option[OAuth2Configuration]] = new AtomicReference(
    Option.empty
  )
  private val usersDatastore = env.datastores.users

  def onStart(): Future[Done] = {

    val initialOidcConfiguration = env.typedConfiguration.openid
      .flatMap(
        _.toIzanamiOAuth2Configuration
      )
      .map(conf => FutureEither.success(Some(conf)))
      .getOrElse(
        env.datastores.configuration
          .readFullConfiguration()
          .map(_.oidcConfiguration)
      )

    initialOidcConfiguration
      .map(maybeConfig => {

        this.currentOidcConfiguration.set(maybeConfig)

        val sourceDescriptor = eventService.consumeGlobal()
        sourceKillSwitch = Some(sourceDescriptor.killswitch)
        sourceDescriptor.source.runForeach {
          case c: ConfigurationUpdated => {
            currentOidcConfiguration.set(c.newConfiguration.oidcConfiguration)
          }
          case _ => ()
        }

        Done.done()
      })
      .value
      .map(e => Done.done())

  }

  def onStop(): Done = {
    sourceKillSwitch.foreach(ks => ks.shutdown())
    Done.done()
  }

  private def canUpdateRightsForUser(user: UserTrait): Boolean = {
    canUpdateRightsForUsers(Set(user))
  }

  private def canUpdateRightsForUsers(users: Set[UserTrait]): Boolean = {
    if (roleRightMode.contains(Supervised)) {
      users.nonEmpty && users.forall(u => u.userType != OIDC)
    } else {
      true
    }
  }

  def generateRightForNewUser(roles: Set[String]): CompleteRights = {
    val defaultRightsToApply = defaultRights
      .map(r =>
        RightService
          .effectiveRights(r, roles)
      )
      .getOrElse(CompleteRights.EMPTY)

    roleRightMode match {
      case Some(Initial) => {
        val maxRights =
          maxRightsByRoles.map(mr => CompleteRights.maxRightsToApply(roles, mr))
        val rightCompliance = maxRights
          .map(r => defaultRightsToApply.checkCompliance(r))
          .getOrElse(
            MaxRightComplianceResult.empty
          )
        if (!rightCompliance.isEmpty) {
          logger.error(
            s"Configured max rights are below configured default rights, therefore default rights are reduced to max rights (roles are ${roles.mkString(",")})"
          )
          defaultRightsToApply.updateToComplyWith(rightCompliance)
        } else {
          defaultRightsToApply
        }
      }
      case Some(Supervised) => defaultRightsToApply
      case None             => CompleteRights.EMPTY
    }
  }

  def updateUserRightsIfNeeded(
      user: UserWithRights,
      newRoles: Set[String],
      conn: Option[SqlConnection]
  ): FutureEither[Option[MaxRightComplianceResult]] = {
    roleRightMode match {
      case Some(Initial) => {
        val maxRights = maxRightsByRoles.map(mr =>
          CompleteRights.maxRightsToApply(newRoles, mr)
        )
        val maxRightComplanceResult = maxRights
          .map(r =>
            CompleteRights(
              user.rights.tenants,
              user.admin
            ).checkCompliance(r)
          )
          .getOrElse(
            MaxRightComplianceResult.empty
          )

        if (!maxRightComplanceResult.isEmpty) {
          env.datastores.users
            .updateUserRights(
              user.username,
              maxRightComplanceResult.rightDiff(
                CompleteRights(
                  admin = user.admin,
                  tenants = user.rights.tenants
                )
              ),
              conn = conn
            )
            .toFEither
            .map(_ => Some(maxRightComplanceResult))
        } else {
          FutureEither.success(Option.empty)
        }
      }
      case Some(Supervised) => {
        val defaultRightsToApply = defaultRights
          .map(r =>
            RightService
              .effectiveRights(r, newRoles)
          )
          .getOrElse(CompleteRights.EMPTY)

        if (
          user.admin != defaultRightsToApply.admin || user.rights.tenants != defaultRightsToApply.tenants
        ) {
          updateUserRights(
            user.username,
            UserRightsUpdateRequest
              .fromRights(defaultRightsToApply),
            force = true,
            conn = conn
          ).map(_ => Option.empty) // FIXME this should return right change
        } else {
          FutureEither.success(Option.empty)
        }
      }
      case None => FutureEither.success(Option.empty)
    }
  }

  def updateUsersRightsForTenant(
      targetUsers: Set[String],
      tenant: String,
      diff: TenantRightDiff,
      conn: Option[SqlConnection] = None,
      conflictStrategy: ImportConflictStrategy = Replace
  ): FutureEither[Done] = {
    for (
      rightsByUsers <- usersDatastore
        .findCompleteRightsFromTenantForUsers(
          targetUsers,
          tenants = Set(tenant)
        )
        .mapToFEither;
      users = rightsByUsers.values.toSet
        .map(u => u.toUserWithCompleteRightForOneTenant(tenant));
      maybeMaxRightsByRoles = maxRightsByRoles;
      res <- doUpdateUserRightsForTenant(
        users = users,
        tenant = tenant,
        diff = diff,
        maybeMaxRightsByRoles = maybeMaxRightsByRoles,
        conn = conn,
        conflictStrategy = conflictStrategy
      )
    ) yield res
  }

  private def doUpdateUserRightsForTenant(
      users: Set[UserWithCompleteRightForOneTenant],
      tenant: String,
      diff: TenantRightDiff,
      maybeMaxRightsByRoles: Option[Map[String, MaxRights]],
      conn: Option[SqlConnection] = None,
      force: Boolean = false,
      conflictStrategy: ImportConflictStrategy = Replace
  ): FutureEither[Done] = {
    def doUpdate() = usersDatastore.updateUsersRightsForTenant(
      users.map(_.username),
      tenant,
      diff,
      conn,
      conflictStrategy
    )

    def checkRights(
        user: UserWithCompleteRightForOneTenant
    ): Either[IzanamiError, Done] = {
      if (canUpdateRightsForUser(user) && shouldCheckMaxRight(user)) {
        maybeMaxRightsByRoles match {
          case Some(maxRightsByRoles) => {
            val maxRights = {
              CompleteRights.maxRightsToApply(user.roles, maxRightsByRoles)
            }
            val baseRight = user.tenantRight
              .getOrElse(TenantRight(level = RightLevel.Read))
            val maybeNewRights = baseRight.applyDiff(diff)
            (maxRights.tenants.get(tenant), maybeNewRights) match {
              case (Some(maxTenantRights), Some(newRight)) => {
                val compliance = newRight.checkCompliance(maxTenantRights)
                if (!compliance.isEmpty) {
                  Left(
                    RightComplianceError(
                      s"User role doesn't allow him to have following rights on tenant ${tenant}:\n"
                        .concat(compliance.toError(tenant).mkString("\n"))
                    )
                  )
                } else {
                  Right(Done.done())
                }
              }
              case _ => Right(Done.done())
            }
          }
          case None => Right(Done.done())
        }
      } else if (canUpdateRightsForUser(user)) {
        Right(Done.done())
      } else {
        Left(CantUpdateOIDCUser())
      }
    }

    val rightCheckResult = users.foldLeft(
      Right(Done.done()): Either[IzanamiError, Done]
    )((res, user) => {
      res.flatMap(_ => {
        if (force) {
          Right(Done.done())
        } else {
          checkRights(user)
        }
      })
    })

    FutureEither
      .from(rightCheckResult)
      .flatMap(_ => doUpdate().toFEither.map(_ => Done.done()))
  }

  def updateUserRightsForTenant(
      targetUser: String,
      tenant: String,
      diff: TenantRightDiff,
      conn: Option[SqlConnection] = None
  ): FutureEither[Done] = {
    for (
      user <- usersDatastore
        .findUserWithRightForTenant(targetUser, tenant = tenant)
        .toFEither;
      maxRightsByRoles = this.maxRightsByRoles;
      res <- doUpdateUserRightsForTenant(
        Set(user),
        tenant,
        diff,
        maxRightsByRoles,
        conn
      )
    ) yield res
  }

  def findUserWithCompleteRights(user: String): FutureEither[UserWithRights] = {
    env.datastores.users
      .findUserWithCompleteRights(user)
      .map(_.toRight(UserDoesNotExist(user)))
      .toFEither
      .map(user => adjustRightsRoMaxRightsIfNeeded(user))
  }

  def findUserRightsForTenant(user: String, tenant: String): FutureEither[
    UserWithRights
  ] = { // TODO return type should be user with single tenant
    usersDatastore
      .findCompleteRightsFromTenant(user, Set(tenant))
      .map(_.toRight(UserDoesNotExist(user)))
      .toFEither
      .map(user => adjustRightsRoMaxRightsIfNeeded(user))
  }

  private def adjustRightsRoMaxRightsIfNeeded(
      user: UserWithRights
  ): UserWithRights = {
    if (shouldCheckMaxRight(user)) {
      maxRightsByRoles
        .map(maxRights => {
          val currentRights =
            CompleteRights(tenants = user.rights.tenants, admin = user.admin)
          val maxRightsToApply =
            CompleteRights.maxRightsToApply(user.roles, maxRights)
          val complianceResult = currentRights.checkCompliance(maxRightsToApply)

          if (complianceResult.isEmpty) {
            user
          } else {
            val rightToUse = currentRights.updateToComplyWith(complianceResult)
            user.copy(
              rights = Rights(rightToUse.tenants),
              admin = rightToUse.admin,
              previsionalRights = true
            )
          }
        })
        .getOrElse(user)
    } else {
      user
    }
  }

  def updateUserRights(
      name: String,
      updateRequest: UserRightsUpdateRequest,
      conn: Option[SqlConnection] = None,
      force: Boolean = false
  ): FutureEither[Done] = {
    usersDatastore
      .findUserWithCompleteRights(name)
      .mapToFEither
      .flatMap {
        case Some(user) if !canUpdateRightsForUser(user) && !force =>
          FutureEither.failure(CantUpdateOIDCUser())
        case None       => FutureEither.failure(UserNotFound(name))
        case Some(user) => {
          val diff = Rights.compare(
            base = user.rights,
            modified = updateRequest.rights,
            baseAdmin = user.admin,
            admin = updateRequest.admin
          )

          val maxRightsAndComplianceResult = maxRightsByRoles
            .map(maxRightsByRoles => {
              val maxRights = CompleteRights
                .maxRightsToApply(user.roles, maxRightsByRoles)
              (
                maxRightsByRoles,
                CompleteRights(
                  tenants = user.rights.tenants,
                  admin = user.admin
                ).applyOnWithOpt(updateRequest.admin)((rights, admin) =>
                  rights.copy(admin = admin)
                ).checkCompliance(maxRights)
              )
            })

          maxRightsAndComplianceResult match {
            case Some((_, compliance)) if !compliance.isEmpty => {
              FutureEither.failure(
                RightComplianceError(
                  s"User role doesn't allow him to have following rights:\n"
                    .concat(compliance.toError.mkString("\n"))
                )
              )
            }
            case o =>
              env.postgresql.executeInOptionalTransaction(
                conn,
                conn => {
                  for (
                    _ <- diff.admin
                      .fold(FutureEither.success(Done.done()))(admin => {
                        usersDatastore
                          .updateUsersAdminStatus(
                            Set(name),
                            admin,
                            conn = Some(conn)
                          )
                          .fEither
                          .map(_ => Done.done())
                      });
                    r <- diff.diff.foldLeft(
                      FutureEither.success(Done.done())
                    )((prev, next) => {
                      prev.flatMap(_ => {
                        val tenant = next._1
                        doUpdateUserRightsForTenant(
                          users = Set(
                            user.toUserWithCompleteRightForOneTenant(tenant)
                          ),
                          tenant = next._1,
                          diff = next._2,
                          maybeMaxRightsByRoles = o.map(_._1),
                          conn = Some(conn),
                          force = force
                        )
                      })
                    })
                  ) yield r
                }
              )
          }
        }
      }
  }

  type Username = String

  def hasRightFor(
      tenant: String,
      userIdentification: UserIdentification,
      tenantLevel: RightLevel
  ): Future[Option[RightCheckConfirmation]] = {
    hasRightFor(
      tenant = tenant,
      userIdentification = userIdentification,
      rights = Set(),
      tenantLevel = Some(tenantLevel)
    )
  }

  def hasRightFor(
      tenant: String,
      userIdentification: UserIdentification,
      rights: Set[RightUnit],
      tenantLevel: Option[RightLevel] = Option.empty
  ): Future[Option[RightCheckConfirmation]] = {
    usersDatastore
      .hasRightFor(
        tenant = tenant,
        userIdentication = userIdentification,
        rights = rights,
        tenantLevel = tenantLevel
      )
      .map {
        case Some(res) if shouldCheckMaxRight(res.user) =>
          oidcRights match {
            case Some(oidcRights)
                if oidcRights.allows(
                  tenant = tenant,
                  userRoles = res.user.roles,
                  requestedRights = rights
                ) =>
              Some(res)
            case None => Some(res)
            case _    => None
          }
        case None      => None
        case Some(res) => Some(res)
      }
  }

  def hasRightForWebhook(
      user: UserIdentification,
      tenant: String,
      webhook: WebhookIdentification,
      level: RightLevel
  ): FutureEither[Option[(Username, EntityIdentifiers)]] = {
    hasRightFor(
      tenant = tenant,
      userIdentification = user,
      rights = Set(WebhookRightUnit(webhook = webhook, rightLevel = level))
    ).map(maybeConfirmation => {
      for (
        confirmation <- maybeConfirmation;
        ids <- confirmation.webhookIdentifiers(webhook)
      ) yield (confirmation.user.username, ids)
    }).mapToFEither
  }

  def hasRightForKey(
      user: UserIdentification,
      tenant: String,
      key: String,
      level: RightLevel
  ): FutureEither[Option[Username]] = {
    hasRightFor(
      tenant = tenant,
      userIdentification = user,
      rights = Set(KeyRightUnit(key = key, rightLevel = level))
    )
      .map(o => o.map(_.user.username))
      .mapToFEither
  }

  def hasRightForProject(
      user: UserIdentification,
      tenant: String,
      project: ProjectIdentification,
      level: ProjectRightLevel
  ): FutureEither[Option[Username]] = {
    hasRightFor(
      tenant = tenant,
      userIdentification = user,
      rights = Set(ProjectRightUnit(project = project, rightLevel = level))
    )
      .map(o => o.map(res => res.user.username))
      .mapToFEither
  }

  def findUsersForProject(
      tenant: String,
      project: String
  ): Future[List[ProjectScopedUser]] = {
    for (
      users <- usersDatastore.findUsersForProject(tenant, project);
      oidcConfig = oidcRights
    ) yield {
      oidcConfig
        .map(rights => {
          users.flatMap(user => {
            val maxPossibleRight =
              rights.maxRightForProject(tenant, project, user.roles)
            maxPossibleRight.toMaybeProjectRightLevel.map(maxRight => {
              if (shouldCheckMaxRight(user)) {
                val rightAboveMaxRights =
                  Option(user.right).exists(_.isGreaterThan(maxRight))
                val defaultRightsAboveMaxRights =
                  user.defaultRight.exists(dr => dr.isGreaterThan(maxRight))
                user.copy(
                  previsionalRights =
                    rightAboveMaxRights || defaultRightsAboveMaxRights,
                  right = if (rightAboveMaxRights) maxRight else user.right,
                  defaultRight = user.defaultRight
                    .map(dr => if (dr.isGreaterThan(maxRight)) maxRight else dr)
                )
              } else {
                user
              }
            })
          })
        })
        .getOrElse(users)
    }
  }

  def findUsersForKey(
      tenant: String,
      key: String
  ): Future[List[SingleItemScopedUser]] = {
    for (
      users <- usersDatastore.findUsersForKey(tenant, key);
      oidcConfig = oidcRights
    ) yield {
      oidcConfig
        .map(rights => {
          users.flatMap(user => {
            val maxPossibleRight =
              rights.maxRightForKey(tenant, key, user.roles)
            maxPossibleRight.toMaybeRightLevel.map(maxRight => {
              if (shouldCheckMaxRight(user)) {
                val rightAboveMaxRights =
                  Option(user.right).exists(_.isGreaterThan(maxRight))
                val defaultRightsAboveMaxRights =
                  user.defaultRight.exists(dr => dr.isGreaterThan(maxRight))
                user.copy(
                  previsionalRights =
                    rightAboveMaxRights || defaultRightsAboveMaxRights,
                  right =
                    if (user.right.isGreaterThan(maxRight)) maxRight
                    else user.right,
                  defaultRight = user.defaultRight
                    .map(dr => if (dr.isGreaterThan(maxRight)) maxRight else dr)
                )
              } else {
                user
              }
            })
          })
        })
        .getOrElse(users)
    }
  }

  def findUsersForWebhook(
      tenant: String,
      webhook: String
  ): Future[List[SingleItemScopedUser]] = {
    for (
      users <- usersDatastore.findUsersForWebhook(tenant, webhook);
      oidcConfig = oidcRights
    ) yield {
      oidcConfig
        .map(rights => {
          users.flatMap(user => {
            val maxPossibleRight =
              rights.maxRightForWebhook(tenant, webhook, user.roles)
            maxPossibleRight.toMaybeRightLevel.map(maxRight => {
              if (shouldCheckMaxRight(user)) {
                val rightAboveMaxRights =
                  Option(user.right).exists(_.isGreaterThan(maxRight))
                val defaultRightsAboveMaxRights =
                  user.defaultRight.exists(dr => dr.isGreaterThan(maxRight))
                user.copy(
                  previsionalRights =
                    rightAboveMaxRights || defaultRightsAboveMaxRights,
                  right =
                    if (user.right.isGreaterThan(maxRight)) maxRight
                    else user.right,
                  defaultRight = user.defaultRight
                    .map(dr => if (dr.isGreaterThan(maxRight)) maxRight else dr)
                )
              } else {
                user
              }
            })
          })
        })
        .getOrElse(users)
    }
  }

  def findUsersForTenant(
      tenant: String
  ): Future[List[UserWithSingleLevelRight]] = {
    for (
      users <- usersDatastore.findUsersForTenant(tenant);
      oidcConfig = oidcRights
    ) yield {
      oidcConfig
        .map(rights => {
          users.flatMap(user => {
            val maxPossibleRight = rights.maxRightForTenant(tenant, user.roles)
            maxPossibleRight.toMaybeRightLevel.map(maxRight => {
              if (
                user.right.exists(r =>
                  r.isGreaterThan(maxRight)
                ) && shouldCheckMaxRight(user)
              ) {
                user.copy(right = Some(maxRight), previsionalRights = true)
              } else {
                user
              }
            })
          })
        })
        .getOrElse(users)
    }
  }

  def findVisibleUsers(username: String): Future[Set[UserWithTenantRights]] = {
    for (
      users <- usersDatastore.findVisibleUsers(username);
      oidcConfig = oidcRights
    ) yield {
      oidcConfig
        .map(rights => {
          users.map(user => {
            var previsionalRights = false
            val newRights = user.tenantRights
              .map { (tenant, rightLevel) =>
                {
                  val maxPossibleRight =
                    rights.maxRightForTenant(tenant, user.roles)
                  maxPossibleRight match {
                    case level: RightLevel
                        if rightLevel.isGreaterThan(
                          level
                        ) && shouldCheckMaxRight(user) => {
                      previsionalRights = true
                      Some((tenant, level))
                    }
                    case RightLevelIncludingNoRight.None => Option.empty
                    case _ => Some((tenant, rightLevel))
                  }
                }
              }
              .flatten
              .toMap
            user.copy(
              tenantRights = newRights,
              previsionalRights = previsionalRights
            )
          })
        })
        .getOrElse(users)
    }
  }

  private def shouldCheckMaxRight(user: UserTrait): Boolean = {
    user.userType == OIDC && currentOidcConfiguration
      .get()
      .map(_.roleRightMode)
      .exists(_ != Supervised) && currentOidcConfiguration
      .get()
      .flatMap(_.maxRightsByRoles)
      .exists(_.nonEmpty)
  }

  private def oidcRights: Option[OIDCRights] = currentOidcConfiguration
    .get()
    .flatMap(_.userRightsByRoles)
    .map(rightByRoles => OIDCRights(rightByRoles))

  private def roleRightMode: Option[RoleRightMode] =
    currentOidcConfiguration.get().flatMap(_.roleRightMode)
  private def maxRightsByRoles: Option[Map[String, MaxRights]] =
    currentOidcConfiguration.get().flatMap(_.maxRightsByRoles)
  private def defaultRights: Option[RightsByRole] = currentOidcConfiguration
    .get()
    .flatMap(
      _.userRightsByRoles
        .map(m => m.view.mapValues(r => r.completeRights).toMap)
    )
}

sealed trait ProjectIdentification
case class ProjectIdIdentification(identification: String)
    extends ProjectIdentification
case class ProjectNameIdentification(identification: String)
    extends ProjectIdentification

sealed trait WebhookIdentification
case class WebhookNameIdentification(name: String) extends WebhookIdentification
case class WebhookIdIdentification(id: UUID) extends WebhookIdentification

object RightService {
  type Role = String
  type Tenant = String
  type Project = String
  type RightsByRole = Map[Role, CompleteRights]
  val DEFAULT_ROLE = ""

  def effectiveRights(
      defaultRights: RightsByRole,
      effectiveRoles: Set[Role]
  ): CompleteRights = {
    defaultRights
      .collect {
        case (role, rights)
            if effectiveRoles.contains(role) || role == DEFAULT_ROLE =>
          rights
      }
      .foldLeft(CompleteRights.EMPTY)((r1, r2) => r1.mergeWith(r2))
  }

  def keepHigher(
      mr1: MaxTenantRoleRights,
      mr2: MaxTenantRoleRights
  ): MaxTenantRoleRights = {
    MaxTenantRoleRights(
      level = if (mr1.level.isGreaterThan(mr2.level)) mr1.level else mr2.level,
      maxProjectRight =
        if (mr1.maxProjectRight.isGreaterThan(mr2.maxProjectRight))
          mr1.maxProjectRight
        else mr2.maxProjectRight,
      maxKeyRight =
        if (mr1.maxKeyRight.isGreaterThan(mr2.maxKeyRight)) mr1.maxKeyRight
        else mr2.maxKeyRight,
      maxWebhookRight =
        if (mr1.maxWebhookRight.isGreaterThan(mr1.maxWebhookRight))
          mr1.maxWebhookRight
        else mr2.maxWebhookRight
    )
  }

  def keepHigher(
      r1: TenantRightWithMaxRights,
      r2: TenantRightWithMaxRights
  ): TenantRightWithMaxRights = {

    val maxRights = keepHigher(r1.maxRight, r2.maxRight)

    val tr = (r1.underlying, r2.underlying) match {
      case (Some(rr1), Some(rr2)) => Some(keepHigher(rr1, rr2))
      case (Some(rr1), _)         => Some(rr1)
      case (_, Some(rr2))         => Some(rr2)
      case _                      => None
    }

    TenantRightWithMaxRights(
      level = tr.map(_.level).getOrElse(RightLevelIncludingNoRight.None),
      projects = tr.map(_.projects).getOrElse(Map()),
      keys = tr.map(_.keys).getOrElse(Map()),
      webhooks = tr.map(_.webhooks).getOrElse(Map()),
      defaultProjectRight = tr
        .map(_.defaultProjectRight)
        .getOrElse(ProjectRightLevelIncludingNoRight.None),
      defaultKeyRight = tr
        .map(_.defaultKeyRight)
        .getOrElse(RightLevelIncludingNoRight.None),
      defaultWebhookRight = tr
        .map(_.defaultWebhookRight)
        .getOrElse(RightLevelIncludingNoRight.None),
      maxProjectRight = maxRights.maxProjectRight,
      maxKeyRight = maxRights.maxKeyRight,
      maxWebhookRight = maxRights.maxWebhookRight,
      maxTenantRight = maxRights.level
    )
  }

  def keepHigher(r1: TenantRight, r2: TenantRight): TenantRight = {
    val level = Seq(r1.level, r2.level).max(RightOrdering)
    val projectRights = r1.projects.toSeq
      .concat(r2.projects.toSeq)
      .groupMapReduce(_._1)(_._2)((r1, r2) =>
        Seq(r1, r2).maxBy(_.level)(ProjectRightOrdering)
      )
    val keyRights = r1.keys.toSeq
      .concat(r2.keys.toSeq)
      .groupMapReduce(_._1)(_._2)((r1, r2) =>
        Seq(r1, r2).maxBy(_.level)(RightOrdering)
      )
    val webhookRights = r1.webhooks.toSeq
      .concat(r2.webhooks.toSeq)
      .groupMapReduce(_._1)(_._2)((r1, r2) =>
        Seq(r1, r2).maxBy(_.level)(RightOrdering)
      )

    TenantRight(
      level = level,
      projects = projectRights,
      keys = keyRights,
      webhooks = webhookRights,
      defaultProjectRight = Ordering(ProjectRightLevelIncludingNoRightOrdering)
        .max(r1.defaultProjectRight, r2.defaultProjectRight),
      defaultKeyRight = Ordering(RightLevelIncludingNoRightOrdering)
        .max(r1.defaultKeyRight, r2.defaultKeyRight),
      defaultWebhookRight = Ordering(RightLevelIncludingNoRightOrdering)
        .max(r1.defaultWebhookRight, r2.defaultWebhookRight)
    )
  }
}

case class RightComplianceChange(
    before: RightLevelIncludingNoRight,
    after: RightLevelIncludingNoRight
)

object RightComplianceChange {
  def writes: Writes[RightComplianceChange] = r => {
    implicit val w: Writes[RightLevelIncludingNoRight] =
      RightLevelIncludingNoRight.writes
    Json.obj("before" -> r.before, "after" -> r.after)
  }
}

case class ProjectRightComplianceChange(
    before: ProjectRightLevelIncludingNoRight,
    after: ProjectRightLevelIncludingNoRight
)

object ProjectRightComplianceChange {
  def writes: Writes[ProjectRightComplianceChange] = r => {
    implicit val w: Writes[ProjectRightLevelIncludingNoRight] =
      ProjectRightLevelIncludingNoRight.writes
    Json.obj("before" -> r.before, "after" -> r.after)
  }
}

case class TenantRightComplianceResult(
    levelRight: Option[RightComplianceChange],
    defaultProjectRight: Option[ProjectRightComplianceChange],
    defaultKeyRight: Option[RightComplianceChange],
    defaultWebhookRight: Option[RightComplianceChange],
    projects: Map[String, ProjectRightComplianceChange],
    keys: Map[String, RightComplianceChange],
    webhooks: Map[String, RightComplianceChange]
) {
  def rightDiff(
      maybeExistingRights: Option[TenantRight]
  ): Option[TenantRightDiff] = {
    if (isEmpty) {
      Option.empty
    } else if (
      levelRight.isDefined && levelRight.exists(
        _.after == RightLevelIncludingNoRight.None
      )
    ) {
      Some(DeleteTenantRights)
    } else {
      levelRight.map(r => r.after)

      val addedProjects = projects.collect {
        case (
              name,
              ProjectRightComplianceChange(before, after: ProjectRightLevel)
            ) => {
          UnscopedFlattenProjectRight(name = name, level = after)
        }
      }.toSet

      val removedProjects = projects.collect {
        case (
              name,
              ProjectRightComplianceChange(
                before,
                ProjectRightLevelIncludingNoRight.None
              )
            ) => {
          name
        }
      }.toSet

      val addedKeys = keys.collect {
        case (name, RightComplianceChange(before, after: RightLevel)) => {
          UnscopedFlattenKeyRight(name = name, level = after)
        }
      }.toSet

      val removedKeys = keys.collect {
        case (
              name,
              RightComplianceChange(before, RightLevelIncludingNoRight.None)
            ) => {
          name
        }
      }.toSet

      val addedWebhooks = webhooks.collect {
        case (name, RightComplianceChange(before, after: RightLevel)) => {
          UnscopedFlattenWebhookRight(name = name, level = after)
        }
      }.toSet

      val removedWebhooks = webhooks.collect {
        case (
              name,
              RightComplianceChange(before, RightLevelIncludingNoRight.None)
            ) => {
          name
        }
      }.toSet
      Some(
        UpsertTenantRights(
          tenantWideUpdate = levelRight.map(tenantLevelChange => {
            TenantWideRightUpdate(
              level = tenantLevelChange.after.toMaybeRightLevel.get,
              defaultProjectRight = defaultProjectRight
                .map(c => c.after)
                .orElse(maybeExistingRights.map(_.defaultProjectRight))
                .getOrElse(ProjectRightLevelIncludingNoRight.None),
              defaultKeyRight = defaultKeyRight
                .map(c => c.after)
                .orElse(maybeExistingRights.map(_.defaultKeyRight))
                .getOrElse(RightLevelIncludingNoRight.None),
              defaultWebhookRight = defaultWebhookRight
                .map(c => c.after)
                .orElse(maybeExistingRights.map(_.defaultWebhookRight))
                .getOrElse(RightLevelIncludingNoRight.None)
            )
          }),
          addedProjectRights = addedProjects,
          removedProjectRights = removedProjects,
          addedKeyRights = addedKeys,
          removedKeyRights = removedKeys,
          addedWebhookRights = addedWebhooks,
          removedWebhookRights = removedWebhooks
        )
      )
    }
  }

  def isEmpty: Boolean =
    levelRight.isEmpty &&
      defaultKeyRight.isEmpty &&
      defaultProjectRight.isEmpty &&
      defaultWebhookRight.isEmpty &&
      projects.isEmpty &&
      keys.isEmpty &&
      webhooks.isEmpty

  def toError(tenant: String): Seq[String] = {
    val msgs = ArrayBuffer[String]()
    if (levelRight.isDefined) {
      msgs.addOne(
        s"right level ${levelRight.get.before} (max allowed is ${levelRight.get.after.toString})"
      )
    }
    if (defaultProjectRight.isDefined) {
      msgs.addOne(
        s"Default project right ${defaultProjectRight.get.before} (max allowed is ${defaultProjectRight.get.after.toString})"
      )
    }
    if (defaultKeyRight.isDefined) {
      msgs.addOne(
        s"Default key right ${defaultKeyRight.get.before} (max allowed is ${defaultKeyRight.get.after.toString})"
      )
    }
    if (defaultWebhookRight.isDefined) {
      msgs.addOne(
        s"Default webhook right ${defaultWebhookRight.get.before} (max allowed is ${defaultWebhookRight.get.after.toString})"
      )
    }
    projects.foreach { (name, complianceIssue) =>
      msgs.addOne(
        s"${complianceIssue.before} right for project $name (max allowed is ${complianceIssue.after.toString})"
      )
    }
    keys.foreach { (name, complianceIssue) =>
      msgs.addOne(
        s"${complianceIssue.before} right for project $name (max allowed is ${complianceIssue.after.toString})"
      )
    }
    webhooks.foreach { (name, complianceIssue) =>
      msgs.addOne(
        s"${complianceIssue.before} right for project $name (max allowed is ${complianceIssue.after.toString})"
      )
    }
    msgs.toSeq
  }
}

object TenantRightComplianceResult {
  def empty: TenantRightComplianceResult = TenantRightComplianceResult(
    Option.empty,
    Option.empty,
    Option.empty,
    Option.empty,
    Map(),
    Map(),
    Map()
  )
  def writes: Writes[TenantRightComplianceResult] = r => {
    implicit val _: Writes[ProjectRightComplianceChange] =
      ProjectRightComplianceChange.writes
    implicit val _: Writes[RightComplianceChange] = RightComplianceChange.writes
    Json
      .obj("projects" -> r.projects, "keys" -> r.keys, "webhooks" -> r.webhooks)
      .applyOnWithOpt(r.defaultProjectRight)((json, defaultProject) => {
        json + ("defaultProject" -> Json.toJson(defaultProject))
      })
      .applyOnWithOpt(r.defaultKeyRight)((json, defaultKey) => {
        json + ("defaultKey" -> Json.toJson(defaultKey))
      })
      .applyOnWithOpt(r.defaultWebhookRight)((json, defaultWebhook) => {
        json + ("defaultWebhook" -> Json.toJson(defaultWebhook))
      })
      .applyOnWithOpt(r.levelRight)((json, levelRight) => {
        json + ("levelRight" -> Json.toJson(levelRight))
      })
  }
}

case class MaxRightComplianceResult(
    admin: Boolean,
    tenants: Map[String, TenantRightComplianceResult]
) {
  def rightDiff(completeRights: CompleteRights): RightDiff = {
    RightDiff(
      admin = if (admin) Some(false) else None,
      diff = tenants
        .map { (name, r) =>
          {
            r.rightDiff(completeRights.tenants.get(name)).map(d => (name, d))
          }
        }
        .collect { case Some(diff) =>
          diff
        }
        .toMap
    )
  }

  def isEmpty: Boolean = !admin && tenants.forall { (_, r) =>
    r.isEmpty
  }
  def toError: Seq[String] = {
    var errors = ArrayBuffer[String]()
    if (admin) {
      errors.addOne(
        "User role doesn't allow him to have admin right on this Izanami instance."
      )
    }
    tenants.foreach { (name, rights) =>
      {
        val tenantErrors = rights.toError(name)
        if (tenantErrors.nonEmpty) {
          errors.addOne(
            s"User role doesn't allow him to have following rights on tenant ${name}:"
          )
          errors = errors.concat(tenantErrors)
        }
      }
    }
    errors.toSeq
  }
}

object MaxRightComplianceResult {
  def writes: Writes[MaxRightComplianceResult] = r => {
    implicit val _: Writes[TenantRightComplianceResult] =
      TenantRightComplianceResult.writes
    Json.obj("admin" -> r.admin, "tenants" -> r.tenants)
  }

  def empty: MaxRightComplianceResult =
    MaxRightComplianceResult(admin = false, tenants = Map())
}

case class OIDCRights(rights: Map[String, CompleteRightsWithMaxRights]) {
  private def maxRightsForRoles(roles: Set[String]): Option[MaxRights] = {
    val maxRights = rights
      .filter { (role, _) =>
        roles.contains(role) || role == DEFAULT_ROLE
      }
      .map { (_, rights) => rights.maxRights }

    if (maxRights.isEmpty) {
      Option.empty
    } else {
      Some(maxRights.reduce((mr1, mr2) => MaxRights.keepHigher(mr1, mr2)))
    }
  }

  def maxRightForTenant(
      tenant: String,
      roles: Set[String]
  ): RightLevelIncludingNoRight = {
    (for (
      maxRights <- maxRightsForRoles(roles);
      tenantRights <- maxRights.tenants.get(tenant)
    ) yield tenantRights.level).getOrElse(Admin)
  }

  def maxRightForProject(
      tenant: String,
      project: String,
      roles: Set[String]
  ): ProjectRightLevelIncludingNoRight = {
    (for (
      maxRights <- maxRightsForRoles(roles);
      tenantRights <- maxRights.tenants.get(tenant)
    ) yield tenantRights.maxProjectRight).getOrElse(ProjectRightLevel.Admin)
  }

  def maxRightForKey(
      tenant: String,
      key: String,
      roles: Set[String]
  ): RightLevelIncludingNoRight = {
    (for (
      maxRights <- maxRightsForRoles(roles);
      tenantRights <- maxRights.tenants.get(tenant)
    ) yield tenantRights.maxKeyRight).getOrElse(RightLevel.Admin)
  }

  def maxRightForWebhook(
      tenant: String,
      webhook: String,
      roles: Set[String]
  ): RightLevelIncludingNoRight = {
    (for (
      maxRights <- maxRightsForRoles(roles);
      tenantRights <- maxRights.tenants.get(tenant)
    ) yield tenantRights.maxWebhookRight).getOrElse(RightLevel.Admin)
  }

  def allows(
      tenant: String,
      userRoles: Set[String],
      requestedRights: Set[RightUnit]
  ): Boolean = {
    maxRightsForRoles(userRoles).forall(maxRight =>
      requestedRights.forall {
        case ProjectRightUnit(name, rightLevel) =>
          maxRight.allowRightForProject(
            tenant = tenant,
            requestLevel = rightLevel
          )
        case KeyRightUnit(name, rightLevel) =>
          maxRight.allowRightForKey(tenant = tenant, requestLevel = rightLevel)
        case WebhookRightUnit(name, rightLevel) =>
          maxRight.allowRightForWebhook(
            tenant = tenant,
            requestLevel = rightLevel
          )
      }
    )
  }

  def allowsWebhookRight(
      userRoles: Set[String],
      webhookTenant: String,
      requestRight: RightLevel
  ): Boolean = {
    maxRightsForRoles(userRoles)
      .exists(mr =>
        mr.allowRightForWebhook(
          tenant = webhookTenant,
          requestLevel = requestRight
        )
      )
  }

  def allowsProjectRight(
      userRoles: Set[String],
      projectTenant: String,
      requestRight: ProjectRightLevel
  ): Boolean = {
    maxRightsForRoles(userRoles)
      .exists(mr =>
        mr.allowRightForProject(
          tenant = projectTenant,
          requestLevel = requestRight
        )
      )
  }

  def allowKeyRight(
      userRoles: Set[String],
      keyTenant: String,
      requestRight: RightLevel
  ): Boolean = {
    maxRightsForRoles(userRoles)
      .exists(mr =>
        mr.allowRightForKey(tenant = keyTenant, requestLevel = requestRight)
      )
  }
}

case class MaxRights(
    admin: Boolean,
    tenants: Map[String, MaxTenantRoleRights] = Map()
) {
  def allowRightForWebhook(tenant: String, requestLevel: RightLevel): Boolean =
    admin || tenants
      .get(tenant)
      .exists(r => r.allowRightForWebhook(requestLevel))

  def allowRightForProject(
      tenant: String,
      requestLevel: ProjectRightLevel
  ): Boolean =
    admin || tenants
      .get(tenant)
      .exists(r => r.allowRightForProject(requestLevel))

  def allowRightForKey(tenant: String, requestLevel: RightLevel): Boolean =
    admin || tenants.get(tenant).exists(r => r.allowRightForKey(requestLevel))

  def hasElementsBelow(other: MaxRights): Boolean = {
    if (other.admin && !admin) {
      true
    } else {
      tenants.exists { (tenant, newRightsForTenant) =>
        {
          other.tenants
            .get(tenant)
            .forall(oldRightsForTenant =>
              newRightsForTenant.hasElementsBelow(oldRightsForTenant)
            )
        }
      }
    }
  }
}

case class MaxTenantRoleRights(
    level: RightLevelIncludingNoRight,
    maxProjectRight: ProjectRightLevelIncludingNoRight,
    maxKeyRight: RightLevelIncludingNoRight,
    maxWebhookRight: RightLevelIncludingNoRight
) {
  def hasElementsBelow(other: MaxTenantRoleRights): Boolean = {
    other.level.isGreaterThan(level) ||
    other.maxWebhookRight.isGreaterThan(maxWebhookRight) ||
    other.maxKeyRight.isGreaterThan(maxKeyRight) ||
    other.maxProjectRight.isGreaterThan(maxProjectRight)
  }
  def allowRightForWebhook(requestLevel: RightLevel): Boolean =
    level == RightLevel.Admin || !requestLevel.isGreaterThan(maxWebhookRight)
  def allowRightForProject(requestLevel: ProjectRightLevel): Boolean =
    level == RightLevel.Admin || !requestLevel.isGreaterThan(maxProjectRight)
  def allowRightForKey(requestLevel: RightLevel): Boolean =
    level == RightLevel.Admin || !requestLevel.isGreaterThan(maxKeyRight)
}

case object MaxRights {
  def keepHigher(mr1: MaxRights, mr2: MaxRights): MaxRights = {
    val admin = mr1.admin || mr2.admin

    val maxRightsByTenants = mr1.tenants.toSeq
      .concat(mr2.tenants.toSeq)
      .groupMapReduce(t => t._1)(t => t._2)((mtrr1, mtrr2) => {
        RightService.keepHigher(mtrr1, mtrr2)
      })
      .toMap

    MaxRights(admin = admin, tenants = maxRightsByTenants)
  }

  def reads: Reads[MaxRights] = json => {
    val admin = (json \ "admin").asOpt[Boolean].getOrElse(false)
    val rightsByTenant = (json \ "tenants")
      .asOpt[Map[String, MaxTenantRoleRights]](
        Reads.map(MaxTenantRoleRights.reads)
      )
      .getOrElse(Map())
    JsSuccess(MaxRights(admin = admin, tenants = rightsByTenant))
  }

  def writes: Writes[MaxRights] = r => {
    Json
      .obj(
        "admin" -> Json.toJson(r.admin),
        "tenants" -> Json.toJson(r.tenants)(
          Writes.map(MaxTenantRoleRights.writes)
        )
      )
  }
}

case object MaxTenantRoleRights {
  def reads: Reads[MaxTenantRoleRights] = json => {
    implicit val rightRead: Reads[RightLevelIncludingNoRight] =
      RightLevelIncludingNoRight.reads
    implicit val projectRightRead: Reads[ProjectRightLevelIncludingNoRight] =
      ProjectRightLevelIncludingNoRight.reads
    for (
      level <- (json \ "level").validate[RightLevelIncludingNoRight];
      project <- (json \ "maxProjectRight")
        .validate[ProjectRightLevelIncludingNoRight];
      key <- (json \ "maxKeyRight").validate[RightLevelIncludingNoRight];
      webhook <- (json \ "maxWebhookRight").validate[RightLevelIncludingNoRight]
    )
      yield MaxTenantRoleRights(
        level,
        project,
        key,
        webhook
      )
  }

  def writes: Writes[MaxTenantRoleRights] = rs => {
    implicit val rightWrites: Writes[RightLevelIncludingNoRight] =
      RightLevelIncludingNoRight.writes
    implicit val projectRightWrites: Writes[ProjectRightLevelIncludingNoRight] =
      ProjectRightLevelIncludingNoRight.writes
    Json
      .obj(
        "level" -> Json.toJson(rs.level),
        "maxProjectRight" -> Json.toJson(rs.maxProjectRight),
        "maxKeyRight" -> Json.toJson(rs.maxKeyRight),
        "maxWebhookRight" -> Json.toJson(rs.maxWebhookRight)
      )
  }

}

case class CompleteRights(tenants: Map[String, TenantRight], admin: Boolean) {
  def mergeWith(other: CompleteRights): CompleteRights = {
    val admin = this.admin || other.admin

    val tenantRights =
      this.tenants.toSeq
        .concat(other.tenants.toSeq)
        .groupMapReduce(_._1)(_._2)((tr1, tr2) => keepHigher(tr1, tr2))

    CompleteRights(admin = admin, tenants = tenantRights)
  }

  def removeTenantsRights(tenant: Set[String]): CompleteRights = {
    copy(admin = admin, tenants = tenants.filter(t => !tenant.contains(t._1)))
  }

  def updateToComplyWith(
      maxRightComplianceResult: MaxRightComplianceResult
  ): CompleteRights = {
    val newAdmin = if (admin && maxRightComplianceResult.admin) false else admin

    val newTenantRights = tenants
      .map { (name, tenantRight) =>
        {
          val maybeComplianceResult = maxRightComplianceResult.tenants.get(name)

          maybeComplianceResult
            .map(complianceResult => {
              tenantRight
                .updateToComplyWith(complianceResult)
                .map(tr => (name, tr))
            })
            .getOrElse(Some(name, tenantRight))
        }
      }
      .collect { case Some(t) =>
        t
      }
      .toMap

    CompleteRights(admin = newAdmin, tenants = newTenantRights)
  }

  def checkCompliance(maxRights: MaxRights): MaxRightComplianceResult = {
    val adminComplianceResult = admin && !maxRights.admin
    val complianceResultByTenant = tenants.map { (name, tenantRight) =>
      {
        val maybeMaxRight = maxRights.tenants.get(name)
        val complianceResult = (maybeMaxRight, tenantRight) match {
          case (None, _) => TenantRightComplianceResult.empty
          case (
                Some(
                  maxRights
                ),
                tr: TenantRight
              ) => {
            tr.checkCompliance(maxRights)
          }
        }
        (name, complianceResult)
      }
    }

    MaxRightComplianceResult(
      admin = adminComplianceResult,
      tenants = complianceResultByTenant
    )
  }

  def removeProjectRights(
      tenant: String,
      project: Set[String]
  ): CompleteRights = {
    tenants
      .get(tenant)
      .map(t =>
        t.copy(projects = t.projects.filter { case (p, _) =>
          !project.contains(p)
        })
      )
      .fold(this)(e => copy(admin = admin, tenants = tenants + (tenant -> e)))
  }

  def removeKeyRights(tenant: String, keys: Set[String]): CompleteRights = {
    tenants
      .get(tenant)
      .map(t =>
        t.copy(keys = t.keys.filter { case (k, _) =>
          !keys.contains(k)
        })
      )
      .fold(this)(e => copy(admin = admin, tenants = tenants + (tenant -> e)))
  }

  def removeWebhookRights(
      tenant: String,
      webhooks: Set[String]
  ): CompleteRights = {
    tenants
      .get(tenant)
      .map(t =>
        t.copy(webhooks = t.webhooks.filter { case (w, _) =>
          !webhooks.contains(w)
        })
      )
      .fold(this)(e => copy(admin = admin, tenants = tenants + (tenant -> e)))
  }
}

case class CompleteRightsWithMaxRights(
    tenants: Map[String, TenantRightWithMaxRights],
    admin: Boolean,
    adminAllowed: Boolean
) {
  def completeRights: CompleteRights = CompleteRights(
    admin = admin,
    tenants = tenants.view
      .mapValues(t => t.underlying)
      .collect { case (n, Some(r)) =>
        (n, r)
      }
      .toMap
  )
  def maxRights: MaxRights = {
    MaxRights(
      admin = adminAllowed,
      tenants = tenants.view
        .mapValues(t => t.maxRight)
        .toMap
    )
  }
  def removeWebhookRights(
      tenant: String,
      webhooks: Set[String]
  ): CompleteRightsWithMaxRights = {
    tenants
      .get(tenant)
      .map(t =>
        t.copy(webhooks = t.webhooks.filter { case (w, _) =>
          !webhooks.contains(w)
        })
      )
      .fold(this)(e => copy(tenants = tenants + (tenant -> e)))
  }

  def removeProjectRights(
      tenant: String,
      project: Set[String]
  ): CompleteRightsWithMaxRights = {
    tenants
      .get(tenant)
      .map(t =>
        t.copy(projects = t.projects.filter { case (p, _) =>
          !project.contains(p)
        })
      )
      .fold(this)(e => copy(tenants = tenants + (tenant -> e)))
  }

  def removeKeyRights(
      tenant: String,
      keys: Set[String]
  ): CompleteRightsWithMaxRights = {
    tenants
      .get(tenant)
      .map(t =>
        t.copy(keys = t.keys.filter { case (k, _) =>
          !keys.contains(k)
        })
      )
      .fold(this)(e => copy(tenants = tenants + (tenant -> e)))
  }

  def removeTenantsRights(tenant: Set[String]): CompleteRightsWithMaxRights = {
    copy(tenants = tenants.filter(t => !tenant.contains(t._1)))
  }
  def mergeWith(
      other: CompleteRightsWithMaxRights
  ): CompleteRightsWithMaxRights = {
    val admin = this.admin || other.admin
    val allowedAdmin = this.adminAllowed || other.adminAllowed

    val tenantRights =
      this.tenants.toSeq
        .concat(other.tenants.toSeq)
        .groupMapReduce(_._1)(_._2)((tr1, tr2) => keepHigher(tr1, tr2))

    CompleteRightsWithMaxRights(
      admin = admin,
      tenants = tenantRights,
      adminAllowed = allowedAdmin
    )
  }
}

object CompleteRightsWithMaxRights {
  def writes: Writes[CompleteRightsWithMaxRights] = r => {
    val jsonRights =
      Json.toJson(r.tenants)(Writes.map(TenantRightWithMaxRights.writes))
    Json.obj(
      "admin" -> r.admin,
      "tenants" -> jsonRights,
      "adminAllowed" -> r.adminAllowed
    )
  }

  def reads: Reads[CompleteRightsWithMaxRights] = json => {
    (for (
      admin <- (json \ "admin").asOpt[Boolean];
      tenants <- (json \ "tenants")
        .asOpt[Map[String, TenantRightWithMaxRights]](
          Reads.map(TenantRightWithMaxRights.reads)
        )
    )
      yield {
        CompleteRightsWithMaxRights(
          admin = admin,
          tenants = tenants,
          adminAllowed = (json \ "adminAllowed").asOpt[Boolean].getOrElse(true)
        )
      })
      .map(r => JsSuccess(r))
      .getOrElse(JsError("Bad body format"))
  }
}

case object CompleteRights {
  val EMPTY: CompleteRights = CompleteRights(admin = false, tenants = Map.empty)

  def writes: Writes[CompleteRights] = r => {
    val jsonRights = Json.toJson(r.tenants)(Writes.map(User.tenantRightWrite))
    Json.obj("admin" -> r.admin, "tenants" -> jsonRights)
  }

  def reads: Reads[CompleteRights] = json => {
    (for (
      admin <- (json \ "admin").asOpt[Boolean];
      tenants <- (json \ "tenants").asOpt[Map[String, TenantRight]](
        Reads.map(User.tenantRightReads)
      )
    ) yield CompleteRights(admin = admin, tenants = tenants))
      .map(r => JsSuccess(r))
      .getOrElse(JsError("Bad body format"))
  }

  def generateComplianceRightChange(
      current: RightLevelIncludingNoRight,
      max: RightLevelIncludingNoRight
  ): Option[RightComplianceChange] = {
    (current, max) match {
      case (curr, m) if (curr.isGreaterThan(m)) =>
        Some(RightComplianceChange(before = curr, after = m))
      case _ => None
    }
  }

  def generateComplianceRightChangeForProject(
      current: ProjectRightLevelIncludingNoRight,
      max: ProjectRightLevelIncludingNoRight
  ): Option[ProjectRightComplianceChange] = {
    (current, max) match {
      case (curr, m) if (curr.isGreaterThan(m)) =>
        Some(ProjectRightComplianceChange(before = curr, after = m))
      case _ => None
    }
  }

  def maxRightsToApply(
      roles: Set[String],
      maxRightsByRole: Map[String, MaxRights]
  ): MaxRights = {
    val rs = maxRightsByRole.filter { (role, _) =>
      {
        roles.contains(role) || DEFAULT_ROLE == role
      }
    }.values

    rs.toList match {
      case Nil          => MaxRights(admin = true, tenants = Map())
      case head :: Nil  => head
      case head :: tail =>
        tail.foldLeft(head)((acc, next) => {
          max(acc, next)
        })
    }
  }

  def max(r1: MaxRights, r2: MaxRights): MaxRights = {
    val admin = r1.admin || r2.admin

    val rightByTenants = r1.tenants.flatMap { case (k, v1) =>
      r2.tenants.get(k).map(v2 => k -> max(v1, v2))
    }

    MaxRights(admin = admin, tenants = rightByTenants)
  }

  def max(
      r1: MaxTenantRoleRights,
      r2: MaxTenantRoleRights
  ): MaxTenantRoleRights = {
    MaxTenantRoleRights(
      level = if (r1.level.isGreaterThan(r2.level)) { r1.level }
      else { r2.level },
      maxProjectRight =
        if (r1.maxProjectRight.isGreaterThan(r2.maxProjectRight))
          r1.maxProjectRight
        else r2.maxProjectRight,
      maxKeyRight =
        if (r1.maxKeyRight.isGreaterThan(r2.maxKeyRight)) r1.maxKeyRight
        else r2.maxKeyRight,
      maxWebhookRight =
        if (r1.maxWebhookRight.isGreaterThan(r2.maxWebhookRight))
          r1.maxWebhookRight
        else r2.maxWebhookRight
    )
  }
}
