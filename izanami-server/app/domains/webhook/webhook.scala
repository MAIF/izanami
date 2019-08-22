package domains.webhook

import java.time.LocalDateTime

import akka.actor.{ActorRef, ActorSystem}
import akka.http.scaladsl.util.FastFuture
import akka.NotUsed
import akka.stream.scaladsl.{Flow, Source}
import domains.Domain.Domain
import domains.events.{EventStore, EventStoreContext}
import domains.webhook.Webhook.WebhookKey
import domains._
import domains.webhook.notifications.WebHooksActor
import env.WebhookConfig
import libs.ziohelper.JsResults.jsResultToError
import play.api.libs.json._
import play.api.libs.ws.WSClient
import store._
import zio._

case class Webhook(clientId: WebhookKey,
                   callbackUrl: String,
                   domains: Seq[Domain] = Seq.empty[Domain],
                   patterns: Seq[String] = Seq.empty[String],
                   types: Seq[String] = Seq.empty[String],
                   headers: JsObject = Json.obj(),
                   created: LocalDateTime = LocalDateTime.now(),
                   isBanned: Boolean = false)

object Webhook {
  type WebhookKey = Key
}

trait WebhookDataStoreModule {
  def webhookDataStore: JsonDataStore
}

trait WebhookContext
    extends DataStoreContext
    with WebhookDataStoreModule
    with EventStoreContext
    with AuthInfoModule[WebhookContext]

object WebhookDataStore extends JsonDataStoreHelper[WebhookContext] {
  override def accessStore = _.webhookDataStore
}

object WebhookService {

  import WebhookInstances._
  import cats.implicits._
  import libs.streams.syntax._
  import domains.events.Events._
  import store.Result._

  def startHooks(wsClient: WSClient, config: WebhookConfig)(
      implicit actorSystem: ActorSystem
  ): zio.RIO[WebhookContext, ActorRef] =
    for {
      r   <- ZIO.runtime[WebhookContext]
      ref = actorSystem.actorOf(WebHooksActor.props(wsClient, config, r), "webhooks")
    } yield ref

  def create(id: WebhookKey, data: Webhook): ZIO[WebhookContext, IzanamiErrors, Webhook] =
    for {
      _        <- IO.when(data.clientId =!= id)(IO.fail(IdMustBeTheSame(data.clientId, id)))
      created  <- WebhookDataStore.create(id, WebhookInstances.format.writes(data))
      webhook  <- jsResultToError(created.validate[Webhook])
      authInfo <- AuthInfo.authInfo
      _        <- EventStore.publish(WebhookCreated(id, webhook, authInfo = authInfo))
    } yield webhook

  def update(oldId: WebhookKey, id: WebhookKey, data: Webhook): ZIO[WebhookContext, IzanamiErrors, Webhook] =
    for {
      mayBeHook <- getById(oldId).refineToOrDie[IzanamiErrors]
      oldValue  <- ZIO.fromOption(mayBeHook).mapError(_ => DataShouldExists(oldId))
      updated   <- WebhookDataStore.update(oldId, id, WebhookInstances.format.writes(data))
      hook      <- jsResultToError(updated.validate[Webhook])
      authInfo  <- AuthInfo.authInfo
      _         <- EventStore.publish(WebhookUpdated(id, oldValue, hook, authInfo = authInfo))
    } yield hook

  def delete(id: WebhookKey): ZIO[WebhookContext, IzanamiErrors, Webhook] =
    for {
      deleted  <- WebhookDataStore.delete(id)
      hook     <- jsResultToError(deleted.validate[Webhook])
      authInfo <- AuthInfo.authInfo
      _        <- EventStore.publish(WebhookDeleted(id, hook, authInfo = authInfo))
    } yield hook

  def deleteAll(patterns: Seq[String]): ZIO[WebhookContext, IzanamiErrors, Unit] =
    WebhookDataStore.deleteAll(patterns)

  def getById(id: WebhookKey): RIO[WebhookContext, Option[Webhook]] =
    for {
      mayBeHook  <- WebhookDataStore.getById(id)
      parsedHook = mayBeHook.flatMap(_.validate[Webhook].asOpt)
    } yield parsedHook

  def findByQuery(query: Query, page: Int, nbElementPerPage: Int): RIO[WebhookContext, PagingResult[Webhook]] =
    WebhookDataStore
      .findByQuery(query, page, nbElementPerPage)
      .map(jsons => JsonPagingResult(jsons))

  def findByQuery(query: Query): RIO[WebhookContext, Source[(Key, Webhook), NotUsed]] =
    WebhookDataStore.findByQuery(query).map(_.readsKV[Webhook])

  def count(query: Query): RIO[WebhookContext, Long] =
    WebhookDataStore.count(query)

  def importData: RIO[WebhookContext, Flow[(String, JsValue), ImportResult, NotUsed]] = {
    import domains.webhook.WebhookInstances._
    ZIO.runtime[WebhookContext].map { runtime =>
      import cats.implicits._
      Flow[(String, JsValue)]
        .map { case (s, json) => (s, json.validate[Webhook]) }
        .mapAsync(4) {
          case (_, JsSuccess(webhook, _)) =>
            runtime.unsafeRunToFuture(
              create(webhook.clientId, webhook).either.map { either =>
                ImportResult.fromResult(either)
              }
            )
          case (s, JsError(_)) => FastFuture.successful(ImportResult.error(ErrorMessage("json.parse.error", s)))
        }
        .fold(ImportResult()) {
          _ |+| _
        }
    }
  }

}
