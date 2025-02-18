package fr.maif.izanami.datastores

import akka.http.scaladsl.util.FastFuture
import fr.maif.izanami.datastores.ConfigurationDatastore.{parseDbMailer, parseInvitationMode}
import fr.maif.izanami.datastores.configurationImplicits.{ConfigurationRow, MailerConfigurationRow}
import fr.maif.izanami.env.Env
import fr.maif.izanami.env.pgimplicits.EnhancedRow
import fr.maif.izanami.errors.{ConfigurationReadError, InternalServerError, IzanamiError}
import fr.maif.izanami.mail.MailerTypes.MailerType
import fr.maif.izanami.mail._
import fr.maif.izanami.models.InvitationMode.InvitationMode
import fr.maif.izanami.models.IzanamiConfiguration.{SMTPConfigurationReads, SMTPConfigurationWrites, mailGunConfigurationReads, mailJetConfigurationReads}
import fr.maif.izanami.models.{FullIzanamiConfiguration, InvitationMode, IzanamiConfiguration, OAuth2Configuration, Rights, User}
import fr.maif.izanami.utils.Datastore
import fr.maif.izanami.utils.syntax.implicits.BetterJsValue
import io.otoroshi.wasm4s.scaladsl.WasmoSettings
import io.vertx.sqlclient.{Row, SqlConnection}
import play.api.libs.json.{JsNull, JsObject, Json}

import java.time.ZoneOffset
import java.util.UUID
import scala.concurrent.Future

class ConfigurationDatastore(val env: Env) extends Datastore {

  def updateOIDCDefaultRightIfNeeded(rights: Rights): Future[Rights] = {
    case class TenantItemsToDelete(projects: Set[String], keys: Set[String], webhooks: Set[String])

    def deleteNeededItems(tenants: Set[String], tenantItems: Map[String, TenantItemsToDelete]): Rights = {
      val rightWithoutTenants = rights.removeTenantsRights(tenants)
      tenantItems.foldLeft(rightWithoutTenants){
        case (r, (tenant, items)) => {
          r.removeKeyRights(tenant, items.keys)
            .removeProjectRights(tenant, items.projects)
            .removeWebhookRights(tenant, items.webhooks)
        }
      }
    }

    val tenants: Set[String] = rights.tenants.keySet
    env.datastores.tenants.readTenants().flatMap(ts => {
      val setTenants = ts.map(_.name).toSet
      val tenantsToDelete = tenants.diff(setTenants)
      val tenantsToKeep = tenants.diff(tenantsToDelete)

      val tenantItemsToDelete = Future.sequence(tenantsToKeep.map(t => {
        val futureExistingProjects = env.postgresql.queryAll(
            "SELECT name from projects",
            schemas=Seq(t)
          ){r => r.optString("name")}
          .map(l => l.toSet)
        val futureExistingKeys = env.postgresql.queryAll(
          "SELECT name from apikeys",
          schemas=Seq(t)
        ){r => r.optString("name")}
          .map(l => l.toSet)
        val futureExistingWebhooks = env.postgresql.queryAll(
            "SELECT name from webhooks",
            schemas=Seq(t)
          ){r => r.optString("name")}
          .map(l => l.toSet)

        for(
          existingProjects <- futureExistingProjects;
          existingKeys <- futureExistingKeys;
          existingWebhooks <- futureExistingWebhooks
        ) yield {
          val projectToDelete = rights.tenants(t).projects.keySet.diff(existingProjects)
          val keyToDelete = rights.tenants(t).keys.keySet.diff(existingKeys)
          val webhookToDelete = rights.tenants(t).webhooks.keySet.diff(existingWebhooks)
          (t -> TenantItemsToDelete(projects = projectToDelete, keys = keyToDelete, webhooks = webhookToDelete))
        }
      })).map(s => s.toMap)

      tenantItemsToDelete.flatMap(toDelete => {
        val newRights = deleteNeededItems(tenantsToDelete, toDelete)
        updateOAuthDefaultRights(newRights).map(_ => newRights)
      })
    })

  }

