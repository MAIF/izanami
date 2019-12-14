package controllers

import controllers.actions.AuthContext
import domains.AuthInfo
import domains.user.{User, UserNoPasswordInstances}
import env.Env
import play.api.libs.json.{JsObject, JsValue, Json}
import play.api.mvc._

class HomeController(_env: Env, AuthAction: ActionBuilder[AuthContext, AnyContent], cc: ControllerComponents)
    extends AbstractController(cc) {

  private lazy val enabledUserManagement: Boolean =
    (_env.izanamiConfig.filter, _env.izanamiConfig.oauth2) match {
      case (_, Some(_))        => false
      case (_: env.Default, _) => true
      case _                   => false
    }
  private lazy val enabledApikeyManagement: Boolean = _env.izanamiConfig.filter match {
    case _: env.Default => true
    case _              => false
  }
  private lazy val baseURL: String             = _env.baseURL
  private lazy val confirmationDialog: Boolean = _env.izanamiConfig.confirmationDialog
  private lazy val logoutUrl: String = if (_env.izanamiConfig.logout.url.startsWith("http")) {
    _env.izanamiConfig.logout.url
  } else {
    s"$baseURL${_env.izanamiConfig.logout.url}"
  }

  private val p: Package      = getClass.getPackage
  private val version: String = p.getImplementationVersion

  def index() = AuthAction { ctx =>
    ctx.auth match {
      case Some(_) =>
        Ok(
          views.html
            .index(_env,
                   baseURL,
                   logoutUrl,
                   confirmationDialog,
                   enabledUserManagement,
                   enabledApikeyManagement,
                   toJson(ctx.auth),
                   version)
        )
      case None =>
        Redirect(s"$baseURL/login")
    }
  }

  def login() = AuthAction { ctx =>
    _env.izanamiConfig.oauth2 match {
      case Some(_) =>
        Redirect(controllers.routes.OAuthController.appLoginPage())
      case _ =>
        Ok(
          views.html.index(_env,
                           baseURL,
                           logoutUrl,
                           confirmationDialog,
                           enabledUserManagement,
                           enabledApikeyManagement,
                           toJson(ctx.auth),
                           version)
        )
    }
  }

  def logout() = Action { _ =>
    _env.izanamiConfig.oauth2 match {
      case Some(_) =>
        Redirect(controllers.routes.OAuthController.appLogout())
      case _ =>
        Redirect(s"${_env.baseURL}/login").withCookies(Cookie(name = _env.cookieName, value = "", maxAge = Some(0)))
    }
  }

  def otherRoutes(anyPath: String) = index()

  private def toJson(auth: Option[AuthInfo]): JsValue = auth match {
    case Some(u: User) => UserNoPasswordInstances.format.writes(u).as[JsObject] - "id"
    case _             => Json.obj()
  }
}
