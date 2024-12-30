package fr.maif.izanami.web

import fr.maif.izanami.env.Env
import fr.maif.izanami.errors.MissingOIDCConfigurationError
import fr.maif.izanami.models.OIDC
import fr.maif.izanami.models.OIDCConfiguration
import fr.maif.izanami.models.Rights
import fr.maif.izanami.models.User
import fr.maif.izanami.models.User.userRightsWrites
import fr.maif.izanami.utils.syntax.implicits.BetterSyntax
import pdi.jwt.JwtJson
import pdi.jwt.JwtOptions
import play.api.libs.json.JsObject
import play.api.libs.json.Json
import play.api.libs.ws.WSAuthScheme
import play.api.mvc.Cookie.SameSite
import play.api.mvc._

import java.util.Base64
import scala.concurrent.ExecutionContext
import scala.concurrent.Future

class LoginController(
    val env: Env,
    val controllerComponents: ControllerComponents,
    sessionAuthAction: AuthenticatedSessionAction
) extends BaseController {
  implicit val ec: ExecutionContext = env.executionContext;

  def openIdConnect: Action[AnyContent] = Action {
    env.datastores.configuration.readOIDCConfiguration() match {
      case None                                                                             => MissingOIDCConfigurationError().toHttpResponse
      case Some(OIDCConfiguration(clientId, _, authorizeUrl, _, redirectUrl, _, _, scopes)) => {
        val hasOpenIdInScope = scopes.exists(s => s.equalsIgnoreCase("openid"))
        val actualScope      = (if (!hasOpenIdInScope) scopes + "openid" else scopes).mkString("%20")
        Redirect(
          s"${authorizeUrl}?scope=$actualScope&client_id=${clientId}&response_type=code&redirect_uri=${redirectUrl}"
        )
      }
    }
  }

  def openIdCodeReturn: Action[AnyContent] = Action.async { implicit request =>
    // TODO handle refresh_token
    {
      for (
        code                                                                                           <- request.body.asJson.flatMap(json => (json \ "code").get.asOpt[String]);
        OIDCConfiguration(clientId, clientSecret, _, tokenUrl, redirectUrl, usernameField, emailField, _) <-
          env.datastores.configuration.readOIDCConfiguration()
      )
        yield env.Ws
          .url(tokenUrl)
          .withAuth(clientId, clientSecret, WSAuthScheme.BASIC)
          .withHttpHeaders(("content-type", "application/x-www-form-urlencoded"))
          .post(Map("grant_type" -> "authorization_code", "code" -> code, "redirect_uri" -> redirectUrl))
          .flatMap(r => {
            val maybeToken = (r.json \ "id_token").get.asOpt[String]
            maybeToken.fold(Future(InternalServerError(Json.obj("message" -> "Failed to retrieve token"))))(token => {
              val maybeClaims = JwtJson.decode(token, JwtOptions(signature = false))
              maybeClaims.toOption
                .flatMap(claims => Json.parse(claims.content).asOpt[JsObject])
                .flatMap(json => {
                  for (
                    username <- (json \ usernameField).asOpt[String];
                    email    <- (json \ emailField).asOpt[String]
                  )
                    yield env.datastores.users
                      .findUser(username)
                      .flatMap(maybeUser =>
                        maybeUser
                          .fold(
                            env.datastores.users
                              .createUser(User(username, email = email, userType = OIDC).withRights(Rights.EMPTY))
                          )(user => Future(Right(user.withRights(Rights.EMPTY))))
                          .map(either => either.map(_ => username))
                      )
                })
                .getOrElse(Future(Left(InternalServerError(Json.obj("message" -> "Failed to read token claims")))))
                .flatMap {
                  // TODO refactor this whole method
                  case Right(username) => env.datastores.users.createSession(username).map(id => Right(id))
                  case Left(err)       => Future(Left(err))
                }
                .map(maybeId => {
                  maybeId
                    .map(id => {
                      env.jwtService.generateToken(id)
                    })
                    .map(token =>
                      NoContent
                        .withCookies(
                          Cookie(name = "token", value = token, httpOnly = false, sameSite = Some(SameSite.Strict))
                        )
                    )
                    .getOrElse(InternalServerError(Json.obj("message" -> "Failed to read token claims")))
                })
            })
          })
    }.getOrElse(Future(InternalServerError(Json.obj("message" -> "Failed to read token claims"))))
  }

  def logout(): Action[AnyContent] = sessionAuthAction.async { implicit request =>
    env.datastores.users
      .deleteSession(request.sessionId)
      .map(_ => {
        NoContent.withCookies(
          Cookie(
            name = "token",
            value = "",
            httpOnly = false,
            sameSite = Some(SameSite.Strict),
            maxAge = Some(0)
          )
        )
      })
  }

  def login(rights: Boolean = false): Action[AnyContent] = Action.async { implicit request =>
    request.headers
      .get("Authorization")
      .map(header => header.split("Basic "))
      .filter(splitted => splitted.length == 2)
      .map(splitted => splitted(1))
      .map(header => {
        Base64.getDecoder.decode(header.getBytes)
      })
      .map(bytes => new String(bytes))
      .map(header => header.split(":"))
      .filter(arr => arr.length == 2) match {
      case Some(Array(username, password, _*)) =>
        env.datastores.users.isUserValid(username, password).flatMap {
          case None       => Future.successful(Forbidden(Json.obj("message" -> "Incorrect credentials")))
          case Some(user) =>
            for {
              _         <- if (user.legacy) env.datastores.users.updateLegacyUser(username, password)
                           else Future.successful(())
              sessionId <- env.datastores.users.createSession(user.username)
              token     <- env.jwtService.generateToken(sessionId).future
              response  <- if (rights) env.datastores.users.findUserWithCompleteRights(user.username).map {
                             case Some(user) => Ok(Json.toJson(user)(userRightsWrites))
                             case None       => InternalServerError(Json.obj("message" -> "Failed to read rights"))
                           }
                           else Future.successful(Ok)
            } yield response.withCookies(
              Cookie(
                name = "token",
                value = token,
                httpOnly = false,
                sameSite = Some(SameSite.Strict),
                maxAge = Some(env.configuration.get[Int]("app.sessions.ttl") - 120)
              )
            )
        }
      case _                                   => Future(Unauthorized(Json.obj("message" -> "Missing credentials")))
    }
  }
}
