package fr.maif.izanami.web

import buildinfo.BuildInfo
import fr.maif.izanami.env.Env
import fr.maif.izanami.errors.BadBodyFormat
import fr.maif.izanami.errors.CantUpdateOIDCCOnfiguration
import fr.maif.izanami.events.EventOrigin.NormalOrigin
import fr.maif.izanami.mail.MailGunMailProvider
import fr.maif.izanami.mail.MailJetMailProvider
import fr.maif.izanami.mail.SMTPMailProvider
import fr.maif.izanami.models.FullIzanamiConfiguration
import fr.maif.izanami.models.IzanamiConfiguration
import fr.maif.izanami.services.FeatureService
import fr.maif.izanami.services.MaxRights
import fr.maif.izanami.utils.Done
import fr.maif.izanami.utils.FutureEither
import fr.maif.izanami.utils.syntax.implicits.BetterSyntax
import fr.maif.izanami.web.ConfigurationController.extractRoleWithLoweredRights
import play.api.libs.json.*
import play.api.mvc.Action
import play.api.mvc.AnyContent
import play.api.mvc.BaseController
import play.api.mvc.ControllerComponents
import play.api.mvc.Results.Ok

import scala.concurrent.ExecutionContext

class ConfigurationController(
    val controllerComponents: ControllerComponents,
    val adminAuthAction: AdminAuthAction,
    val featureService: FeatureService
)(implicit val env: Env)
    extends BaseController {
  implicit val ec: ExecutionContext = env.executionContext;

  def readStats(): Action[AnyContent] = adminAuthAction.async {
    implicit request =>
      {
        env.datastores.stats.retrieveStats().map(Ok(_))
      }
  }

  def updateConfiguration(): Action[JsValue] =
    adminAuthAction.async(parse.json) { implicit request =>
      {
        IzanamiConfiguration.inputFullConfigurationReads.reads(
          request.body
        ) match {
          case JsError(_) => BadBodyFormat().toHttpResponse.future
          case JsSuccess(configurationFromBody, _path) => {
            val futureConfiguration =
              env.datastores.configuration.readFullConfiguration()
            futureConfiguration
              .flatMap(oldConfiguration => {
                val mailerConfigurationWithSecrets = (
                  configurationFromBody.mailConfiguration,
                  oldConfiguration.mailConfiguration
                ) match {
                  case (SMTPMailProvider(newConf), SMTPMailProvider(oldConf))
                      if newConf.password.forall(p => p.isBlank) =>
                    SMTPMailProvider(newConf.copy(password = oldConf.password))
                  case (
                        MailJetMailProvider(newConf),
                        MailJetMailProvider(oldConf)
                      ) if Option(newConf.secret).forall(p => p.isBlank) =>
                    MailJetMailProvider(newConf.copy(secret = oldConf.secret))
                  case (
                        MailGunMailProvider(newConf),
                        MailGunMailProvider(oldConf)
                      ) if Option(newConf.apiKey).forall(p => p.isBlank) =>
                    MailGunMailProvider(newConf.copy(apiKey = oldConf.apiKey))
                  case (newConf, oldConf) => newConf
                }

                val inputConfigurationWithSecret = configurationFromBody.copy(
                  oidcConfiguration =
                    configurationFromBody.oidcConfiguration.map(conf => {
                      if (
                        conf.clientSecret == null || conf.clientSecret.isEmpty
                      ) {
                        conf.copy(clientSecret =
                          oldConfiguration.oidcConfiguration
                            .map(_.clientSecret)
                            .orNull
                        )
                      } else {
                        conf
                      }
                    }),
                  mailConfiguration = mailerConfigurationWithSecrets
                )

                val hasOidcPartChanged =
                  ConfigurationController.hasOIDCConfChanged(
                    oldConfiguration,
                    inputConfigurationWithSecret
                  )
                val rolesToUpdate = extractRoleWithLoweredRights(
                  oldConfiguration.oidcConfiguration
                    .flatMap(_.maxRightsByRoles),
                  inputConfigurationWithSecret.oidcConfiguration
                    .flatMap(_.maxRightsByRoles)
                )

                if (hasOidcPartChanged && !env.isOIDCConfigurationEditable) {
                  FutureEither.failure(CantUpdateOIDCCOnfiguration)
                } else {
                  env.postgresql.executeInTransaction(conn => {

                    (if (rolesToUpdate.nonEmpty) {
                       env.datastores.users
                         .logoutConnectedUsersWithRoleIn(
                           rolesToUpdate,
                           conn = Some(conn)
                         )
                     } else {
                       FutureEither.success(Done.done())
                     }).flatMap(_ => {
                      env.datastores.configuration
                        .updateConfiguration(
                          inputConfigurationWithSecret,
                          userInformation = request.user,
                          origin = NormalOrigin,
                          conn = Some(conn)
                        )
                    })
                  })
                }
              })
              .toResult(_ => NoContent)
          }
        }
      }
    }

  def readConfiguration(): Action[AnyContent] = adminAuthAction.async {
    implicit request: UserNameRequest[AnyContent] =>
      val preventOAuthModification = JsBoolean(!env.isOIDCConfigurationEditable)

      env.datastores.configuration
        .readFullConfiguration()
        .toResult(configuration => {
          val json = Json
            .toJson(configuration)(
              IzanamiConfiguration.configurationWriteForExposition
            )
            .as[JsObject]
          val configurationWithVersion: JsObject = json +
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

  def availableIntegrations(): Action[AnyContent] = Action.async {
    implicit request =>
      val isWasmPresent =
        env.datastores.configuration.readWasmConfiguration().isDefined
      env.datastores.configuration
        .readFullConfiguration()
        .toResult(c => {
          Ok(
            Json.obj(
              "wasmo" -> isWasmPresent,
              "oidc" -> c.oidcConfiguration.exists(_.enabled),
              "forceLegacy" -> env.typedConfiguration.feature.forceLegacy,
              "wasmAllowed" -> featureService.isWasmAllowed
            )
          )
        })
  }
}

object ConfigurationController {
  def extractRoleWithLoweredRights(
      oldConfig: Option[Map[String, MaxRights]],
      newConfig: Option[Map[String, MaxRights]]
  ): Set[String] = {
    (
      oldConfig,
      newConfig
    ) match {
      case (None, Some(maxRightsByRoles))           => maxRightsByRoles.keySet
      case (Some(oldMaxRights), Some(newMaxRights)) => {
        newMaxRights.collect {
          case (role, maxRights)
              if oldMaxRights
                .get(role)
                .forall(oldRights => maxRights.hasElementsBelow(oldRights)) =>
            role
        }.toSet
      }
      case _ => Set()
    }
  }

  def hasElementBelow(
      oldMaxRights: MaxRights,
      newMaxRights: MaxRights
  ): Boolean = {
    if (oldMaxRights.admin && !newMaxRights.admin) {
      true
    } else {
      newMaxRights.tenants.exists { (tenant, newRightsForTenant) =>
        {
          oldMaxRights.tenants
            .get(tenant)
            .forall(oldRightsForTenant =>
              newRightsForTenant.hasElementsBelow(oldRightsForTenant)
            )
        }
      }
    }
  }

  def hasOIDCConfChanged(
      oldConfig: FullIzanamiConfiguration,
      newConfig: FullIzanamiConfiguration
  ): Boolean = {
    oldConfig.oidcConfiguration != newConfig.oidcConfiguration
  }
}
