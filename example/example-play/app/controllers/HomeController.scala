package controllers

import env.{AppConfig, Env}
import play.api.libs.json.{JsError, Json}
import play.api.mvc.{AbstractController, ControllerComponents, Cookie, Cookies}

case class LoginForm(email: String)
object LoginForm {
  implicit val format = Json.format[LoginForm]
}

class HomeController(env: Env, config: AppConfig, val cc: ControllerComponents) extends AbstractController(cc) {

  def index() = Action {
    config.front match {
      case "react" =>
        Ok(views.html.index(env))
      case _ =>
        Ok(views.html.indexangular(env))
    }
  }

  def indexOtherRoutes(path: String) = index()

  def login() = Action(parse.json) { req =>
    req.body
      .validate[LoginForm]
      .fold(
        e => BadRequest(JsError.toJson(e)),
        form => Ok(Json.obj()).withCookies(Cookie("clientId", form.email))
      )

  }

}
