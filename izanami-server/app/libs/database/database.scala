package libs.database

import akka.actor.ActorSystem
import akka.stream.alpakka.dynamodb.DynamoClient
import com.datastax.driver.core.{Cluster, Session}
import elastic.api.Elastic
import env.IzanamiConfig
import libs.logs.IzanamiLogger
import play.api.inject.ApplicationLifecycle
import play.api.Configuration
import play.api.libs.json.JsValue
import play.modules.reactivemongo.{DefaultReactiveMongoApi, ReactiveMongoApi}
import reactivemongo.api.MongoConnection
import store.cassandra.CassandraClient
import store.elastic.ElasticClient
import store.postgresql.PostgresqlClient
import store.redis.{RedisClientBuilder, RedisWrapper}
import scala.concurrent.duration._
import scala.concurrent.Await

trait Drivers {
  def redisClient: Option[RedisWrapper]
  def cassandraClient: Option[(Cluster, Session)]
  def elasticClient: Option[Elastic[JsValue]]
  def mongoApi: Option[ReactiveMongoApi]
  def dynamoClient: Option[DynamoClient]
  def postgresqlClient: Option[PostgresqlClient]
}

object Drivers {
  def apply(izanamiConfig: IzanamiConfig, configuration: Configuration, applicationLifecycle: ApplicationLifecycle)(
      implicit system: ActorSystem
  ): Drivers =
    new Drivers {

      import system.dispatcher

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
        val parsedUri = Await.result(MongoConnection.fromString(c.url), 5.seconds)
        val dbName    = parsedUri.db.orElse(c.database).getOrElse("default")
        IzanamiLogger.info(s"Creating mongo api driver with name:$name, dbName:$dbName, uri:$parsedUri")
        new DefaultReactiveMongoApi(
          parsedUri,
          dbName,
          false,
          configuration,
          applicationLifecycle
        )
      }
      override def redisClient: Option[RedisWrapper]           = getRedisClient
      override def cassandraClient: Option[(Cluster, Session)] = getCassandraClient
      override def elasticClient: Option[Elastic[JsValue]]     = getElasticClient
      override def mongoApi: Option[ReactiveMongoApi]          = getMongoApi
      override def dynamoClient: Option[DynamoClient]          = getDynamoClient
      override def postgresqlClient: Option[PostgresqlClient]  = pgDriver
    }
}
