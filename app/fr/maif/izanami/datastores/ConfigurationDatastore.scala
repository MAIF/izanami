package fr.maif.izanami.datastores

import fr.maif.izanami.datastores.ConfigurationDatastore.parseDbMailer
import fr.maif.izanami.datastores.ConfigurationDatastore.parseInvitationMode
import fr.maif.izanami.datastores.configurationImplicits.ConfigurationRow
import fr.maif.izanami.datastores.configurationImplicits.MailerConfigurationRow
import fr.maif.izanami.env.Env
import fr.maif.izanami.env.pgimplicits.EnhancedRow
import fr.maif.izanami.errors.ConfigurationReadError
import fr.maif.izanami.errors.InternalServerError
import fr.maif.izanami.errors.IzanamiError
import fr.maif.izanami.mail.MailerTypes.MailerType
import fr.maif.izanami.mail._
import fr.maif.izanami.models.FullIzanamiConfiguration
import fr.maif.izanami.models.InvitationMode
import fr.maif.izanami.models.InvitationMode.InvitationMode
import fr.maif.izanami.models.IzanamiConfiguration
import fr.maif.izanami.models.IzanamiConfiguration.SMTPConfigurationReads
import fr.maif.izanami.models.IzanamiConfiguration.SMTPConfigurationWrites
import fr.maif.izanami.models.IzanamiConfiguration.mailGunConfigurationReads
import fr.maif.izanami.models.IzanamiConfiguration.mailJetConfigurationReads
import fr.maif.izanami.models.OIDCConfiguration
import fr.maif.izanami.utils.Datastore
import io.otoroshi.wasm4s.scaladsl.WasmoSettings
import io.vertx.sqlclient.Row
import play.api.libs.json.Json

import java.time.ZoneOffset
import java.util.UUID
import scala.concurrent.Future

class ConfigurationDatastore(val env: Env) extends Datastore {

  def readId(): Future[UUID] = {
    env.postgresql.queryOne(s"""SELECT izanami_id FROM izanami.configuration"""){r => r.optUUID("izanami_id")}
      .map(_.get)

  }
  def readWasmConfiguration(): Option[WasmoSettings] = {
    for(
      url <- env.configuration.getOptional[String]("app.wasmo.url");
      clientId <- env.configuration.getOptional[String]("app.wasmo.client-id");
      clientSecret <- env.configuration.getOptional[String]("app.wasmo.client-secret")
    ) yield WasmoSettings(url, clientId, clientSecret = clientSecret)
  }

  def readOIDCConfiguration(): Option[OIDCConfiguration] = {
    for(
      clientId <- env.configuration.getOptional[String]("app.openid.client-id");
      clientSecret <- env.configuration.getOptional[String]("app.openid.client-secret");
      authorizeUrl <- env.configuration.getOptional[String]("app.openid.authorize-url");
      tokenUrl <- env.configuration.getOptional[String]("app.openid.token-url");
      redirectUrl <- env.configuration.getOptional[String]("app.openid.redirect-url");
      usernameField <- env.configuration.getOptional[String]("app.openid.username-field");
      emailField <- env.configuration.getOptional[String]("app.openid.email-field");
      scopes <- env.configuration.getOptional[String]("app.openid.scopes")
    ) yield OIDCConfiguration(clientId=clientId,
      clientSecret=clientSecret,
      authorizeUrl=authorizeUrl,
      tokenUrl=tokenUrl,
      redirectUrl=redirectUrl,
      usernameField = usernameField,
      emailField = emailField,
      scopes = scopes.split(" ").toSet
    )
  }

  def readConfiguration(): Future[Either[IzanamiError, IzanamiConfiguration]] = {
    env.postgresql
      .queryOne("SELECT mailer, invitation_mode, origin_email, anonymous_reporting, anonymous_reporting_date from izanami.configuration") { row =>
        {
          row.optConfiguration()
        }
      }
      .map(o => {
        o.toRight(ConfigurationReadError())
      })
  }

  def readFullConfiguration(): Future[Either[IzanamiError, FullIzanamiConfiguration]] = {
    env.postgresql
      .queryOne(s"""
           |SELECT c.mailer, c.invitation_mode, c.origin_email, c.anonymous_reporting, m.configuration, m.name
           |FROM izanami.configuration c, izanami.mailers m
           |WHERE m.name = c.mailer
           |""".stripMargin) { row =>
        {
          for (
            mailProviderConfig <- row.optMailerConfiguration();
            invitationMode     <- row.optString("invitation_mode");
            anonymousReporting <- row.optBoolean("anonymous_reporting")
          )
            yield FullIzanamiConfiguration(
              invitationMode = parseInvitationMode(invitationMode),
              mailConfiguration = mailProviderConfig,
              originEmail = row.optString("origin_email"),
              anonymousReporting=anonymousReporting,
              anonymousReportingLastAsked=row.optOffsetDatetime("anonymous_reporting_date").map(_.toInstant)
            )
        }
      }
      .map(o => {
        o.toRight(ConfigurationReadError())
      })
      .recover {
        case _ => {
          Left(InternalServerError())
        }
      }
  }

