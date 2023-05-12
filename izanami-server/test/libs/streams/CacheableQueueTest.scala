package libs.streams

import akka.actor.ActorSystem
import akka.stream.{Materializer, OverflowStrategy}
import akka.stream.scaladsl.{Sink, Source}
import org.scalatest.concurrent.Eventually
import org.scalatest.time.{Millis, Seconds, Span}
import org.scalatest.{MustMatchers, OptionValues, WordSpec}

import java.util.concurrent.atomic.AtomicBoolean
import scala.util.{Failure, Try}
import scala.concurrent.duration.DurationInt
import scala.concurrent.{Await, Future}

class CacheableQueueTest extends WordSpec with MustMatchers with OptionValues with Eventually {

  implicit val system = ActorSystem()
  implicit val mat    = Materializer(system)

  import system.dispatcher

  "Cacheable queue" must {

    "handle publish fail with backpressure strategy" in {
      eventually(timeout(60.seconds), interval(1.seconds)) {
        val queue = CacheableQueue[String](50, queueBufferSize = 50)

        val hasFailed = new AtomicBoolean(false)
        val allOffer = Future.sequence((0 to 60).map { i =>
          val result = queue.offer(s"message-$i")
          result.onComplete {
            case Failure(e) => hasFailed.set(true)
            case _ =>
          }
          result
        })
        Try {
          Await.result(allOffer, 20.seconds)
        }

        // Fail because too many concurrent offer
        hasFailed.get() mustBe true
      }
    }

    "handle heavy publish" in {
      val queue = CacheableQueue[String](50, queueBufferSize = 1000)

      val hasFailed = new AtomicBoolean(false)

      val allOffer = Source((0 to 1000).toList).mapAsync(50) { i =>
        val result = queue.offer(s"message-$i")
        result.onComplete {
          case Failure(e) => hasFailed.set(true)
          case _          =>
        }
        result
      }

      Await.result(allOffer.runWith(Sink.ignore), 20.seconds)

      hasFailed.get() mustBe false
    }
  }
}
