package fr.maif.izanami.models

import fr.maif.izanami.models.ProjectRightLevel.{Admin, superiorOrEqualLevels}
import fr.maif.izanami.models.Rights.{
  TenantRightDiff,
  UpsertTenantRights,
  compare
}
import fr.maif.izanami.services.CompleteRights
import fr.maif.izanami.utils.syntax.implicits.BetterSyntax
import play.api.data.validation.{Constraints, Valid}
import play.api.libs.functional.syntax.toFunctionalBuilderOps
import play.api.libs.json.*
import play.api.mvc.QueryStringBindable

import scala.collection.mutable.ArrayBuffer
import scala.util.matching.Regex

object RightTypes extends Enumeration {
  type RightType = Value
  val Project, Key = Value
}

sealed trait RightUnit {
  def name: String
}
case class ProjectRightUnit(name: String, rightLevel: ProjectRightLevel)
    extends RightUnit
case class KeyRightUnit(name: String, rightLevel: RightLevel) extends RightUnit

sealed trait RightLevel

object RightLevel {
  case object Read extends RightLevel
  case object Write extends RightLevel
  case object Admin extends RightLevel

  object RightOrdering extends Ordering[RightLevel] {
    def compare(a: RightLevel, b: RightLevel): Int = (a, b) match {
      case (ar, br) if ar == br => 0
      case (Admin, _)           => 1
      case (_, Admin)           => -1
      case (Write, _)           => 1
      case (_, Write)           => -1
      case _                    => 1
    }
  }

  val rightLevelWrites: Writes[RightLevel] = {
    case Read  => JsString("Read")
    case Write => JsString("Write")
    case Admin => JsString("Admin")
  }

  val rightLevelReads: Reads[RightLevel] = { json =>
    json
      .asOpt[String]
      .flatMap(s => RightLevel.readFromString(s))
      .map(JsSuccess(_))
      .getOrElse(JsError(s"${json} is not a correct right level"))
  }

  def readFromString(s: String): Option[RightLevel] = {
    s.toUpperCase match {
      case "ADMIN" => Some(RightLevel.Admin)
      case "WRITE" => Some(RightLevel.Write)
      case "READ"  => Some(RightLevel.Read)
      case _       => None
    }
  }

  def superiorOrEqualLevels(level: RightLevel): Set[RightLevel] =
    level match {
      case RightLevel.Read =>
        Set(RightLevel.Read, RightLevel.Write, RightLevel.Admin)
      case RightLevel.Write => Set(RightLevel.Write, RightLevel.Admin)
      case RightLevel.Admin => Set(RightLevel.Admin)
    }

  implicit def queryStringBindable(implicit
      stringBinder: QueryStringBindable[String]
  ): QueryStringBindable[RightLevel] =
    new QueryStringBindable[RightLevel] {
      override def bind(
          key: String,
          params: Map[String, Seq[String]]
      ): Option[Either[String, RightLevel]] = {
        params.get(key).collect { case Seq(s) =>
          readFromString(s).toRight("Invalid right specified")
        }
      }

      override def unbind(key: String, value: RightLevel): String = {
        implicitly[QueryStringBindable[String]].unbind(key, value.toString)
      }
    }
}

sealed trait ProjectRightLevel

