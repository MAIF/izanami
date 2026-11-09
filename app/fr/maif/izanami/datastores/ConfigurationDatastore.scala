package fr.maif.izanami.datastores

import fr.maif.izanami.datastores.ConfigurationDatastore.parseDbMailer
import fr.maif.izanami.datastores.ConfigurationDatastore.parseInvitationMode
import fr.maif.izanami.datastores.configurationImplicits.MailerConfigurationRow
import fr.maif.izanami.env.pgimplicits.EnhancedRow
import fr.maif.izanami.errors.ConfigurationReadError
import fr.maif.izanami.errors.InternalServerError
import fr.maif.izanami.errors.IzanamiError
import fr.maif.izanami.events.EventOrigin
import fr.maif.izanami.events.EventOrigin.TechnicalOrigin
import fr.maif.izanami.events.SourceConfigurationUpdatedEvent
import fr.maif.izanami.mail.*
import fr.maif.izanami.models.*
import fr.maif.izanami.models.IzanamiConfiguration.SMTPConfigurationReads
import fr.maif.izanami.models.IzanamiConfiguration.SMTPConfigurationWrites
import fr.maif.izanami.models.IzanamiConfiguration.mailGunConfigurationReads
import fr.maif.izanami.models.IzanamiConfiguration.mailJetConfigurationReads
import fr.maif.izanami.services.CompleteRightsWithMaxRights
import fr.maif.izanami.utils.Datastore
import fr.maif.izanami.utils.Done
import fr.maif.izanami.utils.FutureEither
import fr.maif.izanami.utils.syntax.implicits.BetterFuture
import fr.maif.izanami.utils.syntax.implicits.BetterFutureEither
import fr.maif.izanami.utils.syntax.implicits.BetterJsValue
import fr.maif.izanami.web.IzanamiApplicationUserInformation
import fr.maif.izanami.web.UserInformation
import io.vertx.sqlclient.Row
import io.vertx.sqlclient.SqlConnection
import play.api.libs.json.JsNull
import play.api.libs.json.Json
import play.api.libs.json.Reads
import play.api.libs.json.Writes

import java.time.ZoneOffset
import java.util.UUID
import scala.concurrent.Future
import fr.maif.izanami.env.Postgresql
import fr.maif.izanami.OpenId
import fr.maif.izanami.events.EventService
import scala.concurrent.ExecutionContext

