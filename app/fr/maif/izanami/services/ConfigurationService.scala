package fr.maif.izanami.services

import fr.maif.izanami.{OpenId, Wasmo}
import fr.maif.izanami.datastores.{ApiKeyDatastore, ConfigurationDatastore, ProjectsDatastore, TenantsDatastore, WebhooksDatastore}
import fr.maif.izanami.events.EventOrigin
import fr.maif.izanami.models.{FullIzanamiConfiguration, Tenant}
import fr.maif.izanami.utils.FutureEither
import fr.maif.izanami.utils.syntax.implicits.BetterFuture
import fr.maif.izanami.web.UserInformation
import io.otoroshi.wasm4s.scaladsl.WasmoSettings
import io.vertx.sqlclient.SqlConnection

import java.util.UUID
import scala.concurrent.{ExecutionContext, Future}

class ConfigurationService(
                            datastore: ConfigurationDatastore,
                            tenantDatastore: TenantsDatastore,
                            projectDatastore: ProjectsDatastore,
                            apiKeyDatastore: ApiKeyDatastore,
                            webhooksDatastore: WebhooksDatastore,
                            maybeOidcConfig: Option[OpenId],
                            wasmoConf: Wasmo)(implicit ec: ExecutionContext) {
  def readFullConfiguration(): FutureEither[FullIzanamiConfiguration] = datastore.readFullConfiguration()

  /** Updates OIDC rights roles to keep only existing stuff. This is used if
   * existing oidc configuration references non existing project / keys /
   * webhooks / tenants. This methods verify existence of everything listed in
   * oidc configuration and remove parts that references non existing stuff.
   *
   * @param rights
   * configuration to update
   * @return
   * updated configuration
   */
  def updateOIDCRightByRolesIfNeeded(
                                      rights: Map[String, CompleteRightsWithMaxRights]
                                    ): FutureEither[Map[String, CompleteRightsWithMaxRights]] = {
    case class TenantItemsToDelete(
                                    projects: Set[String],
                                    keys: Set[String],
                                    webhooks: Set[String]
                                  )

    def deleteNeededItems(
                           rights: Map[String, CompleteRightsWithMaxRights],
                           tenants: Set[String],
                           tenantItems: Map[String, TenantItemsToDelete]
                         ): Map[String, CompleteRightsWithMaxRights] = {
      val rightWithoutTenants =
        rights.view.mapValues(_.removeTenantsRights(tenants)).toMap
      tenantItems.foldLeft(rightWithoutTenants) {
        case (r, (tenant, items)) => {
          r.view
            .mapValues(
              _.removeKeyRights(tenant, items.keys)
                .removeProjectRights(tenant, items.projects)
                .removeWebhookRights(tenant, items.webhooks)
            )
            .toMap
        }
      }
    }

    val tenants: Set[String] = rights.values.flatMap(_.tenants.keySet).toSet
    tenantDatastore
      .readTenants()
      .mapToFEither
      .flatMap(ts => {
        val setTenants = ts.map(_.name).toSet
        val tenantsToDelete = tenants.diff(setTenants)
        val tenantsToKeep = tenants.diff(tenantsToDelete)

        val tenantItemsToDelete =
          tenantsToKeep.foldLeft(FutureEither.success(Map(): Map[
            String,
            TenantItemsToDelete
          ]))((acc, t) => {
            acc.flatMap(map => {
              require(Tenant.isTenantValid(t))
              val tenantRights = rights.values.map(_.tenants(t))
              (for (
                existingProjects <- projectDatastore.checkProjectExistence(t, tenantRights.flatMap(t => t.projects.keySet).toSet)
                  .map(projectExistenceMap => projectExistenceMap.filter((_, exist) => exist).keys.toSet);
                existingKeys <- apiKeyDatastore.checkApiKeyExistence(t, tenantRights.flatMap(t => t.keys.keySet).toSet)
                  .map(keyExistenceMap => keyExistenceMap.filter((_, exist) => exist).keys.toSet);
                existingWebhooks <- webhooksDatastore.checkWebhookExistence(t, tenantRights.flatMap(t => t.webhooks.keySet).toSet)
                  .map(webhookExistenceMap => webhookExistenceMap.filter((_, exist) => exist).keys.toSet)
              ) yield {
                val completeRights = rights.values
                val projectToDelete = completeRights
                  .flatMap(_.tenants(t).projects.keySet.diff(existingProjects))
                  .toSet
                val keyToDelete = tenantRights.flatMap(t => t.keys.keySet.diff(existingKeys))
                  .toSet
                val webhookToDelete = completeRights
                  .flatMap(_.tenants(t).webhooks.keySet.diff(existingWebhooks))
                  .toSet
                map + (t -> TenantItemsToDelete(
                  projects = projectToDelete,
                  keys = keyToDelete,
                  webhooks = webhookToDelete
                ))
              }).mapToFEither
            })
          })

        tenantItemsToDelete.flatMap(toDelete => {
          val newRights = deleteNeededItems(rights, tenantsToDelete, toDelete)
          datastore.updateOAuthRightByRole(newRights).map(_ => newRights)
        })
      })
  }

  def isOIDCConfigurationEditable: Boolean = {
    (for (
      oidcConfig <- maybeOidcConfig;
      _ <- oidcConfig.clientId;
      _ <- oidcConfig.clientSecret;
      _ <- oidcConfig.authorizeUrl;
      _ <- oidcConfig.tokenUrl
    ) yield false)
      .getOrElse(true)
  }

  def readWasmConfiguration(): Option[WasmoSettings] = {
    for (
      url <- wasmoConf.url;
      clientId <- wasmoConf.clientId;
      clientSecret <- wasmoConf.clientSecret
    ) yield WasmoSettings(url.toString, clientId, clientSecret = clientSecret)
  }

  def readId(): Future[UUID] = datastore.readId()

  def updateConfiguration(
                           newConfig: FullIzanamiConfiguration,
                           userInformation: UserInformation,
                           origin: EventOrigin,
                           conn: Option[SqlConnection] = None
                         ): FutureEither[FullIzanamiConfiguration] = datastore.updateConfiguration(newConfig, userInformation, origin, conn)
}

object ConfigurationService {
  def hasOIDCConfChanged(
                          oldConfig: FullIzanamiConfiguration,
                          newConfig: FullIzanamiConfiguration
                        ): Boolean = {
    oldConfig.oidcConfiguration != newConfig.oidcConfiguration
  }
}
