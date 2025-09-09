package fr.maif.izanami

import fr.maif.izanami.models.OAuth2Configuration.OAuth2RawMethodConvert
import fr.maif.izanami.models._
import fr.maif.izanami.services.CompleteRights
import play.api.libs.json.{JsError, JsString, JsSuccess, Reads, Writes}
import pureconfig.ConfigReader
import pureconfig.generic.semiauto._

case class IzanamiTypedConfiguration(app: AppConf, play: PlayRoot)

case class AppConf(
    secret: String,
    defaultSecret: String,
    containerized: Boolean,
    experimental: Experimental,
    reporting: Reporting,
    audit: Audit,
    webhooks: Webhooks,
    wasm: Wasm,
    admin: Admin,
    exposition: Exposition,
    openid: Option[OpenId],
    wasmo: Wasmo,
    pg: Pg,
    authentication: Authentication,
    invitations: Invitations,
    sessions: Sessions,
    passwordResetRequests: PasswordResetRequests,
    search: Search,
    feature: Feature,
    housekeeping: Housekeeping
)

case class Experimental(staleTracking: StaleTracking)
case class StaleTracking(enabled: Boolean)
case class Reporting(url: String)
case class Audit(eventsHoursTtl: Int)
case class Webhooks(retry: Retry)
case class Retry(count: Int, intialDelay: Long, maxDelay: Long, multiplier: Int, checkInterval: Int)
case class Wasm(cache: Cache, queue: WasmQueue)
case class Cache(ttl: Long)
case class WasmQueue(buffer: WasmQueueBuffer)
case class WasmQueueBuffer(size: Int)
case class Admin(username: String, password: Option[String])
case class Exposition(url: Option[String], backend: Option[String])
case class OpenId(
    method: String,
    enabled: Boolean,
    clientId: Option[String],
    clientSecret: Option[String],
    authorizeUrl: Option[String],
    tokenUrl: Option[String],
    redirectUrl: Option[String],
    callbackUrl: Option[String], // legacy, here for compat reasons
    scopes: String,
    emailField: String,
    usernameField: String,
    pkce: OpenIdPkce,
    rightByRoles: Option[Map[String, RoleRights]],
    roleClaim: Option[String],
    roleRightMode: Option[RoleRightMode]
)                                                                                             {
  def toIzanamiOAuth2Configuration: Option[OAuth2Configuration] = {
    for (
      clientId     <- clientId;
      clientSecret <- clientSecret;
      authorizeUrl <- authorizeUrl;
      tokenUrl     <- tokenUrl
    ) yield {
      OAuth2Configuration(
        clientId = clientId,
        clientSecret = clientSecret,
        authorizeUrl = authorizeUrl.toString, // TODO propagate URL type
        tokenUrl = tokenUrl.toString,
        callbackUrl = redirectUrl.orElse(callbackUrl).map(_.toString).getOrElse(""),
        emailField = emailField,
        nameField = usernameField,
        scopes = scopes.replace("\"", ""),
        method = OAuth2RawMethodConvert(method),
        enabled = enabled,
        pkce = if (pkce.enabled) {
          Some(PKCEConfig(enabled = pkce.enabled, algorithm = pkce.algorithm.getOrElse("S256")))
        } else {
          Option.empty
        },
        userRightsByRoles = rightByRoles.map(r => r.view.mapValues(v => v.toRights).toMap),
        roleClaim = roleClaim,
        roleRightMode = roleRightMode
      )
    }
  }
}
case class OpenIdPkce(enabled: Boolean, algorithm: Option[String])
case class Wasmo(url: Option[String], clientId: Option[String], clientSecret: Option[String])
case class Pg(
    uri: Option[String],
    poolSize: Int,
    port: Int,
    host: String,
    password: String,
    database: Option[String],
    username: Option[String],
    user: Option[String], // legacy
    connectTimeout: Option[Int],
    idleTimeout: Option[Int],
    maxLifetime: Option[Int],
    logActivity: Option[Boolean],
    pipeliningLimit: Option[Int],
    extensionsSchema: String,
    ssl: Ssl
)
case class Ssl(
    enabled: Boolean,
    mode: String,
    trustedCertsPath: List[String],
    trustedCerts: List[String],
    clientCertsPath: List[String],
    clientCerts: List[String],
    clientCertPath: Option[String],
    clientCert: Option[String],
    trustedCert: Option[String],
    trustedCertPath: Option[String],
    trustAll: Option[Boolean],
    sslHandshakeTimeout: Option[Int]
)
case class Authentication(secret: String, tokenBodySecret: String)
case class Invitations(ttl: Int)
case class Sessions(ttl: Int)
case class PasswordResetRequests(ttl: Int)
case class Search(similarityThreshold: Double)
case class Feature(callRecords: CallRecords, staleHoursDelay: Long, forceLegacy: Boolean)
case class CallRecords(callRegisterIntervalInSeconds: Long, callRetentionTimeInHours: Long)
case class Housekeeping(startDelayInSeconds: Long, intervalInSeconds: Long)
case class RoleRights(admin: Boolean = false, tenants: Map[String, TenantRoleRights] = Map()) {
  def toRights: CompleteRights = {
    val tenantRights = tenants.view
      .mapValues(r =>
        TenantRight(
          projects = r.projects.view.mapValues(v => ProjectAtomicRight(v.toProjectRightLevel)).toMap,
          keys = r.keys.view.mapValues(v => GeneralAtomicRight(v.toRightLevel)).toMap,
          webhooks = r.webhooks.view.mapValues(v => GeneralAtomicRight(v.toRightLevel)).toMap,
          defaultProjectRight = r.defaultProjectRight.toMaybeProjectRightLevel,
          defaultKeyRight = r.defaultKeyRight.toMaybeRightLevel,
          defaultWebhookRight = r.defaultWebhookRight.toMaybeRightLevel,
          level = r.level.toRightLevel
        )
      )
      .toMap

    CompleteRights(admin = admin, tenants = tenantRights)
  }
}
case class TenantRoleRights(
                             level: ConfigNonNullableRightLevel,
                             defaultProjectRight: ConfigProjectRightLevel = None,
                             defaultKeyRight: BaseConfigRightLevel = None,
                             defaultWebhookRight: BaseConfigRightLevel = None,
                             keys: Map[String, ConfigNonNullableRightLevel] = Map(),
                             webhooks: Map[String, ConfigNonNullableRightLevel] = Map(),
                             projects: Map[String, ConfigNonNullableProjectRightLevel] = Map()
)

