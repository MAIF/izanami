package fr.maif.izanami.web

import buildinfo.BuildInfo
import fr.maif.izanami.{Cluster, Exposition, ExpositionUrls, FeatureConfiguration}
import fr.maif.izanami.datastores.{ConfigurationDatastore, StatsDatastore, TenantsDatastore, UsersDatastore}
import fr.maif.izanami.errors.BadBodyFormat
import fr.maif.izanami.errors.CantUpdateOIDCCOnfiguration
import fr.maif.izanami.events.EventOrigin.NormalOrigin
import fr.maif.izanami.mail.MailGunMailProvider
import fr.maif.izanami.mail.MailJetMailProvider
import fr.maif.izanami.mail.SMTPMailProvider
import fr.maif.izanami.models.FullIzanamiConfiguration
import fr.maif.izanami.models.IzanamiConfiguration
import fr.maif.izanami.services.{FeatureService, MaxRights, PostgresTransactionProvider}
import fr.maif.izanami.utils.Done
import fr.maif.izanami.utils.FutureEither
import fr.maif.izanami.utils.syntax.implicits.BetterSyntax
import fr.maif.izanami.web.ConfigurationController.extractRoleWithLoweredRights
import play.api.libs.json.*
import play.api.mvc.Action
import play.api.mvc.AnyContent
import play.api.mvc.BaseController
import play.api.mvc.ControllerComponents

import scala.concurrent.ExecutionContext
import fr.maif.izanami.models.IzanamiMode
import play.api.Logger

import scala.concurrent.Future

class ConfigurationController(
    val controllerComponents: ControllerComponents,
    val adminAuthAction: AdminAuthAction,
    val featureService: FeatureService,
    val statsDatastore: StatsDatastore,
    configurationDatastore: ConfigurationDatastore,
    tenantsDatastore: TenantsDatastore,
    transactionProvider: PostgresTransactionProvider,
    usersDatastore: UsersDatastore,
    cluster: Cluster,
    exposition: Exposition,
    featureConfiguration: FeatureConfiguration,
    expossitionUrls: ExpositionUrls
)(implicit val ec: ExecutionContext)
    extends BaseController {
  val logger = Logger("ConfigurationController")

  def readStats(): Action[AnyContent] = adminAuthAction.async {
    implicit request =>
      {
        statsDatastore.retrieveStats().map(Ok(_))
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
              configurationDatastore.readFullConfiguration()
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

                if (hasOidcPartChanged && !configurationDatastore.isOIDCConfigurationEditable) {
                  FutureEither.failure(CantUpdateOIDCCOnfiguration)
                } else {
                  transactionProvider.executeInTransaction(conn => {
                    (if (rolesToUpdate.nonEmpty) {
                       usersDatastore
                         .logoutConnectedUsersWithRoleIn(
                           rolesToUpdate,
                           conn = Some(conn)
                         )
                     } else {
                       FutureEither.success(Done.done())
                     }).flatMap(_ => {
                      configurationDatastore
                        .updateConfiguration(
                          inputConfigurationWithSecret,
                          userInformation = StandardUserInformation(username=request.user, authentication = request.authentication),
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
    implicit request =>
      val preventOAuthModification = JsBoolean(!configurationDatastore.isOIDCConfigurationEditable)

      configurationDatastore
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

  def readExpositionUrl(): Action[AnyContent] = Action.async { implicit request =>
    val adminUrl = expossitionUrls.backendUrl
      .getOrElse(expossitionUrls.expositionUrl)
    val clusterConfig = cluster

    val futureClientUrlByContexts = if(clusterConfig.mode == IzanamiMode.Leader) {
      if(clusterConfig.workerUrlByContextsAndTenants.isEmpty && clusterConfig.workerUrlByContexts.nonEmpty) {
        logger.error("worker-url-by-contexts property is deprecated, use worker-url-by-contexts-and-tenants instead")
        val r = tenantsDatastore.readTenants().map(ts => {
          ts.map(_.name).map(tenantName => (tenantName -> clusterConfig.workerUrlByContexts)).toMap
        })

        r
      } else {
        Future.successful(clusterConfig.workerUrlByContextsAndTenants)
      }
      
    }  else {
      Future.successful(Map[String, Map[String, String]]())
    }

    futureClientUrlByContexts.map(urls => {
      Ok(Json.obj("url" -> adminUrl, "clientUrlByContexts" -> Json.toJson(urls)))
    })
    
    
  }

  def availableIntegrations(): Action[AnyContent] = Action.async {
    implicit request =>
      val isWasmPresent =
        configurationDatastore.readWasmConfiguration().isDefined
      configurationDatastore
        .readFullConfiguration()
        .toResult(c => {
          Ok(
            Json.obj(
              "wasmo" -> isWasmPresent,
              "oidc" -> c.oidcConfiguration.exists(_.enabled),
              "forceLegacy" -> featureConfiguration.forceLegacy,
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
