package fr.maif.izanami.web

import fr.maif.izanami.env.Env
import play.api.libs.json.Json
import play.api.mvc.{Action, AnyContent, BaseController, ControllerComponents}

import scala.concurrent.ExecutionContext

class SearchController (
                         val env: Env,
                         val controllerComponents: ControllerComponents,
                         val authAction: AuthenticatedAction
                       ) extends BaseController {
  implicit val ec: ExecutionContext = env.executionContext
 def searchEntities (query: String): Action[AnyContent] = authAction.async { implicit request =>
    env.datastores.searchQueries.searchEntities(query).map(names =>Ok(Json.toJson(names)))
  }
  def searchEntitiesByTenant (tenant: String , query: String): Action[AnyContent] = authAction.async { implicit request =>
    env.datastores.searchQueries.searchEntitiesByTenant(tenant, query).map(names => Ok(Json.toJson(names)))
  }

}