class ConfigurationDatastore(
    postgresql: => Postgresql,
    eventService: => EventService,
    maybeOidcConfig: Option[OpenId]
)(implicit val ec: ExecutionContext) extends Datastore {

  def oidcConfigurationMigration()
      : FutureEither[Option[FutureEither[FullIzanamiConfiguration]]] = {
    readFullConfiguration()
      .map(configuration => {
        val maybeOauth =
          maybeOidcConfig.flatMap(o => o.toIzanamiOAuth2Configuration)
        maybeOauth.map(oauth => {
          updateConfiguration(
            configuration.copy(oidcConfiguration = Some(oauth)),
            origin = TechnicalOrigin,
            userInformation = IzanamiApplicationUserInformation
          )
            .map(res => {
              logger.info(
                "The OIDC configuration has been register in database from environments variables"
              )
              res
            })
        })
      })
  }

  def readId(): Future[UUID] = {
    postgresql
      .queryOne(s"""SELECT izanami_id FROM izanami.configuration""") { r =>
        r.optUUID("izanami_id")
      }
      .map(_.get)

  }

  def readFullConfiguration(): FutureEither[FullIzanamiConfiguration] = {
    val res = postgresql
      .queryOne(s"""
           |SELECT c.mailer, c.invitation_mode, c.origin_email, c.anonymous_reporting, m.configuration, m.name, c.oidc_configuration, c.anonymous_reporting_date
           |FROM izanami.configuration c, izanami.mailers m
           |WHERE m.name = c.mailer
           |""".stripMargin) { row =>
        {
          for (
            mailProviderConfig <- row.optMailerConfiguration();
            invitationMode <- row.optString("invitation_mode");
            anonymousReporting <- row.optBoolean("anonymous_reporting")
          )
            yield {
              val oidcConfiguration = row
                .optJsObject("oidc_configuration")
                .flatMap(r =>
                  r.asOpt[OAuth2Configuration](
                    OAuth2Configuration._fmt.reads(_)
                  )
                )
              FullIzanamiConfiguration(
                invitationMode = parseInvitationMode(invitationMode),
                mailConfiguration = mailProviderConfig,
                originEmail = row.optString("origin_email"),
                anonymousReporting = anonymousReporting,
                anonymousReportingLastAsked = row
                  .optOffsetDatetime("anonymous_reporting_date")
                  .map(_.toInstant),
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

    FutureEither(res)
  }

  def updateOAuthRightByRole(
      rights: Map[String, CompleteRightsWithMaxRights]
  ): FutureEither[Done] = {
    readFullConfiguration().flatMap(oldConf => {
      updateConfiguration(
        oldConf.copy(oidcConfiguration =
          oldConf.oidcConfiguration.map(c =>
            c.copy(userRightsByRoles = Some(rights))
          )
        ),
        userInformation = IzanamiApplicationUserInformation,
        origin = TechnicalOrigin
      )
    }).map(_ => Done.done())
  }

  def updateConfiguration(
      newConfig: FullIzanamiConfiguration,
      userInformation: UserInformation,
      origin: EventOrigin,
      conn: Option[SqlConnection] = None
  ): FutureEither[FullIzanamiConfiguration] = {
    postgresql.executeInOptionalTransaction(
      conn,
      conn => {
        for (
          oldConfig <- readFullConfiguration();
          udpatedMailer <- updateMailerConfiguration(
            newConfig.mailConfiguration,
            Some(conn)
          ).toFEither;
          updatedConfiguration <- {
            postgresql
              .queryOne(
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
                  newConfig.anonymousReportingLastAsked
                    .map(_.atOffset(ZoneOffset.UTC))
                    .orNull,
                  newConfig.oidcConfiguration
                    .map(r => Json.toJson(r)(OAuth2Configuration._fmt))
                    .getOrElse(JsNull)
                    .vertxJsValue
                ),
                conn = Some(conn)
              ) { row =>
                {
                  for (
                    invitationModeStr <- row.optString("invitation_mode");
                    anonymousReporting <- row.optBoolean("anonymous_reporting")
                  )
                    yield {
                      val oidcConfiguration = row
                        .optJsObject("oidc_configuration")
                        .flatMap(r =>
                          r.asOpt[OAuth2Configuration](
                            OAuth2Configuration._fmt.reads(_)
                          )
                        )
                      FullIzanamiConfiguration(
                        invitationMode = parseInvitationMode(invitationModeStr),
                        mailConfiguration = newConfig.mailConfiguration,
                        originEmail = row.optString("origin_email"),
                        anonymousReporting = anonymousReporting,
                        anonymousReportingLastAsked = row
                          .optOffsetDatetime("anonymous_reporting_date")
                          .map(_.toInstant),
                        oidcConfiguration = oidcConfiguration
                      )
                    }
                }
              }
              .map(o =>
                o.toRight(
                  InternalServerError(
                    "Failed to read configuration update result"
                  )
                )
              ).recover(postgresql.pgErrorPartialFunction.andThen(err =>
                Left(err)
              ))
          }.toFEither;
          _ <- if (updatedConfiguration != oldConfig) {
            eventService.emitGlobalEvent(
              SourceConfigurationUpdatedEvent(
                user = userInformation.username,
                origin = origin,
                authentication = userInformation.authentication,
                oldConfiguration = oldConfig,
                newConfiguration = updatedConfiguration
              )
            )(conn).mapToFEither
          } else {
            FutureEither.success(())
          }
        ) yield {
          updatedConfiguration
        }
      }
    )
  }

  private def updateMailerConfiguration(
      mailProviderConfiguration: MailProviderConfiguration,
      conn: Option[SqlConnection]
  ): Future[Either[IzanamiError, MailProviderConfiguration]] = {
    postgresql
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
            case MailJetMailProvider(configuration)
                if configuration.url.isDefined =>
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
            case MailGunMailProvider(configuration)
                if configuration.url.isDefined =>
              Json
                .obj(
                  "url" -> configuration.url.get,
                  "apiKey" -> configuration.apiKey,
                  "region" -> MailGunRegion.mailGunRegionWrites
                    .writes(configuration.region)
                )
                .toString
            case MailGunMailProvider(configuration) =>
              Json
                .obj(
                  "apiKey" -> configuration.apiKey,
                  "region" -> MailGunRegion.mailGunRegionWrites
                    .writes(configuration.region)
                )
                .toString
            case SMTPMailProvider(configuration) =>
              Json.toJson(configuration).toString
          },
          mailProviderConfiguration.mailerType.toString.toUpperCase
        ),
        conn = conn
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
      case "CONSOLE" => MailerType.Console
      case "MAILJET" => MailerType.MailJet
      case "MAILGUN" => MailerType.MailGun
      case "SMTP"    => MailerType.SMTP
      case _         =>
        throw new RuntimeException(s"Failed to read Mailer (readed $dbMailer)")
    }
  }

  def parseInvitationMode(mode: String): InvitationMode = {
    mode match {
      case "MAIL"     => InvitationMode.Mail
      case "RESPONSE" => InvitationMode.Response
      case _          =>
        throw new RuntimeException(
          s"Failed to read Invitation mode (readed ${mode})"
        )
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
          val oidcConfiguration = row
            .optJsObject("oidc_configuration")
            .flatMap(r =>
              r.asOpt[OAuth2Configuration](OAuth2Configuration._fmt.reads(_))
            )
          IzanamiConfiguration(
            parseDbMailer(mailerStr),
            parseInvitationMode(invitationModeStr),
            originEmail = row.optString("origin_email"),
            anonymousReporting = anonymousReporting,
            anonymousReportingLastAsked = row
              .optOffsetDatetime("anonymous_reporting_date")
              .map(_.toInstant),
            oidcConfiguration = oidcConfiguration
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
        case MailerType.MailJet => {
          MailJetMailProvider(
            configuration
              .asOpt[MailJetConfiguration](mailJetConfigurationReads)
              .getOrElse(
                MailJetConfiguration(apiKey = null, secret = null, url = None)
              )
          )
        }
        case MailerType.MailGun => {
          MailGunMailProvider(
            configuration
              .asOpt[MailGunConfiguration](mailGunConfigurationReads)
              .getOrElse(MailGunConfiguration(apiKey = null, url = None))
          )
        }
        case MailerType.SMTP => {
          SMTPMailProvider(
            configuration
              .asOpt[SMTPConfiguration](SMTPConfigurationReads)
              .getOrElse(
                SMTPConfiguration(null, None, None, None, false, false, false)
              )
          )
        }
        case MailerType.Console => ConsoleMailProvider
      }
    }
  }
}
