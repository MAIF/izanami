package libs.streams

import akka.NotUsed
import akka.stream.OverflowStrategy.backpressure
import akka.stream.scaladsl.{Broadcast, BroadcastHub, Flow, GraphDSL, Keep, Sink, Source, SourceQueueWithComplete, Zip}
import akka.stream.{FlowShape, Materializer, OverflowStrategies, OverflowStrategy, QueueOfferResult}
import cats.implicits._
import domains.Key
import libs.streams.CacheableQueue.{Element, QueueElement}
import libs.logs.IzanamiLogger
import play.api.libs.json.{JsValue, Reads}

import scala.concurrent.Future

object syntax {

  implicit class SourceKV(source: Source[(Key, JsValue), NotUsed]) {
    def readsKV[V](implicit reads: Reads[V]): Source[(Key, V), NotUsed] =
      source.mapConcat {
        case (k, v) =>
          reads
            .reads(v)
            .fold(
              { err =>
                IzanamiLogger.error(s"Error parsing $v : $err")
                List.empty[(Key, V)]
              },
              v => List((k, v))
            )
      }
  }
}

object Flows {

  def count[In, Out](aFlow: => Flow[In, Out, NotUsed]): Flow[In, (Out, Int), NotUsed] =
    Flow.fromGraph {
      GraphDSL.create() { implicit b =>
        import GraphDSL.Implicits._

        val bcast = b.add(Broadcast[In](2))
        val zip   = b.add(Zip[Out, Int]())
        val count = Flow[In].fold(0)((acc, _) => acc + 1)

        bcast ~> count ~> zip.in1
        bcast ~> aFlow ~> zip.in0

        FlowShape(bcast.in, zip.out)
      }
    }

}

case class CacheableQueue[T](
    queue: SourceQueueWithComplete[QueueElement[T]],
    sourceWithCache: Source[T, NotUsed],
    rawSource: Source[T, NotUsed]
) extends SourceQueueWithComplete[T] {

  override def offer(elem: T): Future[QueueOfferResult] = queue.offer(Element(elem))
  override def watchCompletion()                        = queue.watchCompletion()
  override def complete()                               = queue.complete()
  override def fail(ex: Throwable): Unit                = queue.fail(ex)
}

object CacheableQueue {

  sealed trait QueueElement[T]
  case class Fake[T]()          extends QueueElement[T]
  case class Element[T](elt: T) extends QueueElement[T]

  object QueueState {
    def empty[T](capacity: Int): QueueState[T] = Empty[T](Seq.empty, capacity: Int)
  }

  sealed trait QueueState[T] {
    def elements: Seq[T]
    def capacity: Int

    def push(elt: T): QueueState[T] = {
      val l = if (elements.size === capacity) {
        elements.dropRight(1) :+ elt
      } else {
        elements :+ elt
      }
      State(elt, l, capacity)
    }
  }
  case class Empty[T](elements: Seq[T] = Seq.empty, capacity: Int)             extends QueueState[T]
  case class State[T](current: T, elements: Seq[T] = Seq.empty, capacity: Int) extends QueueState[T]
  case class Starter[T](elements: Seq[T] = Seq.empty, capacity: Int)           extends QueueState[T]

  def apply[T](
      capacity: Int,
      queueBufferSize: Int = 50,
      broadcastCapacity: Int = 256,
      overflowStrategy: OverflowStrategy = OverflowStrategy.backpressure
  )(
      implicit mat: Materializer
  ): CacheableQueue[T] = {

    val (queue, rawSource: Source[QueueElement[T], NotUsed]) =
      Source
        .queue[QueueElement[T]](queueBufferSize, OverflowStrategy.backpressure)
        .toMat(BroadcastHub.sink(2))(Keep.both)
        .run()

    val (_, tmpSource: Source[QueueState[T], NotUsed]) = rawSource
      .scan(QueueState.empty[T](capacity)) {
        case (state, Fake()) =>
          Starter[T](state.elements, state.capacity)
        case (state, Element(elt)) =>
          state.push(elt)
      }
      .toMat(BroadcastHub.sink(broadcastCapacity))(Keep.both)
      .run()

    val source: Source[T, NotUsed] = tmpSource.statefulMapConcat { () =>
      var first = true
      currentState =>
        currentState match {
          case s: Starter[T] if first =>
            first = false
            s.elements.toList
          case other if first =>
            first = false
            other.elements.toList
          case _: Starter[T] =>
            List.empty
          case State(current, _, _) =>
            List(current)
          case other =>
            IzanamiLogger.error(s"Weird message in cacheable queue, this shouldn't append $other")
            List.empty
        }
    }

    val sourceWithCache = source.mapMaterializedValue { n =>
      queue.offer(Fake[T]())
      n
    }
    val nativeSource = rawSource
      .collect {
        case Element(e) => e
      }
    sourceWithCache.runWith(Sink.ignore)
    nativeSource.runWith(Sink.ignore)
    CacheableQueue(
      queue,
      sourceWithCache,
      nativeSource
    )
  }

}