case object ProjectRightLevel {
  case object Read extends ProjectRightLevel
  case object Update extends ProjectRightLevel
  case object Write extends ProjectRightLevel
  case object Admin extends ProjectRightLevel

  object ProjectRightOrdering extends Ordering[ProjectRightLevel] {
    def compare(a: ProjectRightLevel, b: ProjectRightLevel): Int =
      (a, b) match {
        case (ar, br) if ar == br => 0
        case (Admin, _)           => 1
        case (_, Admin)           => -1
        case (Write, _)           => 1
        case (_, Write)           => -1
        case (Update, _)          => 1
        case (_, Update)          => -1
        case _                    => 1
      }
  }

  val projectRightLevelWrites: Writes[ProjectRightLevel] = {
    case Read   => JsString("Read")
    case Update => JsString("Update")
    case Write  => JsString("Write")
    case Admin  => JsString("Admin")
  }

  val projectRightLevelReads: Reads[ProjectRightLevel] = { json =>
    json
      .asOpt[String]
      .flatMap(str => ProjectRightLevel.readFromString(str))
      .map(JsSuccess(_))
      .getOrElse(JsError(s"${json} is not a correct right level"))
  }

  def readFromString(s: String): Option[ProjectRightLevel] = {
    s.toUpperCase match {
      case "ADMIN"  => Some(ProjectRightLevel.Admin)
      case "WRITE"  => Some(ProjectRightLevel.Write)
      case "UPDATE" => Some(ProjectRightLevel.Update)
      case "READ"   => Some(ProjectRightLevel.Read)
      case _        => None
    }
  }

  def superiorOrEqualLevels(level: ProjectRightLevel): Set[ProjectRightLevel] =
    level match {
      case ProjectRightLevel.Read =>
        Set(
          ProjectRightLevel.Read,
          ProjectRightLevel.Update,
          ProjectRightLevel.Write,
          ProjectRightLevel.Admin
        )
      case ProjectRightLevel.Update =>
        Set(
          ProjectRightLevel.Update,
          ProjectRightLevel.Write,
          ProjectRightLevel.Admin
        )
      case ProjectRightLevel.Write =>
        Set(ProjectRightLevel.Write, ProjectRightLevel.Admin)
      case ProjectRightLevel.Admin => Set(ProjectRightLevel.Admin)
    }

  implicit def queryStringBindable(implicit
      stringBinder: QueryStringBindable[String]
  ): QueryStringBindable[ProjectRightLevel] =
    new QueryStringBindable[ProjectRightLevel] {
      override def bind(
          key: String,
          params: Map[String, Seq[String]]
      ): Option[Either[String, ProjectRightLevel]] = {
        params.get(key).collect { case Seq(s) =>
          readFromString(s).toRight("Invalid right specified")
        }
      }

      override def unbind(key: String, value: ProjectRightLevel): String = {
        implicitly[QueryStringBindable[String]].unbind(key, value.toString)
      }
    }
}

case class UserInvitation(
    email: String,
    rights: Rights = Rights.EMPTY,
    admin: Boolean = false,
    id: String = null
)

case class UserRightsUpdateRequest(
    rights: Rights = Rights.EMPTY,
    admin: Option[Boolean] = None
)

object UserRightsUpdateRequest {
  def fromRights(rights: CompleteRights): UserRightsUpdateRequest = {
    UserRightsUpdateRequest(
      rights = Rights(rights.tenants),
      admin = Some(rights.admin)
    )
  }
}

case class UserInformationUpdateRequest(
    name: String,
    email: String,
    password: String,
    defaultTenant: Option[String]
)

case class UserPasswordUpdateRequest(
    password: String,
    oldPassword: String
)

sealed trait UserType

case object INTERNAL extends UserType

case object OTOROSHI extends UserType

case object OIDC extends UserType

sealed trait UserTrait {
  val username: String
  val email: String
  val password: String = null
  val admin: Boolean = false
  val userType: UserType = INTERNAL
  val defaultTenant: Option[String] = None
  val legacy: Boolean = false

  def withRights(rights: Rights): UserWithRights =
    UserWithRights(
      username,
      email,
      password,
      admin,
      userType,
      rights,
      defaultTenant
    )
}

case class User(
    override val username: String,
    override val email: String = null,
    override val password: String = null,
    override val admin: Boolean = false,
    override val userType: UserType = INTERNAL,
    override val defaultTenant: Option[String] = None,
    override val legacy: Boolean = false
) extends UserTrait {
  def withSingleLevelRight(level: RightLevel): UserWithSingleLevelRight =
    UserWithSingleLevelRight(
      username,
      email,
      password,
      admin,
      userType,
      level,
      defaultTenant
    )

  def withProjectScopedRight(
      level: ProjectRightLevel,
      defaultRight: Option[ProjectRightLevel],
      tenantAdmin: Boolean = false
  ): ProjectScopedUser =
    ProjectScopedUser(
      username,
      email,
      password,
      admin,
      userType,
      level,
      defaultRight,
      defaultTenant,
      tenantAdmin
    )

  def withWebhookOrKeyRight(
      level: RightLevel,
      defaultRight: Option[RightLevel],
      tenantAdmin: Boolean = false
  ): SingleItemScopedUser =
    SingleItemScopedUser(
      username,
      email,
      password,
      admin,
      userType,
      level,
      defaultRight,
      defaultTenant,
      tenantAdmin
    )
}

