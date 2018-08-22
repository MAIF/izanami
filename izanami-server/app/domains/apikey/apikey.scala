package domains.apikey

import akka.{Done, NotUsed}
import akka.actor.ActorSystem
import akka.http.scaladsl.util.FastFuture
import akka.stream.scaladsl.{Flow, Source}
import cats.Monad
import cats.data.EitherT
import cats.effect.Effect
import domains.AuthorizedPattern.AuthorizedPattern
import domains.abtesting.Experiment.ExperimentKey
import domains.apikey.Apikey.ApikeyKey
import domains.events.EventStore
import domains.{AuthInfo, AuthorizedPattern, ImportResult, Key}
import libs.functional.EitherTSyntax
import play.api.Logger
import play.api.libs.json.{JsPath, JsonValidationError}
import store.Result.{AppErrors, ErrorMessage}
import store.SourceUtils.SourceKV
import store._

import scala.concurrent.{ExecutionContext, Future}

case class Apikey(clientId: String, name: String, clientSecret: String, authorizedPattern: AuthorizedPattern)
    extends AuthInfo {
  override def isAllowed(auth: Option[AuthInfo]): Boolean =
    Key.isAllowed(authorizedPattern)(auth)
}

object Apikey {
  import play.api.libs.functional.syntax._
  import play.api.libs.json._
  import play.api.libs.json.Reads._

  type ApikeyKey = Key

  private val reads: Reads[Apikey] = {
    import domains.AuthorizedPattern._
    (
      (__ \ 'clientId).read[String](pattern("^[@0-9\\p{L} .'-]+$".r)) and
      (__ \ 'name).read[String](pattern("^[@0-9\\p{L} .'-]+$".r)) and
      (__ \ 'clientSecret).read[String](pattern("^[@0-9\\p{L} .'-]+$".r)) and
      (__ \ 'authorizedPattern).read[AuthorizedPattern](AuthorizedPattern.reads)
    )(Apikey.apply _)
  }

  private val writes = {
    import domains.AuthorizedPattern._
    Json.writes[Apikey]
  }

  implicit val format = Format[Apikey](reads, writes)

  def isAllowed(pattern: String)(auth: Option[AuthInfo]) =
    Key.isAllowed(pattern)(auth)

  def importData[F[_]: Effect](
      apikeyStore: ApikeyStore[F]
  )(implicit ec: ExecutionContext): Flow[(String, JsValue), ImportResult, NotUsed] = {
    import cats.implicits._
    import AppErrors._
    import libs.streams.syntax._
    Flow[(String, JsValue)]
      .map { case (s, json) => (s, json.validate[Apikey]) }
      .mapAsyncF(4) {
        case (_, JsSuccess(obj, _)) =>
          apikeyStore.create(Key(obj.clientId), obj).map { ImportResult.fromResult }
        case (s, JsError(_)) =>
          Effect[F].pure(ImportResult.error(ErrorMessage("json.parse.error", s)))
      }
      .fold(ImportResult()) { _ |+| _ }
  }

}

trait ApikeyStore[F[_]] extends DataStore[F, ApikeyKey, Apikey]

class ApikeyStoreImpl[F[_]: Monad](jsonStore: JsonDataStore[F], eventStore: EventStore[F])
    extends ApikeyStore[F]
    with EitherTSyntax[F] {

  import cats.syntax._
  import cats.implicits._
  import libs.functional.syntax._
  import Apikey._
  import domains.events.Events._
  import store.Result._

  override def create(id: ExperimentKey, data: Apikey): F[Result[Apikey]] = {
    // format: off
    val r: EitherT[F, AppErrors, Apikey] = for {
      created     <- jsonStore.create(id, Apikey.format.writes(data))   |> liftFEither
      apikey      <- created.validate[Apikey]                           |> liftJsResult{ handleJsError }
      _           <- eventStore.publish(ApikeyCreated(id, apikey))      |> liftF[AppErrors, Done]
    } yield apikey
    // format: on
    r.value
  }

  override def update(oldId: ApikeyKey, id: ApikeyKey, data: Apikey): F[Result[Apikey]] = {
    // format: off
    val r: EitherT[F, AppErrors, Apikey] = for {
      oldValue    <- getById(oldId)                                               |> liftFOption(AppErrors.error("error.data.missing", oldId.key))
      updated     <- jsonStore.update(oldId, id, Apikey.format.writes(data))      |> liftFEither
      experiment  <- updated.validate[Apikey]                                     |> liftJsResult{ handleJsError }
      _           <- eventStore.publish(ApikeyUpdated(id, oldValue, experiment))  |> liftF[AppErrors, Done]
    } yield experiment
    // format: on
    r.value
  }

  override def delete(id: ApikeyKey): F[Result[Apikey]] = {
    // format: off
    val r: EitherT[F, AppErrors, Apikey] = for {
      deleted <- jsonStore.delete(id)                               |> liftFEither
      experiment <- deleted.validate[Apikey]                        |> liftJsResult{ handleJsError }
      _       <- eventStore.publish(ApikeyDeleted(id, experiment))  |> liftF[AppErrors, Done]
    } yield experiment
    // format: on
    r.value
  }

  override def deleteAll(patterns: Seq[String]): F[Result[Done]] =
    jsonStore.deleteAll(patterns)

  override def getById(id: ApikeyKey): F[Option[Apikey]] =
    jsonStore.getById(id).map(_.flatMap(_.validate[Apikey].asOpt))

  override def getByIdLike(patterns: Seq[String], page: Int, nbElementPerPage: Int): F[PagingResult[Apikey]] =
    jsonStore
      .getByIdLike(patterns, page, nbElementPerPage)
      .map(jsons => JsonPagingResult(jsons))

  override def getByIdLike(patterns: Seq[String]): Source[(Key, Apikey), NotUsed] =
    jsonStore.getByIdLike(patterns).readsKV[Apikey]

  override def count(patterns: Seq[String]): F[Long] =
    jsonStore.count(patterns)

  private def handleJsError(err: Seq[(JsPath, Seq[JsonValidationError])]): AppErrors = {
    Logger.error(s"Error parsing json from database $err")
    AppErrors.error("error.json.parsing")
  }
}
