package fr.maif.izanami.datastores

import fr.maif.izanami.datastores.ConfigurationDatastore.{parseDbMailer, parseInvitationMode}
import fr.maif.izanami.datastores.configurationImplicits.{ConfigurationRow, MailerConfigurationRow}
import fr.maif.izanami.env.Env
import fr.maif.izanami.env.pgimplicits.EnhancedRow
import fr.maif.izanami.errors.{ConfigurationReadError, InternalServerError, IzanamiError}
import fr.maif.izanami.mail.MailerTypes.MailerType
import fr.maif.izanami.mail._
import fr.maif.izanami.models.InvitationMode.InvitationMode
import fr.maif.izanami.models.IzanamiConfiguration.{SMTPConfigurationReads, SMTPConfigurationWrites, mailGunConfigurationReads, mailJetConfigurationReads}
import fr.maif.izanami.models.{FullIzanamiConfiguration, InvitationMode, IzanamiConfiguration, OIDCConfiguration}
import fr.maif.izanami.utils.Datastore
import io.otoroshi.wasm4s.scaladsl.WasmoSettings
import io.vertx.sqlclient.Row
import play.api.libs.json.{JsObject, Json}

import scala.concurrent.Future

class ConfigurationDatastore(val env: Env) extends Datastore {
  def readWasmConfiguration(): Option[WasmoSettings] = {
    for(
      url <- env.configuration.getOptional[String]("app.wasmo.url");
      clientId <- env.configuration.getOptional[String]("app.wasmo.clientId");
      clientSecret <- env.configuration.getOptional[String]("app.wasmo.clientSecret")
    ) yield WasmoSettings(url, clientId, clientSecret = clientSecret)
  }

  def readOIDCConfiguration(): Option[OIDCConfiguration] = {
    for(
      clientId <- env.configuration.getOptional[String]("app.openid.clientId");
      clientSecret <- env.configuration.getOptional[String]("app.openid.clientSecret");
      authorizeUrl <- env.configuration.getOptional[String]("app.openid.authorizeUrl");
      tokenUrl <- env.configuration.getOptional[String]("app.openid.tokenUrl");
      redirectUrl <- env.configuration.getOptional[String]("app.openid.redirectUrl")
    ) yield OIDCConfiguration(clientId=clientId, clientSecret=clientSecret, authorizeUrl=authorizeUrl, tokenUrl=tokenUrl, redirectUrl=redirectUrl)
  }

  def readConfiguration(): Future[Either[IzanamiError, IzanamiConfiguration]] = {
    env.postgresql
      .queryOne("SELECT mailer, invitation_mode, origin_email from izanami.configuration") { row =>
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
           |SELECT c.mailer, c.invitation_mode, c.origin_email, m.configuration, m.name
           |FROM izanami.configuration c, izanami.mailers m
           |WHERE m.name = c.mailer
           |""".stripMargin) { row =>
        {
          for (
            mailProviderConfig <- row.optMailerConfiguration();
            invitationMode     <- row.optString("invitation_mode")
          )
            yield FullIzanamiConfiguration(
              invitationMode = parseInvitationMode(invitationMode),
              mailConfiguration = mailProviderConfig,
              originEmail = row.optString("origin_email")
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
         |SET mailer=$$1, invitation_mode=$$2, origin_email=$$3
         |RETURNING *
         |""".stripMargin,
      List(newConfig.mailer.toString.toUpperCase, newConfig.invitationMode.toString.toUpperCase, newConfig.originEmail.orNull )
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
        invitationModeStr <- row.optString("invitation_mode")
      )
        yield IzanamiConfiguration(
          parseDbMailer(mailerStr),
          parseInvitationMode(invitationModeStr),
          originEmail = row.optString("origin_email")
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
