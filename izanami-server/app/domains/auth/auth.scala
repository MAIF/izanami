package domains

import java.io.File
import java.security.{KeyFactory, KeyStore, PrivateKey, Provider, PublicKey, SecureRandom}
import java.security.interfaces.{ECPrivateKey, ECPublicKey, RSAPrivateKey, RSAPublicKey}
import java.security.spec.{PKCS8EncodedKeySpec, X509EncodedKeySpec}
import java.util.Collections

import akka.actor.ActorSystem
import akka.http.scaladsl.model.HttpHeader.ParsingResult
import akka.http.scaladsl.model.headers.Accept
import akka.http.scaladsl.model.{
  ContentTypes,
  FormData,
  HttpEntity,
  HttpHeader,
  HttpMethods,
  HttpRequest,
  HttpResponse,
  MediaTypes,
  StatusCode,
  StatusCodes,
  Uri
}
import akka.http.scaladsl.server.ContentNegotiator.Alternative.ContentType
import akka.http.scaladsl.{ConnectionContext, Http, HttpExt, HttpsConnectionContext}
import akka.stream.scaladsl.Sink
import akka.util.ByteString
import com.auth0.jwt.JWT
import com.auth0.jwt.algorithms.Algorithm
import com.nimbusds.jose.jwk.{ECKey, JWK, RSAKey}
import com.typesafe.config.{Config, ConfigFactory}
import com.typesafe.sslconfig.akka.AkkaSSLConfig
import com.typesafe.sslconfig.akka.util.AkkaLoggerFactory
import com.typesafe.sslconfig.ssl.{
  Ciphers,
  ConfigSSLContextBuilder,
  DefaultKeyManagerFactoryWrapper,
  DefaultTrustManagerFactoryWrapper,
  KeyManagerConfig,
  KeyManagerFactoryWrapper,
  KeyStoreConfig,
  Protocols,
  SSLConfigFactory,
  SSLConfigSettings,
  TrustManagerConfig,
  TrustManagerFactoryWrapper,
  TrustStoreConfig
}
import domains.configuration.PlayModule
import domains.Key
import domains.auth.Oauth2Service
import domains.errors.{IzanamiErrors, Unauthorized}
import domains.user.{OauthUser, User, UserContext, UserService}
import env.configuration.IzanamiConfigModule
import env.{AlgoSettingsConfig, CertificateConfig, ES, HS, IzanamiConfig, JWKS, MtlsConfig, Oauth2Config, RSA}
import javax.net.ssl.{
  KeyManager,
  KeyManagerFactory,
  SSLContext,
  SSLContextSpi,
  SSLEngine,
  SSLParameters,
  SSLServerSocketFactory,
  SSLSessionContext,
  SSLSocketFactory,
  TrustManager,
  TrustManagerFactory,
  X509KeyManager,
  X509TrustManager
}
import libs.logs.{IzanamiLogger, ZLogger}
import play.api.libs.json.{JsArray, JsObject, JsValue, Json}
import play.api.libs.ws.DefaultBodyWritables.writeableOf_urlEncodedSimpleForm
import play.api.libs.ws.WSResponse
import play.api.mvc.{AnyContent, Request}
import zio.{Has, IO, Layer, Task, UIO, URIO, ZIO, ZLayer}
import org.apache.commons.codec.binary.{Base64 => ApacheBase64}
import play.api.Configuration

import scala.concurrent.Future
import scala.concurrent.duration.DurationDouble
import scala.util.Try

