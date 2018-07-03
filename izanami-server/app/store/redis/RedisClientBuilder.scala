package store.redis

import akka.actor.ActorSystem
import env.{Master, RedisConfig, Sentinel}
import io.lettuce.core.api.StatefulRedisConnection
import io.lettuce.core.pubsub.StatefulRedisPubSubConnection
import io.lettuce.core.{RedisClient, RedisURI}
import play.api.inject.ApplicationLifecycle

import scala.concurrent.{ExecutionContext, Future}

object RedisClientBuilder {
  def redisClient(configuration: Option[RedisConfig],
                  actorSystem: ActorSystem,
                  applicationLifecycle: ApplicationLifecycle): Option[RedisWrapper] = {

    import actorSystem.dispatcher

    configuration.map {

      case Master(host, port, password, databaseId) =>
        val builder = RedisURI
          .builder()
          .withHost(host)
          .withPort(port)

        val builderWithPassword =
          password.fold(builder)(builder.withPassword)

        val builderWithDbId = databaseId.fold(builderWithPassword)(builderWithPassword.withDatabase)

        val client = RedisClient.create(builderWithDbId.build())

        applicationLifecycle.addStopHook(() => Future.successful(client.shutdown()))

        RedisWrapper(client, applicationLifecycle)
      case Sentinel(host, port, masterId, password, sentinels, databaseId) =>
        val builder: RedisURI.Builder = RedisURI.Builder
          .sentinel(host, port, masterId)

        val builderWithPassword = password.fold(builder)(builder.withPassword)
        val builderWithDbId     = databaseId.fold(builderWithPassword)(builderWithPassword.withDatabase)

        val builderWithSentinels: RedisURI.Builder = sentinels.toSeq.flatten.foldLeft(builderWithDbId) { (b, s) =>
          b.withSentinel(s.host, s.port)
        }

        val client = RedisClient.create(builderWithSentinels.build())

        applicationLifecycle.addStopHook(() => Future.successful(client.shutdown()))

        RedisWrapper(client, applicationLifecycle)
    }
  }

}

case class RedisWrapper(underlying: RedisClient, applicationLifecycle: ApplicationLifecycle)(
    implicit ec: ExecutionContext
) {

  val connection: StatefulRedisConnection[String, String] = underlying.connect()
  applicationLifecycle.addStopHook(() => Future { connection.close() })

  def connectPubSub(): StatefulRedisPubSubConnection[String, String] = {
    val connection: StatefulRedisPubSubConnection[String, String] = underlying.connectPubSub()
    applicationLifecycle.addStopHook(() => Future { connection.close() })
    connection
  }

}