case class PlayRoot(server: PlayServer)
case class PlayServer(http: PlayHttpConf)
case class PlayHttpConf(port: Long)

sealed trait ConfigProjectRightLevel {
  def toMaybeProjectRightLevel: Option[ProjectRightLevel]
}
sealed trait BaseConfigRightLevel extends ConfigProjectRightLevel {
  def toMaybeRightLevel: Option[RightLevel]
}

sealed trait ConfigNonNullableRightLevel extends ConfigNonNullableProjectRightLevel {
  def toRightLevel: RightLevel
}

sealed trait ConfigNonNullableProjectRightLevel {
  def toProjectRightLevel: ProjectRightLevel
}

case object Read   extends BaseConfigRightLevel with ConfigNonNullableRightLevel           {
  override def toMaybeRightLevel: Option[RightLevel]               = Some(toRightLevel)
  override def toProjectRightLevel: ProjectRightLevel              = ProjectRightLevel.Read
  override def toRightLevel: RightLevel                            = RightLevel.Read
  override def toMaybeProjectRightLevel: Option[ProjectRightLevel] = Some(toProjectRightLevel)
}
case object Write  extends BaseConfigRightLevel with ConfigNonNullableRightLevel           {
  override def toMaybeRightLevel: Option[RightLevel]               = Some(toRightLevel)
  override def toRightLevel: RightLevel                            = RightLevel.Write
  override def toProjectRightLevel: ProjectRightLevel              = ProjectRightLevel.Write
  override def toMaybeProjectRightLevel: Option[ProjectRightLevel] = Some(toProjectRightLevel)
}
case object Admin  extends BaseConfigRightLevel with ConfigNonNullableRightLevel           {
  override def toMaybeRightLevel: Option[RightLevel]               = Some(toRightLevel)
  override def toRightLevel: RightLevel                            = RightLevel.Admin
  override def toProjectRightLevel: ProjectRightLevel              = ProjectRightLevel.Admin
  override def toMaybeProjectRightLevel: Option[ProjectRightLevel] = Some(toProjectRightLevel)
}
case object None   extends BaseConfigRightLevel                                            {
  override def toMaybeRightLevel: Option[RightLevel]               = Option.empty
  override def toMaybeProjectRightLevel: Option[ProjectRightLevel] = Option.empty
}
case object Update extends ConfigProjectRightLevel with ConfigNonNullableProjectRightLevel {
  override def toMaybeProjectRightLevel: Option[ProjectRightLevel] = Some(toProjectRightLevel)
  override def toProjectRightLevel: ProjectRightLevel              = ProjectRightLevel.Update
}

object BaseConfigRightLevel {
  implicit val rightConvert: ConfigReader[BaseConfigRightLevel] = deriveEnumerationReader[BaseConfigRightLevel]
}

object ConfigProjectRightLevel {
  implicit val rightConvert: ConfigReader[ConfigProjectRightLevel] = deriveEnumerationReader[ConfigProjectRightLevel]
}

object ConfigNonNullableRightLevel {
  implicit val rightConvert: ConfigReader[ConfigNonNullableRightLevel] =
    deriveEnumerationReader[ConfigNonNullableRightLevel]
}

object ConfigNonNullableProjectRightLevel {
  implicit val rightConvert: ConfigReader[ConfigNonNullableProjectRightLevel] =
    deriveEnumerationReader[ConfigNonNullableProjectRightLevel]
}

sealed trait RoleRightMode

case object RoleRightMode {
  case object Supervised extends RoleRightMode
  case object Initial    extends RoleRightMode

  def reads: Reads[RoleRightMode] = {
    case JsString("Supervised") => JsSuccess(Supervised)
    case JsString("Initial")    => JsSuccess(Initial)
    case _                      => JsError("Failed to parse role right mode")
  }

  def writes: Writes[RoleRightMode] = {
    case Supervised => JsString("Supervised")
    case Initial    => JsString("Initial")
  }

  implicit val roleRightModeConvert: ConfigReader[RoleRightMode] = deriveEnumerationReader[RoleRightMode]
}
