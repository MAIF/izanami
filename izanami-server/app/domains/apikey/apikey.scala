package domains.apikey

import akka.Done
import akka.actor.ActorSystem
import domains.apikey.ApikeyStore.ApikeyKey
import domains.events.EventStore
import domains.{AuthInfo, Key}
import play.api.libs.json.Json
import store._

import scala.concurrent.Future

case class Apikey(clientId: String, name: String, clientSecret: String, authorizedPattern: String) extends AuthInfo {
  override def isAllowed(auth: Option[AuthInfo]): Boolean =
    Key.isAllowed(authorizedPattern)(auth)
}

object Apikey {
  implicit val format = Json.format[Apikey]
  def isAllowed(pattern: String)(auth: Option[AuthInfo]) =
    Key.isAllowed(pattern)(auth)
}

trait ApikeyStore extends DataStore[ApikeyKey, Apikey]
object ApikeyStore {
  type ApikeyKey = Key

  def apply(jsonStore: => JsonDataStore, eventStore: EventStore, system: ActorSystem): ApikeyStore =
    new ApikeyStoreImpl(jsonStore, eventStore, system)

}

class ApikeyStoreImpl(jsonStore: => JsonDataStore, eventStore: EventStore, system: ActorSystem) extends ApikeyStore {
  import Apikey._
  import domains.events.Events._
  import store.Result._
  import system.dispatcher

  implicit val s  = system
  implicit val es = eventStore

  override def create(id: ApikeyKey, data: Apikey): Future[Result[Apikey]] =
    jsonStore.create(id, format.writes(data)).to[Apikey].andPublishEvent { r =>
      ApikeyCreated(id, r)
    }

  override def update(oldId: ApikeyKey, id: ApikeyKey, data: Apikey): Future[Result[Apikey]] =
    jsonStore
      .update(oldId, id, format.writes(data))
      .to[Apikey]
      .andPublishEvent { r =>
        ApikeyUpdated(id, data, r)
      }

  override def delete(id: ApikeyKey): Future[Result[Apikey]] =
    jsonStore.delete(id).to[Apikey].andPublishEvent { r =>
      ApikeyDeleted(id, r)
    }

  override def deleteAll(patterns: Seq[String]): Future[Result[Done]] =
    jsonStore.deleteAll(patterns)

  override def getById(id: ApikeyKey): FindResult[Apikey] =
    JsonFindResult[Apikey](jsonStore.getById(id))

  override def getByIdLike(patterns: Seq[String], page: Int, nbElementPerPage: Int): Future[PagingResult[Apikey]] =
    jsonStore
      .getByIdLike(patterns, page, nbElementPerPage)
      .map(jsons => JsonPagingResult(jsons))

  override def getByIdLike(patterns: Seq[String]): FindResult[Apikey] =
    JsonFindResult[Apikey](jsonStore.getByIdLike(patterns))

  override def count(patterns: Seq[String]): Future[Long] =
    jsonStore.count(patterns)
}
