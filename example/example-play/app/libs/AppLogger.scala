package libs
import play.api.{Logger, MarkerContext}

object AppLogger {

  private val logger = Logger("application")

  def debug(message: => String)(implicit mc: MarkerContext): Unit = {
    logger.debug(message)
  }

  def info(message: => String)(implicit mc: MarkerContext): Unit = {
    logger.info(message)
  }

  def error(message: => String)(implicit mc: MarkerContext): Unit = {
    logger.error(message)
  }
  def error(message: => String, error: => Throwable)(implicit mc: MarkerContext): Unit = {
    logger.error(message, error)
  }
}