package object auth {

  type AuthInfo = zio.Has[Option[AuthInfo.Service]]

  object AuthInfo {

    trait Service {
      def authorizedPatterns: AuthorizedPatterns
      def id: String
      def name: String
      def mayBeEmail: Option[String]
      def admin: Boolean
    }

    def authInfo: zio.URIO[AuthInfo, Option[AuthInfo.Service]] = ZIO.access[AuthInfo](_.get)

    val empty: Layer[Nothing, AuthInfo] = ZLayer.succeed(None)

    def value(authInfo: AuthInfo.Service): Layer[Nothing, AuthInfo]            = ZLayer.succeed(Some(authInfo))
    def optValue(authInfo: Option[AuthInfo.Service]): Layer[Nothing, AuthInfo] = ZLayer.succeed(authInfo)

    import AuthorizedPatterns._
    import play.api.libs.json._
    import play.api.libs.functional.syntax._

    implicit val format: Format[Service] = Format(
      {
        (
          (__ \ "id").read[String] and
          (__ \ "authorizedPattern").read[AuthorizedPatterns] and
          (__ \ "name").read[String] and
          (__ \ "mayBeEmail").readNullable[String] and
          (__ \ "admin").read[Boolean].orElse(Reads.pure(false))
        )(
          (anId: String, aPattern: AuthorizedPatterns, aName: String, anEmail: Option[String], anAdmin: Boolean) =>
            new AuthInfo.Service {
              def authorizedPatterns: AuthorizedPatterns = aPattern
              def id: String                             = anId
              def name: String                           = aName
              def mayBeEmail: Option[String]             = anEmail
              def admin: Boolean                         = anAdmin
          }
        )
      }, {
        (
          (__ \ "id").write[String] and
          (__ \ "authorizedPattern").write[AuthorizedPatterns] and
          (__ \ "name").write[String] and
          (__ \ "mayBeEmail").writeNullable[String] and
          (__ \ "admin").write[Boolean]
        )(unlift[AuthInfo.Service, (String, AuthorizedPatterns, String, Option[String], Boolean)] { info =>
          Some((info.id, info.authorizedPatterns, info.name, info.mayBeEmail, info.admin))
        })
      }
    )

    def isAdmin(): ZIO[ZLogger with AuthInfo, IzanamiErrors, Unit] =
      for {
        authInfo <- AuthInfo.authInfo
        res <- ZIO.when(!authInfo.exists(_.admin)) {
                ZLogger.debug(s"${authInfo} is not admin") *>
                IO.fail(IzanamiErrors(Unauthorized(None)))
              }
      } yield res

  }

  type OAuthModule = UserContext with ZLogger with AuthInfo

  type OAuthServiceModule = Oauth2Service with OAuthModule

  type Oauth2Service = Has[Oauth2Service.Service]

  object Oauth2Service {

    trait Service {
      def paCallback(
          baseURL: String,
          authConfig: Oauth2Config
      )(implicit request: Request[AnyContent]): ZIO[OAuthModule, IzanamiErrors, User]
    }

    def live(izanamiConfig: IzanamiConfig): ZLayer[PlayModule, Throwable, Oauth2Service] = ZLayer.fromFunction { mix =>
      val playModule                   = mix.get[PlayModule.Service]
      implicit val system: ActorSystem = playModule.system
      new Oauth2ServiceProd(izanamiConfig.oauth2, playModule.configuration)
    }

    def loadCertificate(system: ActorSystem, configuration: Configuration): SSLContext = {

      val config: Config =
        configuration.underlying.getConfig("izanami.oauth2.mtls.ssl-config") // .withFallback(configuration.underlying)

      println(config)

      val sslConfig: SSLConfigSettings = SSLConfigFactory.parse(config)

      val mkLogger = new AkkaLoggerFactory(system)

//      val sslContext = new SSLContext(
//        new SSLContextSpi() {
//          private def looseDisableSNI(defaultParams: SSLParameters): Unit = if (sslConfig.loose.disableSNI) {
//            defaultParams.setServerNames(Collections.emptyList())
//            defaultParams.setSNIMatchers(Collections.emptyList())
//          }
//
//          private def buildKeyManagerFactory(ssl: SSLConfigSettings): KeyManagerFactoryWrapper = {
//            val keyManagerAlgorithm = ssl.keyManagerConfig.algorithm
//            new DefaultKeyManagerFactoryWrapper(keyManagerAlgorithm)
//          }
//          private def buildTrustManagerFactory(ssl: SSLConfigSettings): TrustManagerFactoryWrapper = {
//            val trustManagerAlgorithm = ssl.trustManagerConfig.algorithm
//            new DefaultTrustManagerFactoryWrapper(trustManagerAlgorithm)
//          }
//
//          private def configureProtocols(existingProtocols: Array[String],
//                                         sslConfig: SSLConfigSettings): Array[String] = {
//            val definedProtocols = sslConfig.enabledProtocols match {
//              case Some(configuredProtocols) =>
//                // If we are given a specific list of protocols, then return it in exactly that order,
//                // assuming that it's actually possible in the SSL context.
//                configuredProtocols.filter(existingProtocols.contains).toArray
//              case None =>
//                // Otherwise, we return the default protocols in the given list.
//                Protocols.recommendedProtocols.filter(existingProtocols.contains)
//            }
//
//            val allowWeakProtocols = sslConfig.loose.allowWeakProtocols
//            if (!allowWeakProtocols) {
//              val deprecatedProtocols = Protocols.deprecatedProtocols
//              for (deprecatedProtocol <- deprecatedProtocols) {
//                if (definedProtocols.contains(deprecatedProtocol)) {
//                  throw new IllegalStateException(s"Weak protocol $deprecatedProtocol found in ssl-config.protocols!")
//                }
//              }
//            }
//            definedProtocols
//          }
//
//          private def configureCipherSuites(existingCiphers: Array[String],
//                                            sslConfig: SSLConfigSettings): Array[String] = {
//            val definedCiphers = sslConfig.enabledCipherSuites match {
//              case Some(configuredCiphers) =>
//                // If we are given a specific list of ciphers, return it in that order.
//                configuredCiphers.filter(existingCiphers.contains(_)).toArray
//              case None =>
//                Ciphers.recommendedCiphers.filter(existingCiphers.contains(_)).toArray
//            }
//
//            val allowWeakCiphers = sslConfig.loose.allowWeakCiphers
//            if (!allowWeakCiphers) {
//              val deprecatedCiphers = Ciphers.deprecatedCiphers
//              for (deprecatedCipher <- deprecatedCiphers) {
//                if (definedCiphers.contains(deprecatedCipher)) {
//                  throw new IllegalStateException(s"Weak cipher $deprecatedCipher found in ssl-config.ciphers!")
//                }
//              }
//            }
//            definedCiphers
//          }
//
//          private def getCtx(): SSLContext = {
//            val keyManagerFactory   = buildKeyManagerFactory(sslConfig)
//            val trustManagerFactory = buildTrustManagerFactory(sslConfig)
//            new ConfigSSLContextBuilder(mkLogger, sslConfig, keyManagerFactory, trustManagerFactory).build()
//          }
//
//          private def createSSLEngine(): SSLEngine = {
//            // protocols!
//            val defaultParams    = currentCtx.getDefaultSSLParameters
//            val defaultProtocols = defaultParams.getProtocols
//            val protocols        = configureProtocols(defaultProtocols, sslConfig)
//            // ciphers!
//            val defaultCiphers = defaultParams.getCipherSuites
//            val cipherSuites   = configureCipherSuites(defaultCiphers, sslConfig)
//            // apply "loose" settings
//            looseDisableSNI(defaultParams)
//
//            val engine = currentCtx.createSSLEngine()
//            engine.setSSLParameters(currentCtx.getDefaultSSLParameters)
//            engine.setEnabledProtocols(protocols)
//            engine.setEnabledCipherSuites(cipherSuites)
//            engine
//          }
//
//          lazy val currentCtx: SSLContext = getCtx()
//
//          override def engineCreateSSLEngine(): SSLEngine                  = createSSLEngine()
//          override def engineCreateSSLEngine(s: String, i: Int): SSLEngine = engineCreateSSLEngine()
//          override def engineInit(keyManagers: Array[KeyManager],
//                                  trustManagers: Array[TrustManager],
//                                  secureRandom: SecureRandom): Unit           = ()
//          override def engineGetClientSessionContext(): SSLSessionContext     = currentCtx.getClientSessionContext
//          override def engineGetServerSessionContext(): SSLSessionContext     = currentCtx.getServerSessionContext
//          override def engineGetSocketFactory(): SSLSocketFactory             = currentCtx.getSocketFactory
//          override def engineGetServerSocketFactory(): SSLServerSocketFactory = currentCtx.getServerSocketFactory
//        },
//        new Provider("foo", "1", "foo") {},
//        "foo"
//      ) {}
      val keyManagerFactoryWrapper   = new DefaultKeyManagerFactoryWrapper(sslConfig.keyManagerConfig.algorithm)
      val trustManagerFactoryWrapper = new DefaultTrustManagerFactoryWrapper(sslConfig.trustManagerConfig.algorithm)
      val sslConfigBuilder =
        new ConfigSSLContextBuilder(mkLogger, sslConfig, keyManagerFactoryWrapper, trustManagerFactoryWrapper)
      sslConfigBuilder.build()
    }

    def paCallback(
        baseURL: String,
        authConfig: Oauth2Config
    )(implicit request: Request[AnyContent]): ZIO[OAuthServiceModule, IzanamiErrors, User] =
      ZIO.accessM(_.get[Oauth2Service.Service].paCallback(baseURL, authConfig)(request))

    class Oauth2ServiceProd(mayBeOAuthConfig: Option[Oauth2Config], configuration: Configuration)(
        implicit system: ActorSystem
    ) extends Service {
      val sslContext: Option[SSLContext] =
        mayBeOAuthConfig
          .flatMap(_.mtls.filter(_.enabled))
          .map(c => loadCertificate(system, configuration))
      val connectionContext: Option[HttpsConnectionContext] = sslContext.map { c =>
        ConnectionContext.https(c)
      }

      val http: HttpExt = Http()

      override def paCallback(
          baseURL: String,
          authConfig: Oauth2Config
      )(implicit request: Request[AnyContent]): ZIO[OAuthModule, IzanamiErrors, User] =
        for {
          logger        <- ZLogger("izanami.oauth2")
          _             <- ZIO.fromOption(request.getQueryString("error")).flip.mapError(IzanamiErrors.error)
          code          <- ZIO.fromOption(request.getQueryString("code")).mapError(_ => IzanamiErrors.error("No code :("))
          wsResponse    <- callTokenUrl(baseURL, code, authConfig)
          t             <- decodeToken(wsResponse, authConfig)
          (user, _)     = t
          _             <- logger.debug(s"User from token $user")
          effectiveUser <- ZIO.fromEither(User.fromOAuth(user, authConfig))
          _             <- logger.debug(s"User mapped from token $user")
          _             <- createOrUpdateUserIfNeeded(authConfig, effectiveUser)
          endUser       <- enrichWithDb(authConfig, effectiveUser)
          _             <- logger.debug(s"Oauth user logged with $endUser")
        } yield endUser

      def createOrUpdateUserIfNeeded(authConfig: Oauth2Config,
                                     effectiveUser: OauthUser): ZIO[UserContext, IzanamiErrors, User] =
        if (authConfig.izanamiManagedUser) {
          val id = Key(effectiveUser.id)
          UserService.getByIdWithoutPermissions(id).orDie.flatMap {
            case None => UserService.createWithoutPermission(id, effectiveUser)
            case Some(u: OauthUser) =>
              UserService
                .updateWithoutPermission(id, id, u.copy(name = effectiveUser.name, email = effectiveUser.email))
            case _ => ZIO.succeed(effectiveUser)
          }
        } else {
          ZIO.succeed(effectiveUser)
        }

      def enrichWithDb(authConfig: Oauth2Config, effectiveUser: OauthUser): ZIO[UserContext, IzanamiErrors, User] =
        if (authConfig.izanamiManagedUser) {
          UserService
            .getByIdWithoutPermissions(Key(effectiveUser.id))
            .orDie
            .map {
              _.map { userFromDb =>
                effectiveUser.copy(
                  admin = userFromDb.admin || adminInConfig(authConfig, effectiveUser.name),
                  authorizedPatterns = userFromDb.authorizedPatterns
                )
              }.getOrElse(effectiveUser)
            }
        } else {
          ZIO.succeed(effectiveUser)
        }

      def adminInConfig(authConfig: Oauth2Config, name: String): Boolean =
        authConfig.admins.getOrElse(Seq.empty).contains(name)

      def callTokenUrl(baseURL: String, code: String, authConfig: Oauth2Config)(
          implicit request: Request[AnyContent]
      ): ZIO[OAuthModule, IzanamiErrors, JsValue] = {

        val clientId     = authConfig.clientId
        val clientSecret = authConfig.clientSecret.map(_.trim).filterNot(_.isEmpty)
        val queryParam   = if (authConfig.useCookie) "" else s"?desc=izanami"
        val redirectUri = if (baseURL.startsWith("http")) {
          s"${baseURL}/${controllers.routes.OAuthController.appCallback().url}${queryParam}"
        } else {
          s"${controllers.routes.OAuthController.appCallback().absoluteURL()}${queryParam}"
        }

        val buildRequest: UIO[HttpRequest] = UIO {
          if (authConfig.useJson) {
            val body = Json.obj(
              "code"         -> code,
              "grant_type"   -> "authorization_code",
              "client_id"    -> clientId,
              "redirect_uri" -> redirectUri
            ) ++ clientSecret.map(s => Json.obj("client_secret" -> s)).getOrElse(Json.obj())
            HttpRequest(
              method = HttpMethods.POST,
              uri = Uri(authConfig.tokenUrl),
              entity = HttpEntity(ContentTypes.`application/json`, Json.stringify(body))
            )
          } else {
            HttpRequest(
              method = HttpMethods.POST,
              uri = Uri(authConfig.tokenUrl),
              entity = FormData(
                Map(
                  "code"         -> code,
                  "grant_type"   -> "authorization_code",
                  "client_id"    -> clientId,
                  "redirect_uri" -> redirectUri
                ) ++ clientSecret.toSeq.map(s => ("client_secret" -> s))
              ).toEntity
            )
          }
        }

        for {
          request <- buildRequest
          json    <- httpCallAndParse(request)
        } yield json
      }

      def decodeToken(response: JsValue,
                      authConfig: Oauth2Config): ZIO[OAuthModule, IzanamiErrors, (JsValue, JsValue)] = {

        val rawToken: JsValue = response

        def buildUserInfoRequest(accessToken: String): UIO[HttpRequest] = UIO {
          if (authConfig.useJson) {
            val body = Json.obj(
              "access_token" -> accessToken
            )
            HttpRequest(
              method = HttpMethods.POST,
              uri = Uri(authConfig.userInfoUrl),
              entity = HttpEntity(ContentTypes.`application/json`, Json.stringify(body))
            )
          } else {
            HttpRequest(
              method = HttpMethods.POST,
              uri = Uri(authConfig.userInfoUrl),
              entity = FormData(Map("access_token" -> accessToken)).toEntity,
              headers = List(Accept(MediaTypes.`application/json`))
            )
          }
        }

        (authConfig.readProfileFromToken, authConfig.jwtVerifier.filter(_.enabled)) match {
          case (true, Some(algoConfig)) =>
            for {
              logger <- ZLogger("izanami.oauth2")
              _      <- logger.debug(s"Algo is defined in config, decoding token $rawToken")
              accessToken <- ZIO
                              .fromOption((rawToken \ authConfig.accessTokenField).asOpt[String])
                              .mapError(_ => IzanamiErrors.error(Json.stringify(rawToken)))
              tokenHeader = Try(Json.parse(ApacheBase64.decodeBase64(accessToken.split("\\.")(0))))
                .getOrElse(Json.obj())
              tokenBody = Try(Json.parse(ApacheBase64.decodeBase64(accessToken.split("\\.")(1)))).getOrElse(Json.obj())
              kid       = (tokenHeader \ "kid").asOpt[String]
              alg       = (tokenHeader \ "alg").asOpt[String].getOrElse("RS256")
              algo      <- findAlgorithm(algoConfig, alg, kid)
              _         <- ZIO(JWT.require(algo).acceptLeeway(10).build().verify(accessToken)).orDie
            } yield (tokenBody, rawToken)
          case _ =>
            for {
              logger           <- ZLogger("izanami.oauth2")
              _                <- logger.debug(s"Algo is not defined in config, finding access token from $rawToken")
              maybeAccessToken = (rawToken \ authConfig.accessTokenField).asOpt[String]
              _                <- logger.debug(s"Using userInfoUrl from config to find user data with access token  $maybeAccessToken")
              accessToken <- ZIO
                              .fromOption(maybeAccessToken)
                              .mapError(_ => IzanamiErrors.error(Json.stringify(rawToken)))
              request <- buildUserInfoRequest(accessToken)
              json    <- httpCallAndParse(request)
            } yield (json, rawToken)
        }
      }

      def findAlgorithm(algoSettings: AlgoSettingsConfig,
                        alg: String,
                        kid: Option[String]): ZIO[OAuthModule, IzanamiErrors, Algorithm] = {
        import cats.implicits._
        import zio.interop.catz._
        algoSettings match {
          case HS(_, size, secret) =>
            for {
              logger <- ZLogger("izanami.oauth2")
              _      <- logger.debug(s"decoding with HS$size algo ")
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

          case ES(_, size, publicKey, privateKey) =>
            for {
              logger  <- ZLogger("izanami.oauth2")
              _       <- logger.debug(s"decoding with ES$size algo ")
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
          case RSA(_, size, publicKey, privateKey) =>
            for {
              logger  <- ZLogger("izanami.oauth2")
              _       <- logger.debug(s"decoding with RSA$size algo ")
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

          case JWKS(_, url, headers, timeout) =>
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
              logger <- ZLogger("izanami.oauth2")
              _      <- logger.debug(s"decoding with JWKS $url $alg and $kid")
              request <- Task {
                          HttpRequest(
                            uri = Uri(url),
                            headers = headers.toSeq.flatMap(_.toSeq).map(p => HttpHeader.parse(p._1, p._2)).collect {
                              case ParsingResult.Ok(h, _) => h
                            }
                          )
                        }.orDie
              body <- httpCallAndParse(request)
              res <- ZIO
                      .fromOption {
                        val obj = body.as[JsObject]
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
                 .onError(
                   _ =>
                     ZLogger("izanami.oauth2")
                       .flatMap(_.error("Could not reconstruct the public key, the given algorithm could not be found"))
                 )
                 .orDie
          keySpec <- ZIO(new X509EncodedKeySpec(keyBytes)).orDie
          publicKey <- ZIO(kf.generatePublic(keySpec))
                        .onError(
                          _ => ZLogger("izanami.oauth2").flatMap(_.error("Could not reconstruct the public key"))
                        )
                        .orDie
        } yield publicKey

      def getPrivateKey(keyBytes: Array[Byte], algorithm: String): ZIO[OAuthModule, IzanamiErrors, PrivateKey] =
        for {
          kf <- ZIO(KeyFactory.getInstance(algorithm))
                 .onError(
                   _ =>
                     ZLogger("izanami.oauth2")
                       .flatMap(
                         _.error("Could not reconstruct the private key, the given algorithm could not be found")
                     )
                 )
                 .orDie
          keySpec <- ZIO(new PKCS8EncodedKeySpec(keyBytes)).orDie
          publicKey <- ZIO(kf.generatePrivate(keySpec))
                        .onError(
                          _ => ZLogger("izanami.oauth2").flatMap(_.error("Could not reconstruct the private key"))
                        )
                        .orDie
        } yield publicKey

      private def httpCallAndParse(httpRequest: HttpRequest,
                                   expectedCode: StatusCode = StatusCodes.OK): ZIO[ZLogger, IzanamiErrors, JsValue] =
        for {
          response    <- httpCall(httpRequest)
          strResponse <- parseResponse(response)
          json <- strResponse match {
                   case (code, body) if code == expectedCode => Task(Json.parse(body)).orDie
                   case (code, body) =>
                     ZLogger.error(s"Error during http call $httpRequest\n => $code: $body") *> ZIO.fail(
                       IzanamiErrors.error(body)
                     )
                 }
        } yield json

      private def httpCall(httpRequest: HttpRequest): ZIO[ZLogger, IzanamiErrors, HttpResponse] =
        connectionContext
          .fold(
            ZLogger.debug(s"Calling $httpRequest") *> ZIO.fromFuture { implicit ec =>
              http.singleRequest(httpRequest)
            }
          ) { ctx =>
            ZLogger.debug(s"Calling $httpRequest with MTLS") *> ZIO.fromFuture { implicit ec =>
              http.singleRequest(httpRequest, connectionContext = ctx)
            }
          }
          .orDie

      private def parseResponse(httpResponse: HttpResponse): IO[IzanamiErrors, (StatusCode, String)] =
        httpResponse match {
          case HttpResponse(code, _, entity, _) =>
            ZIO.fromFuture { implicit ec =>
              stringBody(entity).map(b => (code, b))
            }.orDie
        }

      private def stringBody(entity: HttpEntity): Future[String] =
        entity.dataBytes.fold(ByteString.empty)(_ ++ _).map(_.utf8String).runWith(Sink.head)

    }
  }
}
