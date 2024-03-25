package fr.maif.izanami.datastores

import org.apache.commons.codec.binary.Hex
import org.mindrot.jbcrypt.BCrypt._

import java.security.MessageDigest

object HashUtils {
  def bcryptHash(input: String): String = {
    hashpw(input, gensalt())
  }

  def bcryptCheck(input: String, hashed: String): Boolean = {
    checkpw(input, hashed)
  }

  def sha512(toHash: String): Array[Byte] =
    MessageDigest.getInstance("SHA-512").digest(toHash.getBytes)

  def hexSha512(toHash: String): String =
    Hex encodeHexString MessageDigest
      .getInstance("SHA-512")
      .digest(toHash.getBytes)
}