case class ProjectScopedUser(
    override val username: String,
    override val email: String = null,
    override val password: String = null,
    override val admin: Boolean = false,
    override val userType: UserType,
    right: ProjectRightLevel,
    defaultRight: Option[ProjectRightLevel],
    override val defaultTenant: Option[String] = None,
    tenantAdmin: Boolean = false
) extends UserTrait

// Repesent a user with a single key or webhook right
case class SingleItemScopedUser(
    override val username: String,
    override val email: String = null,
    override val password: String = null,
    override val admin: Boolean = false,
    override val userType: UserType,
    right: RightLevel,
    defaultRight: Option[RightLevel],
    override val defaultTenant: Option[String] = None,
    tenantAdmin: Boolean = false
) extends UserTrait

case class UserWithSingleLevelRight(
    override val username: String,
    override val email: String = null,
    override val password: String = null,
    override val admin: Boolean = false,
    override val userType: UserType,
    right: RightLevel,
    override val defaultTenant: Option[String] = None
) extends UserTrait

case class UserWithRights(
    override val username: String,
    override val email: String,
    override val password: String = null,
    override val admin: Boolean = false,
    override val userType: UserType,
    rights: Rights = Rights(tenants = Map()),
    override val defaultTenant: Option[String] = None,
    override val legacy: Boolean = false
) extends UserTrait {
  def hasRight(requiredRights: RequiredRights): Boolean = {
    (!(requiredRights.admin) || admin) &&
    requiredRights.requiredRightsByTenant.forall {
      (tenant, requiredTenantRights) =>
        hasRightForTenant(tenant, requiredTenantRights)
    }
  }

  def hasRightForTenant(
      tenant: String,
      requiredTenantRights: RequiredTenantRights
  ): Boolean = {
    admin || rights.tenants
      .get(tenant)
      .exists(tr => {
        RightLevel
          .superiorOrEqualLevels(requiredTenantRights.level)
          .contains(tr.level) &&
        requiredTenantRights.projects.forall { case (proj, right) =>
          hasRightForProject(
            tenant = tenant,
            project = proj,
            rightLevel = right.level
          )
        } &&
        requiredTenantRights.keys.forall { case (key, right) =>
          hasRightForKey(
            tenant = tenant,
            key = key,
            rightLevel = right.level
          )
        } &&
        requiredTenantRights.webhooks.forall { case (webhook, right) =>
          hasRightForWebhook(
            tenant = tenant,
            webhook = webhook,
            rightLevel = right.level
          )
        }
      })
  }

  def hasRightForProject(
      project: String,
      tenant: String,
      rightLevel: ProjectRightLevel
  ): Boolean = {
    admin || rights.tenants
      .get(tenant)
      .exists(tenantRight =>
        tenantRight.level == RightLevel.Admin || tenantRight.projects
          .get(project)
          .exists(r => superiorOrEqualLevels(rightLevel).contains(r.level))
      )
  }

  def hasRightForKey(
      key: String,
      tenant: String,
      rightLevel: RightLevel
  ): Boolean = {
    admin || rights.tenants
      .get(tenant)
      .exists(tenantRight =>
        tenantRight.level == RightLevel.Admin || tenantRight.keys
          .get(key)
          .exists(r =>
            RightLevel.superiorOrEqualLevels(rightLevel).contains(r.level)
          )
      )
  }

  def hasAdminRightForProject(project: String, tenant: String): Boolean = {
    admin || rights.tenants
      .get(tenant)
      .exists(tenantRight =>
        tenantRight.level == RightLevel.Admin || tenantRight.projects
          .get(project)
          .exists(r => r.level == ProjectRightLevel.Admin)
      )
  }

  def hasAdminRightForKey(key: String, tenant: String): Boolean = {
    admin || rights.tenants
      .get(tenant)
      .exists(tenantRight =>
        tenantRight.level == RightLevel.Admin || tenantRight.keys
          .get(key)
          .exists(r => r.level == RightLevel.Admin)
      )
  }

  def hasAdminRightForWebhook(webhook: String, tenant: String): Boolean = {
    admin || rights.tenants
      .get(tenant)
      .exists(tenantRight =>
        tenantRight.level == RightLevel.Admin || tenantRight.webhooks
          .get(webhook)
          .exists(r => r.level == RightLevel.Admin)
      )
  }

  def hasRightForWebhook(
      webhook: String,
      tenant: String,
      rightLevel: RightLevel
  ): Boolean = {
    admin || rights.tenants
      .get(tenant)
      .exists(tenantRight =>
        tenantRight.level == RightLevel.Admin || tenantRight.webhooks
          .get(webhook)
          .exists(r =>
            RightLevel.superiorOrEqualLevels(rightLevel).contains(r.level)
          )
      )
  }

  def hasAdminRightForTenant(tenant: String): Boolean = {
    admin || rights.tenants
      .get(tenant)
      .exists(tenantRight => tenantRight.level == RightLevel.Admin)
  }
}

