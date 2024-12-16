package fr.maif.izanami.models

import fr.maif.izanami.mail.MailGunRegions.MailGunRegion
import fr.maif.izanami.mail.MailerTypes.{MailJet, MailerType, SMTP}
import fr.maif.izanami.mail.{MailProviderConfiguration, _}
import fr.maif.izanami.models.InvitationMode.InvitationMode
import fr.maif.izanami.utils.syntax.implicits.BetterSyntax
import play.api.libs.functional.syntax.toFunctionalBuilderOps
import play.api.libs.json.Reads.instantReads
import play.api.libs.json._

import java.time.Instant
import java.time.format.DateTimeFormatter

case class OIDCConfiguration(
    clientId: String,
    clientSecret: String,
    authorizeUrl: String,
    tokenUrl: String,
    redirectUrl: String,
    usernameField: String,
    emailField: String,
    scopes: Set[String]
)

case class PKCEConfig(enabled: Boolean = false, algorithm: String = "S256") {
  def asJson: JsValue = {
    Json.obj(
      "enabled"   -> enabled,
      "algorithm" -> algorithm
    )
  }
}

object PKCEConfig {
  val _fmt: Format[PKCEConfig] = new Format[PKCEConfig] {
    override def reads(json: JsValue): JsResult[PKCEConfig] =
      JsSuccess(
        PKCEConfig(
          enabled = (json \ "enabled").asOpt[Boolean].getOrElse(false),
          algorithm = (json \ "algorithm").asOpt[String].getOrElse("S256")
        )
      )
    override def writes(o: PKCEConfig): JsValue             = o.asJson
  }
}

case class OAuth2Configuration(
    enabled: Boolean,
    method: String = "POST",
    sessionMaxAge: Int = 86400,
    clientId: String,
    clientSecret: String,
    tokenUrl: String,
    authorizeUrl: String,
    scopes: String = "openid profile email name",
    pkce: Option[PKCEConfig] = None,
    nameField: String = "name",
    emailField: String = "email",
    callbackUrl: String,
    defaultOIDCUserRights: Rights = Rights.EMPTY,
)

object OAuth2Configuration {

  val _fmt: Format[OAuth2Configuration] = new Format[OAuth2Configuration] {

    override def writes(o: OAuth2Configuration): JsObject = Json.obj(
      "enabled"          -> o.enabled,
      "method"           -> o.method,
      "sessionMaxAge"    -> o.sessionMaxAge,
      "clientId"         -> o.clientId,
      "clientSecret"     -> o.clientSecret,
      "authorizeUrl"     -> o.authorizeUrl,
      "tokenUrl"         -> o.tokenUrl,
      "scopes"           -> o.scopes,
      "pkce"             -> o.pkce.map(_.asJson).getOrElse(JsNull).as[JsValue],
      "nameField"        -> o.nameField,
      "emailField"       -> o.emailField,
      "callbackUrl"      -> o.callbackUrl,
      "defaultOIDCUserRights" -> User.rightWrite.writes(o.defaultOIDCUserRights)
    )

    override def reads(json: JsValue): JsResult[OAuth2Configuration] = {
      val maybeConfig = for (
        enabled               <- (json \ "enabled").asOpt[Boolean];
        clientId              <- (json \ "clientId").asOpt[String];
        clientSecret          <- (json \ "clientSecret").asOpt[String];
        authorizeUrl          <- (json \ "authorizeUrl").asOpt[String];
        tokenUrl              <- (json \ "tokenUrl").asOpt[String];
        nameField             <- (json \ "nameField").asOpt[String];
        emailField            <- (json \ "emailField").asOpt[String];
        scopes                <- (json \ "scopes").asOpt[String];
        callbackUrl           <- (json \ "callbackUrl")
          .asOpt[String]
      ) yield {
        val name = (json \ "name").asOpt[String].getOrElse("")
        val method = (json \ "method").asOpt[String].getOrElse("BASIC")
        val defaultOIDCUserRights = (json \ "defaultOIDCUserRights").asOpt[Rights](User.rightsReads)
        OAuth2Configuration(
          method = method,
          enabled = enabled,
          sessionMaxAge = (json \ "sessionMaxAge").asOpt[Int].getOrElse(86400),
          clientId = clientId,
          clientSecret = clientSecret,
          authorizeUrl = authorizeUrl,
          tokenUrl = tokenUrl,
          nameField = nameField,
          emailField = emailField,
          scopes = scopes,
          pkce = (json \ "pkce").asOpt[PKCEConfig](PKCEConfig._fmt.reads),
          callbackUrl = callbackUrl,
          defaultOIDCUserRights = defaultOIDCUserRights.getOrElse(Rights.EMPTY)
        )
      }

      maybeConfig.map(json => JsSuccess(json)).getOrElse(JsError("Failed to read OAuth2Configuration"))
    }
  }

}

case class IzanamiConfiguration(
    mailer: MailerType,
    invitationMode: InvitationMode,
    originEmail: Option[String],
    anonymousReporting: Boolean,
    anonymousReportingLastAsked: Option[Instant],
    oidcConfiguration: Option[OAuth2Configuration] = None
)

