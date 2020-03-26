package controllers

import akka.util.ByteString
import com.auth0.jwt.algorithms.Algorithm
import controllers.dto.error.ApiErrors
import domains.auth.OAuthModule
import domains.auth.Oauth2Service
import domains.user.User
import env.{Env, Oauth2Config}
import play.api.libs.json.Json
import play.api.mvc.{AbstractController, ControllerComponents, Cookie}
import zio.{Runtime, ZIO}

class OAuthController(_env: Env, mayBeOauth2Config: Option[Oauth2Config], cc: ControllerComponents)(
    implicit R: Runtime[OAuthModule]
) extends AbstractController(cc) {

  import libs.http._

  lazy val _config = _env.izanamiConfig.filter match {
    case env.Default(config) => config
    case _                   => throw new RuntimeException("Wrong config")
  }
  lazy val algorithm: Algorithm = Algorithm.HMAC512(_config.sharedKey)

  def appLoginPage() = Action { implicit request =>
    mayBeOauth2Config match {
      case Some(openIdConnectConfig) =>
        val clientId     = openIdConnectConfig.clientId
        val responseType = "code"
        val scope        = openIdConnectConfig.scope.map(s => s"scope=${s}&").getOrElse("")
        val claims       = Option(openIdConnectConfig.claims).filterNot(_.isEmpty).map(v => s"claims=$v&").getOrElse("")
        val queryParam   = if (openIdConnectConfig.useCookie) "" else s"?desc=izanami"
        val redirectUri = if (_env.baseURL.startsWith("http")) {
          s"${_env.baseURL}/${controllers.routes.OAuthController.appCallback().url}${queryParam}"
        } else {
          s"${controllers.routes.OAuthController.appCallback().absoluteURL()}${queryParam}"
        }
        val loginUrl =
          s"${openIdConnectConfig.loginUrl}?${scope}&${claims}client_id=$clientId&response_type=$responseType&redirect_uri=$redirectUri"
        Redirect(loginUrl)
      case None => BadRequest(Json.toJson(ApiErrors.error("Open Id Connect module not configured")))
    }
  }

  def appLogout() = Action { req =>
    val redirectToOpt: Option[String] = req.queryString.get("redirectTo").map(_.last)
    redirectToOpt match {
      case Some(redirectTo) =>
        Redirect(redirectTo).discardingCookies()
      case _ =>
        BadRequest(Json.toJson(ApiErrors.error("Missing parameters")))
    }
  }

  def appCallback() = Action.asyncZio[OAuthModule] { implicit ctx =>
    mayBeOauth2Config match {
      case Some(openIdConnectConfig) =>
        Oauth2Service
          .paCallback(_env.baseURL, openIdConnectConfig)
          .map { user =>
            Redirect(controllers.routes.HomeController.index())
              .withCookies(Cookie(name = _env.cookieName, value = User.buildToken(user, _config.issuer, algorithm)))
          }
          .mapError { err =>
            BadRequest(Json.toJson(ApiErrors.fromErrors(err.toList)))
          }

      case None => ZIO.succeed(BadRequest(Json.toJson(ApiErrors.error("Open Id Connect module not configured"))))
    }
  }

}
