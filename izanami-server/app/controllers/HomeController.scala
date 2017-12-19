package controllers

import controllers.actions.AuthContext
import domains.AuthInfo
import domains.user.User
import env.Env
import play.api.libs.json.{JsValue, Json}
import play.api.mvc._

class HomeController(_env: Env,
                     AuthAction: ActionBuilder[AuthContext, AnyContent],
                     cc: ControllerComponents)
    extends AbstractController(cc) {

  lazy val enabledUserManagement: Boolean = _env.izanamiConfig.filter match {
    case default: env.Default => true
    case _                    => false
  }
  lazy val logout = _env.izanamiConfig.logout.url

  def index() = AuthAction { ctx =>
    ctx.auth match {
      case Some(auth) =>
        Ok(
          views.html
            .index(_env, logout, enabledUserManagement, toJson(ctx.auth)))
      case None =>
        Redirect("/login")
    }
  }

  def login() = AuthAction { ctx =>
    Ok(views.html.index(_env, logout, enabledUserManagement, toJson(ctx.auth)))
  //Ok(views.html.login(_env))
  }

  def otherRoutes(anyPath: String) = index()

  private def toJson(auth: Option[AuthInfo]): JsValue = auth match {
    case Some(u: User) => Json.toJson(u)
    case _             => Json.obj()
  }
}
