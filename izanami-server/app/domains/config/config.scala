package domains.config

import akka.Done
import akka.actor.ActorSystem
import domains.config.ConfigStore._
import domains.events.EventStore
import domains.{AuthInfo, Key}
import play.api.libs.json.{JsValue, Json}
import store._

import scala.concurrent.Future

case class Config(id: ConfigKey, value: JsValue) {

  def isAllowed = Key.isAllowed(id) _

}

object Config {
  implicit val format = Json.format[Config]

  def isAllowed(key: Key)(auth: Option[AuthInfo]) = Key.isAllowed(key)(auth)
}

trait ConfigStore extends DataStore[ConfigKey, Config]

object ConfigStore {

  type ConfigKey = Key

  def apply(jsonStore: JsonDataStore, eventStore: EventStore, system: ActorSystem): ConfigStore =
    new ConfigStoreImpl(jsonStore, eventStore, system)
}

class ConfigStoreImpl(jsonStore: JsonDataStore, eventStore: EventStore, system: ActorSystem) extends ConfigStore {

  import Config._
  import store.Result._
  import system.dispatcher
  implicit val s  = system
  implicit val es = eventStore
  import domains.events.Events._

  override def create(id: ConfigKey, data: Config): Future[Result[Config]] =
    jsonStore.create(id, format.writes(data)).to[Config].andPublishEvent { r =>
      ConfigCreated(id, r)
    }

  override def update(oldId: ConfigKey, id: ConfigKey, data: Config): Future[Result[Config]] =
    jsonStore
      .update(oldId, id, format.writes(data))
      .to[Config]
      .andPublishEvent { r =>
        ConfigUpdated(id, data, r)
      }

  override def delete(id: ConfigKey): Future[Result[Config]] =
    jsonStore.delete(id).to[Config].andPublishEvent { r =>
      ConfigDeleted(id, r)
    }

  override def deleteAll(patterns: Seq[String]): Future[Result[Done]] =
    jsonStore.deleteAll(patterns)

  override def getById(id: ConfigKey): FindResult[Config] =
    JsonFindResult[Config](jsonStore.getById(id))

  override def getByIdLike(patterns: Seq[String], page: Int, nbElementPerPage: Int): Future[PagingResult[Config]] =
    jsonStore
      .getByIdLike(patterns, page, nbElementPerPage)
      .map(jsons => JsonPagingResult(jsons))

  override def getByIdLike(patterns: Seq[String]): FindResult[Config] =
    JsonFindResult[Config](jsonStore.getByIdLike(patterns))

  override def count(patterns: Seq[String]): Future[Long] =
    jsonStore.count(patterns)

}
