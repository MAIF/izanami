package libs

import akka.actor.ActorSystem
import akka.stream.alpakka.dynamodb.DynamoClient
import com.datastax.driver.core.{Cluster, Session}
import domains.configuration.PlayModule
import elastic.es6.api.{Elastic => Elastic6}
import elastic.es7.api.{Elastic => Elastic7}
import env.IzanamiConfig
import env.configuration.IzanamiConfigModule
import libs.logs.ZLogger
import play.api.Configuration
import play.api.libs.json.JsValue
import play.modules.reactivemongo.{DefaultReactiveMongoApi, ReactiveMongoApi}
import reactivemongo.api.MongoConnection
import store.cassandra.CassandraClient
import store.elastic.{Elastic6Client, Elastic7Client}
import store.postgresql.PostgresqlClient
import store.redis.{RedisClientBuilder, RedisWrapper}
import zio.{Has, Task, ZLayer}

package object database {

  object Drivers {

    type DriverLayerContext = PlayModule with IzanamiConfigModule with ZLogger

    type RedisDriver = Has[Option[RedisWrapper]]

    val redisClientLayer: ZLayer[DriverLayerContext, Throwable, RedisDriver] =
      ZLayer.fromFunctionManaged { mix =>
        val playModule: PlayModule.Service                   = mix.get[PlayModule.Service]
        val izanamiConfigModule: IzanamiConfigModule.Service = mix.get[IzanamiConfigModule.Service]
        implicit val actorSystem: ActorSystem                = playModule.system
        RedisClientBuilder.redisClient(izanamiConfigModule.izanamiConfig.db.redis, actorSystem)
      }

    type CassandraDriver = Has[Option[(Cluster, Session)]]

    val cassandraClientLayer: ZLayer[DriverLayerContext, Throwable, CassandraDriver] =
      ZLayer.fromFunctionManaged { mix =>
        val playModule: PlayModule.Service    = mix.get[PlayModule.Service]
        val izanamiConfig: IzanamiConfig      = mix.get[IzanamiConfigModule.Service].izanamiConfig
        implicit val actorSystem: ActorSystem = playModule.system
        CassandraClient.cassandraClient(izanamiConfig.db.cassandra).provide(Has(mix.get[ZLogger.Service]))
      }

    type Elastic6Driver = Has[Option[Elastic6[JsValue]]]

    lazy val elastic6ClientLayer: ZLayer[DriverLayerContext, Throwable, Elastic6Driver] = {
      ZLayer.fromFunction { mix =>
        val playModule: PlayModule.Service    = mix.get[PlayModule.Service]
        val izanamiConfig: IzanamiConfig      = mix.get[IzanamiConfigModule.Service].izanamiConfig
        implicit val actorSystem: ActorSystem = playModule.system
        izanamiConfig.db.elastic.map(c => Elastic6Client(c, actorSystem))
      }
    }

    type Elastic7Driver = Has[Option[Elastic7[JsValue]]]

    lazy val elastic7ClientLayer: ZLayer[DriverLayerContext, Throwable, Elastic7Driver] = {
      ZLayer.fromFunction { mix =>
        val playModule: PlayModule.Service    = mix.get[PlayModule.Service]
        val izanamiConfig: IzanamiConfig      = mix.get[IzanamiConfigModule.Service].izanamiConfig
        implicit val actorSystem: ActorSystem = playModule.system
        izanamiConfig.db.elastic.filter(_.version >= 7).map(c => Elastic7Client(c, actorSystem))
      }
    }

    type DynamoDriver = Has[Option[DynamoClient]]

    lazy val dynamoClientLayer: ZLayer[DriverLayerContext, Throwable, Has[Option[DynamoClient]]] = {
      ZLayer.fromFunctionM { mix =>
        val playModule: PlayModule.Service    = mix.get[PlayModule.Service]
        val izanamiConfig: IzanamiConfig      = mix.get[IzanamiConfigModule.Service].izanamiConfig
        implicit val actorSystem: ActorSystem = playModule.system
        store.dynamo.DynamoClient
          .dynamoClient(izanamiConfig.db.dynamo)
          .provide(Has(mix.get[ZLogger.Service]))
      }
    }
    type PostgresDriver = Has[Option[PostgresqlClient]]
    lazy val postgresqldriverLayer: ZLayer[DriverLayerContext, Throwable, Has[Option[PostgresqlClient]]] = {
      ZLayer.fromFunctionManaged { mix =>
        val playModule: PlayModule.Service    = mix.get[PlayModule.Service]
        val izanamiConfig: IzanamiConfig      = mix.get[IzanamiConfigModule.Service].izanamiConfig
        implicit val actorSystem: ActorSystem = playModule.system
        store.postgresql.PostgresqlClient
          .postgresqlClient(actorSystem, izanamiConfig.db.postgresql)
          .provide(Has(mix.get[ZLogger.Service]))
      }
    }

    type MongoDriver = Has[Option[ReactiveMongoApi]]

    lazy val mongoApiLayer: ZLayer[DriverLayerContext, Throwable, MongoDriver] = {
      ZLayer.fromFunctionM { mix =>
        val playModule: PlayModule.Service    = mix.get[PlayModule.Service]
        val configuration: Configuration      = playModule.configuration
        val izanamiConfig: IzanamiConfig      = mix.get[IzanamiConfigModule.Service].izanamiConfig
        implicit val actorSystem: ActorSystem = playModule.system
        izanamiConfig.db.mongo
          .map { c =>
            Task
              .fromFuture(implicit ec => MongoConnection.fromString(c.url))
              .flatMap { parsedUri =>
                val name   = c.name.getOrElse("default")
                val dbName = parsedUri.db.orElse(c.database).getOrElse("default")
                ZLogger.info(s"Creating mongo api driver with name:$name, dbName:$dbName, uri:$parsedUri") *>
                Task(
                  Some(
                    new DefaultReactiveMongoApi(
                      parsedUri,
                      dbName,
                      false,
                      configuration,
                      playModule.applicationLifecycle
                    )(actorSystem.dispatcher)
                  )
                )
              }
          }
          .getOrElse(Task(None))
          .provide(Has(mix.get[ZLogger.Service]))
      }
    }
  }
}
