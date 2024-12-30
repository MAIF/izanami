package fr.maif.izanami.web

import buildinfo.BuildInfo
import fr.maif.izanami.env.Env
import fr.maif.izanami.errors.BadBodyFormat
import fr.maif.izanami.mail.ConsoleMailProvider
import fr.maif.izanami.mail.MailGunMailProvider
import fr.maif.izanami.mail.MailJetMailProvider
import fr.maif.izanami.mail.MailerTypes
import fr.maif.izanami.mail.SMTPMailProvider
import fr.maif.izanami.models.IzanamiConfiguration
import fr.maif.izanami.models.IzanamiConfiguration.SMTPConfigurationWrites
import fr.maif.izanami.models.IzanamiConfiguration.mailGunConfigurationWrite
import fr.maif.izanami.models.IzanamiConfiguration.mailJetConfigurationWrites
import fr.maif.izanami.models.IzanamiConfiguration.mailerReads
import fr.maif.izanami.utils.syntax.implicits.BetterSyntax
import play.api.libs.json.JsError
import play.api.libs.json.JsObject
import play.api.libs.json.JsString
import play.api.libs.json.JsSuccess
import play.api.libs.json.JsValue
import play.api.libs.json.Json
import play.api.mvc.Action
import play.api.mvc.AnyContent
import play.api.mvc.BaseController
import play.api.mvc.ControllerComponents

import scala.concurrent.ExecutionContext
import scala.concurrent.Future

class ConfigurationController(
    val controllerComponents: ControllerComponents,
    val adminAuthAction: AdminAuthAction
)(implicit val env: Env)
    extends BaseController {
  implicit val ec: ExecutionContext = env.executionContext;


  def readStats(): Action[AnyContent] = adminAuthAction.async { implicit request => {
    env.datastores.stats.retrieveStats().map(Ok(_))
  } }
  def updateConfiguration(): Action[JsValue] = adminAuthAction.async(parse.json) { implicit request =>
    {
      IzanamiConfiguration.configurationReads.reads(request.body) match {
        case JsSuccess(configuration, path) => {
          env.datastores.configuration.updateConfiguration(configuration).map(_ => NoContent)
        }
        case JsError(_)                     => BadBodyFormat().toHttpResponse.future
      }
    }
  }

  def readConfiguration(): Action[AnyContent] = adminAuthAction.async { implicit request: UserNameRequest[AnyContent] =>
    env.datastores.configuration
      .readConfiguration()
      .map {
        case Left(error)          => error.toHttpResponse
        case Right(configuration) => {
          val configurationWithVersion:JsObject = (Json.toJson(configuration).as[JsObject]) + ("version" -> JsString(BuildInfo.version))
          Ok(configurationWithVersion)
        }
      }
  }

  def readExpositionUrl(): Action[AnyContent] = Action { implicit request =>
    val url = env.configuration.getOptional[String]("app.exposition.backend")
      .getOrElse(env.expositionUrl)
    Ok(Json.obj("url" -> url))
  }

  def availableIntegrations(): Action[AnyContent] = Action { implicit request =>
    val isWasmPresent = env.datastores.configuration.readWasmConfiguration().isDefined
    val isOidcPresent = env.datastores.configuration.readOIDCConfiguration().isDefined

    Ok(Json.obj(
      "wasmo" -> isWasmPresent,
      "oidc" -> isOidcPresent
    ))
  }

  def readMailerConfiguration(id: String): Action[AnyContent] = adminAuthAction.async {
    implicit request: UserNameRequest[AnyContent] =>
      mailerReads.reads(JsString(id)).fold(_ => {
        Future.successful(BadRequest(Json.obj("message" -> "Unknown mail provider")))
      },
        mailerType => {
          env.datastores.configuration
            .readMailerConfiguration(mailerType)
            .map {
              case Left(error)          => error.toHttpResponse
              case Right(MailJetMailProvider(configuration)) => Ok(Json.toJson(configuration))
              case Right(ConsoleMailProvider) => Ok(Json.obj())
              case Right(MailGunMailProvider(configuration)) => Ok(Json.toJson(configuration))
              case Right(SMTPMailProvider(configuration)) => Ok(Json.toJson(configuration))
            }
        })

  }

  def updateMailerConfiguration(id: String): Action[JsValue] = adminAuthAction.async(parse.json) { implicit request =>
    {
      mailerReads
        .reads(JsString(id))
        .flatMap{
          case MailerTypes.Console => JsError("Can't update built in Console mail provider")
          case t@_ => JsSuccess(t)
        }
        .flatMap(mailer => IzanamiConfiguration.mailProviderConfigurationReads(mailer).reads(request.body)) match {
        case JsSuccess(configuration, _) if !configuration.mailerType.toString.equalsIgnoreCase(id) =>
          BadRequest(Json.obj("message" -> "url id and configuration mailer type should match")).future
        case JsSuccess(configuration, _)                                                            =>
          env.datastores.configuration.updateMailerConfiguration(configuration).map(_ => NoContent)
        case JsError(_)                                                                             => BadBodyFormat().toHttpResponse.future
      }
    }
  }
}
