package fr.maif.izanami.security

import fr.maif.izanami.env.Env
import fr.maif.izanami.security.JwtService.decodeJWT
import fr.maif.izanami.security.JwtService.encrypt
import pdi.jwt.JwtAlgorithm
import pdi.jwt.JwtClaim
import pdi.jwt.JwtJson
import play.api.libs.json.JsValue

import java.time.Instant
import java.util.Base64
import javax.crypto.Cipher
import javax.crypto.spec.SecretKeySpec
import scala.util.Try

class JwtService(env: Env) {
  def generateToken(username: String, content: JsValue = null): String = {
    val secondsSinceEpoch = Instant.now().getEpochSecond
    var claim             = JwtClaim(
      issuer = Some("Izanami"),
      subject = Some(username),
      expiration = Some(secondsSinceEpoch + 3600),
      notBefore = Some(secondsSinceEpoch - 60),
      issuedAt = Some(secondsSinceEpoch),
      audience = Some(Set(env.expositionUrl))
    )
    claim = Option(content).map(c => claim.withContent(c.toString())).getOrElse(claim)
    encrypt(JwtJson.encode(
      claim,
      env.configuration.get[String]("app.authentication.secret"),
      JwtAlgorithm.HS256
    ), env.encryptionKey)
  }

  def parseJWT(token: String): Try[JwtClaim] =
    decodeJWT(token, env.configuration.get[String]("app.authentication.secret"), env.encryptionKey)
}

object JwtService {
  def encrypt(subject: String, secret: SecretKeySpec): String = {
    val cipher: Cipher = Cipher.getInstance("AES")
    cipher.init(Cipher.ENCRYPT_MODE, secret)

    new String(Base64.getUrlEncoder.encode(cipher.doFinal(subject.getBytes())))
  }

  def decrypt(subject: String, secret: SecretKeySpec): String = {
    val cipher: Cipher = Cipher.getInstance("AES")
    cipher.init(Cipher.DECRYPT_MODE, secret)
    new String(cipher.doFinal(Base64.getUrlDecoder.decode(subject.getBytes())))
  }

  def decodeJWT(token: String, signingSecret: String, secret: SecretKeySpec): Try[JwtClaim] = {
    // Factorize with AuthAction code
    JwtJson
      .decode(decrypt(token, secret), signingSecret, Seq(JwtAlgorithm.HS256))
  }
}
