package domains.webhook

import java.time.LocalDateTime

import akka.{Done, NotUsed}
import akka.actor.ActorSystem
import akka.stream.scaladsl.Source
import domains.Domain.Domain
import domains.events.EventStore
import domains.events.Events.{WebhookCreated, WebhookDeleted, WebhookUpdated}
import domains.webhook.WebhookStore._
import domains.webhook.notifications.WebHooksActor
import domains.{AuthInfo, Domain, Key}
import env.{DbDomainConfig, WebhookConfig}
import play.api.libs.json._
import play.api.libs.ws._
import store.Result.{ErrorMessage, Result}
import store.SourceUtils.SourceKV
import store._

import scala.concurrent.Future

case class Webhook(clientId: WebhookKey,
                   callbackUrl: String,
                   domains: Seq[Domain] = Seq.empty[Domain],
                   patterns: Seq[String] = Seq.empty[String],
                   types: Seq[String] = Seq.empty[String],
                   headers: JsObject = Json.obj(),
                   created: LocalDateTime = LocalDateTime.now(),
                   isBanned: Boolean = false) {
  def isAllowed = Key.isAllowed(clientId) _
}

object Webhook {

  import Domain._
  import play.api.libs.json._
  import playjson.rules._
  import shapeless.syntax.singleton._

  private val reads: Reads[Webhook] = jsonRead[Webhook].withRules(
    'domains ->> orElse(Seq.empty[Domain]) and
    'patterns ->> orElse(Seq.empty[String]) and
    'types ->> orElse(Seq.empty[String]) and
    'headers ->> orElse(Json.obj()) and
    'created ->> orElse(LocalDateTime.now()) and
    'isBanned ->> orElse(false)
  )

  private val writes = Json.writes[Webhook]

  implicit val format = Format(reads, writes)

  def isAllowed(key: WebhookKey)(auth: Option[AuthInfo]) =
    Key.isAllowed(key)(auth)
}

trait WebhookStore extends DataStore[WebhookKey, Webhook]

object WebhookStore {

  type WebhookKey = Key

  sealed trait WebhookMessages

  def apply(jsonStore: JsonDataStore,
            eventStore: EventStore,
            webHookConfig: WebhookConfig,
            wsClient: WSClient,
            actorSystem: ActorSystem): WebhookStore = {
    val webhookStore =
      new WebhookStoreImpl(jsonStore, webHookConfig.db, eventStore, actorSystem)
    actorSystem.actorOf(WebHooksActor.props(wsClient, eventStore, webhookStore, webHookConfig), "webhooks")
    webhookStore
  }
}

class WebhookStoreImpl(jsonStore: JsonDataStore, config: DbDomainConfig, eventStore: EventStore, system: ActorSystem)
    extends WebhookStore {

  import Webhook._
  import WebhookStore._
  import system.dispatcher
  private implicit val s  = system
  private implicit val es = eventStore

  private val lockKey = Key.Empty / "batch" / "lock"

  override def create(id: WebhookKey, data: Webhook): Future[Result[Webhook]] =
    jsonStore.create(id, format.writes(data)).to[Webhook].andPublishEvent { r =>
      WebhookCreated(id, r)
    }

  override def update(oldId: WebhookKey, id: WebhookKey, data: Webhook): Future[Result[Webhook]] =
    this.getById(oldId).one.flatMap {
      case Some(oldValue) =>
        jsonStore
          .update(oldId, id, format.writes(data))
          .to[Webhook]
          .andPublishEvent { r =>
            WebhookUpdated(id, oldValue, r)
          }
      case None =>
        Future.successful(Result.errors(ErrorMessage("error.data.missing", oldId.key)))
    }

  override def delete(id: WebhookKey): Future[Result[Webhook]] =
    jsonStore.delete(id).to[Webhook].andPublishEvent { r =>
      WebhookDeleted(id, r)
    }

  override def deleteAll(patterns: Seq[String]): Future[Result[Done]] =
    jsonStore.deleteAll(patterns)

  override def getById(id: WebhookKey): FindResult[Webhook] =
    JsonFindResult[Webhook](jsonStore.getById(id))

  override def getByIdLike(patterns: Seq[String], page: Int, nbElementPerPage: Int): Future[PagingResult[Webhook]] =
    jsonStore
      .getByIdLike(patterns, page, nbElementPerPage)
      .map(jsons => JsonPagingResult(jsons))

  override def getByIdLike(patterns: Seq[String]): Source[(Key, Webhook), NotUsed] =
    jsonStore.getByIdLike(patterns).readsKV[Webhook]

  override def count(patterns: Seq[String]): Future[Long] =
    jsonStore.count(patterns)
}
