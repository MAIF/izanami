package libs
import java.util.concurrent.CompletionStage

import scala.concurrent.{Future, Promise}

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
