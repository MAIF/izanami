package fr.maif.izanami.services

import fr.maif.izanami.RoleRightMode.Supervised
import fr.maif.izanami.{RoleRightMode, RoleRights, TenantRoleRights}
import fr.maif.izanami.env.Env
import fr.maif.izanami.errors.{CantUpdateOIDCUser, IzanamiError}
import fr.maif.izanami.models.ProjectRightLevel.ProjectRightOrdering
import fr.maif.izanami.models.RightLevel.RightOrdering
import fr.maif.izanami.models.Rights.TenantRightDiff
import fr.maif.izanami.models.{
  GeneralAtomicRight,
  OAuth2Configuration,
  OIDC,
  ProjectAtomicRight,
  ProjectRightLevel,
  Rights,
  TenantRight,
  User,
  UserRightsUpdateRequest
}
import fr.maif.izanami.services.RightService.{effectiveRights, keepHigher, RightsByRole, Role}
import fr.maif.izanami.utils.syntax.implicits.{BetterJsValue, BetterSyntax}
import play.api.libs.json.{JsError, JsSuccess, Json, Reads, Writes}

import scala.concurrent.{ExecutionContext, Future}

class RightService(env: Env) {
  private implicit val executionContext: ExecutionContext = env.executionContext

  def canUpdateRightsForUser(username: String): Future[Boolean] = {
    if (areOIDCUserRightsUpdatable) {
      env.datastores.users.findUser(username).map(maybeUser => maybeUser.exists(u => u.userType != OIDC))
    } else {
      Future.successful(true)
    }
  }

  private def areOIDCUserRightsUpdatable: Boolean =
    env.typedConfiguration.openid.exists(o => o.roleRightMode.contains(Supervised))

  def generateRightsForRoles(roles: Set[Role]): Future[Option[CompleteRights]] = {
    maybeRightByRoles.map(o => o.map(rights => effectiveRights(rights, roles)))
  }

  def maybeRightByRoles: Future[Option[RightsByRole]] = {
    env.datastores.configuration
      .readFullConfiguration()
      .map(_.toOption.flatMap(_.oidcConfiguration))
      .map(_.map(c => c.userRightsByRoles))
  }

  def updateUserRightsForTenant(
      targetUser: String,
      tenant: String,
      diff: TenantRightDiff
  ): Future[Either[IzanamiError, Unit]] = {
    canUpdateRightsForUser(targetUser).flatMap {
      case false => Future.successful(Left(CantUpdateOIDCUser()))
      case true  => {
        env.datastores.users.updateUserRightsForTenant(targetUser, tenant, diff).map(Right(_))
      }
    }
  }

  def updateUserRights(
      targetUser: String,
      updateRequest: UserRightsUpdateRequest
  ): Future[Either[IzanamiError, Unit]] = {
    canUpdateRightsForUser(targetUser).flatMap {
      case false => Future.successful(Left(CantUpdateOIDCUser()))
      case true  => {
        env.datastores.users.updateUserRights(targetUser, updateRequest)
      }
    }
  }
}

case object RightService {
  type Role         = String
  type Tenant       = String
  type Project      = String
  type RightsByRole = Map[Role, CompleteRights]
  val DEFAULT_ROLE = ""

  def effectiveRights(defaultRights: RightsByRole, effectiveRoles: Set[Role]): CompleteRights = {
    defaultRights
      .collect {
        case (role, rights) if effectiveRoles.contains(role) || role == DEFAULT_ROLE => rights
      }
      .foldLeft(CompleteRights.EMPTY)((r1, r2) => r1.mergeWith(r2))
  }

  def keepHigher(r1: TenantRight, r2: TenantRight): TenantRight = {
    val level         = Seq(r1.level, r2.level).max(RightOrdering)
    val projectRights = r1.projects.toSeq
      .concat(r2.projects.toSeq)
      .groupMapReduce(_._1)(_._2)((r1, r2) => Seq(r1, r2).maxBy(_.level)(ProjectRightOrdering))
    val keyRights     = r1.keys.toSeq
      .concat(r2.keys.toSeq)
      .groupMapReduce(_._1)(_._2)((r1, r2) => Seq(r1, r2).maxBy(_.level)(RightOrdering))
    val webhookRights = r1.webhooks.toSeq
      .concat(r2.webhooks.toSeq)
      .groupMapReduce(_._1)(_._2)((r1, r2) => Seq(r1, r2).maxBy(_.level)(RightOrdering))

    TenantRight(
      level = level,
      projects = projectRights,
      keys = keyRights,
      webhooks = webhookRights,
      defaultProjectRight = Ordering.Option(ProjectRightOrdering).max(r1.defaultProjectRight, r2.defaultProjectRight),
      defaultKeyRight = Ordering.Option(RightOrdering).max(r1.defaultKeyRight, r2.defaultKeyRight),
      defaultWebhookRight = Ordering.Option(RightOrdering).max(r1.defaultWebhookRight, r2.defaultWebhookRight)
    )
  }
}

case class CompleteRights(tenants: Map[String, TenantRight], admin: Boolean) {
  def mergeWith(other: CompleteRights): CompleteRights = {
    val admin = this.admin || other.admin

    val tenantRights =
      this.tenants.concat(other.tenants).groupMapReduce(_._1)(_._2)((tr1, tr2) => keepHigher(tr1, tr2))

    CompleteRights(admin = admin, tenants = tenantRights)
  }

  def removeTenantsRights(tenant: Set[String]): CompleteRights = {
    copy(admin = admin, tenants = tenants.filter(t => !tenant.contains(t._1)))
  }

  def removeProjectRights(tenant: String, project: Set[String]): CompleteRights = {
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

  def removeWebhookRights(tenant: String, webhooks: Set[String]): CompleteRights = {
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

case object CompleteRights {
  val EMPTY = CompleteRights(admin = false, tenants = Map.empty)

  def writes: Writes[CompleteRights] = r => {
    val jsonRights = Json.toJson(r.tenants)(Writes.map(User.tenantRightWrite))
    Json.obj("admin" -> r.admin, "tenants" -> jsonRights)
  }

  def reads: Reads[CompleteRights] = json => {
    (for (
      admin   <- (json \ "admin").asOpt[Boolean];
      tenants <- (json \ "tenants").asOpt[Map[String, TenantRight]](Reads.map(User.tenantRightReads))
    ) yield CompleteRights(admin = admin, tenants = tenants))
      .map(r => JsSuccess(r))
      .getOrElse(JsError("Bad body format"))
  }
}
