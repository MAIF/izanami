package fr.maif.izanami.services

import fr.maif.izanami.RoleRightMode.{Initial, Supervised}
import fr.maif.izanami.datastores.{SessionIdentification, UserIdentification}
import fr.maif.izanami.{RoleRightMode, RoleRights, TenantRoleRights}
import fr.maif.izanami.env.Env
import fr.maif.izanami.errors.{CantUpdateOIDCUser, IzanamiError, RightComplianceError, UserDoesNotExist, UserNotFound}
import fr.maif.izanami.models.ProjectRightLevel.{ProjectRightOrdering, Read}
import fr.maif.izanami.models.RightLevel.{Admin, RightOrdering}
import fr.maif.izanami.models.Rights.{DeleteTenantRights, RightDiff, TenantRightDiff, UnscopedFlattenKeyRight, UnscopedFlattenProjectRight, UnscopedFlattenTenantRight, UnscopedFlattenWebhookRight, UpsertTenantRights}
import fr.maif.izanami.models.{GeneralAtomicRight, KeyRightUnit, OAuth2Configuration, OIDC, ProjectAtomicRight, ProjectRightLevel, ProjectRightLevelIncludingNoRight, ProjectRightUnit, ProjectScopedUser, RightLevel, RightLevelIncludingNoRight, RightUnit, Rights, SingleItemScopedUser, TenantRight, TenantRightWithMaxRights, User, UserRightsUpdateRequest, UserTrait, UserWithCompleteRightForOneTenant, UserWithRights, UserWithSingleLevelRight, UserWithTenantRights, WebhookRightUnit}
import fr.maif.izanami.services.RightService.{DEFAULT_ROLE, RightsByRole, Role, effectiveRights, keepHigher}
import fr.maif.izanami.utils.{Done, FutureEither}
import fr.maif.izanami.utils.syntax.implicits.{BetterFuture, BetterFutureEither, BetterJsValue, BetterSyntax}
import fr.maif.izanami.web.ImportController.{ImportConflictStrategy, Replace}
import io.vertx.sqlclient.SqlConnection
import play.api.libs.json.{JsError, JsSuccess, Json, Reads, Writes}

import java.util.UUID
import scala.collection.mutable.ArrayBuffer
import scala.concurrent.{ExecutionContext, Future}

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
      webhookNameById.find { (keyId, keyName) => name == keyName }.map {
        (id, name) => EntityIdentifiers(id = id, name = name)
      }
    case WebhookIdIdentification(id) =>
      webhookNameById.get(id).map { name =>
        EntityIdentifiers(id = id, name = name)
      }
  }
}

case class EntityIdentifiers(name: String, id: UUID)

class RightService(env: Env) {
  private implicit val executionContext: ExecutionContext = env.executionContext

  def canUpdateRightsForUser(user: UserTrait): Boolean = {
    canUpdateRightsForUsers(Set(user))
  }

  def canUpdateRightsForUsers(users: Set[UserTrait]): Boolean = {
    if (areOIDCUserRightsUpdatable) {
      users.nonEmpty && users.forall(u => u.userType != OIDC)
    } else {
      true
    }
  }

  private def areOIDCUserRightsUpdatable: Boolean =
    env.typedConfiguration.openid.exists(o =>
      o.roleRightMode.contains(Supervised)
    )

  def generateRightsForRoles(
      roles: Set[Role],
      rights: RightsByRole
  ): CompleteRights = {
    effectiveRights(rights, roles)
  }