case class UserWithTenantRights(
    override val username: String,
    override val email: String,
    override val password: String = null,
    override val admin: Boolean = false,
    override val userType: UserType,
    override val defaultTenant: Option[String] = None,
    tenantRights: Map[String, RightLevel] = Map()
) extends UserTrait

case class UserWithCompleteRightForOneTenant(
    override val username: String,
    override val email: String,
    override val password: String = null,
    override val userType: UserType,
    override val admin: Boolean = false,
    override val defaultTenant: Option[String] = None,
    tenantRight: Option[TenantRight]
) extends UserTrait {
  def hasRightForProject(project: String, level: ProjectRightLevel): Boolean = {
    val maybeTenantAdmin = tenantRight.map(t => t.level == RightLevel.Admin)

    admin || maybeTenantAdmin
      .filter(_ == true)
      .getOrElse(
        tenantRight
          .flatMap(tr =>
            tr.projects.get(project).map(_.level).orElse(tr.defaultProjectRight)
          )
          .exists(r =>
            ProjectRightLevel.superiorOrEqualLevels(level).contains(r)
          )
      )
  }

  def hasRightForTenant(level: RightLevel): Boolean = {
    admin || tenantRight.exists(t =>
      RightLevel.superiorOrEqualLevels(level).contains(t.level)
    )
  }

  def rightLevelForProject(project: String): Option[ProjectRightLevel] = {
    val isTenantAdmin =
      admin || tenantRight.exists(t => t.level == RightLevel.Admin)

    if (isTenantAdmin) {
      Some(Admin)
    } else {
      tenantRight
        .flatMap(tr =>
          tr.projects.get(project).map(_.level).orElse(tr.defaultProjectRight)
        )
    }
  }
}

sealed trait BaseAtomicRight
case class GeneralAtomicRight(level: RightLevel)
case class ProjectAtomicRight(level: ProjectRightLevel)

case class RequiredRights(
    admin: Boolean,
    requiredRightsByTenant: Map[String, RequiredTenantRights] = Map()
)

case class RequiredTenantRights(
    level: RightLevel,
    projects: Map[String, ProjectAtomicRight] = Map(),
    keys: Map[String, GeneralAtomicRight] = Map(),
    webhooks: Map[String, GeneralAtomicRight] = Map()
)

case class TenantRight(
    level: RightLevel,
    projects: Map[String, ProjectAtomicRight] = Map(),
    keys: Map[String, GeneralAtomicRight] = Map(),
    webhooks: Map[String, GeneralAtomicRight] = Map(),
    defaultProjectRight: Option[ProjectRightLevel] = None,
    defaultKeyRight: Option[RightLevel] = None,
    defaultWebhookRight: Option[RightLevel] = None
)

