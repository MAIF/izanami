package izanami

import akka.http.scaladsl.util.FastFuture

import scala.concurrent.Future
import scala.util.control.NonFatal

package object commons {

  def handleFailure[T](errorStrategy: ErrorStrategy)(v: T): PartialFunction[Throwable, Future[T]] =
    errorStrategy match {
      case Crash => {
        case e =>
          FastFuture.failed(e)
      }
      case RecoverWithFallback => {
        case NonFatal(_) => FastFuture.successful(v)
      }
    }
}
