package libs

import java.util.concurrent.atomic.AtomicLong
import cats.implicits._

import scala.util.Random

class IdGenerator(generatorId: Long) {
  def nextId(): Long = IdGenerator.nextId(generatorId)
}

object IdGenerator {

  private[this] val CHARACTERS =
    "ABCDEFGHIJKLMNOPQRSTUVWXYZabcdefghijklmnopqrstuvwxyz0123456789".toCharArray
      .map(_.toString)
  private[this] val EXTENDED_CHARACTERS =
    "ABCDEFGHIJKLMNOPQRSTUVWXYZabcdefghijklmnopqrstuvwxyz0123456789*$%)([]!=+-_:/;.><&".toCharArray
      .map(_.toString)
  private[this] val INIT_STRING = for (i <- 0 to 15)
    yield Integer.toHexString(i)

  private[this] val minus         = 1288834974657L
  private[this] val counter       = new AtomicLong(-1L)
  private[this] val lastTimestamp = new AtomicLong(-1L)

  def apply(generatorId: Long) = new IdGenerator(generatorId)

  def nextId(generatorId: Long): Long = synchronized {
    if (generatorId > 1024L)
      throw new RuntimeException("Generator id can't be larger than 1024")
    val timestamp = System.currentTimeMillis
    if (timestamp < lastTimestamp.get())
      throw new RuntimeException("Clock is running backward. Sorry :-(")
    lastTimestamp.set(timestamp)
    counter.compareAndSet(4095, -1L)
    ((timestamp - minus) << 22L) | (generatorId << 10L) | counter
      .incrementAndGet()
  }

  def uuid: String =
    (for {
      c <- 0 to 36
    } yield
      c match {
        case i if i === 9 || i === 14 || i === 19 || i === 24 => "-"
        case i if i === 15                                    => "4"
        case i if c === 20                                    => INIT_STRING((Random.nextDouble() * 4.0).toInt | 8)
        case i                                                => INIT_STRING((Random.nextDouble() * 15.0).toInt | 0)
      }).mkString("")

  def token(characters: Array[String], size: Int): String =
    (for {
      i <- 0 to size - 1
    } yield characters(Random.nextInt(characters.size))).mkString("")

  def token(size: Int): String         = token(CHARACTERS, size)
  def token: String                    = token(64)
  def extendedToken(size: Int): String = token(EXTENDED_CHARACTERS, size)
  def extendedToken: String            = token(EXTENDED_CHARACTERS, 64)
}