case class Rights(tenants: Map[String, TenantRight]) {
  def withTenantRight(name: String, level: RightLevel): Rights = {
    val newTenants =
      if (tenants.contains(name))
        tenants + (name -> tenants(name).copy(level = level))
      else tenants + (name -> TenantRight(level = level))
    copy(tenants = newTenants)
  }

  def withProjectRight(
      name: String,
      tenant: String,
      level: ProjectRightLevel
  ): Rights = {
    val newTenants =
      if (tenants.contains(tenant)) tenants
      else tenants + (tenant -> TenantRight(level = RightLevel.Read))
    val newProjects =
      newTenants(tenant).projects + (name -> ProjectAtomicRight(level = level))
    copy(tenants =
      newTenants + (tenant -> newTenants(tenant).copy(projects = newProjects))
    )
  }

  def withKeyRight(name: String, tenant: String, level: RightLevel): Rights = {
    val newTenants =
      if (tenants.contains(tenant)) tenants
      else tenants + (tenant -> TenantRight(level = RightLevel.Read))
    val newKeys =
      newTenants(tenant).keys + (name -> GeneralAtomicRight(level = level))
    copy(tenants =
      newTenants + (tenant -> newTenants(tenant).copy(keys = newKeys))
    )
  }

  def removeTenantsRights(tenant: Set[String]): Rights = {
    copy(tenants = tenants.filter(t => !tenant.contains(t._1)))
  }

  def removeProjectRights(tenant: String, project: Set[String]): Rights = {
    tenants
      .get(tenant)
      .map(t =>
        t.copy(projects = t.projects.filter { case (p, _) =>
          !project.contains(p)
        })
      )
      .fold(this)(e => copy(tenants = tenants + (tenant -> e)))
  }

  def removeKeyRights(tenant: String, keys: Set[String]): Rights = {
    tenants
      .get(tenant)
      .map(t =>
        t.copy(keys = t.keys.filter { case (k, _) =>
          !keys.contains(k)
        })
      )
      .fold(this)(e => copy(tenants = tenants + (tenant -> e)))
  }

  def removeWebhookRights(tenant: String, webhooks: Set[String]): Rights = {
    tenants
      .get(tenant)
      .map(t =>
        t.copy(webhooks = t.webhooks.filter { case (w, _) =>
          !webhooks.contains(w)
        })
      )
      .fold(this)(e => copy(tenants = tenants + (tenant -> e)))
  }
}

object Rights {
  case class RightDiff(
      diff: Map[String, TenantRightDiff] = Map()
  )

  sealed trait TenantRightDiff

  case object DeleteTenantRights extends TenantRightDiff
  case class UpsertTenantRights(
      addedTenantRight: Option[UnscopedFlattenTenantRight] = Option.empty,
      addedProjectRights: Set[UnscopedFlattenProjectRight] = Set(),
      removedProjectRights: Set[String] = Set(),
      addedKeyRights: Set[UnscopedFlattenKeyRight] = Set(),
      removedKeyRights: Set[String] = Set(),
      addedWebhookRights: Set[UnscopedFlattenWebhookRight] = Set(),
      removedWebhookRights: Set[String] = Set()
  ) extends TenantRightDiff {
    require(
      addedTenantRight.isDefined || Seq(
        addedKeyRights,
        addedWebhookRights,
        addedProjectRights,
        removedProjectRights,
        removedWebhookRights,
        removedKeyRights
      ).exists(_.isEmpty),
      "Tenant right update request doesn't contain any rights"
    )
  }

  sealed trait FlattenRight

  case class UnscopedFlattenTenantRight(
      level: RightLevel,
      defaultProjectRight: Option[ProjectRightLevel] = None,
      defaultKeyRight: Option[RightLevel] = None,
      defaultWebhookRight: Option[RightLevel] = None
  ) extends FlattenRight

  case class FlattenTenantRight(
      name: String,
      level: RightLevel,
      defaultProjectRight: Option[ProjectRightLevel] = None,
      defaultKeyRight: Option[RightLevel] = None,
      defaultWebhookRight: Option[RightLevel] = None
  ) extends FlattenRight

  case class FlattenProjectRight(
      name: String,
      tenant: String,
      level: ProjectRightLevel
  ) extends FlattenRight
  case class UnscopedFlattenProjectRight(name: String, level: ProjectRightLevel)
      extends FlattenRight

  case class FlattenKeyRight(name: String, tenant: String, level: RightLevel)
      extends FlattenRight
  case class UnscopedFlattenKeyRight(name: String, level: RightLevel)
      extends FlattenRight

  case class FlattenWebhookRight(
      name: String,
      tenant: String,
      level: RightLevel
  ) extends FlattenRight
  case class UnscopedFlattenWebhookRight(name: String, level: RightLevel)
      extends FlattenRight

  val EMPTY: Rights = Rights(tenants = Map())

