package filters

import akka.stream.Materializer
import libs.http.HttpContext
import play.api.mvc.{Filter, RequestHeader, Result}
import zio._

import scala.concurrent.Future

abstract class ZioFilter[R <: Has[_]](implicit r: HttpContext[R], implicit val mat: Materializer) extends Filter {

  override def apply(f: RequestHeader => Future[Result])(rh: RequestHeader): Future[Result] = {

    val value: ZIO[R, Throwable, Result]             = filter(f.andThen(fResult => Task.fromFuture(_ => fResult)))(rh)
    val runnableIO: ZIO[zio.ZEnv, Throwable, Result] = value.provideLayer(r)
    val future: Future[Result]                       = Runtime.default.unsafeRunToFuture(runnableIO)
    future
  }

  def filter(f: RequestHeader => Task[Result])(rh: RequestHeader): ZIO[R, Throwable, Result]

}