  def updateUsersRightsForTenant(
      targetUsers: Set[String],
      tenant: String,
      diff: TenantRightDiff,
      conn: Option[SqlConnection] = None,
      conflictStrategy: ImportConflictStrategy = Replace
  ): FutureEither[Done] = {
    for (
      rightsByUsers <- env.datastores.users
        .findCompleteRightsFromTenantForUsers(
          targetUsers,
          tenants = Set(tenant)
        )
        .mapToFEither;
      users = rightsByUsers.values.toSet
        .map(u => u.toUserWithCompleteRightForOneTenant(tenant));
      maybeMaxRightsByRoles <- env.datastores.configuration
        .readFullConfiguration()
        .map(_.oidcConfiguration.flatMap(_.maxRightsByRoles));
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
    def doUpdate() = env.datastores.users.updateUsersRightsForTenant(
      users.map(_.username),
      tenant,
      diff,
      conn,
      conflictStrategy
    )

    def checkRights(
        user: UserWithCompleteRightForOneTenant
    ): Either[IzanamiError, Done] = {
      if (canUpdateRightsForUser(user) && user.userType == OIDC && env.externalRightMode.exists(_ != Supervised)) {
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
      user <- env.datastores.users
        .findUserWithRightForTenant(targetUser, tenant = tenant)
        .toFEither;
      maxRightsByRoles <- env.datastores.configuration
        .readFullConfiguration()
        .map(_.oidcConfiguration.flatMap(_.maxRightsByRoles));
      res <- doUpdateUserRightsForTenant(
        Set(user),
        tenant,
        diff,
        maxRightsByRoles,
        conn
      )
    ) yield res
  }

  def updateUserRights(
      name: String,
      updateRequest: UserRightsUpdateRequest,
      conn: Option[SqlConnection] = None,
      force: Boolean = false
  ): FutureEither[Done] = {
    env.datastores.users
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

          val res = env.datastores.configuration
            .readFullConfiguration()
            .map(_.oidcConfiguration.flatMap(_.maxRightsByRoles))
            .map(maybeMaxRights => {
              maybeMaxRights
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
            })
            .flatMap {
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
                          env.datastores.users
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

          res
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
    env.datastores.users
      .hasRightFor(
        tenant = tenant,
        userIdentication = userIdentification,
        rights = rights,
        tenantLevel = tenantLevel
      )
      .flatMap {
        case Some(res) if res.user.userType == OIDC  && env.externalRightMode.exists(_ != Supervised)=>
          env.datastores.configuration.readOIDCRightConfiguration().map {
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
        case None => Future.successful(None)
        case Some(res) => Future.successful(Some(res))
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
      users <- env.datastores.users.findUsersForProject(tenant, project);
      oidcConfig <- env.datastores.configuration.readOIDCRightConfiguration()
    ) yield {
      oidcConfig.map(rights => {
        users.flatMap(user => {
          val maxPossibleRight = rights.maxRightForProject(tenant,  project, user.roles)
          maxPossibleRight.toMaybeProjectRightLevel.map(maxRight => {
            if (user.userType == OIDC && env.externalRightMode.exists(_ != Supervised)) {
              user.copy(right = if(Option(user.right).exists(_.isGreaterThan(maxRight))) maxRight else user.right, defaultRight = user.defaultRight.map(dr => if(dr.isGreaterThan(maxRight)) maxRight else dr))
            } else {
              user
            }
          })
        })
      }).getOrElse(users)
    }
  }

  def findUsersForKey(
                       tenant: String,
                       key: String
                     ): Future[List[SingleItemScopedUser]] = {
    for (
      users <- env.datastores.users.findUsersForKey(tenant, key);
      oidcConfig <- env.datastores.configuration.readOIDCRightConfiguration()
    ) yield {
      oidcConfig.map(rights => {
        users.flatMap(user => {
          val maxPossibleRight = rights.maxRightForKey(tenant, key, user.roles)
          maxPossibleRight.toMaybeRightLevel.map(maxRight => {
            if (user.userType == OIDC && env.externalRightMode.exists(_ != Supervised)) {
              user.copy(right = if(user.right.isGreaterThan(maxRight)) maxRight else user.right, defaultRight = user.defaultRight.map(dr => if(dr.isGreaterThan(maxRight)) maxRight else dr))
            } else {
              user
            }
          })
        })
      }).getOrElse(users)
    }
  }

  def findUsersForWebhook(
                           tenant: String,
                           webhook: String
                         ): Future[List[SingleItemScopedUser]] = {
    for (
      users <- env.datastores.users.findUsersForWebhook(tenant, webhook);
      oidcConfig <- env.datastores.configuration.readOIDCRightConfiguration()
    ) yield {
      oidcConfig.map(rights => {
        users.flatMap(user => {
          val maxPossibleRight = rights.maxRightForWebhook(tenant, webhook, user.roles)
          maxPossibleRight.toMaybeRightLevel.map(maxRight => {
            if (user.userType == OIDC && env.externalRightMode.exists(_ != Supervised)) {
              user.copy(right = if(user.right.isGreaterThan(maxRight)) maxRight else user.right, defaultRight = user.defaultRight.map(dr => if(dr.isGreaterThan(maxRight)) maxRight else dr))
            } else {
              user
            }
          })
        })
      }).getOrElse(users)
    }
  }

  def findUsersForTenant(tenant: String):  Future[List[UserWithSingleLevelRight]] = {
    for(
      users <- env.datastores.users.findUsersForTenant(tenant);
      oidcConfig <- env.datastores.configuration.readOIDCRightConfiguration()
    ) yield {
      oidcConfig.map(rights => {
        users.flatMap(user => {
          val maxPossibleRight = rights.maxRightForTenant(tenant, user.roles)
          maxPossibleRight.toMaybeRightLevel.map(maxRight => {
            if(user.right.isGreaterThan(maxRight) && user.userType == OIDC  && env.externalRightMode.exists(_ != Supervised)) {
              user.copy(right = maxRight)
            } else {
              user
            }
          })
        })
      }).getOrElse(users)
    }
  }

  def findVisibleUsers(username: String): Future[Set[UserWithTenantRights]] = {
    for(
      users <- env.datastores.users.findVisibleUsers(username);
      oidcConfig <- env.datastores.configuration.readOIDCRightConfiguration()
    ) yield {
      oidcConfig.map(rights => {
        users.map(user => {
          val newRights = user.tenantRights.map {(tenant, rightLevel) => {
            val maxPossibleRight = rights.maxRightForTenant(tenant, user.roles)
            maxPossibleRight match {
              case level: RightLevel if rightLevel.isGreaterThan(level) && user.userType == OIDC  && env.externalRightMode.exists(_ != Supervised) => Some((tenant, level))
              case RightLevelIncludingNoRight.None => Option.empty
              case _ => Some((tenant, rightLevel))
            }
          }}.flatten.toMap
          user.copy(tenantRights = newRights)
        })
      }).getOrElse(users)
    }
  }
}

sealed trait ProjectIdentification
case class ProjectIdIdentification(identification: UUID)
    extends ProjectIdentification
case class ProjectNameIdentification(identification: String)
    extends ProjectIdentification

sealed trait WebhookIdentification
case class WebhookNameIdentification(name: String) extends WebhookIdentification
case class WebhookIdIdentification(id: UUID) extends WebhookIdentification


case object RightService {
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
        .flatMap(_.defaultProjectRight)
        .getOrElse(ProjectRightLevelIncludingNoRight.None),
      defaultKeyRight = tr
        .flatMap(_.defaultKeyRight)
        .getOrElse(RightLevelIncludingNoRight.None),
      defaultWebhookRight = tr
        .flatMap(_.defaultWebhookRight)
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
      defaultProjectRight = Ordering
        .Option(ProjectRightOrdering)
        .max(r1.defaultProjectRight, r2.defaultProjectRight),
      defaultKeyRight = Ordering
        .Option(RightOrdering)
        .max(r1.defaultKeyRight, r2.defaultKeyRight),
      defaultWebhookRight = Ordering
        .Option(RightOrdering)
        .max(r1.defaultWebhookRight, r2.defaultWebhookRight)
    )
  }
}

case class RightComplianceChange(
    before: RightLevelIncludingNoRight,
    after: RightLevelIncludingNoRight
)
case class ProjectRightComplianceChange(
    before: ProjectRightLevelIncludingNoRight,
    after: ProjectRightLevelIncludingNoRight
)

case class TenantRightComplianceResult(
    levelRight: Option[RightComplianceChange],
    defaultProjectRight: Option[ProjectRightComplianceChange],
    defaultKeyRight: Option[RightComplianceChange],
    defaultWebhookRight: Option[RightComplianceChange],
    projects: Map[String, ProjectRightComplianceChange],
    keys: Map[String, RightComplianceChange],
    webhooks: Map[String, RightComplianceChange]
) {
  def rightDiff: Option[TenantRightDiff] = {
    if (isEmpty) {
      Option.empty
    } else if (
      levelRight.isDefined && levelRight.exists(
        _.after == RightLevelIncludingNoRight.None
      )
    ) {
      Some(DeleteTenantRights)
    } else {
      val levelDiff = levelRight.map(r => r.after)

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
          addedTenantRight = levelDiff.flatMap(_.toMaybeRightLevel),
          addedProjectRights = addedProjects,
          removedProjectRights = removedProjects,
          addedKeyRights = addedKeys,
          removedKeyRights = removedKeys,
          addedWebhookRights = addedWebhooks,
          removedWebhookRights = removedWebhooks,
          addedDefaultProjectRight =
            defaultProjectRight.flatMap(_.after.toMaybeProjectRightLevel),
          removedDefaultProjectRight = defaultProjectRight.exists(
            _.after == ProjectRightLevelIncludingNoRight.None
          ),
          addedDefaultKeyRight =
            defaultKeyRight.flatMap(_.after.toMaybeRightLevel),
          removedDefaultKeyRight =
            defaultKeyRight.exists(_.after == RightLevelIncludingNoRight.None),
          addedDefaultWebhookRight =
            defaultWebhookRight.flatMap(_.after.toMaybeRightLevel),
          removedDefaultWebhookRight = defaultWebhookRight.exists(
            _.after == RightLevelIncludingNoRight.None
          )
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
        s"default project right ${defaultProjectRight.get.before} (max allowed is ${defaultProjectRight.get.after.toString})"
      )
    }
    if (defaultKeyRight.isDefined) {
      msgs.addOne(
        s"default key right ${defaultKeyRight.get.before} (max allowed is ${defaultKeyRight.get.after.toString})"
      )
    }
    if (defaultWebhookRight.isDefined) {
      msgs.addOne(
        s"default key right ${defaultWebhookRight.get.before} (max allowed is ${defaultWebhookRight.get.after.toString})"
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

case object TenantRightComplianceResult {
  def empty: TenantRightComplianceResult = TenantRightComplianceResult(
    Option.empty,
    Option.empty,
    Option.empty,
    Option.empty,
    Map(),
    Map(),
    Map()
  )
}

case class MaxRightComplianceResult(
    admin: Boolean,
    tenants: Map[String, TenantRightComplianceResult]
) {
  def rightDiff: RightDiff = {
    RightDiff(
      admin = if (admin) Some(false) else None,
      diff = tenants
        .map { (name, r) =>
          {
            r.rightDiff.map(d => (name, d))
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

case class OIDCRights(rights: Map[String, CompleteRightsWithMaxRights]) {
  private def maxRightsForRoles(roles: Set[String]): Option[MaxRights] = {
    val maxRights = rights
      .filter { (role, rights) =>
        roles.contains(role) || role == DEFAULT_ROLE
      }
      .map { (role, rights) => rights.maxRights }

    if (maxRights.isEmpty) {
      Option.empty
    } else {
        Some(maxRights.reduce((mr1, mr2) => MaxRights.keepHigher(mr1, mr2)))
    }
  }

  def maxRightForTenant(tenant: String, roles: Set[String]): RightLevelIncludingNoRight = {
    (for(
      maxRights <- maxRightsForRoles(roles);
      tenantRights <- maxRights.tenants.get(tenant)
    ) yield tenantRights.level).getOrElse(Admin)
  }

  def maxRightForProject(tenant: String, project: String, roles: Set[String]): ProjectRightLevelIncludingNoRight = {
    (for (
      maxRights <- maxRightsForRoles(roles);
      tenantRights <- maxRights.tenants.get(tenant)
    ) yield tenantRights.maxProjectRight).getOrElse(ProjectRightLevel.Admin)
  }

  def maxRightForKey(tenant: String, key: String, roles: Set[String]): RightLevelIncludingNoRight = {
    (for (
      maxRights <- maxRightsForRoles(roles);
      tenantRights <- maxRights.tenants.get(tenant)
    ) yield tenantRights.maxKeyRight).getOrElse(RightLevel.Admin)
  }

  def maxRightForWebhook(tenant: String, webhook: String, roles: Set[String]): RightLevelIncludingNoRight = {
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
      })
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
    level == RightLevel.Admin || maxWebhookRight.isGreaterThan(requestLevel)
  def allowRightForProject(requestLevel: ProjectRightLevel): Boolean =
    level == RightLevel.Admin || maxProjectRight.isGreaterThan(requestLevel)
  def allowRightForKey(requestLevel: RightLevel): Boolean =
    level == RightLevel.Admin || maxKeyRight.isGreaterThan(requestLevel)
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

object MaxRightComplianceResult {
  def empty: MaxRightComplianceResult =
    MaxRightComplianceResult(admin = false, tenants = Map())
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
      current: Option[RightLevel],
      max: RightLevelIncludingNoRight
  ): Option[RightComplianceChange] = {
    (current, max) match {
      case (Some(curr), m) if (curr.isGreaterThan(m)) =>
        Some(RightComplianceChange(before = curr, after = m))
      case _ => None
    }
  }

  def generateComplianceRightChangeForProject(
      current: Option[ProjectRightLevel],
      max: ProjectRightLevelIncludingNoRight
  ): Option[ProjectRightComplianceChange] = {
    (current, max) match {
      case (Some(curr), m) if (curr.isGreaterThan(m)) =>
        Some(ProjectRightComplianceChange(before = curr, after = m))
      case _ => None
    }
  }

  def maxRightsToApply(
      roles: Set[String],
      maxRightsByRole: Map[String, MaxRights]
  ): MaxRights = {
    val rs = maxRightsByRole.filter { (role, maxRights) =>
      {
        roles.contains(role) || DEFAULT_ROLE == role
      }
    }.values

    rs.reduce((mr1, mr2) => {
      max(mr1, mr2)
    })
  }

  def max(r1: MaxRights, r2: MaxRights): MaxRights = {
    val admin = r1.admin || r2.admin
    val rightByTenants = r1.tenants.toSeq
      .concat(r2.tenants)
      .groupMapReduce(_._1)(_._2)((r1, r2) => max(r1, r2))

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