  def compare(
      base: Option[TenantRight],
      modified: Option[TenantRight]
  ): Option[TenantRightDiff] = {
    def flattenProjects(
        tenantRight: TenantRight
    ): Set[UnscopedFlattenProjectRight] = {
      tenantRight.projects.map {
        case (projectName, ProjectAtomicRight(level)) =>
          UnscopedFlattenProjectRight(name = projectName, level = level)
      }.toSet
    }

    def flattenKeys(tenantRight: TenantRight): Set[UnscopedFlattenKeyRight] = {
      tenantRight.keys.map { case (keyName, GeneralAtomicRight(level)) =>
        UnscopedFlattenKeyRight(name = keyName, level = level)
      }.toSet
    }

    def flattenWebhooks(
        tenantRight: TenantRight
    ): Set[UnscopedFlattenWebhookRight] = {
      tenantRight.webhooks.map {
        case (webhookName, GeneralAtomicRight(level)) =>
          UnscopedFlattenWebhookRight(name = webhookName, level = level)
      }.toSet
    }

    (base, modified) match {
      case (None, None)                 => None
      case (Some(existingRights), None) =>
        Some(
          DeleteTenantRights
        )
      case (None, Some(newRights)) =>
        Some(
          UpsertTenantRights(
            addedTenantRight = Some(
              UnscopedFlattenTenantRight(
                level = newRights.level,
                defaultProjectRight = newRights.defaultProjectRight,
                defaultKeyRight = newRights.defaultKeyRight,
                defaultWebhookRight = newRights.defaultWebhookRight
              )
            ),
            addedProjectRights = flattenProjects(newRights),
            addedKeyRights = flattenKeys(newRights),
            addedWebhookRights = flattenWebhooks(newRights)
          )
        )
      case (
            Some(
              oldR @ TenantRight(
                oldLevel,
                _,
                _,
                _,
                oldDefaultProjectRight,
                oldDefaultKeyRight,
                oldDefaultWebhookRight
              )
            ),
            Some(
              newR @ TenantRight(
                newLevel,
                _,
                _,
                _,
                newDefaultProjectRight,
                newDefaultKeyRight,
                newDefaultWebhookRight
              )
            )
          )
          if oldLevel != newLevel || oldDefaultProjectRight != newDefaultProjectRight || oldDefaultKeyRight != newDefaultKeyRight || oldDefaultWebhookRight != newDefaultWebhookRight => {
        Some(
          UpsertTenantRights(
            addedTenantRight = Some(
              UnscopedFlattenTenantRight(
                level = newLevel,
                defaultProjectRight = newR.defaultProjectRight,
                defaultKeyRight = newR.defaultKeyRight,
                defaultWebhookRight = newR.defaultWebhookRight
              )
            ),
            addedProjectRights =
              flattenProjects(newR).diff(flattenProjects(oldR)),
            removedProjectRights =
              flattenProjects(oldR).diff(flattenProjects(newR)).map(_.name),
            addedKeyRights = flattenKeys(newR).diff(flattenKeys(oldR)),
            removedKeyRights =
              flattenKeys(oldR).diff(flattenKeys(newR)).map(_.name),
            addedWebhookRights =
              flattenWebhooks(newR).diff(flattenWebhooks(oldR)),
            removedWebhookRights =
              flattenWebhooks(oldR).diff(flattenWebhooks(newR)).map(_.name)
          )
        )
      }
      case (Some(oldR), Some(newR)) => {
        Some(
          UpsertTenantRights(
            addedProjectRights =
              flattenProjects(newR).diff(flattenProjects(oldR)),
            removedProjectRights =
              flattenProjects(oldR).diff(flattenProjects(newR)).map(_.name),
            addedKeyRights = flattenKeys(newR).diff(flattenKeys(oldR)),
            removedKeyRights =
              flattenKeys(oldR).diff(flattenKeys(newR)).map(_.name),
            addedWebhookRights =
              flattenWebhooks(newR).diff(flattenWebhooks(oldR)),
            removedWebhookRights =
              flattenWebhooks(oldR).diff(flattenWebhooks(newR)).map(_.name)
          )
        )
      }
    }
  }

  def compare(base: Rights, modified: Rights): RightDiff = {

    val allTenants = base.tenants.keySet.concat(modified.tenants.keySet)

    val rightDiffByTenant = allTenants
      .flatMap(tenant => {
        val maybeBaseRight = base.tenants.get(tenant)
        val maybeModifiedRight = modified.tenants.get(tenant)
        Rights
          .compare(maybeBaseRight, maybeModifiedRight)
          .toSeq
          .map(r => (tenant, r))
      })
      .toMap
    RightDiff(rightDiffByTenant)
  }
}

