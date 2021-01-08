package controllers

import controllers.actions.AuthContext
import domains.auth.AuthInfo
import domains.user.{User, UserNoPasswordInstances}
import env.{Env, Oauth2Config}
import play.api.libs.json.{JsObject, JsValue, Json}
import play.api.mvc._
import izanami.BuildInfo

class HomeController(_env: Env, AuthAction: ActionBuilder[AuthContext, AnyContent], cc: ControllerComponents)
    extends AbstractController(cc) {

  private val maybeOauth2Config: Option[Oauth2Config] = _env.izanamiConfig.oauth2.filter(_.enabled)

  private lazy val userManagementMode: String =
    (_env.izanamiConfig.filter, maybeOauth2Config) match {
      case (_, Some(c)) if c.izanamiManagedUser => "OAuth"
      case (_: env.Default, _)                  => "Izanami"
      case _                                    => "None"
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

  private val version: String = BuildInfo.version
  private val commit: String  = BuildInfo.gitCommitId

  def index() = AuthAction { ctx =>
    ctx.auth match {
      case Some(_) =>
        Ok(
          views.html
            .index(
              _env,
              baseURL,
              logoutUrl,
              confirmationDialog,
              userManagementMode,
              enabledApikeyManagement,
              toJson(ctx.auth),
              version,
              commit
            )
        )
      case None =>
        Redirect(s"$baseURL/login")
    }
  }

  def login() = AuthAction { ctx =>
    maybeOauth2Config match {
      case Some(_) =>
        Redirect(controllers.routes.OAuthController.appLoginPage())
      case _ =>
        Ok(
          views.html.index(
            _env,
            baseURL,
            logoutUrl,
            confirmationDialog,
            userManagementMode,
            enabledApikeyManagement,
            toJson(ctx.auth),
            version,
            commit
          )
        )
    }
  }

  def logout() = Action { _ =>
    maybeOauth2Config match {
      case Some(_) =>
        Redirect(controllers.routes.OAuthController.appLogout())
      case _ =>
        Redirect(s"${_env.baseURL}/login").withCookies(Cookie(name = _env.cookieName, value = "", maxAge = Some(0)))
    }
  }

  def otherRoutes(anyPath: String) = index()

  private def toJson(auth: Option[AuthInfo.Service]): JsValue = auth match {
    case Some(u: User) => UserNoPasswordInstances.format.writes(u).as[JsObject] - "id"
    case _             => Json.obj()
  }
}
