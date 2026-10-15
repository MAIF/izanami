package fr.maif.izanami.security

import com.github.blemale.scaffeine.Scaffeine
import com.github.benmanes.caffeine.cache.Ticker
import fr.maif.izanami.errors.{InvalidOIDCToken, IzanamiError}
import fr.maif.izanami.models.OAuth2Configuration
import pdi.jwt.{JwtAlgorithm, JwtClaim, JwtJson, JwtOptions}
import pdi.jwt.algorithms.JwtAsymmetricAlgorithm
import play.api.Logger
import play.api.libs.json.*
import play.api.libs.ws.WSClient

import java.math.BigInteger
import java.net.URI
import java.security.{AlgorithmParameters, KeyFactory, PublicKey}
import java.security.spec.{ECGenParameterSpec, ECParameterSpec, ECPoint, ECPublicKeySpec, RSAPublicKeySpec}
import java.time.Instant
import java.util.Base64
import scala.concurrent.{ExecutionContext, Future}
import scala.concurrent.duration.*
import scala.util.Try
import scala.util.control.NonFatal

class OidcTokenValidator(wsClient: WSClient, ticker: Ticker = Ticker.systemTicker())(implicit ec: ExecutionContext) {
  private case class SigningKey(
      kid: Option[String],
      algorithm: Option[String],
      supportedAlgorithms: Set[String],
      publicKey: PublicKey
  )

  private val logger = Logger("izanami.oidc.validation")
  private val keysCache = Scaffeine().ticker(ticker).expireAfterWrite(5.minutes).maximumSize(32)
    .build[String, Future[Seq[SigningKey]]]()
  private val refreshCooldown = Scaffeine().ticker(ticker).expireAfterWrite(30.seconds).maximumSize(32)
    .build[String, Boolean]()
  private val options = JwtOptions(leeway = 10)

  def validate(token: String, configuration: OAuth2Configuration, nonce: String): Future[Either[IzanamiError, JwtClaim]] = {
    val missing = Seq(
      "issuer" -> configuration.issuer,
      "audience" -> configuration.audience,
      "jwksUrl (signature verification)" -> configuration.jwksUrl
    ).collect { case (name, None) => name }
    if (missing.nonEmpty) logger.warn(
      s"OIDC compatibility mode: missing ${missing.mkString(", ")}; corresponding token checks are disabled. Configure all three values to enable full validation."
    )

    val decoded = JwtJson.decode(token, options.copy(signature = false))
    def verified = configuration.jwksUrl match {
      case None => Future.fromTry(decoded)
      case Some(url) =>
        val header = Try(Json.parse(new String(Base64.getUrlDecoder.decode(token.split("\\.")(0)), "UTF-8")).as[JsObject])
        Future.fromTry(header).flatMap(h => verifySignature(token, h, url))
    }
    // Decode failures must also become a rejected login, not an HTTP 500.
    Future.fromTry(decoded).flatMap(_ => verified).map { claim =>
      val content = Json.parse(claim.content).as[JsObject]
      val now = Instant.now().getEpochSecond
      require(claim.issuer.exists(_.nonEmpty) && claim.subject.exists(_.nonEmpty), "Missing identity")
      require(claim.expiration.isDefined && claim.issuedAt.exists(_ <= now + 10), "Missing or invalid token timestamps")
      require(claim.issuedAt.get < claim.expiration.get, "Invalid token lifetime")
      require((content \ "nonce").asOpt[String].contains(nonce), "Nonce mismatch")
      require(configuration.issuer.forall(iss => claim.issuer.contains(iss)), "Issuer mismatch")
      configuration.audience.foreach { audience =>
        require(claim.audience.exists(_.contains(audience)), "Audience mismatch")
        val authorizedParty = (content \ "azp").toOption.map(_.as[String])
        require(claim.audience.forall(_.size <= 1) || authorizedParty.isDefined, "Missing authorized party")
        require(authorizedParty.forall(_ == configuration.clientId), "Authorized party mismatch")
      }
      Right(claim)
    }.recover { case NonFatal(_) =>
      logger.warn("Rejected OIDC ID token: signature or claims validation failed")
      Left(InvalidOIDCToken)
    }
  }

  private def loadKeys(url: String): Future[Seq[SigningKey]] = {
    val pending = keysCache.get(url, fetchKeys)
    pending.recoverWith { case NonFatal(error) =>
      // A failed initial load must not evict a newer successful refresh.
      keysCache.underlying.asMap().remove(url, pending)
      Future.failed(error)
    }
  }