object User {
  val PASSWORD_REGEXP: Regex =
    "^[a-zA-Z0-9_\\-+=;: ,?!$%'\"^@*<>&|#/\\\\()\\[\\]{}]{8,50}$".r

  implicit val rightLevelRead: Reads[RightLevel] = RightLevel.rightLevelReads
  implicit val rightLevelWrite: Writes[RightLevel] = RightLevel.rightLevelWrites
  implicit val projectRightLevelRead: Reads[ProjectRightLevel] =
    ProjectRightLevel.projectRightLevelReads
  implicit val projectRightLevelWrite: Writes[ProjectRightLevel] =
    ProjectRightLevel.projectRightLevelWrites

  implicit val rightReads: Reads[GeneralAtomicRight] =
    Json.reads[GeneralAtomicRight]
  implicit val rightWrites: Writes[GeneralAtomicRight] =
    Json.writes[GeneralAtomicRight]
  implicit val projectRightReads: Reads[ProjectAtomicRight] =
    Json.reads[ProjectAtomicRight]
  implicit val projectRightWrites: Writes[ProjectAtomicRight] =
    Json.writes[ProjectAtomicRight]

  implicit val tenantRightReads: Reads[TenantRight] = (
    (__ \ "level").read[RightLevel](RightLevel.rightLevelReads) and
      (__ \ "projects").readWithDefault[Map[String, ProjectAtomicRight]](
        Map()
      ) and
      (__ \ "keys").readWithDefault[Map[String, GeneralAtomicRight]](Map()) and
      (__ \ "webhooks").readWithDefault[Map[String, GeneralAtomicRight]](
        Map()
      ) and
      (__ \ "defaultProjectRight").readNullable[ProjectRightLevel](
        ProjectRightLevel.projectRightLevelReads
      ) and
      (__ \ "defaultKeyRight").readNullable[RightLevel](
        RightLevel.rightLevelReads
      ) and
      (__ \ "defaultWebhookRight").readNullable[RightLevel](
        RightLevel.rightLevelReads
      )
  )(TenantRight.apply _)

  implicit val rightsReads: Reads[Rights] =
    (__ \ "tenants")
      .readWithDefault[Map[String, TenantRight]](Map())
      .map(Rights.apply)

  implicit val tenantRightWrite: Writes[TenantRight] = { tenantRight =>
    Json.obj(
      "level" -> tenantRight.level.toString,
      "projects" -> tenantRight.projects,
      "keys" -> tenantRight.keys,
      "webhooks" -> tenantRight.webhooks,
      "defaultProjectRight" -> tenantRight.defaultProjectRight,
      "defaultKeyRight" -> tenantRight.defaultKeyRight,
      "defaultWebhookRight" -> tenantRight.defaultWebhookRight
    )
  }

  implicit val rightWrite: Writes[Rights] = Json.writes[Rights]

  implicit val userReads: Reads[User] = (
    (__ \ "username")
      .read[String]
      .filter(name => USERNAME_REGEXP.pattern.matcher(name).matches()) and
      (__ \ "email")
        .read[String]
        .filter(name => USERNAME_REGEXP.pattern.matcher(name).matches()) and
      (__ \ "password")
        .read[String]
        .filter(name => PASSWORD_REGEXP.pattern.matcher(name).matches()) and
      (__ \ "admin").readWithDefault[Boolean](false) and
      (__ \ "defaultTenant").readNullable[String]
  )((username, email, password, admin, defaultTenant) =>
    User(
      username = username,
      email = email,
      password = password,
      admin = admin,
      defaultTenant = defaultTenant
    )
  )

  implicit val userWithRightsReads: Reads[UserWithRights] = (
    userReads and
      (__ \ "rights").readWithDefault[Rights](Rights(tenants = Map()))
  )((user, rights) => user.withRights(rights))

  implicit val userWrites: Writes[User] = { user =>
    Json.obj(
      "username" -> user.username,
      "email" -> user.email,
      "userType" -> user.userType.toString,
      "admin" -> user.admin,
      "defaultTenant" -> user.defaultTenant
    )
  }

