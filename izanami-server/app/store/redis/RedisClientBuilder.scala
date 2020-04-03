package store.redis

import akka.actor.ActorSystem
import env.{Master, RedisConfig, Sentinel}
import io.lettuce.core.api.StatefulRedisConnection
import io.lettuce.core.pubsub.StatefulRedisPubSubConnection
import io.lettuce.core.{RedisClient, RedisURI}
import play.api.inject.ApplicationLifecycle
import zio.{Managed, Task, UIO}

import scala.concurrent.{ExecutionContext, Future}

object RedisClientBuilder {

  def redisClient(configuration: Option[RedisConfig],
                  actorSystem: ActorSystem): Managed[Throwable, Option[RedisWrapper]] = {

    import actorSystem.dispatcher

    configuration
      .map {

        case Master(host, port, poolSize, password, databaseId) =>
          Managed
            .make(Task {
              val builder = RedisURI
                .builder()
                .withHost(host)
                .withPort(port)

              val builderWithPassword =
                password.fold(builder)(builder.withPassword)

              val builderWithDbId = databaseId.fold(builderWithPassword)(builderWithPassword.withDatabase)

              RedisClient.create(builderWithDbId.build())
            })({ client =>
              UIO(client.shutdown())
            })
            .map(client => Some(RedisWrapper(client, poolSize)))

        case Sentinel(host, port, poolSize, masterId, password, sentinels, databaseId) =>
          Managed
            .make(Task {
              val builder: RedisURI.Builder = RedisURI.Builder
                .sentinel(host, port, masterId)

              val builderWithPassword = password.fold(builder)(builder.withPassword)
              val builderWithDbId     = databaseId.fold(builderWithPassword)(builderWithPassword.withDatabase)

              val builderWithSentinels: RedisURI.Builder = sentinels.toSeq.flatten.foldLeft(builderWithDbId) { (b, s) =>
                b.withSentinel(s.host, s.port)
              }

              RedisClient.create(builderWithSentinels.build())
            })(client => UIO(client.shutdown()))
            .map(client => Some(RedisWrapper(client, poolSize)))
      }
      .getOrElse(Managed.effectTotal(None))
  }

}

case class RedisWrapper(underlying: RedisClient, poolSize: Int)(
    implicit ec: ExecutionContext
) {

  val managedConnection: Managed[Throwable, StatefulRedisConnection[String, String]] = {
    Managed.make(Task(underlying.connect()))(c => UIO(c.close()))
  }

  val connection: StatefulRedisConnection[String, String] = underlying.connect()

  val connectPubSub: Managed[Throwable, StatefulRedisPubSubConnection[String, String]] =
    Managed
      .make(Task { underlying.connectPubSub() })(c => UIO(c.close()))

}
