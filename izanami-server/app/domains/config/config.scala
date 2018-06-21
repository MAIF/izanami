package domains.config

import akka.{Done, NotUsed}
import akka.actor.ActorSystem
import akka.http.scaladsl.util.FastFuture
import akka.stream.scaladsl.{Flow, Source}
import domains.config.ConfigStore._
import domains.events.EventStore
import domains.{AuthInfo, ImportResult, Key}
import play.api.libs.json.{JsError, JsSuccess, JsValue, Json}
import store.Result.ErrorMessage
import store.SourceUtils.SourceKV
import store._

import scala.concurrent.{ExecutionContext, Future}

case class Config(id: ConfigKey, value: JsValue) {

  def isAllowed = Key.isAllowed(id) _

}

object Config {
  implicit val format = Json.format[Config]

  def isAllowed(key: Key)(auth: Option[AuthInfo]) = Key.isAllowed(key)(auth)

  def importData(
      configStore: ConfigStore
  )(implicit ec: ExecutionContext): Flow[(String, JsValue), ImportResult, NotUsed] = {
    import cats.implicits._
    import store.Result.AppErrors._

    Flow[(String, JsValue)]
      .map { case (s, json) => (s, json.validate[Config]) }
      .mapAsync(4) {
        case (_, JsSuccess(obj, _)) =>
          configStore.create(obj.id, obj) map { ImportResult.fromResult }
        case (s, JsError(_)) =>
          FastFuture.successful(ImportResult.error(ErrorMessage("json.parse.error", s)))
      }
      .fold(ImportResult()) { _ |+| _ }
  }

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
    this.getById(oldId).one.flatMap {
      case Some(oldValue) =>
        jsonStore
          .update(oldId, id, format.writes(data))
          .to[Config]
          .andPublishEvent { r =>
            ConfigUpdated(id, oldValue, r)
          }
      case None =>
        Future.successful(Result.errors(ErrorMessage("error.data.missing", oldId.key)))
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

  override def getByIdLike(patterns: Seq[String]): Source[(Key, Config), NotUsed] =
    jsonStore.getByIdLike(patterns).readsKV[Config]

  override def count(patterns: Seq[String]): Future[Long] =
    jsonStore.count(patterns)

}