case class FullIzanamiConfiguration(
    invitationMode: InvitationMode,
    originEmail: Option[String],
    mailConfiguration: MailProviderConfiguration,
    anonymousReporting: Boolean,
    anonymousReportingLastAsked: Option[Instant],
    oidcConfiguration: Option[OAuth2Configuration] = None
)

object InvitationMode extends Enumeration {
  type InvitationMode = Value
  val Mail, Response = Value
}

object IzanamiConfiguration {
  implicit val mailerReads: Reads[MailerType] = { json =>
    json
      .asOpt[String]
      .flatMap(str => MailerTypes.values.find(v => str.equalsIgnoreCase(v.toString)))
      .map(JsSuccess(_))
      .getOrElse(JsError(s"${json} is not a correct right level"))
  }

  implicit val mailGunRegionReads: Reads[MailGunRegion] = { json =>
    json
      .asOpt[String]
      .flatMap(str => MailGunRegions.values.find(v => str.equalsIgnoreCase(v.toString)))
      .map(JsSuccess(_))
      .getOrElse(JsError(s"${json} is not a correct right level"))
  }

  implicit val invitationModeReads: Reads[InvitationMode] = { json =>
    json
      .asOpt[String]
      .flatMap(str => InvitationMode.values.find(v => str.equalsIgnoreCase(v.toString)))
      .map(JsSuccess(_))
      .getOrElse(JsError(s"${json} is not a correct right level"))
  }

  implicit val mailJetConfigurationReads: Reads[MailJetConfiguration] = (
    (__ \ "apiKey").read[String] and
      (__ \ "secret").read[String] and
      (__ \ "url").readNullable[String]
  )((apiKey, secret, url) => MailJetConfiguration(apiKey = apiKey, secret = secret, url = url))

  implicit val mailGunConfigurationReads: Reads[MailGunConfiguration] = (
    (__ \ "apiKey").read[String] and
      (__ \ "url").readNullable[String] and
      (__ \ "region").read[MailGunRegion]
  )((apiKey, url, region) => MailGunConfiguration(apiKey = apiKey, url = url, region = region))

  implicit val SMTPConfigurationReads: Reads[SMTPConfiguration] = (
    (__ \ "host").read[String] and
      (__ \ "port").readNullable[Int] and
      (__ \ "user").readNullable[String] and
      (__ \ "password").readNullable[String] and
      (__ \ "auth").read[Boolean] and
      (__ \ "starttlsEnabled").read[Boolean] and
      (__ \ "smtps").read[Boolean]
  )((host, maybePort, maybeUser, maybePassword, auth, starttls, smtps) =>
    SMTPConfiguration(
      host = host,
      port = maybePort,
      user = maybeUser,
      password = maybePassword,
      auth = auth,
      starttlsEnabled = starttls,
      smtps = smtps
    )
  )

  implicit val SMTPConfigurationWrites: Writes[SMTPConfiguration] = conf => {
    Json
      .obj(
        "host"            -> conf.host,
        "auth"            -> conf.auth,
        "starttlsEnabled" -> conf.starttlsEnabled,
        "smtps"           -> conf.smtps
      )
      .applyOnWithOpt(conf.port) { (json, port) => json ++ Json.obj("port" -> port) }
      .applyOnWithOpt(conf.user) { (json, user) => json ++ Json.obj("user" -> user) }
      .applyOnWithOpt(conf.password) { (json, password) => json ++ Json.obj("password" -> password) }
  }

  implicit val mailProviderConfigurationReads: (MailerType => Reads[MailProviderConfiguration]) = mailerType =>
    json => {
      (mailerType match {
        case MailerTypes.MailGun => {
          json.asOpt[MailGunConfiguration].map(conf => MailGunMailProvider(conf))
        }
        case MailJet             => {
          json.asOpt[MailJetConfiguration].map(conf => MailJetMailProvider(conf))
        }
        case SMTP                => {
          json.asOpt[SMTPConfiguration].map(conf => SMTPMailProvider(conf))
        }
        case MailerTypes.Console => Some(ConsoleMailProvider)
      }).map(JsSuccess(_))
        .getOrElse(JsError("Bad mail configuration format"))
    }

  implicit val mailJetConfigurationWrites: Writes[MailJetConfiguration] = json => {
    Json.obj(
      "url"    -> json.url,
      "apiKey" -> json.apiKey,
      "secret" -> json.secret
    )
  }

  implicit val mailGunConfigurationWrite: Writes[MailGunConfiguration] = json => {
    Json.obj(
      "url"    -> json.url,
      "apiKey" -> json.apiKey,
      "region" -> json.region.toString.toUpperCase
    )
  }

  val mailConfigurationWrites: Writes[MailProviderConfiguration] = {
    case ConsoleMailProvider => {
      Json.obj("mailer" -> MailerTypes.Console.toString)
    }
    case m: MailGunMailProvider => Json.obj("mailer" -> MailerTypes.MailGun.toString) ++ mailGunConfigurationWrite.writes(m.configuration).as[JsObject]
    case m: MailJetMailProvider => Json.obj("mailer" -> MailerTypes.MailJet.toString) ++ mailJetConfigurationWrites.writes(m.configuration).as[JsObject]
    case m: SMTPMailProvider => Json.obj("mailer" -> MailerTypes.SMTP.toString) ++ SMTPConfigurationWrites.writes(m.configuration).as[JsObject]
  }

