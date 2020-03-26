package libs
import play.api.{MarkerContext, Logger => PlayLogger}
import zio.{UIO, URIO, ZIO}

package object logs {

  type ZLogger = zio.Has[ZLogger.Service]

  object ZLogger {

    trait Service {
      import zio._
      def logger(name: String): ZLogger.Service

      def debug(message: => String)(implicit mc: MarkerContext): UIO[Unit]

      def debug(message: => String, error: => Throwable)(implicit mc: MarkerContext): UIO[Unit]

      def info(message: => String)(implicit mc: MarkerContext): UIO[Unit]

      def error(message: => String)(implicit mc: MarkerContext): UIO[Unit]

      def error(message: => String, error: => Throwable)(implicit mc: MarkerContext): UIO[Unit]
    }

    def apply(loggerName: String): URIO[ZLogger, ZLogger.Service] =
      ZIO.access(_.get.logger(loggerName))

    def debug(message: => String)(implicit mc: MarkerContext): URIO[ZLogger, Unit] =
      ZIO.accessM(_.get.debug(message))
    def debug(message: => String, error: => Throwable)(implicit mc: MarkerContext): URIO[ZLogger, Unit] =
      ZIO.accessM(_.get.debug(message, error))
    def info(message: => String)(implicit mc: MarkerContext): URIO[ZLogger, Unit] =
      ZIO.accessM(_.get.info(message))
    def error(message: => String)(implicit mc: MarkerContext): URIO[ZLogger, Unit] =
      ZIO.accessM(_.get.error(message))
    def error(message: => String, error: => Throwable)(implicit mc: MarkerContext): URIO[ZLogger, Unit] =
      ZIO.accessM(_.get.error(message, error))
  }

  class ProdLogger(val logger: PlayLogger = PlayLogger("izanami")) extends ZLogger.Service {

    override def logger(name: String): ZLogger.Service = new ProdLogger(PlayLogger(name))

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
}
