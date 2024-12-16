package fr.maif.izanami.web

import fr.maif.izanami.env.Env
import fr.maif.izanami.errors.MissingOIDCConfigurationError
import fr.maif.izanami.models.User.userRightsWrites
import fr.maif.izanami.models.{OAuth2Configuration, OIDC, Rights, User}
import fr.maif.izanami.utils.syntax.implicits.BetterSyntax
import pdi.jwt.{JwtJson, JwtOptions}
import play.api.libs.json.JsPath.\
import play.api.libs.json.{JsArray, JsObject, Json}
import play.api.libs.ws.WSAuthScheme
import play.api.mvc.Cookie.SameSite
import play.api.mvc._

import java.security.{MessageDigest, SecureRandom}
import java.util.Base64
import scala.concurrent.duration.DurationInt
import scala.concurrent.{ExecutionContext, Future}

class LoginController(
    val env: Env,
    val controllerComponents: ControllerComponents,
    sessionAuthAction: AuthenticatedSessionAction
) extends BaseController {
  implicit val ec: ExecutionContext = env.executionContext

  private def generatePKCECodes(codeChallengeMethod: Option[String] = Some("S256")) = {
    val code         = new Array[Byte](120)
    val secureRandom = new SecureRandom()
    secureRandom.nextBytes(code)

    val codeVerifier = new String(Base64.getUrlEncoder.withoutPadding().encodeToString(code)).slice(0, 120)

    val bytes  = codeVerifier.getBytes("US-ASCII")
    val md     = MessageDigest.getInstance("SHA-256")
    md.update(bytes, 0, bytes.length)
    val digest = md.digest

    codeChallengeMethod match {
      case Some("S256") =>
        (codeVerifier, org.apache.commons.codec.binary.Base64.encodeBase64URLSafeString(digest), "S256")
      case _            => (codeVerifier, codeVerifier, "plain")
    }
  }

  def openIdConnect = Action.async { implicit request =>
    env.datastores.configuration.readFullConfiguration().map(e => e.toOption.flatMap(_.oidcConfiguration)).map  {
      case None => MissingOIDCConfigurationError().toHttpResponse
      case Some(OAuth2Configuration(
        enabled,
        method,
        _sessionMaxAge,
        clientId,
        _clientSecret,
        _tokenUrl,
        authorizeUrl,
        scopes,
        pkce,
        _nameField,
        _emailField,
        callbackUrl,
        defaultOIDCUserRights)) => {

        if (!enabled) {
          BadRequest(Json.obj("message" -> "Something wrong happened"))
        } else {
          val hasOpenIdInScope = scopes.split(" ").toSet.exists(s => s.equalsIgnoreCase("openid"))
          val actualScope = (if (!hasOpenIdInScope) scopes + " openid" else scopes).replace(" ", "%20")

          if (pkce.exists(_.enabled)) {
            val (codeVerifier, codeChallenge, codeChallengeMethod) = generatePKCECodes(pkce.get.algorithm.some)

            Redirect(
              s"$authorizeUrl?scope=$actualScope&client_id=$clientId&response_type=code&redirect_uri=$callbackUrl&code_challenge=$codeChallenge&code_challenge_method=$codeChallengeMethod"
            ).addingToSession(
              "code_verifier" -> codeVerifier
            )
          } else {
            Redirect(s"$authorizeUrl?scope=$actualScope&client_id=$clientId&response_type=code&redirect_uri=$callbackUrl")
          }
        }
      }
    }
  }

  def openIdCodeReturn = Action.async { implicit request =>
    // TODO handle refresh_token
    {
      for (
        code                                                                                           <- request.body.asJson.flatMap(json => (json \ "code").get.asOpt[String]).asFuture;
        oauth2ConfigurationOpt <- env.datastores.configuration.readFullConfiguration().map(_.toOption.flatMap(_.oidcConfiguration))
      )
        yield {
          if (code.isEmpty || oauth2ConfigurationOpt.isEmpty || oauth2ConfigurationOpt.exists(_.enabled == false))  {
            InternalServerError(Json.obj("message" -> "Failed to read token claims")).asFuture
          } else {
            val OAuth2Configuration(
              _enabled,
              method,
              _sessionMaxAge,
              clientId,
              clientSecret,
              tokenUrl,
              _authorizeUrl,
              _scopes,
              _pkce,
              nameField,
              emailField,
              callbackUrl,
              defaultOIDCUserRights) = oauth2ConfigurationOpt.get

            var builder = env.Ws
                .url(tokenUrl)
                .withHttpHeaders(("content-type", "application/x-www-form-urlencoded"))
            var body =  Map(
                  "grant_type" -> "authorization_code",
                  "code" -> code.get,
                  "redirect_uri" -> callbackUrl
                )

            if (method == "BASIC") {
              builder = builder
                .withAuth(clientId, clientSecret, WSAuthScheme.BASIC)
            } else {
              body = Map(
                  "grant_type" -> "authorization_code",
                  "code" -> code.get,
                  "redirect_uri" -> callbackUrl,
                  "client_id" -> clientId,
                  "client_secret" -> clientSecret
                )
            }

            if (_pkce.exists(_.enabled)) {
              body = body + ("code_verifier" -> request.session.get(s"code_verifier").getOrElse(""))
            }

            builder.post(body)
              .flatMap(r => {
                  val maybeToken = (r.json \ "id_token").get.asOpt[String]

                  maybeToken.fold(Future(InternalServerError(Json.obj("message" -> "Failed to retrieve token"))))(token => {
                    val maybeClaims = JwtJson.decode(token, JwtOptions(signature = false))
                    maybeClaims.toOption
                      .flatMap(claims => Json.parse(claims.content).asOpt[JsObject])
                      .flatMap(json => {
                        for (
                          username <- (json \ nameField).asOpt[String];
                          email <- (json \ emailField).asOpt[String]
                        )
                        yield {
                          env.datastores.users
                            .findUser(username)
                            .flatMap(maybeUser =>
                              maybeUser
                                .fold(
                                  env.datastores.users
                                    .createUser(User(username, email = email, userType = OIDC)
                                      .withRights(defaultOIDCUserRights))
                                )(user => Future(Right(user.withRights(defaultOIDCUserRights))))
                                .map(either => either.map(_ => username))
                            )
                        }
                      })
                      .getOrElse(Future(Left(InternalServerError(Json.obj("message" -> "Failed to read token claims")))))
                      .flatMap {
                        // TODO refactor this whole method
                        case Right(username) => env.datastores.users.createSession(username).map(id => Right(id))
                        case Left(err) => Future(Left(err))
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
          }
        }
    }.flatten
    //.getOrElse(Future(InternalServerError(Json.obj("message" -> "Failed to read token claims"))))
  }

  def fetchOpenIdConnectConfiguration = Action.async { implicit request =>
    request.body.asJson.flatMap(body => (body \ "url").asOpt[String]) match {
      case None => BadRequest(Json.obj("error" -> "missing field")).future
      case Some(url) =>
        env.Ws
          .url(url)
          .withRequestTimeout(10.seconds)
          .get()
          .map { resp =>
            val result: Option[OAuth2Configuration] = if (resp.status == 200) {
              val body = Json.parse(resp.body)

              val issuer = (body \ "issuer").asOpt[String];
              val tokenUrl = (body \ "token_endpoint").asOpt[String];
              val authorizeUrl = (body \ "authorization_endpoint").asOpt[String];

              val claims = (body \ "claims_supported")
                .asOpt[JsArray].map(Json.stringify)
                .getOrElse("""["email","name"]""")
              val scope = (body \ "scopes_supported")
                .asOpt[Seq[String]]
                .map(_.mkString(" "))
                .getOrElse("openid email profile")

              OAuth2Configuration(
                clientId = null,
                clientSecret = null,
                tokenUrl = tokenUrl.getOrElse(""),
                authorizeUrl = authorizeUrl.getOrElse(""),
                scopes = scope,
                pkce = None,
                callbackUrl = s"${env.expositionUrl}/login",
                method = "BASIC",
                enabled = true
              ).some
            } else {
              None
            }

            result match {
              case Some(value) => Ok(OAuth2Configuration._fmt.writes(value))
              case None => NotFound(Json.obj())
            }
          }
      }
    }

  def logout() = sessionAuthAction.async { implicit request =>
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
