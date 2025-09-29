package fr.maif.izanami.web

import controllers.Assets
import fr.maif.izanami.env.Env
import play.api.mvc.{Action, AnyContent, BaseController, ControllerComponents}

class FrontendController(val assets: Assets, val controllerComponents: ControllerComponents)(implicit val env: Env) extends BaseController {

  def headers: List[(String, String)] = List(
    "Access-Control-Allow-Origin" -> "*",
    "Access-Control-Allow-Methods" -> "GET, POST, OPTIONS, DELETE, PUT",
    "Access-Control-Max-Age" -> "3600",
    "Access-Control-Allow-Headers" -> "Origin, Content-Type, Accept, Authorization, Izanami-Client-Id, Izanami-Client-Secret",
    "Access-Control-Allow-Credentials" -> "true"
  )

  def rootOptions: Action[AnyContent] = options("/")

  def options(url: String): Action[AnyContent] = Action { request =>
    NoContent.withHeaders(headers: _*)
  }
    def index: Action[AnyContent] = assets.at("index.html")

    def assetOrDefault(resource: String): Action[AnyContent] = /*if (resource.startsWith("/api")){
      Action.async(r => errorHandler.onClientError(r, NOT_FOUND, "Not found"))
    } else {*/
      if (resource.contains(".")) assets.at(resource) else index
    //}
}
