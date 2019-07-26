package libs
import java.util.concurrent.CompletionStage

import cats.effect.Async

import scala.concurrent.{ExecutionContext, Future, Promise}
import scala.util.{Failure, Success}

object effects {

  implicit class CSOps[T](cs: CompletionStage[T]) {
    def toFuture: Future[T] = {
      val p = Promise[T]
      cs.whenComplete((ok, e) => {
        if (e != null) {
          p.failure(e)
        } else {
          p.success(ok)
        }
      })
      p.future
    }
  }

}