  implicit val fullConfigurationReads: Reads[FullIzanamiConfiguration] = json => {
    (for (
      mailer <- (json \ "mailerConfiguration" \ "mailer").asOpt[MailerType];
      mailerConfiguration  <- (json \ "mailerConfiguration").asOpt[MailProviderConfiguration](mailProviderConfigurationReads(mailer));
      invitationMode     <- (json \ "invitationMode").asOpt[InvitationMode];
      anonymousReporting <- (json \ "anonymousReporting").asOpt[Boolean]
    ) yield {
      val anonymousReportingLastAsked =
        (json \ "anonymousReportingLastAsked").asOpt[Instant](instantReads(DateTimeFormatter.ISO_OFFSET_DATE_TIME))
      val oidcConfiguration           = (json \ "oidcConfiguration").asOpt[OAuth2Configuration](OAuth2Configuration._fmt.reads)
      val originEmail                 = (json \ "originEmail").asOpt[String]

      (mailer, originEmail) match {
        case (MailerTypes.Console, _) =>
          JsSuccess(
            FullIzanamiConfiguration(
              mailConfiguration = mailerConfiguration,
              invitationMode = invitationMode,
              originEmail = originEmail,
              anonymousReporting = anonymousReporting,
              anonymousReportingLastAsked = anonymousReportingLastAsked,
              oidcConfiguration = oidcConfiguration
            )
          )
        case (_, None)                => JsError("Origin email is missing")
        case (_, maybeEmail)          =>
          JsSuccess(
            FullIzanamiConfiguration(
              mailConfiguration = mailerConfiguration,
              invitationMode = invitationMode,
              originEmail = maybeEmail,
              anonymousReporting = anonymousReporting,
              anonymousReportingLastAsked = anonymousReportingLastAsked,
              oidcConfiguration = oidcConfiguration
            )
          )
      }
    }).getOrElse(JsError("Bad body format"))
  }

  val fullConfigurationWrites: Writes[FullIzanamiConfiguration] = conf => {
    val oidcConfiguration = conf.oidcConfiguration.map(OAuth2Configuration._fmt.writes).getOrElse(JsNull)
    Json.obj(
      "mailerConfiguration"        -> Json.toJson(conf.mailConfiguration)(mailConfigurationWrites),
      "invitationMode"              -> conf.invitationMode.toString,
      "originEmail"                 -> conf.originEmail,
      "anonymousReporting"          -> conf.anonymousReporting,
      "anonymousReportingLastAsked" -> conf.anonymousReportingLastAsked,
      "oidcConfiguration"           -> oidcConfiguration
    )
  }

  implicit val configurationReads: Reads[IzanamiConfiguration] = json => {
    (for (
      mailer             <- (json \ "mailer").asOpt[MailerType];
      invitationMode     <- (json \ "invitationMode").asOpt[InvitationMode];
      anonymousReporting <- (json \ "anonymousReporting").asOpt[Boolean]
    ) yield {
      val anonymousReportingLastAsked =
        (json \ "anonymousReportingLastAsked").asOpt[Instant](instantReads(DateTimeFormatter.ISO_OFFSET_DATE_TIME))
      val oidcConfiguration           = (json \ "oidcConfiguration").asOpt[OAuth2Configuration](OAuth2Configuration._fmt.reads)
      val originEmail                 = (json \ "originEmail").asOpt[String]

      (mailer, originEmail) match {
        case (MailerTypes.Console, _) =>
          JsSuccess(
            IzanamiConfiguration(
              mailer = mailer,
              invitationMode = invitationMode,
              originEmail = originEmail,
              anonymousReporting = anonymousReporting,
              anonymousReportingLastAsked = anonymousReportingLastAsked,
              oidcConfiguration = oidcConfiguration
            )
          )
        case (_, None)                => JsError("Origin email is missing")
        case (_, maybeEmail)          =>
          JsSuccess(
            IzanamiConfiguration(
              mailer = mailer,
              invitationMode = invitationMode,
              originEmail = maybeEmail,
              anonymousReporting = anonymousReporting,
              anonymousReportingLastAsked = anonymousReportingLastAsked,
              oidcConfiguration = oidcConfiguration
            )
          )
      }
    }).getOrElse(JsError("Bad body format"))
  }

  implicit val configurationWrites: Writes[IzanamiConfiguration] = conf => {
    val oidcConfiguration = conf.oidcConfiguration.map(OAuth2Configuration._fmt.writes).getOrElse(JsNull)
    Json.obj(
      "mailer"                      -> conf.mailer.toString,
      "invitationMode"              -> conf.invitationMode.toString,
      "originEmail"                 -> conf.originEmail,
      "anonymousReporting"          -> conf.anonymousReporting,
      "anonymousReportingLastAsked" -> conf.anonymousReportingLastAsked,
      "oidcConfiguration"           -> oidcConfiguration
    )
  }
}
