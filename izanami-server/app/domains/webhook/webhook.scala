package domains

import java.time.LocalDateTime

import akka.actor.{ActorRef, ActorSystem}
import akka.NotUsed
import akka.stream.scaladsl.{Flow, Source}
import domains.Domain.Domain
import domains.events.EventStore
import domains.webhook.Webhook.WebhookKey
import domains._
import domains.auth.AuthInfo
import domains.configuration.{AkkaModule, PlayModule}
import domains.user.UserDataStore
import domains.webhook.notifications.WebHooksActor
import env.WebhookConfig
import env.configuration.IzanamiConfigModule
import libs.ziohelper.JsResults.jsResultToError
import play.api.libs.json._
import play.api.libs.ws.WSClient
import errors.IzanamiErrors
import libs.database.Drivers
import libs.logs.ZLogger
import store._
import store.datastore.{JsonDataStore, _}
import store.memorywithdb.InMemoryWithDbStore
import zio._

package object webhook {

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

  type WebhookDataStore = zio.Has[WebhookDataStore.Service]

  object WebhookDataStore {
    trait Service {
      def webhookDataStore: JsonDataStore.Service
    }

    case class WebhookDataStoreProd(webhookDataStore: JsonDataStore.Service) extends Service

    object > extends JsonDataStoreHelper[WebhookDataStore with DataStoreContext] {
      override def getStore: URIO[WebhookDataStore with DataStoreContext, JsonDataStore.Service] =
        ZIO.access(_.get[WebhookDataStore.Service].webhookDataStore)
    }

    def value(webhookDataStore: JsonDataStore.Service): ULayer[WebhookDataStore] =
      ZLayer.succeed(WebhookDataStoreProd(webhookDataStore))

    val live: ZLayer[AkkaModule with PlayModule with Drivers with IzanamiConfigModule, Nothing, WebhookDataStore] =
      JsonDataStore
        .live(c => c.webhook.db, InMemoryWithDbStore.webhookEventAdapter)
        .map(s => Has(WebhookDataStoreProd(s.get)))
  }

  type WebhookContext = WebhookDataStore with ZLogger with EventStore with AuthInfo

  object WebhookService {

    import WebhookInstances._
    import cats.implicits._
    import libs.streams.syntax._
    import domains.events.Events._
    import errors._
    import IzanamiErrors._

    def startHooks(wsClient: WSClient, config: WebhookConfig)(
        implicit actorSystem: ActorSystem
    ): zio.RIO[WebhookContext, ActorRef] =
      for {
        r   <- ZIO.runtime[WebhookContext]
        ref = actorSystem.actorOf(WebHooksActor.props(wsClient, config, r), "webhooks")
      } yield ref

    def create(id: WebhookKey, data: Webhook): ZIO[WebhookContext, IzanamiErrors, Webhook] =
      for {
        _        <- AuthorizedPatterns.isAllowed(id, PatternRights.C)
        _        <- IO.when(data.clientId =!= id)(IO.fail(IdMustBeTheSame(data.clientId, id).toErrors))
        created  <- WebhookDataStore.>.create(id, WebhookInstances.format.writes(data))
        webhook  <- jsResultToError(created.validate[Webhook])
        authInfo <- AuthInfo.authInfo
        _        <- EventStore.publish(WebhookCreated(id, webhook, authInfo = authInfo))
      } yield webhook

    def update(oldId: WebhookKey, id: WebhookKey, data: Webhook): ZIO[WebhookContext, IzanamiErrors, Webhook] =
      for {
        _         <- AuthorizedPatterns.isAllowed(id, PatternRights.U)
        mayBeHook <- getById(oldId)
        oldValue  <- ZIO.fromOption(mayBeHook).mapError(_ => DataShouldExists(oldId).toErrors)
        updated   <- WebhookDataStore.>.update(oldId, id, WebhookInstances.format.writes(data))
        hook      <- jsResultToError(updated.validate[Webhook])
        authInfo  <- AuthInfo.authInfo
        _         <- EventStore.publish(WebhookUpdated(id, oldValue, hook, authInfo = authInfo))
      } yield hook

    def delete(id: WebhookKey): ZIO[WebhookContext, IzanamiErrors, Webhook] =
      for {
        _        <- AuthorizedPatterns.isAllowed(id, PatternRights.D)
        deleted  <- WebhookDataStore.>.delete(id)
        hook     <- jsResultToError(deleted.validate[Webhook])
        authInfo <- AuthInfo.authInfo
        _        <- EventStore.publish(WebhookDeleted(id, hook, authInfo = authInfo))
      } yield hook

    def deleteAll(patterns: Seq[String]): ZIO[WebhookContext, IzanamiErrors, Unit] =
      WebhookDataStore.>.deleteAll(patterns)

    def getByIdWithoutPermissions(id: WebhookKey): RIO[WebhookContext, Option[Webhook]] =
      for {
        mayBeHook  <- WebhookDataStore.>.getById(id)
        parsedHook = mayBeHook.flatMap(_.validate[Webhook].asOpt)
      } yield parsedHook

    def getById(id: WebhookKey): ZIO[WebhookContext, IzanamiErrors, Option[Webhook]] =
      AuthorizedPatterns.isAllowed(id, PatternRights.R) *> getByIdWithoutPermissions(id).orDie

    def findByQuery(query: Query, page: Int, nbElementPerPage: Int): RIO[WebhookContext, PagingResult[Webhook]] =
      WebhookDataStore.>.findByQuery(query, page, nbElementPerPage)
        .map(jsons => JsonPagingResult(jsons))

    def findByQuery(query: Query): RIO[WebhookContext, Source[(Key, Webhook), NotUsed]] =
      WebhookDataStore.>.findByQuery(query).map(_.readsKV[Webhook])

    def count(query: Query): RIO[WebhookContext, Long] =
      WebhookDataStore.>.count(query)

    def importData(
        strategy: ImportStrategy = ImportStrategy.Keep
    ): RIO[WebhookContext, Flow[(String, JsValue), ImportResult, NotUsed]] =
      ImportData
        .importDataFlow[WebhookContext, WebhookKey, Webhook](
          strategy,
          _.clientId,
          key => getById(key),
          (key, data) => create(key, data),
          (key, data) => update(key, key, data)
        )(WebhookInstances.format)

  }
}
