package fr.maif.izanami.mail

import com.mailjet.client.resource.Emailv31
import com.mailjet.client.{ClientOptions, MailjetClient, MailjetRequest}
import com.sun.mail.smtp.SMTPTransport
import fr.maif.izanami.env.Env
import fr.maif.izanami.errors.{IzanamiError, MailSendingError, MissingMailProviderConfigurationError}
import fr.maif.izanami.mail.MailGunRegion.Europe
import fr.maif.izanami.mail.MailerTypes.MailerType
import fr.maif.izanami.utils.FutureEither
import fr.maif.izanami.utils.syntax.implicits.BetterFutureEither
import org.json.{JSONArray, JSONObject}
import play.api.Logger
import play.api.libs.json.{JsError, JsString, JsSuccess, Reads, Writes}
import play.api.libs.ws.{WSAuthScheme, WSClient}
import play.api.libs.ws.DefaultBodyWritables.writeableOf_urlEncodedSimpleForm

import java.util.{Objects, Properties}
import javax.mail.internet.{InternetAddress, MimeMessage}
import javax.mail.{Message, Session}
import scala.concurrent.{ExecutionContext, Future}
import scala.jdk.FutureConverters.CompletionStageOps
import scala.util.Using

case class Mail(subject: String, targetMail: String, textContent: String = "", htmlContent: String = "")

sealed trait MailProviderConfiguration {
  val mailerType: MailerType
}

object ConsoleMailProvider extends MailProviderConfiguration {
  val mailerType: MailerType = MailerTypes.Console
}

case class MailJetConfiguration(apiKey: String, secret: String, url: Option[String] = None)

case class SMTPConfiguration(
    host: String,
    port: Option[Int] = None,
    user: Option[String] = None,
    password: Option[String] = None,
    auth: Boolean = false,
    starttlsEnabled: Boolean = false,
    smtps: Boolean = true
)

case class SMTPMailProvider(configuration: SMTPConfiguration) extends MailProviderConfiguration {
  val mailerType: MailerType = MailerTypes.SMTP
}

case class MailJetMailProvider(configuration: MailJetConfiguration) extends MailProviderConfiguration {
  val mailerType: MailerType = MailerTypes.MailJet
}

sealed trait MailGunRegion

object MailGunRegion {
  case object Europe extends MailGunRegion
  case object US     extends MailGunRegion
  def mailGunRegionWrites: Writes[MailGunRegion] = {
    case Europe => JsString("Europe")
    case US     => JsString("US")
  }

  def mailGunRegionReads: Reads[MailGunRegion] = {
    case JsString(str) if str.toUpperCase == "EUROPE" => JsSuccess(Europe)
    case JsString(str) if str.toUpperCase == "US"     => JsSuccess(US)
    case _                  => JsError("Unknown mailgun region")
  }
}

case class MailGunConfiguration(
    apiKey: String,
    url: Option[String],
    region: MailGunRegion = Europe
)

case class MailGunMailProvider(configuration: MailGunConfiguration) extends MailProviderConfiguration {
  val mailerType: MailerType = MailerTypes.MailGun
}

class Mails(env: Env) {
  private val mailFactory                   = new MailFactory(env)
  private implicit val ec: ExecutionContext = env.executionContext

  def sendMail(mail: Mail): FutureEither[Unit] = {
    env.datastores.configuration
      .readFullConfiguration()
      .flatMap(configuration => {
        configuration.mailConfiguration match {
          case ConsoleMailProvider                                                        => ConsoleMailService.sendMail(mail)
          case MailJetMailProvider(MailJetConfiguration(apiKey, secret, _))
              if Objects.isNull(apiKey) || Objects.isNull(secret) =>
            FutureEither.failure(MissingMailProviderConfigurationError("MailJet"))
          case MailJetMailProvider(conf)                                                  => MailJetService.sendMail(mail, conf, configuration.originEmail.get)
          case MailGunMailProvider(configuration) if Objects.isNull(configuration.apiKey) =>
            FutureEither.failure(MissingMailProviderConfigurationError("MailJet"))
          case MailGunMailProvider(mailConf)                                              =>
            MailGunService.sendMail(mail, mailConf, configuration.originEmail.get, env.Ws)
          case SMTPMailProvider(mailConf)                                                 =>
            SMTPMailService.sendMail(mail, mailConf, configuration.originEmail.get)
        }
      })
  }

  def sendInvitationMail(targetAdress: String, token: String): FutureEither[Unit] =
    sendMail(mailFactory.invitationEmail(targetAdress, token))

  def sendPasswordResetEmail(targetAdress: String, token: String): FutureEither[Unit] =
    sendMail(mailFactory.passwordResetEmail(targetAdress, token))
}