  def readId(): Future[UUID] = {
    env.postgresql.queryOne(s"""SELECT izanami_id FROM izanami.configuration""") { r => r.optUUID("izanami_id") }
      .map(_.get)

  }

  def readWasmConfiguration(): Option[WasmoSettings] = {
    for (
      url <- env.configuration.getOptional[String]("app.wasmo.url");
      clientId <- env.configuration.getOptional[String]("app.wasmo.client-id");
      clientSecret <- env.configuration.getOptional[String]("app.wasmo.client-secret")
    ) yield WasmoSettings(url, clientId, clientSecret = clientSecret)
  }

  def readFullConfiguration(): Future[Either[IzanamiError, FullIzanamiConfiguration]] = {
    env.postgresql
      .queryOne(
        s"""
           |SELECT c.mailer, c.invitation_mode, c.origin_email, c.anonymous_reporting, m.configuration, m.name, c.oidc_configuration, c.anonymous_reporting_date
           |FROM izanami.configuration c, izanami.mailers m
           |WHERE m.name = c.mailer
           |""".stripMargin) { row => {
        for (
          mailProviderConfig <- row.optMailerConfiguration();
          invitationMode <- row.optString("invitation_mode");
          anonymousReporting <- row.optBoolean("anonymous_reporting")
        )
        yield {
          val oidcConfiguration = row.optJsObject("oidc_configuration").flatMap(r => r.asOpt[OAuth2Configuration](OAuth2Configuration._fmt.reads))
          FullIzanamiConfiguration(
            invitationMode = parseInvitationMode(invitationMode),
            mailConfiguration = mailProviderConfig,
            originEmail = row.optString("origin_email"),
            anonymousReporting = anonymousReporting,
            anonymousReportingLastAsked = row.optOffsetDatetime("anonymous_reporting_date").map(_.toInstant),
            oidcConfiguration = oidcConfiguration
          )
        }
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

  def updateOAuthDefaultRights(rights: Rights): Future[Unit] = {
    env.postgresql.queryRaw(
      s"""
         |UPDATE izanami.configuration
         |SET
         |  oidc_configuration=jsonb_set(oidc_configuration, '{defaultOIDCUserRights}', $$1)
         |RETURNING *
         |""".stripMargin,
      List(
        User.rightWrite.writes(rights).vertxJsValue
      )
    ){_ => Some(())}
  }

  def updateConfiguration(newConfig: FullIzanamiConfiguration): Future[Either[IzanamiError, FullIzanamiConfiguration]] = {
    env.postgresql.executeInTransaction(conn => {
      updateMailerConfiguration(newConfig.mailConfiguration, Some(conn))
        .flatMap{
          case Left(err) => Future.successful(Left(err))
          case Right(mailConfig) => {
            env.postgresql.queryOne(
              s"""
                 |UPDATE izanami.configuration
                 |SET
                 |  mailer=$$1,
                 |  invitation_mode=$$2,
                 |  origin_email=$$3,
                 |  anonymous_reporting=$$4,
                 |  anonymous_reporting_date=$$5,
                 |  oidc_configuration=$$6
                 |RETURNING *
                 |""".stripMargin,
              List(
                newConfig.mailConfiguration.mailerType.toString.toUpperCase,
                newConfig.invitationMode.toString.toUpperCase,
                newConfig.originEmail.orNull,
                java.lang.Boolean.valueOf(newConfig.anonymousReporting),
                newConfig.anonymousReportingLastAsked.map(_.atOffset(ZoneOffset.UTC)).orNull,
                newConfig.oidcConfiguration
                  .map(r => Json.toJson(r)(OAuth2Configuration._fmt.writes))
                  .getOrElse(JsNull)
                  .vertxJsValue
              )
            ) { row => {
              for (
                invitationModeStr <- row.optString("invitation_mode");
                anonymousReporting <- row.optBoolean("anonymous_reporting")
              )
              yield {
                val oidcConfiguration = row.optJsObject("oidc_configuration").flatMap(r => r.asOpt[OAuth2Configuration](OAuth2Configuration._fmt.reads))
                FullIzanamiConfiguration(
                  invitationMode = parseInvitationMode(invitationModeStr),
                  mailConfiguration = mailConfig,
                  originEmail = row.optString("origin_email"),
                  anonymousReporting = anonymousReporting,
                  anonymousReportingLastAsked = row.optOffsetDatetime("anonymous_reporting_date").map(_.toInstant),
                  oidcConfiguration = oidcConfiguration,
                )
              }
            }
            }.map(o => o.toRight(InternalServerError("Failed to read configuration update result")))
              .recover(env.postgresql.pgErrorPartialFunction.andThen(err => Left(err)))
          }
        }
        })
  }

  def updateMailerConfiguration(
                                 mailProviderConfiguration: MailProviderConfiguration,
                                 conn: Option[SqlConnection] = None
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
            case ConsoleMailProvider => "{}"
            case MailJetMailProvider(configuration) if configuration.url.isDefined =>
              Json
                .obj(
                  "url" -> configuration.url.get,
                  "apiKey" -> configuration.apiKey,
                  "secret" -> configuration.secret
                )
                .toString
            case MailJetMailProvider(configuration) =>
              Json
                .obj(
                  "apiKey" -> configuration.apiKey,
                  "secret" -> configuration.secret
                )
                .toString
            case MailGunMailProvider(configuration) if configuration.url.isDefined =>
              Json
                .obj(
                  "url" -> configuration.url.get,
                  "apiKey" -> configuration.apiKey,
                  "region" -> configuration.region
                )
                .toString
            case MailGunMailProvider(configuration) =>
              Json
                .obj(
                  "apiKey" -> configuration.apiKey,
                  "region" -> configuration.region
                )
                .toString
            case SMTPMailProvider(configuration) =>
              Json.toJson(configuration).toString
          },
          mailProviderConfiguration.mailerType.toString.toUpperCase
        ),
        conn=conn
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
      case "SMTP" => MailerTypes.SMTP
      case _ => throw new RuntimeException(s"Failed to read Mailer (readed ${dbMailer})")
    }
  }

