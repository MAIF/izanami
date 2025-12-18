package fr.maif.izanami.services

import fr.maif.izanami.RoleRightMode.Supervised
import fr.maif.izanami.{RoleRightMode, RoleRights, TenantRoleRights}
import fr.maif.izanami.env.Env
import fr.maif.izanami.errors.{CantUpdateOIDCUser, IzanamiError, UserNotFound}
import fr.maif.izanami.models.ProjectRightLevel.ProjectRightOrdering
import fr.maif.izanami.models.RightLevel.RightOrdering
import fr.maif.izanami.models.Rights.{
  DeleteTenantRights,
  RightDiff,
  TenantRightDiff,
  UnscopedFlattenKeyRight,
  UnscopedFlattenProjectRight,
  UnscopedFlattenTenantRight,
  UnscopedFlattenWebhookRight,
  UpsertTenantRights
}
import fr.maif.izanami.models.{
  GeneralAtomicRight,
  OAuth2Configuration,
  OIDC,
  ProjectAtomicRight,
  ProjectRightLevel,
  RightLevel,
  Rights,
  TenantRight,
  User,
  UserRightsUpdateRequest,
  UserWithRights
}
import fr.maif.izanami.services.RightService.{
  DEFAULT_ROLE,
  RightsByRole,
  Role,
  effectiveRights,
  keepHigher
}
import fr.maif.izanami.utils.syntax.implicits.{BetterJsValue, BetterSyntax}
import fr.maif.izanami.web.ImportController.{ImportConflictStrategy, Replace}
import io.vertx.sqlclient.SqlConnection
import play.api.libs.json.{JsError, JsSuccess, Json, Reads, Writes}

import scala.collection.mutable.ArrayBuffer
import scala.concurrent.{ExecutionContext, Future}

class RightService(env: Env) {
  private implicit val executionContext: ExecutionContext = env.executionContext

  def canUpdateRightsForUser(username: String): Future[Boolean] = {
    canUpdateRightsForUsers(Set(username))
  }

