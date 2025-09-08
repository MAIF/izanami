package fr.maif.izanami.web

import buildinfo.BuildInfo
import fr.maif.izanami.env.Env
import fr.maif.izanami.errors.{BadBodyFormat, CantUpdateOIDCCOnfiguration, IzanamiError}
import fr.maif.izanami.mail.{ConsoleMailProvider, MailGunMailProvider, MailJetMailProvider, MailerTypes, SMTPMailProvider}
import fr.maif.izanami.models.{FullIzanamiConfiguration, IzanamiConfiguration, Rights}
import fr.maif.izanami.models.IzanamiConfiguration.{SMTPConfigurationWrites, mailGunConfigurationWrite, mailJetConfigurationWrites, mailerReads}
import fr.maif.izanami.utils.FutureEither
import fr.maif.izanami.utils.syntax.implicits.BetterSyntax
import play.api.libs.json.{JsBoolean, JsError, JsFalse, JsNull, JsObject, JsString, JsSuccess, JsValue, Json}
import play.api.mvc.Results.Ok
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

  private def hasOIDCConfChanged(
      oldConfig: FullIzanamiConfiguration,
     newConfig: FullIzanamiConfiguration
  ): Boolean = {
    oldConfig.oidcConfiguration != newConfig.oidcConfiguration
  }

  private def updateOIDCRightIfNeeded(conf: FullIzanamiConfiguration): Future[FullIzanamiConfiguration] = {
    conf.oidcConfiguration
      .flatMap(_.userRightsByRoles)
      .fold(conf.future)(rights => {
        env.datastores.configuration.updateOAuthRightByRole(rights)
          .map(updatedRights => conf
            .copy(oidcConfiguration = conf.oidcConfiguration.get.copy(userRightsByRoles = Some(rights)).some)
          )
      })
  }

  def updateConfiguration(): Action[JsValue] = adminAuthAction.async(parse.json) { implicit request =>
      {
        IzanamiConfiguration.fullConfigurationReads.reads(request.body) match {
          case JsError(_) => BadBodyFormat().toHttpResponse.future
          case JsSuccess(configurationFromBody, _path) => {
            val futureConfiguration = env.datastores.configuration.readFullConfiguration()
            futureConfiguration.flatMap(oldConfiguration => {
              val inputConfigurationWithSecret = configurationFromBody.copy(
                oidcConfiguration = configurationFromBody.oidcConfiguration.map(conf => {
                  if(conf.clientSecret == null || conf.clientSecret.isEmpty) {
                    conf.copy(clientSecret = oldConfiguration.oidcConfiguration.map(_.clientSecret).orNull)
                  } else {
                    conf
                  }
                })
              )

              val hasOidcPartChanged = hasOIDCConfChanged(oldConfiguration, inputConfigurationWithSecret)
              if(hasOidcPartChanged && !env.isOIDCConfigurationEditable) {
                FutureEither.failure(CantUpdateOIDCCOnfiguration)
              } else {
                env.datastores.configuration.updateConfiguration(inputConfigurationWithSecret)
              }
            }).toResult(_ => NoContent)
          }
        }
    }
  }

  def readConfiguration(): Action[AnyContent] = adminAuthAction.async { implicit request: UserNameRequest[AnyContent] =>
    val preventOAuthModification = JsBoolean(!env.isOIDCConfigurationEditable)

    env.datastores.configuration
      .readFullConfiguration()
      .toResult(configuration => {
        val json = Json.toJson(configuration)(IzanamiConfiguration.configurationWriteForExposition).as[JsObject]
        val configurationWithVersion:JsObject = json +
          ("version" -> JsString(BuildInfo.version)) +
          ("preventOAuthModification" -> preventOAuthModification)
        Ok(configurationWithVersion)
      })
  }

  def readExpositionUrl(): Action[AnyContent] = Action { implicit request =>
    val url = env.typedConfiguration.exposition.backend
      .getOrElse(env.expositionUrl)
    Ok(Json.obj("url" -> url))
  }

  def availableIntegrations(): Action[AnyContent] = Action.async { implicit request =>
    val isWasmPresent = env.datastores.configuration.readWasmConfiguration().isDefined
    env.datastores.configuration.readFullConfiguration()
      .toResult(c => {
        Ok(Json.obj(
          "wasmo" -> isWasmPresent,
          "oidc" -> c.oidcConfiguration.exists(_.enabled),
          "forceLegacy" -> env.typedConfiguration.feature.forceLegacy
        ))
      })
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
