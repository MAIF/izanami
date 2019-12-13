package controllers

import akka.http.scaladsl.util.FastFuture
import akka.util.ByteString
import com.auth0.jwt.JWT
import com.auth0.jwt.algorithms.Algorithm
import controllers.dto.error.ApiErrors
import domains.{AuthInfoModule, AuthorizedPattern, OAuthModule, PlayModule}
import domains.errors.IzanamiErrors
import domains.user.User
import env.{Env, OpenIdConnectConfig}
import play.api.libs.json.{JsObject, JsValue, Json}
import play.api.libs.ws.DefaultBodyWritables.writeableOf_urlEncodedSimpleForm
import play.api.libs.ws.WSResponse
import play.api.mvc.{AbstractController, AnyContent, ControllerComponents, Cookie, Request, Results}
import zio.{IO, Runtime, ZIO}
import org.apache.commons.codec.binary.{Base64 => ApacheBase64}

import scala.util.Try

class OAuthController(_env: Env, cc: ControllerComponents)(implicit R: Runtime[OAuthModule])
    extends AbstractController(cc) {

  import cats.implicits._
  import libs.http._

  private val mayBeOpenIdConnectConfig: Option[OpenIdConnectConfig] = _env.izanamiConfig.openIdConnect

  lazy val _config = _env.izanamiConfig.filter match {
    case env.Default(config) => config
    case _                   => throw new RuntimeException("Wrong config")
  }
  lazy val cookieName           = _config.cookieClaim
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
    mayBeOpenIdConnectConfig match {
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
      case None => BadRequest(Json.toJson(ApiErrors.error("Open Id Connect modulme not configured")))
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
    mayBeOpenIdConnectConfig match {
      case Some(openIdConnectConfig) =>
        paCallback(openIdConnectConfig)
          .map { user =>
            val token: String = User.buildToken(user, _config.issuer, algorithm)
            Redirect(controllers.routes.HomeController.index())
              .withCookies(Cookie(name = cookieName, value = token))
          }
          .mapError { err =>
            BadRequest(Json.toJson(ApiErrors.fromErrors(err.toList)))
          }

      case None => ZIO.succeed(BadRequest(Json.toJson(ApiErrors.error("Open Id Connect modulme not configured"))))
    }
  }

  def paCallback(
      authConfig: OpenIdConnectConfig
  )(implicit request: Request[AnyContent]): ZIO[PlayModule, IzanamiErrors, User] =
    for {
      _          <- ZIO.fromOption(request.getQueryString("error")).flip.mapError(IzanamiErrors.error)
      code       <- ZIO.fromOption(request.getQueryString("code")).mapError(_ => IzanamiErrors.error("No code :("))
      wsResponse <- callTokenUrl(code, authConfig)
      t          <- decodeToken(wsResponse, authConfig)
      (user, _)  = t
    } yield {
      User(
        id = (user \ authConfig.idField).as[String],
        name = (user \ authConfig.nameField)
          .asOpt[String]
          .orElse((user \ "sub").asOpt[String])
          .getOrElse("No Name"),
        email = (user \ authConfig.emailField).asOpt[String].getOrElse("no.name@foo.bar"),
        admin = (user \ authConfig.adminField).asOpt[Boolean].getOrElse(false),
        authorizedPattern = (user \ authConfig.authorizedPatternField)
          .asOpt[String]
          .map(s => AuthorizedPattern(s))
          .getOrElse(AuthorizedPattern(""))
      )
    }

  def callTokenUrl(code: String, authConfig: OpenIdConnectConfig)(
      implicit request: Request[AnyContent]
  ): ZIO[PlayModule, IzanamiErrors, WSResponse] = {

    val clientId     = authConfig.clientId
    val clientSecret = Option(authConfig.clientSecret).map(_.trim).filterNot(_.isEmpty)
    val queryParam   = if (authConfig.useCookie) "" else s"?desc=izanami"
    val redirectUri = if (_env.baseURL.startsWith("http")) {
      s"${_env.baseURL}/${controllers.routes.OAuthController.appCallback().url}${queryParam}"
    } else {
      s"${controllers.routes.OAuthController.appCallback().absoluteURL()}${queryParam}"
    }

    for {
      playModule <- ZIO.environment[PlayModule]
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
                  authConfig: OpenIdConnectConfig): ZIO[PlayModule, IzanamiErrors, (JsValue, JsValue)] = {

    val rawToken: JsValue = response.json
    println(rawToken)
    val accessToken = (rawToken \ authConfig.accessTokenField).as[String]

    val jwtVerifier: Option[Algorithm] = None
    if (authConfig.readProfileFromToken && jwtVerifier.isDefined) {
      // println(accessToken)
      val algo = jwtVerifier.get
      val tokenHeader =
        Try(Json.parse(ApacheBase64.decodeBase64(accessToken.split("\\.")(0)))).getOrElse(Json.obj())
      val tokenBody =
        Try(Json.parse(ApacheBase64.decodeBase64(accessToken.split("\\.")(1)))).getOrElse(Json.obj())
      val kid = (tokenHeader \ "kid").asOpt[String]
      val alg = (tokenHeader \ "alg").asOpt[String].getOrElse("RS256")
      // FIXME JWSK
      for {
        _ <- ZIO(JWT.require(algo).acceptLeeway(10).build().verify(accessToken)).refineToOrDie[IzanamiErrors]

      } yield (tokenBody, rawToken)

    } else {

      for {
        playModule <- ZIO.environment[PlayModule]
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
