package domains.events.impl

import akka.actor.ActorSystem
import akka.stream.scaladsl.Source
import akka.{Done, NotUsed}
import domains.Domain.Domain
import domains.configuration.PlayModule
import domains.events.Events.IzanamiEvent
import domains.events.{EventLogger, EventStore}
import env.RedisEventsConfig
import io.lettuce.core.api.StatefulRedisConnection
import io.lettuce.core.pubsub.{RedisPubSubListener, StatefulRedisPubSubConnection}
import libs.streams.CacheableQueue
import libs.logs.IzanamiLogger
import play.api.libs.json.{JsError, JsResult, JsSuccess, Json}
import domains.errors.IzanamiErrors
import libs.database.Drivers.RedisDriver
import store.redis.RedisWrapper
import zio.{IO, Managed, Task, ZLayer}

import scala.util.Failure

object RedisEventStore {

  def live(config: RedisEventsConfig): ZLayer[RedisDriver with PlayModule, Throwable, EventStore] =
    ZLayer.fromFunctionManaged { mix =>
      val playModule        = mix.get[PlayModule.Service]
      val Some(redisDriver) = mix.get[Option[RedisWrapper]]
      create(redisDriver, config, playModule.system)
    }

  def create(client: RedisWrapper,
             config: RedisEventsConfig,
             system: ActorSystem): Managed[Throwable, RedisEventStore] =
    (client.managedConnection <*>
    client.connectPubSub).map {
      case (connection, connectionPubSub) =>
        new RedisEventStore(connection, connectionPubSub, config, system)
    }
}

class RedisEventStore(connection: StatefulRedisConnection[String, String],
                      connectionPubSub: StatefulRedisPubSubConnection[String, String],
                      config: RedisEventsConfig,
                      system: ActorSystem)
    extends EventStore.Service {

  import EventLogger._
  import system.dispatcher

  implicit private val s = system

  logger.info(s"Starting redis event store")

  private val queue = CacheableQueue[IzanamiEvent](500, queueBufferSize = 500)

  connectionPubSub.addListener(new RedisPubSubListener[String, String] {
    private def publishMessage(message: String) = {
      val json                           = Json.parse(message)
      val result: JsResult[IzanamiEvent] = json.validate[IzanamiEvent]
      result match {
        case JsSuccess(e, _) =>
          logger.debug(s"Receiving new event $e from Redis topic")
          queue.offer(e).onComplete {
            case Failure(e) => IzanamiLogger.error(s"Error publishing event to queue", e)
            case r          => IzanamiLogger.debug(s"Event published to queue $r")
          }
        case JsError(errors) =>
          logger.error(s"Error deserializing event of type ${json \ "type"} : $errors")
      }
    }

    override def message(channel: String, message: String): Unit =
      publishMessage(message)

    override def message(pattern: String, channel: String, message: String): Unit =
      publishMessage(message)

    override def subscribed(channel: String, count: Long): Unit = {}

    override def psubscribed(pattern: String, count: Long): Unit = {}

    override def unsubscribed(channel: String, count: Long): Unit = {}

    override def punsubscribed(pattern: String, count: Long): Unit = {}
  })

  connectionPubSub.async().subscribe(config.topic)

  override def publish(event: IzanamiEvent): IO[IzanamiErrors, Done] = {
    logger.debug(s"Publishing event $event to Redis topic izanamiEvents")
    s.eventStream.publish(event)
    IO.effectAsync[Throwable, Done] { cb =>
        connection
          .async()
          .publish(config.topic, Json.stringify(event.toJson))
          .whenComplete { (_, e) =>
            if (e != null) {
              logger.error(s"Error publishing event to Redis", e)
              cb(IO.fail(e))
            } else {
              cb(IO.succeed(Done))
            }
          }
      }
      .orDie
  }

  override def events(domains: Seq[Domain],
                      patterns: Seq[String],
                      lastEventId: Option[Long]): Source[IzanamiEvent, NotUsed] =
    lastEventId match {
      case Some(_) =>
        queue.sourceWithCache
          .via(dropUntilLastId(lastEventId))
          .filter(eventMatch(patterns, domains))
      case None =>
        queue.rawSource
          .filter(eventMatch(patterns, domains))
    }

  override def check(): Task[Unit] = IO.effectAsync { cb =>
    connection
      .async()
      .get("test")
      .whenComplete((_, e) => {
        if (e != null) {
          cb(IO.fail(e))
        } else {
          cb(IO.succeed(()))
        }
      })
  }
}
