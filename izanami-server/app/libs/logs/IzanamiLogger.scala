package libs.logs
import play.api.{Logger, MarkerContext}

object IzanamiLogger {

  val logger = Logger("izanami")

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