  def updateConfiguration(newConfig: IzanamiConfiguration): Future[Option[IzanamiConfiguration]] = {
    env.postgresql.queryOne(
      s"""
         |UPDATE izanami.configuration
         |SET mailer=$$1, invitation_mode=$$2, origin_email=$$3, anonymous_reporting=$$4, anonymous_reporting_date=$$5
         |RETURNING *
         |""".stripMargin,
      List(newConfig.mailer.toString.toUpperCase, newConfig.invitationMode.toString.toUpperCase, newConfig.originEmail.orNull, java.lang.Boolean.valueOf(newConfig.anonymousReporting), newConfig.anonymousReportingLastAsked.map(_.atOffset(ZoneOffset.UTC)).orNull )
    ) { row =>
      row.optConfiguration()
    }
  }

  def readMailerConfiguration(mailerType: MailerType): Future[Either[IzanamiError, MailProviderConfiguration]] = {
    env.postgresql
      .queryOne(
        s"""SELECT name, configuration FROM izanami.mailers WHERE name=$$1""",
        List(mailerType.toString.toUpperCase)
      ) { row =>
        row.optMailerConfiguration()
      }
      .map(o => {
        o.toRight(ConfigurationReadError())
      })
  }

  def updateMailerConfiguration(
      mailProviderConfiguration: MailProviderConfiguration
  ): Future[Either[IzanamiError, MailProviderConfiguration]] = {
    env.postgresql
      .queryOne(
        s"""
           |UPDATE izanami.mailers
           |SET configuration=$$1::JSONB
           |WHERE name=$$2
           |RETURNING *
           |""".stripMargin,
        List(
          mailProviderConfiguration match {
            case ConsoleMailProvider                                               => "{}"
            case MailJetMailProvider(configuration) if configuration.url.isDefined =>
              Json
                .obj(
                  "url"    -> configuration.url.get,
                  "apiKey" -> configuration.apiKey,
                  "secret" -> configuration.secret
                )
                .toString
            case MailJetMailProvider(configuration)                                =>
              Json
                .obj(
                  "apiKey" -> configuration.apiKey,
                  "secret" -> configuration.secret
                )
                .toString
            case MailGunMailProvider(configuration) if configuration.url.isDefined =>
              Json
                .obj(
                  "url"         -> configuration.url.get,
                  "apiKey"      -> configuration.apiKey,
                  "region" -> configuration.region
                )
                .toString
            case MailGunMailProvider(configuration)                                =>
              Json
                .obj(
                  "apiKey"      -> configuration.apiKey,
                  "region" -> configuration.region
                )
                .toString
            case SMTPMailProvider(configuration) =>
              Json.toJson(configuration).toString
          },
          mailProviderConfiguration.mailerType.toString.toUpperCase
        )
      ) { row =>
        row.optMailerConfiguration()
      }
      .map(o => {
        o.toRight(ConfigurationReadError())
      })
      .recover {
        case _ => {
          Left(InternalServerError())
        }
      }
  }
}

object ConfigurationDatastore {
  def parseDbMailer(dbMailer: String): MailerType = {
    dbMailer match {
      case "CONSOLE" => MailerTypes.Console
      case "MAILJET" => MailerTypes.MailJet
      case "MAILGUN" => MailerTypes.MailGun
      case "SMTP"    => MailerTypes.SMTP
      case _         => throw new RuntimeException(s"Failed to read Mailer (readed ${dbMailer})")
    }
  }

  def parseInvitationMode(mode: String): InvitationMode = {
    mode match {
      case "MAIL"     => InvitationMode.Mail
      case "RESPONSE" => InvitationMode.Response
      case _          => throw new RuntimeException(s"Failed to read Invitation mode (readed ${mode})")
    }
  }
}

object configurationImplicits {
  implicit class ConfigurationRow(val row: Row) extends AnyVal {
    def optConfiguration(): Option[IzanamiConfiguration] = {
      for (
        mailerStr         <- row.optString("mailer");
        invitationModeStr <- row.optString("invitation_mode");
        anonymousReporting <- row.optBoolean("anonymous_reporting")
      )
        yield IzanamiConfiguration(
          parseDbMailer(mailerStr),
          parseInvitationMode(invitationModeStr),
          originEmail = row.optString("origin_email"),
          anonymousReporting=anonymousReporting,
          anonymousReportingLastAsked = row.optOffsetDatetime("anonymous_reporting_date").map(_.toInstant)
        )
    }
  }

  implicit class MailerConfigurationRow(val row: Row) extends AnyVal {
    def optMailerConfiguration(): Option[MailProviderConfiguration] = {
      for (
        name          <- row.optString("name");
        configuration <- row.optJsObject("configuration")
      ) yield parseDbMailer(name) match {
        case MailerTypes.MailJet => {
          MailJetMailProvider(
            configuration
              .asOpt[MailJetConfiguration]
              .getOrElse(MailJetConfiguration(apiKey = null, secret = null, url = None))
          )
        }
        case MailerTypes.MailGun => {
          MailGunMailProvider(
            configuration
              .asOpt[MailGunConfiguration]
              .getOrElse(MailGunConfiguration(apiKey = null, url = None))
          )
        }
        case MailerTypes.SMTP => {
          SMTPMailProvider(
            configuration.asOpt[SMTPConfiguration]
              .getOrElse(SMTPConfiguration(null, None, None, None, false, false, false))
          )
        }
        case MailerTypes.Console => ConsoleMailProvider
      }
    }
  }
}