  implicit val userWithTenantRightWrites: Writes[UserWithSingleLevelRight] = {
    user =>
      {
        Json
          .obj(
            "username" -> user.username,
            "email" -> user.email,
            "userType" -> user.userType.toString,
            "admin" -> user.admin,
            "defaultTenant" -> user.defaultTenant
          )
          .applyOnWithOpt(Option(user.right)) { (json, right) =>
            json ++ Json.obj(
              "right" -> RightLevel.rightLevelWrites.writes(right)
            )
          }
      }
  }

  implicit val projectScopedUserWrites: Writes[ProjectScopedUser] = { user =>
    {
      Json
        .obj(
          "username" -> user.username,
          "email" -> user.email,
          "userType" -> user.userType.toString,
          "admin" -> user.admin,
          "defaultTenant" -> user.defaultTenant,
          "tenantAdmin" -> user.tenantAdmin,
          "defaultTenant" -> user.defaultTenant,
          "defaultRight" -> user.defaultRight
        )
        .applyOnWithOpt(Option(user.right)) { (json, right) =>
          json ++ Json.obj(
            "right" -> ProjectRightLevel.projectRightLevelWrites.writes(right)
          )
        }
    }
  }

  implicit val singleItemScopedUserWrites: Writes[SingleItemScopedUser] = {
    user =>
      {
        Json
          .obj(
            "username" -> user.username,
            "email" -> user.email,
            "userType" -> user.userType.toString,
            "admin" -> user.admin,
            "defaultTenant" -> user.defaultTenant,
            "tenantAdmin" -> user.tenantAdmin,
            "defaultRight" -> user.defaultRight
          )
          .applyOnWithOpt(Option(user.right)) { (json, right) =>
            json ++ Json.obj(
              "right" -> RightLevel.rightLevelWrites.writes(right)
            )
          }
      }
  }

  implicit val userRightsWrites: Writes[UserWithRights] = { user =>
    Json.obj(
      "username" -> user.username,
      "admin" -> user.admin,
      "rights" -> user.rights,
      "email" -> user.email,
      "userType" -> user.userType,
      "defaultTenant" -> user.defaultTenant
    )
  }

  implicit val userTypeReads: Reads[UserType] = { json =>
    (json.asOpt[String].map(_.toUpperCase) flatMap {
      case "OTOROSHI" => OTOROSHI.some
      case "INTERNAL" => INTERNAL.some
      case "OIDC"     => OIDC.some
      case _          => None
    }).map(JsSuccess(_)).getOrElse(JsError(s"Unknown user type ${json}"))
  }

  implicit val userTypeWrites: Writes[UserType] = userType =>
    JsString(userType.toString)

  implicit val userWithTenantRightsWrite: Writes[UserWithTenantRights] =
    Json.writes[UserWithTenantRights]

  implicit val userInvitationReads: Reads[UserInvitation] =
    ((__ \ "email").read[String] and
      (__ \ "rights").readWithDefault[Rights](Rights.EMPTY) and
      (__ \ "admin").readWithDefault[Boolean](false))((email, rights, admin) =>
      UserInvitation(email = email, rights = rights, admin = admin)
    )

  implicit val userRightsUpdateReads: Reads[UserRightsUpdateRequest] =
    ((__ \ "rights").read[Rights] and
      (__ \ "admin").readNullable[Boolean])((rights, admin) =>
      UserRightsUpdateRequest(rights = rights, admin = admin)
    )

  implicit val userUpdateReads: Reads[UserInformationUpdateRequest] =
    ((__ \ "username")
      .read[String]
      .filter(name => USERNAME_REGEXP.pattern.matcher(name).matches()) and
      (__ \ "email")
        .read[String]
        .filter(Constraints.emailAddress.apply(_) == Valid) and
      (__ \ "defaultTenant").readNullable[String] and
      (__ \ "password").read[String])((name, email, defaultTenant, password) =>
      UserInformationUpdateRequest(
        name = name,
        email = email,
        password = password,
        defaultTenant = defaultTenant
      )
    )

  implicit val userPasswordUpdateReads: Reads[UserPasswordUpdateRequest] =
    ((__ \ "password")
      .read[String]
      .filter(name => PASSWORD_REGEXP.pattern.matcher(name).matches()) and
      (__ \ "oldPassword").read[String])((password, oldPassword) =>
      UserPasswordUpdateRequest(oldPassword = oldPassword, password = password)
    )
}
