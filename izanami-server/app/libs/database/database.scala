package libs.database

import akka.actor.ActorSystem
import com.datastax.driver.core.{Cluster, Session}
import elastic.api.Elastic
import env.IzanamiConfig
import play.api.inject.ApplicationLifecycle
import play.api.{Configuration, Logger}
import play.api.libs.json.JsValue
import play.modules.reactivemongo.{DefaultReactiveMongoApi, ReactiveMongoApi}
import reactivemongo.api.MongoConnection
import redis.RedisClientMasterSlaves
import store.cassandra.CassandraClient
import store.elastic.ElasticClient
import store.redis.RedisClient

trait Drivers {
  def redisClient: Option[RedisClientMasterSlaves]
  def cassandraClient: Option[(Cluster, Session)]
  def elasticClient: Option[Elastic[JsValue]]
  def mongoApi: Option[ReactiveMongoApi]
}

object Drivers {
  def apply(izanamiConfig: IzanamiConfig, configuration: Configuration, applicationLifecycle: ApplicationLifecycle)(
      implicit system: ActorSystem
  ): Drivers = {

    def getRedisClient: Option[RedisClientMasterSlaves] =
      RedisClient.redisClient(izanamiConfig.db.redis, system)

    def getCassandraClient: Option[(Cluster, Session)] =
      CassandraClient.cassandraClient(izanamiConfig.db.cassandra)

    def getElasticClient: Option[Elastic[JsValue]] =
      izanamiConfig.db.elastic.map(c => ElasticClient(c, system))

    def getMongoApi: Option[ReactiveMongoApi] = izanamiConfig.db.mongo.map { c =>
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
    new Drivers {
      override def redisClient: Option[RedisClientMasterSlaves] = getRedisClient
      override def cassandraClient: Option[(Cluster, Session)]  = getCassandraClient
      override def elasticClient: Option[Elastic[JsValue]]      = getElasticClient
      override def mongoApi: Option[ReactiveMongoApi]           = getMongoApi

    }
  }
}
