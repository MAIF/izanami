package domains.webhook

import java.time.LocalDateTime

import akka.actor.ActorSystem
import akka.{Done, NotUsed}
import akka.stream.scaladsl.{Flow, Source}
import cats.data.EitherT
import cats.effect.Effect
import domains.Domain.Domain
import domains.events.EventStore
import domains.webhook.Webhook.WebhookKey
import domains._
import domains.webhook.notifications.WebHooksActor
import env.{DbDomainConfig, WebhookConfig}
import libs.functional.EitherTSyntax
import libs.logs.IzanamiLogger
import play.api.libs.json._
import play.api.libs.ws.WSClient
import store.Result.Result

import store._

import scala.concurrent.ExecutionContext

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

trait WebhookService[F[_]] {
  def create(id: WebhookKey, data: Webhook): F[Result[Webhook]]
  def update(oldId: WebhookKey, id: WebhookKey, data: Webhook): F[Result[Webhook]]
  def delete(id: WebhookKey): F[Result[Webhook]]
  def deleteAll(patterns: Seq[String]): F[Result[Done]]
  def getById(id: WebhookKey): F[Option[Webhook]]
  def findByQuery(query: Query, page: Int = 1, nbElementPerPage: Int = 15): F[PagingResult[Webhook]]
  def findByQuery(query: Query): Source[(WebhookKey, Webhook), NotUsed]
  def count(query: Query): F[Long]
  def importData(implicit ec: ExecutionContext): Flow[(String, JsValue), ImportResult, NotUsed]
}

class WebhookServiceImpl[F[_]: Effect](jsonStore: JsonDataStore[F],
                                       config: DbDomainConfig,
                                       webHookConfig: WebhookConfig,
                                       eventStore: EventStore[F],
                                       wsClient: WSClient)(implicit actorSystem: ActorSystem)
    extends WebhookService[F]
    with EitherTSyntax[F] {

  import WebhookInstances._

  import cats.implicits._
  import libs.functional.syntax._
  import libs.streams.syntax._
  import domains.events.Events._
  import store.Result._

  actorSystem.actorOf(WebHooksActor.props[F](wsClient, eventStore, this, webHookConfig), "webhooks")

  override def create(id: WebhookKey, data: Webhook): F[Result[Webhook]] = {
    // format: off
    val r: EitherT[F, AppErrors, Webhook] = for {
      created     <- jsonStore.create(id, WebhookInstances.format.writes(data))   |> liftFEither
      user        <- created.validate[Webhook]                           |> liftJsResult{ handleJsError }
      _           <- eventStore.publish(WebhookCreated(id, user))        |> liftF[AppErrors, Done]
    } yield user
    // format: on
    r.value
  }

  override def update(oldId: WebhookKey, id: WebhookKey, data: Webhook): F[Result[Webhook]] = {
    // format: off
    val r: EitherT[F, AppErrors, Webhook] = for {
      oldValue    <- getById(oldId)                                                |> liftFOption(AppErrors.error("error.data.missing", oldId.key))
      updated     <- jsonStore.update(oldId, id, WebhookInstances.format.writes(data))      |> liftFEither
      user        <- updated.validate[Webhook]                                     |> liftJsResult{ handleJsError }
      _           <- eventStore.publish(WebhookUpdated(id, oldValue, user))        |> liftF[AppErrors, Done]
    } yield user
    // format: on
    r.value
  }

  override def delete(id: WebhookKey): F[Result[Webhook]] = {
    // format: off
    val r: EitherT[F, AppErrors, Webhook] = for {
      deleted <- jsonStore.delete(id)                       |> liftFEither
      user    <- deleted.validate[Webhook]                     |> liftJsResult{ handleJsError }
      _       <- eventStore.publish(WebhookDeleted(id, user))  |> liftF[AppErrors, Done]
    } yield user
    // format: on
    r.value
  }

  override def deleteAll(patterns: Seq[String]): F[Result[Done]] =
    jsonStore.deleteAll(patterns)

  override def getById(id: WebhookKey): F[Option[Webhook]] =
    jsonStore.getById(id).map(_.flatMap(_.validate[Webhook].asOpt))

  override def findByQuery(query: Query, page: Int, nbElementPerPage: Int): F[PagingResult[Webhook]] =
    jsonStore
      .findByQuery(query, page, nbElementPerPage)
      .map(jsons => JsonPagingResult(jsons))

  override def findByQuery(query: Query): Source[(Key, Webhook), NotUsed] =
    jsonStore.findByQuery(query).readsKV[Webhook]

  override def count(query: Query): F[Long] =
    jsonStore.count(query)

  def importData(implicit ec: ExecutionContext): Flow[(String, JsValue), ImportResult, NotUsed] = {
    import cats.implicits._
    import store.Result.AppErrors._
    import libs.streams.syntax._

    Flow[(String, JsValue)]
      .map { case (s, json) => (s, WebhookInstances.format.reads(json)) }
      .mapAsyncF(4) {
        case (_, JsSuccess(obj, _)) =>
          create(obj.clientId, obj).map { ImportResult.fromResult }
        case (s, JsError(_)) =>
          Effect[F].pure(ImportResult.error(ErrorMessage("json.parse.error", s)))
      }
      .fold(ImportResult()) { _ |+| _ }
  }

  private def handleJsError(err: Seq[(JsPath, Seq[JsonValidationError])]): AppErrors = {
    IzanamiLogger.error(s"Error parsing json from database $err")
    AppErrors.error("error.json.parsing")
  }

}
