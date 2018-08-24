package domains.webhook

import java.time.LocalDateTime

import akka.{Done, NotUsed}
import akka.stream.scaladsl.{Flow, Source}
import cats.data.EitherT
import cats.effect.Effect
import domains.Domain.Domain
import domains.events.EventStore
import domains.webhook.Webhook.WebhookKey
import domains._
import env.DbDomainConfig
import libs.functional.EitherTSyntax
import play.api.Logger
import play.api.libs.json._
import store.Result.Result
import store.SourceUtils.SourceKV
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

trait WebhookStore[F[_]] {
  def create(id: WebhookKey, data: Webhook): F[Result[Webhook]]
  def update(oldId: WebhookKey, id: WebhookKey, data: Webhook): F[Result[Webhook]]
  def delete(id: WebhookKey): F[Result[Webhook]]
  def deleteAll(patterns: Seq[String]): F[Result[Done]]
  def getById(id: WebhookKey): F[Option[Webhook]]
  def getByIdLike(patterns: Seq[String], page: Int = 1, nbElementPerPage: Int = 15): F[PagingResult[Webhook]]
  def getByIdLike(patterns: Seq[String]): Source[(WebhookKey, Webhook), NotUsed]
  def count(patterns: Seq[String]): F[Long]
  def importData(implicit ec: ExecutionContext): Flow[(String, JsValue), ImportResult, NotUsed]
}

class WebhookStoreImpl[F[_]: Effect](jsonStore: JsonDataStore[F], config: DbDomainConfig, eventStore: EventStore[F])
    extends WebhookStore[F]
    with EitherTSyntax[F] {

  import WebhookInstances._

  import cats.implicits._
  import libs.functional.syntax._
  import domains.events.Events._
  import store.Result._

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

  override def getByIdLike(patterns: Seq[String], page: Int, nbElementPerPage: Int): F[PagingResult[Webhook]] =
    jsonStore
      .getByIdLike(patterns, page, nbElementPerPage)
      .map(jsons => JsonPagingResult(jsons))

  override def getByIdLike(patterns: Seq[String]): Source[(Key, Webhook), NotUsed] =
    jsonStore.getByIdLike(patterns).readsKV[Webhook]

  override def count(patterns: Seq[String]): F[Long] =
    jsonStore.count(patterns)

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
    Logger.error(s"Error parsing json from database $err")
    AppErrors.error("error.json.parsing")
  }

}
