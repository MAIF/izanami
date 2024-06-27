package fr.maif.izanami.web

import fr.maif.izanami.env.Env
import play.api.libs.json.Json
import play.api.mvc._

import scala.concurrent.{ExecutionContext, Future}

class SearchController(
    val env: Env,
    val controllerComponents: ControllerComponents,
    val tenantAuthAction: TenantAuthActionFactory,
    val userDetailedAuthAction: DetailledAuthAction
) extends BaseController {
  implicit val ec: ExecutionContext = env.executionContext

  def searchEntities(query: String): Action[AnyContent] = userDetailedAuthAction.async {
    implicit request: UserRequestWithCompleteRights[AnyContent] =>
      val userTenants = request.user.rights.tenants
      if (userTenants.nonEmpty) {
        env.datastores.searchQueries
          .searchEntities(userTenants, query)
          .map(entities =>
            entities.fold(
              err => Results.Status(err.status)(Json.toJson(err)),
              entities => Ok(Json.toJson(entities))
            )
          )

      } else {
        Future.successful(Forbidden(Json.obj("message" -> "User has no tenants rights ")))
      }

  }

  def searchEntitiesByTenant(tenant: String, query: String): Action[AnyContent] = userDetailedAuthAction.async {
    implicit request: UserRequestWithCompleteRights[AnyContent] =>
      val userTenants = request.user.rights.tenants

      if (userTenants.nonEmpty && userTenants.contains(tenant)) {
        val filteredTenants = userTenants.filter { case (t, _) => t == tenant }
        env.datastores.searchQueries
          .searchEntities(filteredTenants, query)
          .map(entities =>
            entities.fold(
              err => Results.Status(err.status)(Json.toJson(err)),
              entities => Ok(Json.toJson(entities))
            )
          )
      } else {
        Future.successful(Forbidden(Json.obj("message" -> s"User has no rights for this tenant ${tenant}")))
      }
  }

}