object MailerTypes extends Enumeration {
  type MailerType = Value
  val MailJet, Console, MailGun, SMTP = Value
}

object MailGunService {
  val US_URL     = "https://api.mailgun.net/v3"
  val EUROPE_URL = "https://api.eu.mailgun.net/v3"

  def sendMail(mail: Mail, mailerConfiguration: MailGunConfiguration, originEmail: String, ws: WSClient)(implicit
      ec: ExecutionContext
  ): FutureEither[Unit] = {
    val domain  = originEmail.split("@")(1)
    val url     = mailerConfiguration.url.getOrElse(if (mailerConfiguration.region == Europe) EUROPE_URL else US_URL)
    val request = ws
      .url(s"${url}/${domain}/messages")
      .withAuth("api", mailerConfiguration.apiKey, WSAuthScheme.BASIC)
    request
      .post(
        Map(
          "from"    -> s"""Izanami ${originEmail}""",
          "to"      -> s"""${mail.targetMail}""",
          "subject" -> "You've been invited to Izanami",
          "html"    -> mail.htmlContent
        )
      )
      .map {
        case response if response.status >= 400 => Left(MailSendingError(response.body, response.status))
        case _                                  => Right(())

      }
      .toFEither
  }
}

object MailJetService {
  def sendMail(mail: Mail, mailerConfiguration: MailJetConfiguration, originEmail: String)(implicit
      ec: ExecutionContext
  ): FutureEither[Unit] = {
    val clientBuilder = ClientOptions.builder()
    mailerConfiguration.url.foreach(url => clientBuilder.baseUrl(url))
    val client        = new MailjetClient(
      clientBuilder
        .apiKey(mailerConfiguration.apiKey)
        .apiSecretKey(mailerConfiguration.secret)
        .build()
    );
    val request       = new MailjetRequest(Emailv31.resource)
      .property(
        Emailv31.MESSAGES,
        new JSONArray()
          .put(
            new JSONObject()
              .put(
                Emailv31.Message.FROM,
                new JSONObject()
                  .put("Email", originEmail)
                  .put("Name", "Izanami")
              )
              .put(
                Emailv31.Message.TO,
                new JSONArray()
                  .put(
                    new JSONObject()
                      .put("Email", mail.targetMail)
                  )
              )
              .put(Emailv31.Message.SUBJECT, mail.subject)
              .put(Emailv31.Message.TEXTPART, mail.textContent)
              .put(Emailv31.Message.HTMLPART, mail.htmlContent)
          )
      )
    val res           = client
      .postAsync(request)
      .asScala
      .map(response => {
        if (response.getStatus > 400) {
          Left(MailSendingError(response.toString, response.getStatus))
        } else {
          Right(())
        }
      })(ec)
    FutureEither(res)
  }
}

object SMTPMailService {
  def sendMail(mail: Mail, configuration: SMTPConfiguration, originEmail: String)(implicit
      ec: ExecutionContext
  ): FutureEither[Unit] = {
    val props    = new Properties()
    val protocol = if (configuration.smtps) "smtps" else "smtp"
    props.put(s"mail.${protocol}.host", configuration.host)
    configuration.port.map(port => props.put(s"mail.${protocol}.port", port))
    props.put(s"mail.${protocol}.starttls.enable", configuration.starttlsEnabled)
    props.put(s"mail.${protocol}.auth", configuration.auth)

    val session = Session.getInstance(props, null)
    val msg     = new MimeMessage(session)
    msg.setFrom(new InternetAddress(originEmail))
    msg.addRecipient(Message.RecipientType.TO, new InternetAddress(mail.targetMail))
    msg.setSubject("Izanami")
    msg.setContent(mail.htmlContent, "text/html; charset=utf-8")

    FutureEither.from(Using(session.getTransport(protocol).asInstanceOf[SMTPTransport]) { transport =>
      {
        transport.connect(
          configuration.host,
          configuration.user.getOrElse(originEmail),
          configuration.password.orNull
        )
        transport.sendMessage(msg, msg.getAllRecipients)
      }
    }.toEither.left.map(t => {
      MailSendingError(t.getMessage, 500)
    }))

  }
}

object ConsoleMailService {
  private val logger: Logger = Logger("izanami-mailer")
  def sendMail(
      mail: Mail
  ): FutureEither[Unit] = {
    logger.info(s"""
         |To: ${mail.targetMail}
         |Subject: ${mail.subject}
         |Text content: ${mail.textContent}
         |Html content: ${mail.htmlContent}
         |""".stripMargin)
    FutureEither(Future.successful(Right(())))
  }
}
