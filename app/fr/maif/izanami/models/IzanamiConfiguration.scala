package fr.maif.izanami.models

import fr.maif.izanami.mail.MailGunRegions.MailGunRegion
import fr.maif.izanami.mail.MailerTypes.MailJet
import fr.maif.izanami.mail.MailerTypes.MailerType
import fr.maif.izanami.mail.MailerTypes.SMTP
import fr.maif.izanami.mail._
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

case class IzanamiConfiguration(
    mailer: MailerType,
    invitationMode: InvitationMode,
    originEmail: Option[String],
    anonymousReporting: Boolean,
    anonymousReportingLastAsked: Option[Instant]
)

case class FullIzanamiConfiguration(
    invitationMode: InvitationMode,
    originEmail: Option[String],
    mailConfiguration: MailProviderConfiguration,
    anonymousReporting: Boolean,
    anonymousReportingLastAsked: Option[Instant]
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
    SMTPConfiguration(host = host, port = maybePort, user = maybeUser, password = maybePassword ,auth=auth, starttlsEnabled = starttls, smtps=smtps)
  )

  implicit val SMTPConfigurationWrites: Writes[SMTPConfiguration] = conf => {
    Json.obj(
      "host" -> conf.host,
      "auth" -> conf.auth,
      "starttlsEnabled" -> conf.starttlsEnabled,
      "smtps" -> conf.smtps
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
        case SMTP             => {
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

  implicit val configurationReads: Reads[IzanamiConfiguration] = json => {
    (for (
      mailer         <- (json \ "mailer").asOpt[MailerType];
      invitationMode <- (json \ "invitationMode").asOpt[InvitationMode];
      anonymousReporting <- (json \ "anonymousReporting").asOpt[Boolean]
    ) yield {
      val anonymousReportingLastAsked = (json \ "anonymousReportingLastAsked").asOpt[Instant](instantReads(DateTimeFormatter.ISO_OFFSET_DATE_TIME))
      val originEmail = (json \ "originEmail").asOpt[String]
      (mailer, originEmail) match {
        case (MailerTypes.Console, _) =>
          JsSuccess(IzanamiConfiguration(mailer = mailer, invitationMode = invitationMode, originEmail = originEmail, anonymousReporting=anonymousReporting, anonymousReportingLastAsked=anonymousReportingLastAsked))
        case (_, None)                => JsError("Origin email is missing")
        case (_, maybeEmail)          =>
          JsSuccess(IzanamiConfiguration(mailer = mailer, invitationMode = invitationMode, originEmail = maybeEmail, anonymousReporting=anonymousReporting, anonymousReportingLastAsked=anonymousReportingLastAsked))
      }
    }).getOrElse(JsError("Bad body format"))
  }

  implicit val configurationWrites: Writes[IzanamiConfiguration] = conf => {
    Json.obj(
      "mailer"         -> conf.mailer.toString,
      "invitationMode" -> conf.invitationMode.toString,
      "originEmail"    -> conf.originEmail,
      "anonymousReporting" -> conf.anonymousReporting,
      "anonymousReportingLastAsked" -> conf.anonymousReportingLastAsked
    )
  }
}