  private def fetchKeys(url: String): Future[Seq[SigningKey]] = {
    val uri = URI.create(url)
    require(Set("https", "http").contains(uri.getScheme) && uri.getHost != null && uri.getUserInfo == null,
      "Invalid JWKS URL")
    if (uri.getScheme == "http") logger.warn("OIDC JWKS URL uses HTTP; use HTTPS outside trusted local environments")
    wsClient.url(url).withFollowRedirects(false).withRequestTimeout(10.seconds).get().map { response =>
      require(response.status == 200 && response.bodyAsBytes.length <= 1024 * 1024, "Failed to retrieve JWKS")
      val keys = (response.json \ "keys").as[Seq[JsObject]]
      require(keys.nonEmpty && keys.size <= 100, "Invalid JWKS")
      keys.flatMap { key =>
        Try(parseSigningKey(key)).fold(
          error => {
            logger.warn("Ignoring invalid JWKS signing key", error)
            None
          },
          identity
        )
      }
    }
  }

  private def verifySignature(token: String, header: JsObject, url: String): Future[JwtClaim] = {
    val algorithm: JwtAsymmetricAlgorithm = (header \ "alg").as[String] match {
      case "RS256" => JwtAlgorithm.RS256
      case "RS384" => JwtAlgorithm.RS384
      case "RS512" => JwtAlgorithm.RS512
      case "ES256" => JwtAlgorithm.ES256
      case "ES384" => JwtAlgorithm.ES384
      case "ES512" => JwtAlgorithm.ES512
      case _ => throw new IllegalArgumentException("Unsupported OIDC signing algorithm")
    }
    require((header \ "crit").toOption.forall(_.asOpt[Seq[String]].exists(_.isEmpty)), "Unsupported critical JWT header")
    val kid = (header \ "kid").asOpt[String]

    def matchingKeys(keys: Seq[SigningKey]): Seq[SigningKey] = keys.filter { key =>
      kid.forall(id => key.kid.contains(id)) &&
        key.algorithm.forall(_ == algorithm.name) &&
        key.supportedAlgorithms.contains(algorithm.name)
    }

    loadKeys(url).flatMap { keys =>
      if (matchingKeys(keys).isEmpty && refreshCooldown.asMap().putIfAbsent(url, true).isEmpty) {
        // Keep the current entry (and its original expiry) while fetching.
        // Only a successful retrieval may replace it and start a new TTL.
        fetchKeys(url).map { refreshed =>
          keysCache.put(url, Future.successful(refreshed))
          refreshed
        }.recover { case NonFatal(_) =>
          logger.warn("OIDC JWKS refresh failed; previously cached keys retain their original expiry")
          keys
        }
      } else Future.successful(keys)
    }.flatMap { keys =>
      Future.fromTry(Try {
        val candidates = matchingKeys(keys)
        require(candidates.size == 1, "Missing or ambiguous signing key")
        JwtJson.decode(token, candidates.head.publicKey, Seq(algorithm), options).get
      })
    }
  }

  private def parseSigningKey(key: JsObject): Option[SigningKey] = {
    val algorithm = (key \ "alg").asOpt[String]
    val rsaAlgorithms = Set("RS256", "RS384", "RS512")
    val ecCurves = Map(
      "P-256" -> ("ES256", "secp256r1"),
      "P-384" -> ("ES384", "secp384r1"),
      "P-521" -> ("ES512", "secp521r1")
    )
    if (!(key \ "use").asOpt[String].forall(_ == "sig") ||
      !(key \ "key_ops").asOpt[Seq[String]].forall(_.contains("verify"))) {
      return None
    }

    def integer(field: String): BigInteger = new BigInteger(1, Base64.getUrlDecoder.decode((key \ field).as[String]))
    val kid = (key \ "kid").asOpt[String]
    (key \ "kty").asOpt[String] match {
      case Some("RSA") if algorithm.forall(rsaAlgorithms.contains) =>
        val modulus = integer("n")
        require(modulus.bitLength() >= 2048, "RSA key is too small")
        val publicKey = KeyFactory.getInstance("RSA")
          .generatePublic(new RSAPublicKeySpec(modulus, integer("e")))
        Some(SigningKey(kid, algorithm, rsaAlgorithms, publicKey))
      case Some("EC") =>
        (key \ "crv").asOpt[String].flatMap(ecCurves.get)
          .filter { (supportedAlgorithm, _) => algorithm.forall(_ == supportedAlgorithm) }
          .map { (supportedAlgorithm, javaCurve) =>
            val parameters = AlgorithmParameters.getInstance("EC")
            parameters.init(new ECGenParameterSpec(javaCurve))
            val publicKey = KeyFactory.getInstance("EC").generatePublic(new ECPublicKeySpec(
              new ECPoint(integer("x"), integer("y")), parameters.getParameterSpec(classOf[ECParameterSpec])
            ))
            SigningKey(kid, algorithm, Set(supportedAlgorithm), publicKey)
          }
      case _ => None
    }
  }
}
