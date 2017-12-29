package domains.events.impl

import java.io.Closeable

import akka.{Done, NotUsed}
import akka.actor.{Actor, ActorSystem, Props}
import akka.http.scaladsl.util.FastFuture
import akka.stream.{ActorMaterializer, Materializer, OverflowStrategy}
import akka.stream.scaladsl.{BroadcastHub, Flow, Source, SourceQueueWithComplete}
import domains.Domain.Domain
import domains.events.EventLogger._
import domains.events.EventStore
import domains.events.Events.IzanamiEvent

import scala.concurrent.Future
import scala.util.Try

class BasicEventStore(system: ActorSystem) extends EventStore {

  private implicit val s                 = system
  private implicit val mat: Materializer = ActorMaterializer()

  logger.info("Starting default event store")

  val source = Source
    .queue[IzanamiEvent](1000, OverflowStrategy.dropBuffer)
    .mapMaterializedValue { q =>
      system.actorOf(EventStreamActor.props(q))
      NotUsed
    }
    .runWith(BroadcastHub.sink(1024))

  override def publish(event: IzanamiEvent): Future[Done] =
  //Already published
    FastFuture.successful(Done)

  override def events(domains: Seq[Domain],
                      patterns: Seq[String],
                      lastEventId: Option[Long]): Source[IzanamiEvent, NotUsed] =
    source
      .via(dropUntilLastId(lastEventId))
      .filter { eventMatch(patterns, domains) }

  override def close() = {}

}

private[events] object EventStreamActor {
  def props(queue: SourceQueueWithComplete[IzanamiEvent]) =
    Props(new EventStreamActor(queue))
}

private[events] class EventStreamActor(queue: SourceQueueWithComplete[IzanamiEvent]) extends Actor {

  import context.dispatcher

  override def receive = {
    case e: IzanamiEvent =>
      logger.debug(s"New event : $e")
      queue.offer(e)
  }

  override def preStart(): Unit = {
    queue
      .watchCompletion()
      .onComplete(_ => Try(context.system.eventStream.unsubscribe(self)))
    context.system.eventStream.subscribe(self, classOf[IzanamiEvent])
  }

  override def postStop(): Unit = {
    context.system.eventStream.unsubscribe(self)
    queue.complete()
  }
}