  def canUpdateRightsForUsers(usernames: Set[String]): Future[Boolean] = {
    if (areOIDCUserRightsUpdatable) {
      env.datastores.users
        .findUsers(usernames)
        .map(users => users.nonEmpty && users.forall(u => u.userType != OIDC))
    } else {
      Future.successful(true)
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
  ): Future[Either[IzanamiError, Unit]] = {
    canUpdateRightsForUsers(targetUsers).flatMap {
      case false => Future.successful(Left(CantUpdateOIDCUser()))
      case true  => {
        env.datastores.users.updateUsersRightsForTenant(
          targetUsers,
          tenant,
          diff,
          conn,
          conflictStrategy
        )
      }
    }
  }

  def updateUserRightsForTenant(
      targetUser: String,
      tenant: String,
      diff: TenantRightDiff,
      conn: Option[SqlConnection] = None
  ): Future[Either[IzanamiError, Unit]] = {
    canUpdateRightsForUser(targetUser).flatMap {
      case false => Future.successful(Left(CantUpdateOIDCUser()))
      case true  => {
        env.datastores.users.updateUserRightsForTenant(
          targetUser,
          tenant,
          diff,
          conn
        )
      }
    }
  }

  def updateUserRights(
      name: String,
      updateRequest: UserRightsUpdateRequest,
      conn: Option[SqlConnection] = None,
      force: Boolean = false
  ): Future[Either[IzanamiError, Unit]] = {
    canUpdateRightsForUser(name).flatMap {
      case false if !force => Future.successful(Left(CantUpdateOIDCUser()))
      case _               => {
        env.datastores.users
          .findUserWithCompleteRights(name)
          .flatMap {
            case Some(UserWithRights(_, _, _, admin, _, rights, _, _)) => {
              val diff = Rights.compare(
                base = rights,
                modified = updateRequest.rights,
                baseAdmin = admin,
                admin = updateRequest.admin
              )

              env.datastores.users.updateUserRights(
                name = name,
                rightDiff = diff
              )
            }
            case None => Left(UserNotFound(name)).future
          }
      }
    }

  }
}

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

case class RightComplianceChange(before: RightLevel, after: Option[RightLevel])
case class ProjectRightComplianceChange(
    before: ProjectRightLevel,
    after: Option[ProjectRightLevel]
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
    } else if (levelRight.isDefined && levelRight.get.after.isEmpty) {
      Some(DeleteTenantRights)
    } else {
      val levelDiff = levelRight.flatMap(r => r.after)

      val addedProjects = projects.collect {
        case (name, ProjectRightComplianceChange(before, Some(after))) => {
          UnscopedFlattenProjectRight(name = name, level = after)
        }
      }.toSet

      val removedProjects = projects.collect {
        case (name, ProjectRightComplianceChange(before, None)) => {
          name
        }
      }.toSet

      val addedKeys = keys.collect {
        case (name, RightComplianceChange(before, Some(after))) => {
          UnscopedFlattenKeyRight(name = name, level = after)
        }
      }.toSet

      val removedKeys = keys.collect {
        case (name, RightComplianceChange(before, None)) => {
          name
        }
      }.toSet

      val addedWebhooks = webhooks.collect {
        case (name, RightComplianceChange(before, Some(after))) => {
          UnscopedFlattenWebhookRight(name = name, level = after)
        }
      }.toSet

      val removedWebhooks = webhooks.collect {
        case (name, RightComplianceChange(before, None)) => {
          name
        }
      }.toSet
      Some(
        UpsertTenantRights(
          addedTenantRight = levelDiff,
          addedProjectRights = addedProjects,
          removedProjectRights = removedProjects,
          addedKeyRights = addedKeys,
          removedKeyRights = removedKeys,
          addedWebhookRights = addedWebhooks,
          removedWebhookRights = removedWebhooks,
          addedDefaultProjectRight = defaultProjectRight.flatMap(_.after),
          removedDefaultProjectRight =
            defaultProjectRight.exists(_.after.isEmpty),
          addedDefaultKeyRight = defaultKeyRight.flatMap(_.after),
          removedDefaultKeyRight = defaultKeyRight.exists(_.after.isEmpty),
          addedDefaultWebhookRight = defaultWebhookRight.flatMap(_.after),
          removedDefaultWebhookRight =
            defaultWebhookRight.exists(_.after.isEmpty)
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
        s"right level ${levelRight.get.before} (max allowed is ${levelRight.get.after.map(_.toString).getOrElse("none")})"
      )
    }
    if (defaultProjectRight.isDefined) {
      msgs.addOne(
        s"default project right ${defaultProjectRight.get.before} (max allowed is ${defaultProjectRight.get.after.map(_.toString).getOrElse("none")})"
      )
    }
    if (defaultKeyRight.isDefined) {
      msgs.addOne(
        s"default key right ${defaultKeyRight.get.before} (max allowed is ${defaultKeyRight.get.after.map(_.toString).getOrElse("none")})"
      )
    }
    if (defaultWebhookRight.isDefined) {
      msgs.addOne(
        s"default key right ${defaultWebhookRight.get.before} (max allowed is ${defaultWebhookRight.get.after.map(_.toString).getOrElse("none")})"
      )
    }
    projects.foreach { (name, complianceIssue) =>
      msgs.addOne(
        s"${complianceIssue.before} right for project $name (max allowed is ${complianceIssue.after.getOrElse("none")})"
      )
    }
    keys.foreach { (name, complianceIssue) =>
      msgs.addOne(
        s"${complianceIssue.before} right for project $name (max allowed is ${complianceIssue.after.getOrElse("none")})"
      )
    }
    webhooks.foreach { (name, complianceIssue) =>
      msgs.addOne(
        s"${complianceIssue.before} right for project $name (max allowed is ${complianceIssue.after.getOrElse("none")})"
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

case class MaxRights(
    admin: Boolean,
    tenants: Map[String, MaxTenantRoleRights] = Map()
)

case class MaxTenantRoleRights(
    level: RightLevel,
    maxProjectRight: Option[ProjectRightLevel] = Option.empty,
    maxKeyRight: Option[RightLevel] = Option.empty,
    maxWebhookRight: Option[RightLevel] = Option.empty
)

case object MaxRights {
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
    (json \ "level")
      .asOpt[RightLevel](RightLevel.rightLevelReads)
      .fold(JsError("Failed to read "))(level => {
        val maxProjectRight =
          (json \ "maxProjectRight")
            .asOpt[ProjectRightLevel](ProjectRightLevel.projectRightLevelReads)
        val maxKeyRight =
          (json \ "maxKeyRight")
            .asOpt[RightLevel](RightLevel.rightLevelReads)
        val maxWebhookRight = (json \ "maxWebhookRight")
          .asOpt[RightLevel](RightLevel.rightLevelReads)

        JsSuccess(
          MaxTenantRoleRights(
            level,
            maxProjectRight,
            maxKeyRight,
            maxWebhookRight
          )
        )
      })
  }

  def writes: Writes[MaxTenantRoleRights] = rs => {
    Json
      .obj("level" -> Json.toJson(rs.level)(RightLevel.rightLevelWrites))
      .applyOnWithOpt(rs.maxProjectRight)((json, l) => {
        json + ("maxProjectRight" -> Json.toJson(l)(
          ProjectRightLevel.projectRightLevelWrites
        ))
      })
      .applyOnWithOpt(rs.maxKeyRight)((json, l) => {
        json + ("maxKeyRight" -> Json
          .toJson(l)(RightLevel.rightLevelWrites))
      })
      .applyOnWithOpt(rs.maxWebhookRight)((json, l) => {
        json + ("maxWebhookRight" -> Json
          .toJson(l)(RightLevel.rightLevelWrites))
      })
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

  def updateToComplyWith(maxRightComplianceResult: MaxRightComplianceResult): CompleteRights = {
    val newAdmin = if (admin && maxRightComplianceResult.admin) false else admin

    val newTenantRights = tenants
      .map { (name, tenantRight) => {
        val maybeComplianceResult = maxRightComplianceResult.tenants.get(name)

        maybeComplianceResult
          .map(complianceResult => {
            if (complianceResult.levelRight.exists(_.after.isEmpty)) {
              Option.empty
            } else {
              val newLevel = complianceResult.levelRight
                .flatMap(_.after)
                .getOrElse(tenantRight.level)
              val newDefaultProjectRight =
                complianceResult.defaultProjectRight
                  .map(dpr => dpr.after)
                  .getOrElse(tenantRight.defaultProjectRight)
              val newDefaultKeyRight = complianceResult.defaultKeyRight
                .map(dkr => dkr.after)
                .getOrElse(tenantRight.defaultKeyRight)
              val newDefaultWebhookRight =
                complianceResult.defaultWebhookRight
                  .map(dwr => dwr.after)
                  .getOrElse(tenantRight.defaultWebhookRight)

              val newProjectRights = tenantRight.projects
                .map {
                  case (name, right) => {
                    val maybeProjectComplianceChange =
                      complianceResult.projects.get(name)
                    if (
                      maybeProjectComplianceChange.exists(_.after.isEmpty)
                    ) {
                      Option.empty
                    } else {
                      Some(
                        (
                          name,
                          maybeProjectComplianceChange
                            .map(_.after.get)
                            .map(r => ProjectAtomicRight(r))
                            .getOrElse(right)
                        )
                      )
                    }
                  }
                }
                .collect { case Some(p) =>
                  p
                }
                .toMap

              val newWebhookRights = tenantRight.webhooks
                .map {
                  case (name, right) => {
                    val maybeWebhookComplanceChange =
                      complianceResult.webhooks.get(name)
                    if (maybeWebhookComplanceChange.exists(_.after.isEmpty)) {
                      Option.empty
                    } else {
                      Some(
                        (
                          name,
                          maybeWebhookComplanceChange
                            .map(_.after.get)
                            .map(r => GeneralAtomicRight(r))
                            .getOrElse(right)
                        )
                      )
                    }
                  }
                }
                .collect { case Some(p) =>
                  p
                }
                .toMap

              val newKeyRights = tenantRight.keys
                .map {
                  case (name, right) => {
                    val maybeKeyComplianceChange =
                      complianceResult.keys.get(name)
                    if (maybeKeyComplianceChange.exists(_.after.isEmpty)) {
                      Option.empty
                    } else {
                      Some(
                        (
                          name,
                          maybeKeyComplianceChange
                            .map(_.after.get)
                            .map(r => GeneralAtomicRight(r))
                            .getOrElse(right)
                        )
                      )
                    }
                  }
                }
                .collect { case Some(p) =>
                  p
                }
                .toMap

              Some(
                name,
                TenantRight(
                  level = newLevel,
                  projects = newProjectRights,
                  keys = newKeyRights,
                  webhooks = newWebhookRights,
                  defaultProjectRight = newDefaultProjectRight,
                  defaultKeyRight = newDefaultKeyRight,
                  defaultWebhookRight = newDefaultWebhookRight
                )
              )
            }
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
                TenantRight(
                  level,
                  projects,
                  keys,
                  webhooks,
                  defaultProjectRight,
                  defaultKeyRight,
                  defaultWebhookRight
                )
              ) => {
            val maybeLevelNonCompliance =
              if (level.isGreaterThan(maxRights.level))
                Some(
                  RightComplianceChange(
                    before = level,
                    after = Some(maxRights.level)
                  )
                )
              else None

            val maybeDefaultKeyNonCompliance =
              CompleteRights.generateComplianceRightChange(
                defaultKeyRight,
                maxRights.maxKeyRight
              )
            val keyComplianceChange = keys
              .map { (name, rightLevel) =>
                CompleteRights
                  .generateComplianceRightChange(
                    Some(rightLevel.level),
                    maxRights.maxKeyRight
                  )
                  .map(complianceChange => (name, complianceChange))
              }
              .collect { case Some(nonCompliance) =>
                nonCompliance
              }
              .toMap
            val maybeDefaultWebhookNonCompliance =
              CompleteRights.generateComplianceRightChange(
                defaultWebhookRight,
                maxRights.maxWebhookRight
              )
            val webhookComplianceChange = webhooks
              .map { (name, rightLevel) =>
                CompleteRights
                  .generateComplianceRightChange(
                    Some(rightLevel.level),
                    maxRights.maxWebhookRight
                  )
                  .map(complianceChange => (name, complianceChange))
              }
              .collect { case Some(nonCompliance) =>
                nonCompliance
              }
              .toMap
            val maybeDefaultProjectNonCompliance =
              CompleteRights.generateComplianceRightChangeForProject(
                defaultProjectRight,
                maxRights.maxProjectRight
              )
            val projectComplianceChange = projects
              .map { (name, rightLevel) =>
                CompleteRights
                  .generateComplianceRightChangeForProject(
                    Some(rightLevel.level),
                    maxRights.maxProjectRight
                  )
                  .map(complianceChange => (name, complianceChange))
              }
              .collect { case Some(nonCompliance) =>
                nonCompliance
              }
              .toMap

            TenantRightComplianceResult(
              levelRight = maybeLevelNonCompliance,
              defaultProjectRight = maybeDefaultProjectNonCompliance,
              defaultKeyRight = maybeDefaultKeyNonCompliance,
              defaultWebhookRight = maybeDefaultWebhookNonCompliance,
              projects = projectComplianceChange,
              keys = keyComplianceChange,
              webhooks = webhookComplianceChange
            )
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
      max: Option[RightLevel]
  ): Option[RightComplianceChange] = {
    (current, max) match {
      case (Some(r), None) =>
        Some(RightComplianceChange(before = r, after = Option.empty))
      case (Some(curr), Some(m)) if (curr.isGreaterThan(m)) =>
        Some(RightComplianceChange(before = curr, after = Some(m)))
      case _ => None
    }
  }

  def generateComplianceRightChangeForProject(
      current: Option[ProjectRightLevel],
      max: Option[ProjectRightLevel]
  ): Option[ProjectRightComplianceChange] = {
    (current, max) match {
      case (Some(r), None) =>
        Some(ProjectRightComplianceChange(before = r, after = Option.empty))
      case (Some(curr), Some(m)) if (curr.isGreaterThan(m)) =>
        Some(ProjectRightComplianceChange(before = curr, after = Some(m)))
      case _ => None
    }
  }

  def maxRightsToApply(
      roles: Set[String],
      maxRightsByRole: Map[String, MaxRights]
  ): MaxRights = {
    maxRightsByRole
      .filter { (role, maxRights) =>
        roles.contains(role) || DEFAULT_ROLE == role
      }
      .values
      .reduce((mr1, mr2) => max(mr1, mr2))
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
      maxProjectRight = (r1.maxProjectRight, r2.maxProjectRight) match {
        case (Some(mp1), Some(mp2)) if (mp1.isGreaterThan(mp2)) => Some(mp1)
        case (_, Some(mp2))                                     => Some(mp2)
        case (o, _)                                             => o
      },
      maxKeyRight = (r1.maxKeyRight, r2.maxKeyRight) match {
        case (Some(mp1), Some(mp2)) if (mp1.isGreaterThan(mp2)) => Some(mp1)
        case (_, Some(mp2))                                     => Some(mp2)
        case (o, _)                                             => o
      },
      maxWebhookRight = (r1.maxWebhookRight, r2.maxWebhookRight) match {
        case (Some(mp1), Some(mp2)) if (mp1.isGreaterThan(mp2)) => Some(mp1)
        case (_, Some(mp2))                                     => Some(mp2)
        case (o, _)                                             => o
      }
    )
  }
}
