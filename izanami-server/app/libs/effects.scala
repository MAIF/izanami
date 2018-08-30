package libs
import java.util.concurrent.CompletionStage

import cats.effect.Async

import scala.concurrent.{ExecutionContext, Future}
import scala.util.{Failure, Success}

object effects {

  implicit class CSOps[T](cs: CompletionStage[T]) {
    def toF[F[_]: Async]: F[T] = Async[F].async { cb =>
      cs.whenComplete((ok, e) => {
        if (e != null) {
          cb(Left(e))
        } else {
          cb(Right(ok))
        }
      })
    }
  }

  implicit class FutureOps[T](cs: Future[T]) {
    def toF[F[_]: Async](implicit ec: ExecutionContext): F[T] = Async[F].async { cb =>
      cs.onComplete {
        case Success(ok) => cb(Right(ok))
        case Failure(e)  => cb(Left(e))
      }
    }
  }

}
