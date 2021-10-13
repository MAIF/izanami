package store.redis

import akka.actor.ActorSystem
import env.{Location, Master, RedisConfig, Sentinel}
import io.lettuce.core.api.StatefulRedisConnection
import io.lettuce.core.pubsub.StatefulRedisPubSubConnection
import io.lettuce.core.{ClientOptions, RedisClient, RedisURI, SslOptions}
import zio.{Managed, Task, UIO}

import java.io.File
import scala.concurrent.ExecutionContext

object RedisClientBuilder {

  def redisClient(configuration: Option[RedisConfig],
                  actorSystem: ActorSystem): Managed[Throwable, Option[RedisWrapper]] = {

    import actorSystem.dispatcher

    configuration
      .map {
        case Master(host, port, poolSize, password, databaseId, tls, keyPass, keyStorePath, trustStorePath) =>
          Managed
            .make(Task {
              val builder = RedisURI
                .builder()
                .withHost(host)
                .withPort(port)
                .withSsl(tls)

              val builderWithPassword = password.fold(builder)(builder.withPassword)
              val builderWithDbId = databaseId.fold(builderWithPassword)(builderWithPassword.withDatabase)

              createClient(keyStorePath, trustStorePath, keyPass, builderWithDbId)
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

  private def createClient(keyStorePath: Location, trustStorePath: Location, keyPass: Option[String], builder: RedisURI.Builder) = {
    val client = RedisClient.create(builder.build())
    for {
      sslOptions <- getSslOptions(keyStorePath, trustStorePath, keyPass)
    } yield {
      val clientOptions = ClientOptions.builder.sslOptions(sslOptions).build()
      client.setOptions(clientOptions)
    }
    client
  }

  private def getSslOptions(mayBeKeyStorePath: Location, mayBeTrustStorePath: Location, mayBeKeyPass: Option[String]) =
    for {
      trustStorePath <- mayBeTrustStorePath.location
      keyStorePath <- mayBeKeyStorePath.location
      keyPass <- mayBeKeyPass
    } yield {
      SslOptions.builder()
        .jdkSslProvider()
        .keystore(new File(keyStorePath), keyPass.toCharArray)
        .truststore(new File(trustStorePath), keyPass)
        .build();
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
