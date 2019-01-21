package libs.database

import akka.actor.ActorSystem
import akka.stream.alpakka.dynamodb.scaladsl.DynamoClient
import cats.effect.{Async, ConcurrentEffect, ContextShift}
import com.datastax.driver.core.{Cluster, Session}
import doobie.util.transactor.Transactor
import elastic.api.Elastic
import env.IzanamiConfig
import play.api.inject.ApplicationLifecycle
import play.api.{Configuration, Logger}
import play.api.libs.json.JsValue
import play.modules.reactivemongo.{DefaultReactiveMongoApi, ReactiveMongoApi}
import reactivemongo.api.MongoConnection
import store.cassandra.CassandraClient
import store.elastic.ElasticClient
import store.postgresql.PostgresqlClient
import store.redis.{RedisClientBuilder, RedisWrapper}

trait Drivers[F[_]] {
  def redisClient: Option[RedisWrapper]
  def cassandraClient: Option[(Cluster, Session)]
  def elasticClient: Option[Elastic[JsValue]]
  def mongoApi: Option[ReactiveMongoApi]
  def dynamoClient: Option[DynamoClient]
  def postgresqlClient: Option[PostgresqlClient[F]]
}

object Drivers {
  def apply[F[_]: ContextShift: ConcurrentEffect](izanamiConfig: IzanamiConfig,
                                                  configuration: Configuration,
                                                  applicationLifecycle: ApplicationLifecycle)(
      implicit system: ActorSystem
  ): Drivers[F] =
    new Drivers[F] {

      lazy val getRedisClient: Option[RedisWrapper] =
        RedisClientBuilder.redisClient(izanamiConfig.db.redis, system, applicationLifecycle)

      lazy val getCassandraClient: Option[(Cluster, Session)] =
        CassandraClient.cassandraClient(izanamiConfig.db.cassandra)

      lazy val getElasticClient: Option[Elastic[JsValue]] =
        izanamiConfig.db.elastic.map(c => ElasticClient(c, system))

      lazy val getDynamoClient: Option[DynamoClient] =
        store.dynamo.DynamoClient.dynamoClient(izanamiConfig.db.dynamo)

      lazy val pgDriver =
        store.postgresql.PostgresqlClient.postgresqlClient(system, applicationLifecycle, izanamiConfig.db.postgresql)

      lazy val getMongoApi: Option[ReactiveMongoApi] = izanamiConfig.db.mongo.map { c =>
        val name      = c.name.getOrElse("default")
        val parsedUri = MongoConnection.parseURI(c.url).get
        val dbName    = parsedUri.db.orElse(c.database).getOrElse("default")
        Logger.info(s"Creating mongo api driver with name:$name, dbName:$dbName, uri:$parsedUri")
        new DefaultReactiveMongoApi(
          name,
          parsedUri,
          dbName,
          false,
          configuration,
          applicationLifecycle
        )
      }
      override def redisClient: Option[RedisWrapper]             = getRedisClient
      override def cassandraClient: Option[(Cluster, Session)]   = getCassandraClient
      override def elasticClient: Option[Elastic[JsValue]]       = getElasticClient
      override def mongoApi: Option[ReactiveMongoApi]            = getMongoApi
      override def dynamoClient: Option[DynamoClient]            = getDynamoClient
      override def postgresqlClient: Option[PostgresqlClient[F]] = pgDriver
    }
}
