package domains.events.impl

import akka.actor.{Actor, ActorSystem, Props}
import akka.stream.scaladsl.{Source, SourceQueueWithComplete}
import akka.stream.{ActorMaterializer, Materializer}
import akka.{Done, NotUsed}
import domains.Domain.Domain
import domains.configuration.PlayModule
import domains.events.EventLogger._
import domains.events.EventStore
import domains.events.Events.IzanamiEvent
import libs.streams.CacheableQueue
import domains.errors.IzanamiErrors
import env.InMemoryEventsConfig
import store.datastore.DataStoreLayerContext
import zio.{IO, Task, ZLayer}

import scala.util.Try

object BasicEventStore {
  def live(config: InMemoryEventsConfig): ZLayer[DataStoreLayerContext, Throwable, EventStore] = ZLayer.fromFunction {
    mix =>
      implicit val system: ActorSystem = mix.get[PlayModule.Service].system
      new BasicEventStore(config)
  }
}

class BasicEventStore(config: InMemoryEventsConfig)(implicit system: ActorSystem) extends EventStore.Service {

  logger.info("Starting default event store")

  private val queue = CacheableQueue[IzanamiEvent](500, queueBufferSize = config.backpressureBufferSize)
  system.actorOf(EventStreamActor.props(queue))

  override def publish(event: IzanamiEvent): IO[IzanamiErrors, Done] =
    //Already published
    Task {
      system.eventStream.publish(event)
      Done
    }.orDie

  override def events(
      domains: Seq[Domain],
      patterns: Seq[String],
      lastEventId: Option[Long]
  ): Source[IzanamiEvent, NotUsed] =
    lastEventId match {
      case Some(_) =>
        queue.sourceWithCache
          .via(dropUntilLastId(lastEventId))
          .filter(eventMatch(patterns, domains))
      case None =>
        queue.rawSource
          .filter(eventMatch(patterns, domains))
    }

  override def check(): Task[Unit] = IO.succeed(())
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
