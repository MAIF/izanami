package domains.events.impl

import java.net.InetSocketAddress

import akka.{Done, NotUsed}
import akka.actor.{ActorSystem, PoisonPill, Props}
import akka.stream.{ActorMaterializer, OverflowStrategy}
import akka.stream.scaladsl.{BroadcastHub, Keep, Source, SourceQueueWithComplete}
import akka.util.ByteString
import domains.Domain.Domain
import domains.events.EventLogger.logger
import domains.events.{EventLogger, EventStore}
import domains.events.Events.IzanamiEvent
import env.RedisEventsConfig
import play.api.Logger
import play.api.libs.json.{JsError, JsResult, JsSuccess, Json}
import redis.RedisClientMasterSlaves
import redis.actors.RedisSubscriberActor
import redis.api.pubsub.{Message, PMessage}

import scala.concurrent.Future
import scala.util.{Failure, Try}

class RedisEventStore(client: RedisClientMasterSlaves, config: RedisEventsConfig, system: ActorSystem)
    extends EventStore {

  import EventLogger._

  import system.dispatcher

  implicit private val s   = system
  implicit private val mat = ActorMaterializer()

  logger.info(s"Starting redis event store")

  private val (queue, source) = Source
    .queue[IzanamiEvent](1000, OverflowStrategy.dropHead)
    .toMat(BroadcastHub.sink[IzanamiEvent](1024))(Keep.both)
    .run()

  val actor = system.actorOf(
    Props(new SubscribeActor(client, config, queue))
      .withDispatcher("rediscala.rediscala-client-worker-dispatcher"),
    "eventRedisActor"
  )

  override def publish(event: IzanamiEvent): Future[Done] = {
    logger.debug(s"Publishing event $event to Redis topic izanamiEvents")
    val publish =
      client.publish(config.topic, Json.stringify(event.toJson)).map { _ =>
        Done
      }
    publish.onComplete {
      case Failure(e) =>
        logger.error(s"Error publishing event to Redis", e)
      case _ =>
    }
    publish
  }

  override def events(domains: Seq[Domain],
                      patterns: Seq[String],
                      lastEventId: Option[Long]): Source[IzanamiEvent, NotUsed] =
    source
      .via(dropUntilLastId(lastEventId))
      .filter(eventMatch(patterns, domains))

  override def close() = actor ! PoisonPill
}

private[events] class SubscribeActor(client: RedisClientMasterSlaves,
                                     config: RedisEventsConfig,
                                     queue: SourceQueueWithComplete[IzanamiEvent])
    extends RedisSubscriberActor(
      new InetSocketAddress(client.master.host, client.master.port),
      Seq(config.topic),
      Nil,
      authPassword = client.master.password,
      onConnectStatus = connected => {
        logger.info(s"Connected to redis pub sub: $connected")
      }
    ) {

  import context.dispatcher

  override def onMessage(m: Message): Unit =
    sendMessage(m.data)

  override def onPMessage(pm: PMessage): Unit =
    sendMessage(pm.data)

  private def sendMessage(bs: ByteString) = {
    val json                           = Json.parse(bs.utf8String)
    val result: JsResult[IzanamiEvent] = json.validate[IzanamiEvent]
    result match {
      case JsSuccess(e, _) =>
        logger.debug(s"Receiving new event $e from Redis topic")
        queue.offer(e).onComplete {
          case Failure(e) => Logger.error(s"Error publishing event to queue", e)
          case r          => Logger.debug(s"Event published to queue $r")
        }
      case JsError(errors) =>
        logger.error(s"Error deserializing event of type ${json \ "type"} : $errors")
    }
  }

  override def preStart(): Unit = {
    super.preStart()
    logger.debug(s"Creating redis events handler")
    queue
      .watchCompletion()
      .onComplete(_ => Try(context.system.eventStream.unsubscribe(self)))
  }

  override def postStop(): Unit = {
    super.postStop()
    queue.complete()
  }
}
