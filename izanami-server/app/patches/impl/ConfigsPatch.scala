package patches.impl

import akka.Done
import akka.actor.ActorSystem
import akka.stream.{ActorMaterializer, Materializer}
import akka.stream.scaladsl.Sink
import com.datastax.driver.core.{Cluster, Session}
import domains.config.ConfigStore.ConfigKey
import domains.config.{Config, ConfigStore}
import elastic.api.Elastic
import env._
import patches.PatchInstance
import play.api.Logger
import play.api.inject.ApplicationLifecycle
import play.api.libs.json.{JsValue, Json}
import play.modules.reactivemongo.ReactiveMongoApi
import redis.RedisClientMasterSlaves
import store.JsonDataStore
import store.cassandra.CassandraJsonDataStore
import store.elastic.ElasticJsonDataStore
import store.leveldb.LevelDBJsonDataStore
import store.mongo.MongoJsonDataStore
import store.redis.RedisJsonDataStore

import scala.concurrent.Future

private[impl] case class OldConfig(id: ConfigKey, value: String)

private[impl] object OldConfig {
  val format = Json.format[OldConfig]
}

class ConfigsPatch(
    izanamiConfig: IzanamiConfig,
    configStore: => ConfigStore,
    redisClient: => Option[RedisClientMasterSlaves],
    cassandraClient: => Option[(Cluster, Session)],
    elasticClient: => Option[Elastic[JsValue]],
    mongoClient: => Option[ReactiveMongoApi],
    applicationLifecycle: ApplicationLifecycle,
    actorSystem: ActorSystem
) extends PatchInstance {

  implicit val system: ActorSystem        = actorSystem
  implicit val materializer: Materializer = ActorMaterializer()

  override def patch(): Future[Done] = {

    val conf: DbDomainConfig = izanamiConfig.config.db
    Logger.info(s"Patch for configs starting for DB ${conf.`type`}")

    // format: off
    lazy val jsonDataStore: Option[JsonDataStore] = conf.`type` match {
      case InMemory    => None //Nothing to do Here, data are transcient
      case Redis       => redisClient.map(cli => RedisJsonDataStore(cli, conf, actorSystem))
      case LevelDB     => izanamiConfig.db.leveldb.map(levelDb => LevelDBJsonDataStore(levelDb, conf, actorSystem, applicationLifecycle))
      case Cassandra   => cassandraClient.map(c => CassandraJsonDataStore(c._2, izanamiConfig.db.cassandra.get, conf, actorSystem))
      case Elastic     => elasticClient.map(es => ElasticJsonDataStore(es, izanamiConfig.db.elastic.get, conf, actorSystem))
      case Mongo       => mongoClient.map(mongo => MongoJsonDataStore(mongo, conf, actorSystem))
    }
    // format: on
    Logger.info(s"Patch for configs starting for DB ${conf.`type`} with ${jsonDataStore.getClass.getSimpleName}")
    jsonDataStore match {
      case None =>
        Logger.info(s"Empty datastore nothing to do")
        Future.successful(Done)
      case Some(store) =>
        store
          .getByIdLike(Seq("*"))
          .stream
          .mapAsync(2) { l =>
            val config: OldConfig = OldConfig.format.reads(l).get
            configStore.update(config.id, config.id, Config(config.id, Json.parse(config.value)))
          }
          .runWith(Sink.foreach {
            case Right(e) => Logger.debug(s"Config updated with success => $e")
            case Left(e)  => Logger.debug(s"Config update failure $e")
          })
    }
  }
}
