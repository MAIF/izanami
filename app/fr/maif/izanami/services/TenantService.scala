package fr.maif.izanami.services

import fr.maif.izanami.datastores.{
  ProjectsDatastore,
  TagsDatastore,
  TenantsDatastore
}
import fr.maif.izanami.errors.GenericBadRequest
import fr.maif.izanami.models.RightLevel.superiorOrEqualLevels
import fr.maif.izanami.models.*
import fr.maif.izanami.utils.{Done, FutureEither}
import fr.maif.izanami.utils.syntax.implicits.BetterFuture
import fr.maif.izanami.web.UserInformation

import scala.concurrent.{ExecutionContext, Future}

class TenantService(
    datastore: => TenantsDatastore,
    projectDatastore: => ProjectsDatastore,
    tagDatastore: => TagsDatastore
)(implicit ec: ExecutionContext) {
  def updateTenant(
      name: String,
      updateRequest: TenantCreationRequest
  ): FutureEither[Done] = {
    if (name != updateRequest.name) {
      FutureEither.failure(
        GenericBadRequest("Modification of a tenant name is not permitted")
      )
    } else {
      datastore.updateTenant(name = name, updateRequest = updateRequest)
    }
  }

  def createTenant(
      tenantCreationRequest: TenantCreationRequest,
      user: UserInformation
  ): FutureEither[Tenant] = {
    datastore.createTenant(
      tenantCreationRequest = tenantCreationRequest,
      user = user
    )
  }

  def readTenants(
      user: UserWithTenantRights,
      right: Option[RightLevel]
  ): Future[Seq[SimpleTenant]] = {
    if (user.admin) {
      datastore
        .readTenants()
    } else {
      val minimumRightLevel = right.getOrElse(RightLevel.Read)
      val allowedTenants = Option(user.tenantRights)
        .map(m =>
          m.filter { case (name, level) =>
            superiorOrEqualLevels(minimumRightLevel).contains(level)
          }.keys
            .toSet
        )
        .getOrElse(Set())
      datastore
        .readTenantsFiltered(allowedTenants)
    }

  }

  def deleteTenant(
      name: String,
      user: UserInformation
  ): FutureEither[Done] = {
    datastore.deleteTenant(name = name, user = user)
  }

  def readTenant(name: String, user: UserInformation)
      : FutureEither[SimpleTenantWithProjectAndTags] = {
    datastore.readTenantByName(name)
      .flatMap(tenant => {
        for (
          projects <- projectDatastore.readTenantProjectForUser(
            tenant.name,
            user.username
          ).mapToFEither;
          tags <- tagDatastore.readTags(tenant.name).mapToFEither
        ) yield {
          SimpleTenantWithProjectAndTags(
            name = tenant.name,
            projects = projects,
            description = tenant.description,
            tags = tags
          )
        }
      })
  }

}
