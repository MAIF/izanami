package controllers

import java.security.{KeyFactory, PrivateKey, PublicKey}
import java.security.interfaces.{ECPrivateKey, ECPublicKey, RSAPrivateKey, RSAPublicKey}
import java.security.spec.{PKCS8EncodedKeySpec, X509EncodedKeySpec}

import scala.concurrent.duration.DurationDouble
import akka.util.ByteString
import com.auth0.jwt.JWT
import com.auth0.jwt.algorithms.Algorithm
import com.nimbusds.jose.jwk.{ECKey, JWK, RSAKey}
import controllers.dto.error.ApiErrors
import domains.OAuthModule
import domains.errors.IzanamiErrors
import domains.user.User
import env.{AlgoSettingsConfig, ES, Env, HS, JWKS, Oauth2Config, RSA}
import libs.logs.Logger
import play.api.libs.json.{JsArray, JsObject, JsValue, Json}
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

    val rawToken: JsValue = response.json

    (authConfig.readProfileFromToken, authConfig.jwtVerifier) match {
      case (true, Some(algoConfig)) =>
        for {
          _ <- Logger.debug(s"Token $rawToken")
          accessToken <- ZIO
                          .fromOption((rawToken \ authConfig.accessTokenField).asOpt[String])
                          .mapError(_ => IzanamiErrors.error(Json.stringify(rawToken)))
          tokenHeader = Try(Json.parse(ApacheBase64.decodeBase64(accessToken.split("\\.")(0)))).getOrElse(Json.obj())
          tokenBody   = Try(Json.parse(ApacheBase64.decodeBase64(accessToken.split("\\.")(1)))).getOrElse(Json.obj())
          kid         = (tokenHeader \ "kid").asOpt[String]
          alg         = (tokenHeader \ "alg").asOpt[String].getOrElse("RS256")
          algo        <- findAlgorithm(algoConfig, alg, kid)
          _           <- ZIO(JWT.require(algo).acceptLeeway(10).build().verify(accessToken)).refineToOrDie[IzanamiErrors]
        } yield (tokenBody, rawToken)
      case _ =>
        for {
          _                <- Logger.debug(s"Token $rawToken")
          maybeAccessToken = (rawToken \ authConfig.accessTokenField).asOpt[String]
          accessToken      <- ZIO.fromOption(maybeAccessToken).mapError(_ => IzanamiErrors.error(Json.stringify(rawToken)))
          playModule       <- ZIO.environment[OAuthModule]
          wsClient         = playModule.wSClient
          response <- ZIO
                       .fromFuture { implicit ec =>
                         if (authConfig.useJson) {
                           wsClient
                             .url(authConfig.userInfoUrl)
                             .post(
                               Json.obj(
                                 "access_token" -> accessToken
                               )
                             )
                         } else {
                           wsClient
                             .url(authConfig.userInfoUrl)
                             .post(
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

  def findAlgorithm(algoSettings: AlgoSettingsConfig,
                    alg: String,
                    kid: Option[String]): ZIO[OAuthModule, IzanamiErrors, Algorithm] = {
    import cats.implicits._
    import zio.interop.catz._
    algoSettings match {
      case HS(size, secret) =>
        for {
          _ <- Logger.debug(s"decoding with HS$size algo ")
          res <- ZIO
                  .fromOption {
                    size match {
                      case 256 => Some(Algorithm.HMAC256(secret))
                      case 384 => Some(Algorithm.HMAC384(secret))
                      case 512 => Some(Algorithm.HMAC512(secret))
                      case _   => None
                    }
                  }
                  .mapError(_ => IzanamiErrors.error("error.hs.size.invalid"))
        } yield res

      case ES(size, publicKey, privateKey) =>
        for {
          _       <- Logger.debug(s"decoding with ES$size algo ")
          pubKey  <- getEsPublicKey(publicKey)
          privKey <- privateKey.filterNot(_.trim.isEmpty).traverse(k => getEsPrivateKey(k))
          algo <- ZIO
                   .fromOption {
                     size match {
                       case 256 => Some(Algorithm.ECDSA256(pubKey, privKey.orNull))
                       case 384 => Some(Algorithm.ECDSA384(pubKey, privKey.orNull))
                       case 512 => Some(Algorithm.ECDSA512(pubKey, privKey.orNull))
                       case _   => None
                     }
                   }
                   .mapError(_ => IzanamiErrors.error("error.es.size.invalid"))
        } yield algo
      case RSA(size, publicKey, privateKey) =>
        for {
          _       <- Logger.debug(s"decoding with RSA$size algo ")
          pubKey  <- getRsaPublicKey(publicKey)
          privKey <- privateKey.filterNot(_.trim.isEmpty).traverse(k => getRsaPrivateKey(k))
          algo <- ZIO
                   .fromOption {
                     size match {
                       case 256 => Some(Algorithm.RSA256(pubKey, privKey.orNull))
                       case 384 => Some(Algorithm.RSA384(pubKey, privKey.orNull))
                       case 512 => Some(Algorithm.RSA512(pubKey, privKey.orNull))
                       case _   => None
                     }
                   }
                   .mapError(_ => IzanamiErrors.error("error.rsa.size.invalid"))
        } yield algo

      case JWKS(url, headers, timeout) =>
        def algoFromJwk(alg: String, jwk: JWK): Option[Algorithm] =
          jwk match {
            case rsaKey: RSAKey =>
              alg match {
                case "RS256" => Some(Algorithm.RSA256(rsaKey.toRSAPublicKey, null))
                case "RS384" => Some(Algorithm.RSA384(rsaKey.toRSAPublicKey, null))
                case "RS512" => Some(Algorithm.RSA512(rsaKey.toRSAPublicKey, null))
              }
            case ecKey: ECKey =>
              alg match {
                case "EC256" => Some(Algorithm.ECDSA256(ecKey.toECPublicKey, null))
                case "EC384" => Some(Algorithm.ECDSA384(ecKey.toECPublicKey, null))
                case "EC512" => Some(Algorithm.ECDSA512(ecKey.toECPublicKey, null))
              }
            case _ => None
          }

        for {
          _        <- Logger.debug(s"decoding with JWKS $url ")
          module   <- ZIO.environment[OAuthModule]
          wsClient = module.wSClient
          resp <- ZIO
                   .fromFuture { implicit ec =>
                     wsClient
                       .url(url)
                       .withRequestTimeout(timeout.getOrElse(1.seconds))
                       .withHttpHeaders(headers.map(_.toSeq).getOrElse(Seq.empty): _*)
                       .get()
                   }
                   .refineToOrDie[IzanamiErrors]
          res <- ZIO
                  .fromOption {
                    val obj = Json.parse(resp.body).as[JsObject]
                    (obj \ "keys").asOpt[JsArray] match {
                      case Some(values) => {
                        val keys = values.value.map { k =>
                          val jwk = JWK.parse(Json.stringify(k))
                          (jwk.getKeyID, jwk)
                        }.toMap
                        kid.flatMap(
                          k =>
                            keys.get(k) match {
                              case Some(jwk) => algoFromJwk(alg, jwk)
                              case None      => None
                          }
                        )
                      }
                      case None => None
                    }
                  }
                  .mapError(_ => IzanamiErrors.error("error.jwks.invalid"))
        } yield res
    }
  }

  def getEsPublicKey(value: String): ZIO[OAuthModule, IzanamiErrors, ECPublicKey] = {
    val publicBytes = ApacheBase64.decodeBase64(
      value.replace("-----BEGIN PUBLIC KEY-----\n", "").replace("\n-----END PUBLIC KEY-----", "").trim()
    )
    getPublicKey(publicBytes, "EC").map(_.asInstanceOf[ECPublicKey])
  }

  def getEsPrivateKey(value: String): ZIO[OAuthModule, IzanamiErrors, ECPrivateKey] =
    if (value.trim.isEmpty) {
      null // Yeah, I know ...
    } else {
      val privateBytes = ApacheBase64.decodeBase64(
        value.replace("-----BEGIN PRIVATE KEY-----\n", "").replace("\n-----END PRIVATE KEY-----", "").trim()
      )
      getPrivateKey(privateBytes, "EC").map(_.asInstanceOf[ECPrivateKey])
    }

  def getRsaPublicKey(value: String): ZIO[OAuthModule, IzanamiErrors, RSAPublicKey] = {
    val publicBytes = ApacheBase64.decodeBase64(
      value.replace("-----BEGIN PUBLIC KEY-----\n", "").replace("\n-----END PUBLIC KEY-----", "").trim()
    )
    getPublicKey(publicBytes, "RSA").map(_.asInstanceOf[RSAPublicKey])
  }

  def getRsaPrivateKey(value: String): ZIO[OAuthModule, IzanamiErrors, RSAPrivateKey] =
    if (value.trim.isEmpty) {
      null // Yeah, I know ...
    } else {
      val privateBytes = ApacheBase64.decodeBase64(
        value.replace("-----BEGIN PRIVATE KEY-----\n", "").replace("\n-----END PRIVATE KEY-----", "").trim()
      )
      getPrivateKey(privateBytes, "RSA").map(_.asInstanceOf[RSAPrivateKey])
    }

  def getPublicKey(keyBytes: Array[Byte], algorithm: String): ZIO[OAuthModule, IzanamiErrors, PublicKey] =
    for {
      kf <- ZIO(KeyFactory.getInstance(algorithm))
             .onError(_ => Logger.error("Could not reconstruct the public key, the given algorithm could not be found"))
             .refineToOrDie[IzanamiErrors]
      keySpec <- ZIO(new X509EncodedKeySpec(keyBytes)).refineToOrDie[IzanamiErrors]
      publicKey <- ZIO(kf.generatePublic(keySpec))
                    .onError(_ => Logger.error("Could not reconstruct the public key"))
                    .refineToOrDie[IzanamiErrors]
    } yield publicKey

  def getPrivateKey(keyBytes: Array[Byte], algorithm: String): ZIO[OAuthModule, IzanamiErrors, PrivateKey] =
    for {
      kf <- ZIO(KeyFactory.getInstance(algorithm))
             .onError(
               _ => Logger.error("Could not reconstruct the private key, the given algorithm could not be found")
             )
             .refineToOrDie[IzanamiErrors]
      keySpec <- ZIO(new PKCS8EncodedKeySpec(keyBytes)).refineToOrDie[IzanamiErrors]
      publicKey <- ZIO(kf.generatePrivate(keySpec))
                    .onError(_ => Logger.error("Could not reconstruct the private key"))
                    .refineToOrDie[IzanamiErrors]
    } yield publicKey
}
