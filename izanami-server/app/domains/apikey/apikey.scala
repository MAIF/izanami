package domains.apikey

import akka.{Done, NotUsed}
import akka.stream.scaladsl.{Flow, Source}
import cats.data.EitherT
import cats.effect.Effect
import domains.AuthorizedPattern.AuthorizedPattern
import domains.abtesting.Experiment.ExperimentKey
import domains.apikey.Apikey.ApikeyKey
import domains.events.EventStore
import domains._
import libs.functional.EitherTSyntax
import libs.logs.IzanamiLogger
import play.api.libs.json._
import store.Result.Result

import store._

import scala.concurrent.ExecutionContext

case class Apikey(clientId: String, name: String, clientSecret: String, authorizedPattern: AuthorizedPattern)
    extends AuthInfo

object Apikey {
  type ApikeyKey = Key
}

trait ApikeyService[F[_]] {
  def create(id: ApikeyKey, data: Apikey): F[Result[Apikey]]
  def update(oldId: ApikeyKey, id: Key, data: Apikey): F[Result[Apikey]]
  def delete(id: ApikeyKey): F[Result[Apikey]]
  def deleteAll(patterns: Seq[String]): F[Result[Done]]
  def getById(id: ApikeyKey): F[Option[Apikey]]
  def getByIdLike(patterns: Seq[String], page: Int = 1, nbElementPerPage: Int = 15): F[PagingResult[Apikey]]
  def getByIdLike(patterns: Seq[String]): Source[(Key, Apikey), NotUsed]
  def count(patterns: Seq[String]): F[Long]
  def importData(implicit ec: ExecutionContext): Flow[(String, JsValue), ImportResult, NotUsed]
}

class ApikeyStoreImpl[F[_]: Effect](jsonStore: JsonDataStore[F], eventStore: EventStore[F])
    extends ApikeyService[F]
    with EitherTSyntax[F] {

  import cats.syntax._
  import cats.implicits._
  import libs.functional.syntax._
  import libs.streams.syntax._
  import Apikey._
  import ApikeyInstances._
  import domains.events.Events._
  import store.Result._

  override def create(id: ExperimentKey, data: Apikey): F[Result[Apikey]] = {
    // format: off
    val r: EitherT[F, AppErrors, Apikey] = for {
      created     <- jsonStore.create(id, Json.toJson(data))   |> liftFEither
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
      updated     <- jsonStore.update(oldId, id, Json.toJson(data))      |> liftFEither
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
    IzanamiLogger.error(s"Error parsing json from database $err")
    AppErrors.error("error.json.parsing")
  }

  def importData(implicit ec: ExecutionContext): Flow[(String, JsValue), ImportResult, NotUsed] = {
    import cats.implicits._
    import AppErrors._
    import libs.streams.syntax._
    Flow[(String, JsValue)]
      .map { case (s, json) => (s, ApikeyInstances.format.reads(json)) }
      .mapAsyncF(4) {
        case (_, JsSuccess(obj, _)) =>
          create(Key(obj.clientId), obj).map { ImportResult.fromResult _ }
        case (s, JsError(_)) =>
          Effect[F].pure(ImportResult.error(ErrorMessage("json.parse.error", s)))
      }
      .fold(ImportResult()) { _ |+| _ }
  }
}
