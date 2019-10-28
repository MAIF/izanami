package controllers

import controllers.actions.AuthContext
import domains.AuthInfo
import domains.user.{User, UserNoPasswordInstances}
import env.Env
import play.api.libs.json.{JsObject, JsValue, Json}
import play.api.mvc._

class HomeController(_env: Env, AuthAction: ActionBuilder[AuthContext, AnyContent], cc: ControllerComponents)
    extends AbstractController(cc) {

  lazy val enabledUserManagement: Boolean = _env.izanamiConfig.filter match {
    case _: env.Default => true
    case _              => false
  }
  lazy val baseURL: String = _env.baseURL
  lazy val logout: String = if (_env.izanamiConfig.logout.url.startsWith("http")) {
    _env.izanamiConfig.logout.url
  } else {
    s"$baseURL${_env.izanamiConfig.logout.url}"
  }

  val p       = getClass.getPackage
  val version = p.getImplementationVersion

  def index() = AuthAction { ctx =>
    ctx.auth match {
      case Some(_) =>
        Ok(
          views.html
            .index(_env, baseURL, logout, enabledUserManagement, toJson(ctx.auth), version)
        )
      case None =>
        Redirect(s"$baseURL/login")
    }
  }

  def login() = AuthAction { ctx =>
    Ok(views.html.index(_env, baseURL, logout, enabledUserManagement, toJson(ctx.auth), version))
  }

  def otherRoutes(anyPath: String) = index()

  private def toJson(auth: Option[AuthInfo]): JsValue = auth match {
    case Some(u: User) => UserNoPasswordInstances.format.writes(u).as[JsObject] - "id"
    case _             => Json.obj()
  }
}
