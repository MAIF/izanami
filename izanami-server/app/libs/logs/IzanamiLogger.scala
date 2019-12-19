package libs.logs
import play.api.{MarkerContext, Logger => PlayLogger}
import zio.{UIO, ZIO}

trait Logger {
  import zio._
  def logger(name: String): Logger

  def debug(message: => String)(implicit mc: MarkerContext): UIO[Unit]

  def debug(message: => String, error: => Throwable)(implicit mc: MarkerContext): UIO[Unit]

  def info(message: => String)(implicit mc: MarkerContext): UIO[Unit]

  def error(message: => String)(implicit mc: MarkerContext): UIO[Unit]

  def error(message: => String, error: => Throwable)(implicit mc: MarkerContext): UIO[Unit]
}

trait LoggerModule {
  def logger: Logger
}

object Logger {

  def apply(loggerName: String): ZIO[LoggerModule, Nothing, Logger] =
    ZIO.access(_.logger.logger(loggerName))

  def debug(message: => String)(implicit mc: MarkerContext): ZIO[LoggerModule, Nothing, Unit] =
    ZIO.accessM(_.logger.debug(message))
  def debug(message: => String, error: => Throwable)(implicit mc: MarkerContext): ZIO[LoggerModule, Nothing, Unit] =
    ZIO.accessM(_.logger.debug(message, error))
  def info(message: => String)(implicit mc: MarkerContext): ZIO[LoggerModule, Nothing, Unit] =
    ZIO.accessM(_.logger.info(message))
  def error(message: => String)(implicit mc: MarkerContext): ZIO[LoggerModule, Nothing, Unit] =
    ZIO.accessM(_.logger.error(message))
  def error(message: => String, error: => Throwable)(implicit mc: MarkerContext): ZIO[LoggerModule, Nothing, Unit] =
    ZIO.accessM(_.logger.error(message, error))
}

class ProdLogger(val logger: PlayLogger = PlayLogger("izanami")) extends Logger {

  override def logger(name: String): Logger = new ProdLogger(PlayLogger(name))

  def debug(message: => String)(implicit mc: MarkerContext): UIO[Unit] =
    UIO(logger.debug(message)(mc))

  def debug(message: => String, error: => Throwable)(implicit mc: MarkerContext): UIO[Unit] =
    UIO(logger.debug(message, error)(mc))

  def info(message: => String)(implicit mc: MarkerContext): UIO[Unit] =
    UIO(logger.info(message)(mc))

  def error(message: => String)(implicit mc: MarkerContext): UIO[Unit] =
    UIO(logger.error(message)(mc))

  def error(message: => String, error: => Throwable)(implicit mc: MarkerContext): UIO[Unit] =
    UIO(logger.error(message, error)(mc))
}

object IzanamiLogger {

  val logger = PlayLogger("izanami")

  def debug(message: => String)(implicit mc: MarkerContext): Unit =
    logger.debug(message)(mc)

  def debug(message: => String, error: => Throwable)(implicit mc: MarkerContext): Unit =
    logger.debug(message, error)(mc)

  def info(message: => String)(implicit mc: MarkerContext): Unit =
    logger.info(message)(mc)

  def error(message: => String)(implicit mc: MarkerContext): Unit =
    logger.error(message)(mc)

  def error(message: => String, error: => Throwable)(implicit mc: MarkerContext): Unit =
    logger.error(message, error)(mc)
}
