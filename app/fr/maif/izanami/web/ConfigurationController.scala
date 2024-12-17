package fr.maif.izanami.web

import buildinfo.BuildInfo
import fr.maif.izanami.env.Env
import fr.maif.izanami.errors.BadBodyFormat
import fr.maif.izanami.mail.{ConsoleMailProvider, MailGunMailProvider, MailJetMailProvider, MailerTypes, SMTPMailProvider}
import fr.maif.izanami.models.{FullIzanamiConfiguration, IzanamiConfiguration, Rights}
import fr.maif.izanami.models.IzanamiConfiguration.{SMTPConfigurationWrites, mailGunConfigurationWrite, mailJetConfigurationWrites, mailerReads}
import fr.maif.izanami.utils.syntax.implicits.BetterSyntax
import play.api.libs.json.{JsBoolean, JsError, JsObject, JsString, JsSuccess, JsValue, Json}
import play.api.mvc.{Action, AnyContent, BaseController, ControllerComponents}

import scala.concurrent.{ExecutionContext, Future}

class ConfigurationController(
    val controllerComponents: ControllerComponents,
    val adminAuthAction: AdminAuthAction
)(implicit val env: Env)
    extends BaseController {
  implicit val ec: ExecutionContext = env.executionContext;


  def readStats(): Action[AnyContent] = adminAuthAction.async { implicit _ => {
    env.datastores.stats.retrieveStats().map(Ok(_))
  } }

  def updateConfiguration(): Action[JsValue] = adminAuthAction.async(parse.json) { implicit request =>
      {
        IzanamiConfiguration.fullConfigurationReads.reads(request.body) match {
          case JsError(_) => BadBodyFormat().toHttpResponse.future
          case JsSuccess(configurationFromBody, _path) =>
            configurationFromBody.oidcConfiguration
              .map(_.defaultOIDCUserRights)
              .fold(Future.successful(configurationFromBody))(r => {
                env.datastores.configuration.updateOIDCDefaultRightIfNeeded(r)
                  .map(newRigths => configurationFromBody
                    .copy(oidcConfiguration = configurationFromBody.oidcConfiguration.map(c => c.copy(defaultOIDCUserRights = newRigths))))
              })
              .flatMap(configurationWithUpdatedRights => {
                (if (env.isConfigurationEditable) {
                  Some(configurationWithUpdatedRights).future
                } else {
                  env.datastores.configuration.readFullConfiguration().map {
                    case Right(configuration)
                      if configurationWithUpdatedRights.oidcConfiguration.exists(_.defaultOIDCUserRights != Rights.EMPTY) =>
                      Some(configuration.copy(oidcConfiguration =
                        configurationWithUpdatedRights.oidcConfiguration
                          .map(c => c.copy(defaultOIDCUserRights = configurationWithUpdatedRights.oidcConfiguration.get.defaultOIDCUserRights))))
                    case _ => None
                  }
                })
                  .flatMap {
                    case Some(newConf) => env.datastores.configuration.updateConfiguration(newConf).map(_ => NoContent)
                    case None => BadRequest(Json.obj("message" -> "invalid configuration update request")).future
                  }
              })
        }
    }
  }

  def readConfiguration(): Action[AnyContent] = adminAuthAction.async { implicit request: UserNameRequest[AnyContent] =>
    val preventOAuthModification = JsBoolean(!env.isConfigurationEditable)

    env.datastores.configuration
      .readFullConfiguration()
      .map {
        case Left(error)          => error.toHttpResponse
        case Right(configuration) =>
          val configurationWithVersion:JsObject = Json.toJson(configuration)(IzanamiConfiguration.fullConfigurationWrites).as[JsObject] +
            ("version" -> JsString(BuildInfo.version)) +
            ("preventOAuthModification" -> preventOAuthModification)
          Ok(configurationWithVersion)
      }
  }

  def readExpositionUrl(): Action[AnyContent] = Action { implicit request =>
    val url = env.configuration.getOptional[String]("app.exposition.backend")
      .getOrElse(env.expositionUrl)
    Ok(Json.obj("url" -> url))
  }

  def availableIntegrations(): Action[AnyContent] = Action.async { implicit request =>
    val isWasmPresent = env.datastores.configuration.readWasmConfiguration().isDefined
    env.datastores.configuration.readFullConfiguration()
      .map{
        case Left(err) => err.toHttpResponse
        case Right(c:FullIzanamiConfiguration) => {
          Ok(Json.obj(
            "wasmo" -> isWasmPresent,
            "oidc" -> c.oidcConfiguration.exists(_.enabled)
          ))
        }
      }
  }

  /*def readMailerConfiguration(id: String): Action[AnyContent] = adminAuthAction.async {
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
  }*/
}
