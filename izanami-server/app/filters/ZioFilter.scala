package filters

import akka.stream.Materializer
import play.api.mvc.{Filter, RequestHeader, Result}
import zio._

import scala.concurrent.Future

abstract class ZioFilter[R](implicit r: Runtime[R], implicit val mat: Materializer) extends Filter {

  override def apply(f: RequestHeader => Future[Result])(rh: RequestHeader): Future[Result] =
    r.unsafeRunToFuture(filter(f.andThen(fResult => Task.fromFuture(_ => fResult)))(rh))

  def filter(f: RequestHeader => Task[Result])(rh: RequestHeader): ZIO[R, Throwable, Result]

}
