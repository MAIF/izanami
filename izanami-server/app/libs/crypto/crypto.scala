package libs.crypto

import java.security.MessageDigest

import org.apache.commons.codec.binary.Hex

object Sha {

  def sha512(toHash: String): Array[Byte] =
    MessageDigest.getInstance("SHA-512").digest(toHash.getBytes)

  def hexSha512(toHash: String): String =
    Hex encodeHexString MessageDigest
      .getInstance("SHA-512")
      .digest(toHash.getBytes)

}
