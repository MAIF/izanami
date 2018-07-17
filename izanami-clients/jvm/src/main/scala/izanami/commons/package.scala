package izanami

import akka.actor.ActorSystem
import akka.http.scaladsl.util.FastFuture

import scala.concurrent.Future
import scala.util.control.NonFatal

package object commons {

  def handleFailure[T](
      errorStrategy: ErrorStrategy
  )(v: T)(implicit system: ActorSystem): PartialFunction[Throwable, Future[T]] =
    errorStrategy match {
      case Crash => {
        case e =>
          system.log.error(e, "Error during call")
          FastFuture.failed(e)
      }
      case RecoverWithFallback => {
        case NonFatal(e) =>
          system.log.error(e, "Error during call, recovering ...")
          FastFuture.successful(v)
      }
    }
}
