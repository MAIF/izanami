package controllers

import akka.http.scaladsl.util.FastFuture
import akka.util.ByteString
import com.auth0.jwt.JWT
import com.auth0.jwt.algorithms.Algorithm
import controllers.dto.error.ApiErrors
import domains.OAuthModule
import domains.errors.IzanamiErrors
import domains.user.User
import env.{Env, Oauth2Config}
import libs.logs.Logger
import play.api.libs.json.{JsValue, Json}
import play.api.libs.ws.DefaultBodyWritables.writeableOf_urlEncodedSimpleForm
import play.api.libs.ws.WSResponse
import play.api.mvc.{AbstractController, AnyContent, ControllerComponents, Cookie, Request, Results}
import zio.{IO, Runtime, ZIO}
import org.apache.commons.codec.binary.{Base64 => ApacheBase64}

import scala.util.Try

class OAuthController(_env: Env, cc: ControllerComponents)(implicit R: Runtime[OAuthModule])
    extends AbstractController(cc) {

  import libs.http._

  private val mayBeOauth2Config: Option[Oauth2Config] = _env.izanamiConfig.oauth2

  lazy val _config = _env.izanamiConfig.filter match {
    case env.Default(config) => config
    case _                   => throw new RuntimeException("Wrong config")
  }
  lazy val algorithm: Algorithm = Algorithm.HMAC512(_config.sharedKey)

  //FIXME to complete
  def appLoginPageOptions() = Action {
    val headers: Map[String, String] = Map(
      "Access-Control-Allow-Origin"      -> "*",
      "Access-Control-Allow-Credentials" -> "true",
      "Access-Control-Allow-Methods"     -> "GET"
    )
    Results.Ok(ByteString.empty).withHeaders(headers.toSeq: _*)
  }

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
        paCallback(openIdConnectConfig)
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

  def paCallback(
      authConfig: Oauth2Config
  )(implicit request: Request[AnyContent]): ZIO[OAuthModule, IzanamiErrors, User] =
    for {
      _             <- ZIO.fromOption(request.getQueryString("error")).flip.mapError(IzanamiErrors.error)
      code          <- ZIO.fromOption(request.getQueryString("code")).mapError(_ => IzanamiErrors.error("No code :("))
      wsResponse    <- callTokenUrl(code, authConfig)
      t             <- decodeToken(wsResponse, authConfig)
      (user, _)     = t
      _             <- Logger.info(s"User from token $user")
      effectiveUser <- ZIO.fromEither(User.fromOAuth(user, authConfig))
      _             <- Logger.info(s"Oauth user logged with $effectiveUser")
    } yield effectiveUser

  def callTokenUrl(code: String, authConfig: Oauth2Config)(
      implicit request: Request[AnyContent]
  ): ZIO[OAuthModule, IzanamiErrors, WSResponse] = {

    val clientId     = authConfig.clientId
    val clientSecret = Option(authConfig.clientSecret).map(_.trim).filterNot(_.isEmpty)
    val queryParam   = if (authConfig.useCookie) "" else s"?desc=izanami"
    val redirectUri = if (_env.baseURL.startsWith("http")) {
      s"${_env.baseURL}/${controllers.routes.OAuthController.appCallback().url}${queryParam}"
    } else {
      s"${controllers.routes.OAuthController.appCallback().absoluteURL()}${queryParam}"
    }

    for {
      playModule <- ZIO.environment[OAuthModule]
      wsClient   = playModule.wSClient
      response <- ZIO
                   .fromFuture { implicit ec =>
                     val builder = wsClient.url(authConfig.tokenUrl)
                     if (authConfig.useJson) {
                       builder.post(
                         Json.obj(
                           "code"         -> code,
                           "grant_type"   -> "authorization_code",
                           "client_id"    -> clientId,
                           "redirect_uri" -> redirectUri
                         ) ++ clientSecret.map(s => Json.obj("client_secret" -> s)).getOrElse(Json.obj())
                       )
                     } else {
                       builder.post(
                         Map(
                           "code"         -> code,
                           "grant_type"   -> "authorization_code",
                           "client_id"    -> clientId,
                           "redirect_uri" -> redirectUri
                         ) ++ clientSecret.toSeq.map(s => ("client_secret" -> s))
                       )(writeableOf_urlEncodedSimpleForm)
                     }
                   }
                   .refineToOrDie[IzanamiErrors]
    } yield response
  }

  def decodeToken(response: WSResponse,
                  authConfig: Oauth2Config): ZIO[OAuthModule, IzanamiErrors, (JsValue, JsValue)] = {

    val rawToken: JsValue              = response.json
    val jwtVerifier: Option[Algorithm] = None
    if (authConfig.readProfileFromToken && jwtVerifier.isDefined) {
      // FIXME JWSK
      for {
        _ <- Logger.info(s"Token $rawToken")
        accessToken <- ZIO
                        .fromOption((rawToken \ authConfig.accessTokenField).asOpt[String])
                        .mapError(_ => IzanamiErrors.error(Json.stringify(rawToken)))
        algo        = jwtVerifier.get
        tokenHeader = Try(Json.parse(ApacheBase64.decodeBase64(accessToken.split("\\.")(0)))).getOrElse(Json.obj())
        tokenBody   = Try(Json.parse(ApacheBase64.decodeBase64(accessToken.split("\\.")(1)))).getOrElse(Json.obj())
        kid         = (tokenHeader \ "kid").asOpt[String]
        alg         = (tokenHeader \ "alg").asOpt[String].getOrElse("RS256")
        _           <- ZIO(JWT.require(algo).acceptLeeway(10).build().verify(accessToken)).refineToOrDie[IzanamiErrors]

      } yield (tokenBody, rawToken)

    } else {
      for {
        _ <- Logger.info(s"Token $rawToken")
        accessToken <- ZIO
                        .fromOption((rawToken \ authConfig.accessTokenField).asOpt[String])
                        .mapError(_ => IzanamiErrors.error(Json.stringify(rawToken)))
        playModule <- ZIO.environment[OAuthModule]
        wsClient   = playModule.wSClient
        response <- ZIO
                     .fromFuture { implicit ec =>
                       val builder2 = wsClient.url(authConfig.userInfoUrl)
                       if (authConfig.useJson) {
                         builder2.post(
                           Json.obj(
                             "access_token" -> accessToken
                           )
                         )
                       } else {
                         builder2.post(
                           Map(
                             "access_token" -> accessToken
                           )
                         )(writeableOf_urlEncodedSimpleForm)
                       }
                     }
                     .refineToOrDie[IzanamiErrors]
      } yield (response.json, rawToken)
    }
  }

}
