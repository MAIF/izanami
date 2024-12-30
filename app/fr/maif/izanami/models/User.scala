package fr.maif.izanami.models

import fr.maif.izanami.models.RightLevels.RightLevel
import fr.maif.izanami.models.RightTypes.RightType
import fr.maif.izanami.utils.syntax.implicits.BetterSyntax
import play.api.data.validation.Constraints
import play.api.data.validation.Valid
import play.api.libs.functional.syntax.toFunctionalBuilderOps
import play.api.libs.json._
import play.api.mvc.QueryStringBindable

import scala.collection.mutable.ArrayBuffer
import scala.util.matching.Regex

object RightTypes extends Enumeration {
  type RightType = Value
  val Project, Key = Value
}

case class RightUnit(name: String, rightType: RightType, rightLevel: RightLevel)

object RightLevels extends Enumeration {
  type RightLevel = Value
  val Read, Write, Admin = Value

  def superiorOrEqualLevels(level: RightLevel): Set[Value] =
    level match {
      case RightLevels.Read  => Set(RightLevels.Read, RightLevels.Write, RightLevels.Admin)
      case RightLevels.Write => Set(RightLevels.Write, RightLevels.Admin)
      case RightLevels.Admin => Set(RightLevels.Admin)
    }

  implicit def queryStringBindable(implicit
      stringBinder: QueryStringBindable[String]
  ): QueryStringBindable[RightLevel] =
    new QueryStringBindable[RightLevel] {
      override def bind(key: String, params: Map[String, Seq[String]]): Option[Either[String, RightLevel]] = {
        params.get(key).collect { case Seq(s) =>
          RightLevels.values.find(_.toString.equalsIgnoreCase(s)).toRight("Invalid right specified")
        }
      }
      override def unbind(key: String, value: RightLevel): String = {
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

case class UserInformationUpdateRequest(
    name: String,
    email: String,
    password: String,
    defaultTenant: Option[String]
)

case class  UserPasswordUpdateRequest(
    password: String,
    oldPassword: String
)
sealed trait UserType
case object INTERNAL extends UserType
case object OTOROSHI extends UserType
case object OIDC     extends UserType

sealed trait UserTrait {
  val username: String
  val email: String
  val password: String                           = null
  val admin: Boolean                             = false
  val userType: UserType                         = INTERNAL
  val defaultTenant: Option[String]              = None
  val legacy: Boolean                            = false
  def withRights(rights: Rights): UserWithRights =
    UserWithRights(username, email, password, admin, userType, rights, defaultTenant)
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
  def withSingleLevelRight(level: RightLevel): UserWithSingleLevelRight                                        =
    UserWithSingleLevelRight(username, email, password, admin, userType, level, defaultTenant)
  def withSingleTenantScopedRightLevel(level: RightLevel, tenantAdmin: Boolean = false): UserWithSingleScopedRight =
    UserWithSingleScopedRight(username, email, password, admin, userType, level, defaultTenant, tenantAdmin)
}

case class UserWithSingleScopedRight(
    override val username: String,
    override val email: String = null,
    override val password: String = null,
    override val admin: Boolean = false,
    override val userType: UserType,
    right: RightLevel,
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
  def hasAdminRightForProject(project: String, tenant: String): Boolean = {
    admin || rights.tenants
      .get(tenant)
      .exists(tenantRight =>
        tenantRight.level == RightLevels.Admin || tenantRight.projects
          .get(project)
          .exists(r => r.level == RightLevels.Admin)
      )
  }

  def hasAdminRightForKey(key: String, tenant: String): Boolean = {
    admin || rights.tenants
      .get(tenant)
      .exists(tenantRight =>
        tenantRight.level == RightLevels.Admin || tenantRight.keys.get(key).exists(r => r.level == RightLevels.Admin)
      )
  }

  def hasAdminRightForWebhook(webhook: String, tenant: String): Boolean = {
    admin || rights.tenants
      .get(tenant)
      .exists(tenantRight =>
        tenantRight.level == RightLevels.Admin || tenantRight.webhooks.get(webhook).exists(r => r.level == RightLevels.Admin)
      )
  }

  def hasAdminRightForTenant(tenant: String): Boolean = {
    admin || rights.tenants.get(tenant).exists(tenantRight => tenantRight.level == RightLevels.Admin)
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
  def hasRightForProject(project: String, level: RightLevel): Boolean = {
    val maybeTenantAdmin = tenantRight.map(t => t.level == RightLevels.Admin)

    admin || maybeTenantAdmin
      .filter(_ == true)
      .getOrElse(
        tenantRight
          .flatMap(tr => tr.projects.get(project))
          .exists(r => RightLevels.superiorOrEqualLevels(r.level).contains(level))
      )
  }
}

case class AtomicRight(level: RightLevel)

case class TenantRight(
    level: RightLevel,
    projects: Map[String, AtomicRight] = Map(),
    keys: Map[String, AtomicRight] = Map(),
    webhooks: Map[String, AtomicRight] = Map()
)

case class Rights(tenants: Map[String, TenantRight]) {
  def withTenantRight(name: String, level: RightLevel): Rights = {
    val newTenants =
      if (tenants.contains(name)) tenants + (name -> tenants(name).copy(level = level))
      else tenants + (name                        -> TenantRight(level = level))
    copy(tenants = newTenants)
  }

  def withProjectRight(name: String, tenant: String, level: RightLevel): Rights = {
    val newTenants  =
      if (tenants.contains(tenant)) tenants else tenants + (tenant -> TenantRight(level = RightLevels.Read))
    val newProjects = newTenants(tenant).projects + (name -> AtomicRight(level = level))
    copy(tenants = newTenants + (tenant -> newTenants(tenant).copy(projects = newProjects)))
  }

  def withKeyRight(name: String, tenant: String, level: RightLevel): Rights = {
    val newTenants =
      if (tenants.contains(tenant)) tenants else tenants + (tenant -> TenantRight(level = RightLevels.Read))
    val newKeys    = newTenants(tenant).keys + (name -> AtomicRight(level = level))
    copy(tenants = newTenants + (tenant -> newTenants(tenant).copy(keys = newKeys)))
  }
}

object Rights {
  case class RightDiff(
      addedTenantRights: Seq[FlattenTenantRight] = Seq(),
      removedTenantRights: Seq[FlattenTenantRight] = Seq(),
      addedProjectRights: Map[String, Seq[FlattenProjectRight]] = Map(),
      removedProjectRights: Map[String, Seq[FlattenProjectRight]] = Map(),
      addedKeyRights: Map[String, Seq[FlattenKeyRight]] = Map(),
      removedKeyRights: Map[String, Seq[FlattenKeyRight]] = Map(),
      addedWebhookRights: Map[String, Seq[FlattenWebhookRight]] = Map(),
      removedWebhookRights: Map[String, Seq[FlattenWebhookRight]] = Map()
  )

  case class TenantRightDiff(
      addedTenantRight: Option[FlattenTenantRight] = Option.empty,
      removedTenantRight: Option[FlattenTenantRight] = Option.empty,
      addedProjectRights: Set[FlattenProjectRight] = Set(),
      removedProjectRights: Set[FlattenProjectRight] = Set(),
      addedKeyRights: Set[FlattenKeyRight] = Set(),
      removedKeyRights: Set[FlattenKeyRight] = Set(),
      addedWebhookRights: Set[FlattenWebhookRight] = Set(),
      removedWebhookRights: Set[FlattenWebhookRight] = Set()
  )
  sealed trait FlattenRight
  case class FlattenTenantRight(name: String, level: RightLevel)                  extends FlattenRight
  case class FlattenProjectRight(name: String, tenant: String, level: RightLevel) extends FlattenRight
  case class FlattenKeyRight(name: String, tenant: String, level: RightLevel)     extends FlattenRight
  case class FlattenWebhookRight(name: String, tenant: String, level: RightLevel) extends FlattenRight
  val EMPTY: Rights = Rights(tenants = Map())
  // TODO refactor me
  def compare(tenantName: String, base: Option[TenantRight], modified: Option[TenantRight]): Option[TenantRightDiff] = {
    def flattenProjects(tenantRight: TenantRight): Set[FlattenProjectRight] = {
      tenantRight.projects.map { case (projectName, AtomicRight(level)) =>
        FlattenProjectRight(name = projectName, level = level, tenant = tenantName)
      }.toSet
    }

    def flattenKeys(tenantRight: TenantRight): Set[FlattenKeyRight] = {
      tenantRight.keys.map { case (projectName, AtomicRight(level)) =>
        FlattenKeyRight(name = projectName, level = level, tenant = tenantName)
      }.toSet
    }

    def flattenWebhooks(tenantRight: TenantRight): Set[FlattenWebhookRight] = {
      tenantRight.webhooks.map { case (projectName, AtomicRight(level)) =>
        FlattenWebhookRight(name = projectName, level = level, tenant = tenantName)
      }.toSet
    }

    (base, modified) match {
      case (None, None)                 => None
      case (Some(existingRights), None) =>
        Some(
          TenantRightDiff(
            removedTenantRight = Some(FlattenTenantRight(name = tenantName, level = existingRights.level)),
            removedProjectRights = flattenProjects(existingRights),
            removedKeyRights = flattenKeys(existingRights),
            removedWebhookRights = flattenWebhooks(existingRights)
          )
        )
      case (None, Some(newRights))      =>
        Some(
          TenantRightDiff(
            addedTenantRight = Some(FlattenTenantRight(name = tenantName, level = newRights.level)),
            addedProjectRights = flattenProjects(newRights),
            addedKeyRights = flattenKeys(newRights),
            addedWebhookRights = flattenWebhooks(newRights)
          )
        )
      case (Some(oldR @ TenantRight(oldLevel, _, _, _)), Some(newR @ TenantRight(newLevel, _, _, _)))
          if oldLevel != newLevel => {
        Some(
          TenantRightDiff(
            addedTenantRight = Some(FlattenTenantRight(name = tenantName, level = newLevel)),
            removedTenantRight = Some(FlattenTenantRight(name = tenantName, level = oldLevel)),
            addedProjectRights = flattenProjects(newR).diff(flattenProjects(oldR)),
            removedProjectRights = flattenProjects(oldR).diff(flattenProjects(newR)),
            addedKeyRights = flattenKeys(newR).diff(flattenKeys(oldR)),
            removedKeyRights = flattenKeys(oldR).diff(flattenKeys(newR)),
            addedWebhookRights = flattenWebhooks(newR).diff(flattenWebhooks(oldR)),
            removedWebhookRights = flattenWebhooks(oldR).diff(flattenWebhooks(newR))
          )
        )
      }
      case (Some(oldR), Some(newR))     => {
        Some(
          TenantRightDiff(
            addedProjectRights = flattenProjects(newR).diff(flattenProjects(oldR)),
            removedProjectRights = flattenProjects(oldR).diff(flattenProjects(newR)),
            addedKeyRights = flattenKeys(newR).diff(flattenKeys(oldR)),
            removedKeyRights = flattenKeys(oldR).diff(flattenKeys(newR)),
            addedWebhookRights = flattenWebhooks(newR).diff(flattenWebhooks(oldR)),
            removedWebhookRights = flattenWebhooks(oldR).diff(flattenWebhooks(newR))
          )
        )
      }
    }
  }

  def compare(base: Rights, modified: Rights): RightDiff = {
    def extractFlattenRights(
        rights: Rights
    ): (Set[FlattenTenantRight], Set[FlattenProjectRight], Set[FlattenKeyRight], Set[FlattenWebhookRight]) = {
      val tenants  = ArrayBuffer[FlattenTenantRight]()
      val projects = ArrayBuffer[FlattenProjectRight]()
      val keys     = ArrayBuffer[FlattenKeyRight]()
      val webhooks = ArrayBuffer[FlattenWebhookRight]()
      rights.tenants.foreach {
        case (tenantName, tenantRights) => {
          tenants.addOne(FlattenTenantRight(name = tenantName, level = tenantRights.level))
          projects.addAll(
            tenantRights.projects
              .map { case (projectName, level) =>
                FlattenProjectRight(name = projectName, tenant = tenantName, level = level.level)
              }
          )

          keys.addAll(tenantRights.keys.map { case (keyName, level) =>
            FlattenKeyRight(name = keyName, tenant = tenantName, level = level.level)
          })

          webhooks.addAll(tenantRights.webhooks.map { case (keyName, level) =>
            FlattenWebhookRight(name = keyName, tenant = tenantName, level = level.level)
          })
        }
      }

      (tenants.toSet, projects.toSet, keys.toSet, webhooks.toSet)
    }

    val (baseTenantRights, baseProjectRights, baseKeyRights, basedWebhookRights) = extractFlattenRights(base)
    val (newTenantRights, newProjectRights, newKeyRights, newWebhookRights)      = extractFlattenRights(modified)

    RightDiff(
      addedTenantRights = newTenantRights.diff(baseTenantRights).toSeq,
      removedTenantRights = baseTenantRights.diff(newTenantRights).toSeq,
      addedProjectRights = newProjectRights.diff(baseProjectRights).toSeq.groupBy(_.tenant),
      removedProjectRights = baseProjectRights.diff(newProjectRights).toSeq.groupBy(_.tenant),
      addedKeyRights = newKeyRights.diff(baseKeyRights).toSeq.groupBy(_.tenant),
      removedKeyRights = baseKeyRights.diff(newKeyRights).toSeq.groupBy(_.tenant),
      addedWebhookRights = newWebhookRights.diff(basedWebhookRights).toSeq.groupBy(_.tenant),
      removedWebhookRights = basedWebhookRights.diff(newWebhookRights).toSeq.groupBy(_.tenant)
    )
  }
}

object User {
  val PASSWORD_REGEXP: Regex = "^[a-zA-Z0-9_]{8,100}$".r

  implicit val rightLevelReads: Reads[RightLevel] = { json =>
    json
      .asOpt[String]
      .flatMap(str => RightLevels.values.find(v => str.equalsIgnoreCase(v.toString)))
      .map(JsSuccess(_))
      .getOrElse(JsError(s"${json} is not a correct right level"))
  }

  implicit val rightReads: Reads[AtomicRight]   = Json.reads[AtomicRight]
  implicit val rightWrites: Writes[AtomicRight] = Json.writes[AtomicRight]

  implicit val tenantRightReads: Reads[TenantRight] = (
    (__ \ "level").read[RightLevel] and
      (__ \ "projects").readWithDefault[Map[String, AtomicRight]](Map()) and
      (__ \ "keys").readWithDefault[Map[String, AtomicRight]](Map()) and
      (__ \ "webhooks").readWithDefault[Map[String, AtomicRight]](Map())
  )(TenantRight.apply _)

  implicit val rightsReads: Reads[Rights] =
    (__ \ "tenants").readWithDefault[Map[String, TenantRight]](Map()).map(Rights.apply)

  implicit val tenantRightWrite: Writes[TenantRight] = { tenantRight =>
    Json.obj(
      "level"    -> tenantRight.level.toString,
      "projects" -> tenantRight.projects,
      "keys"     -> tenantRight.keys,
      "webhooks" -> tenantRight.webhooks
    )
  }

  implicit val rightWrite: Writes[Rights] = Json.writes[Rights]

  implicit val userReads: Reads[User] = (
    (__ \ "username").read[String].filter(name => NAME_REGEXP.pattern.matcher(name).matches()) and
      (__ \ "email").read[String].filter(name => NAME_REGEXP.pattern.matcher(name).matches()) and
      (__ \ "password").read[String].filter(name => PASSWORD_REGEXP.pattern.matcher(name).matches()) and
      (__ \ "admin").readWithDefault[Boolean](false) and
      (__ \ "defaultTenant").readNullable[String]
  )((username, email, password, admin, defaultTenant) =>
    User(username = username, email = email, password = password, admin = admin, defaultTenant = defaultTenant)
  )

  implicit val userWithRightsReads: Reads[UserWithRights] = (
    userReads and
      (__ \ "rights").readWithDefault[Rights](Rights(tenants = Map()))
  )((user, rights) => user.withRights(rights))

  implicit val userWrites: Writes[User] = { user =>
    Json.obj(
      "username"      -> user.username,
      "email"         -> user.email,
      "userType"      -> user.userType.toString,
      "admin"         -> user.admin,
      "defaultTenant" -> user.defaultTenant
    )
  }

  implicit val userWithTenantRightWrites: Writes[UserWithSingleLevelRight] = { user =>
    {
      Json
        .obj(
          "username"      -> user.username,
          "email"         -> user.email,
          "userType"      -> user.userType.toString,
          "admin"         -> user.admin,
          "defaultTenant" -> user.defaultTenant
        )
        .applyOnWithOpt(Option(user.right)) { (json, right) => json ++ Json.obj("right" -> right) }
    }
  }

  implicit val userWithSingleProjectRightWrites: Writes[UserWithSingleScopedRight] = { user =>
    {
      Json
        .obj(
          "username"      -> user.username,
          "email"         -> user.email,
          "userType"      -> user.userType.toString,
          "admin"         -> user.admin,
          "defaultTenant" -> user.defaultTenant,
          "tenantAdmin"   -> user.tenantAdmin
        )
        .applyOnWithOpt(Option(user.right)) { (json, right) => json ++ Json.obj("right" -> right) }
    }
  }

  implicit val userRightsWrites: Writes[UserWithRights] = { user =>
    Json.obj(
      "username"      -> user.username,
      "admin"         -> user.admin,
      "rights"        -> user.rights,
      "email"         -> user.email,
      "userType"      -> user.userType,
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

  implicit val userTypeWrites: Writes[UserType] = userType => JsString(userType.toString)

  implicit val userWithTenantRightsWrite: Writes[UserWithTenantRights] = Json.writes[UserWithTenantRights]

  implicit val userInvitationReads: Reads[UserInvitation] = ((__ \ "email").read[String] and
    (__ \ "rights").readWithDefault[Rights](Rights.EMPTY) and
    (__ \ "admin").readWithDefault[Boolean](false))((email, rights, admin) =>
    UserInvitation(email = email, rights = rights, admin = admin)
  )

  implicit val userRightsUpdateReads: Reads[UserRightsUpdateRequest] = ((__ \ "rights").read[Rights] and
    (__ \ "admin").readNullable[Boolean])((rights, admin) => UserRightsUpdateRequest(rights = rights, admin = admin))

  implicit val userUpdateReads: Reads[UserInformationUpdateRequest] =
    ((__ \ "username").read[String].filter(name => NAME_REGEXP.pattern.matcher(name).matches()) and
      (__ \ "email").read[String].filter(Constraints.emailAddress.apply(_) == Valid) and
      (__ \ "defaultTenant").readNullable[String] and
      (__ \ "password").read[String])((name, email, defaultTenant, password) =>
      UserInformationUpdateRequest(name = name, email = email, password = password, defaultTenant = defaultTenant)
    )

  implicit val userPasswordUpdateReads: Reads[UserPasswordUpdateRequest] =
    ((__ \ "password").read[String].filter(name => PASSWORD_REGEXP.pattern.matcher(name).matches()) and
      (__ \ "oldPassword").read[String])((password, oldPassword) =>
      UserPasswordUpdateRequest(oldPassword = oldPassword, password = password)
    )
}