  def parseInvitationMode(mode: String): InvitationMode = {
    mode match {
      case "MAIL" => InvitationMode.Mail
      case "RESPONSE" => InvitationMode.Response
      case _ => throw new RuntimeException(s"Failed to read Invitation mode (readed ${mode})")
    }
  }
}

object configurationImplicits {
  implicit class ConfigurationRow(val row: Row) extends AnyVal {
    def optConfiguration(): Option[IzanamiConfiguration] = {
      for (
        mailerStr <- row.optString("mailer");
        invitationModeStr <- row.optString("invitation_mode");
        anonymousReporting <- row.optBoolean("anonymous_reporting")
      )
      yield {
        val oidcConfiguration = row.optJsObject("oidc_configuration").flatMap(r => r.asOpt[OAuth2Configuration](OAuth2Configuration._fmt.reads))
        IzanamiConfiguration(
          parseDbMailer(mailerStr),
          parseInvitationMode(invitationModeStr),
          originEmail = row.optString("origin_email"),
          anonymousReporting = anonymousReporting,
          anonymousReportingLastAsked = row.optOffsetDatetime("anonymous_reporting_date").map(_.toInstant),
          oidcConfiguration = oidcConfiguration,
        )
      }
    }
  }

  implicit class MailerConfigurationRow(val row: Row) extends AnyVal {
    def optMailerConfiguration(): Option[MailProviderConfiguration] = {
      for (
        name <- row.optString("name");
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
