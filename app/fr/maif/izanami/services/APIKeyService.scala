package fr.maif.izanami.services

import fr.maif.izanami.datastores.ApiKeyDatastore
import fr.maif.izanami.models.ApiKey
import fr.maif.izanami.utils.FutureEither
import fr.maif.izanami.web.UserInformation
import scala.concurrent.Future
import fr.maif.izanami.utils.Done
import fr.maif.izanami.datastores.UsernameIdentification
import fr.maif.izanami.models.ProjectRightUnit
import fr.maif.izanami.models.ProjectRightLevel
import fr.maif.izanami.models.RightLevel
import fr.maif.izanami.errors.NotEnoughRights
import fr.maif.izanami.utils.syntax.implicits.BetterFuture
import scala.concurrent.ExecutionContext
import fr.maif.izanami.errors.ApiKeyDoesNotExist

class APIKeyService(
    datastore: ApiKeyDatastore,
    rightService: RightService
)(implicit ec: ExecutionContext) {

  def createAPIKey(
      tenant: String,
      key: ApiKey,
      user: UserInformation
  ): FutureEither[ApiKey] = {
    rightService.hasRightFor(
      tenant,
      userIdentification =
        UsernameIdentification(user.username),
      rights = key.projects
        .map(p =>
          ProjectRightUnit(
            project = ProjectNameIdentification(p),
            rightLevel = ProjectRightLevel.Write
          )
        ),
      tenantLevel = if (key.admin) Some(RightLevel.Admin) else None
    ).mapToFEither
      .flatMap {
        case Some(u) => datastore.createApiKey(
            key
              .withNewSecret()
              .withNewClientId(),
            user
          )
        case None =>
          FutureEither.failure[ApiKey](NotEnoughRights(s"${user.username} does not have right on one or more of these projects : ${key.projects
              .mkString(",")} or is not tenant admin (if admin key was required)"))
      }
  }

  def readAPIKey(tenant: String, name: String): Future[Option[ApiKey]] = {
    datastore.readApiKey(tenant = tenant, name = name)
  }

  def updateAPIKey(
      tenant: String,
      oldName: String,
      newKey: ApiKey,
      user: UserInformation
  ): FutureEither[Done] = {

    readAPIKey(tenant, name = oldName)
      .mapToFEither
      .flatMap {
        case None => {

          FutureEither.failure(ApiKeyDoesNotExist(oldName))
        }
        case Some(key) => {

          FutureEither.success(key)
        }
      }.map(oldKey => {

        (
          newKey.projects.filter(!oldKey.projects.contains(_)),
          oldKey.admin != newKey.admin
        )
      })
      .flatMap {
        case (newProjects, false) if newProjects.isEmpty => {

          FutureEither.success(true)
        }
        case (newProjects, adminChanged) => {

          rightService
            .hasRightFor(
              tenant,
              userIdentification =
                UsernameIdentification(user.username),
              rights = newProjects.map(p =>
                ProjectRightUnit(
                  project = ProjectNameIdentification(p),
                  rightLevel = ProjectRightLevel.Write
                )
              ),
              tenantLevel =
                if (adminChanged) Some(RightLevel.Admin) else None
            ).map(_.isDefined).mapToFEither
        }
      }
      .flatMap {
        case true => {
          datastore.updateApiKey(tenant, oldName, newKey = newKey)
        }
        case false => {

          FutureEither.failure(NotEnoughRights(
            s"${user.username} does not have right on key projects"
          ))
        }
      }
  }

  def readVisibleAPIKeysForUser(
      tenant: String,
      username: String
  ): Future[List[ApiKey]] = {
    datastore.readApiKeys(tenant = tenant, username = username)
  }

  def deleteApiKey(
      tenant: String,
      name: String
  ): FutureEither[String] = {
    datastore.deleteApiKey(tenant = tenant, name = name)
  }
}
