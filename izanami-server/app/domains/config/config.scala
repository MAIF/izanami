package domains.config

import akka.{Done, NotUsed}
import akka.http.scaladsl.util.FastFuture
import akka.stream.scaladsl.{Flow, Source}
import cats.Monad
import cats.data.EitherT
import domains.config.Config.ConfigKey
import domains.events.EventStore
import domains.{AuthInfo, ImportResult, Key}
import libs.functional.EitherTSyntax
import play.api.Logger
import play.api.libs.json._
import store.Result.ErrorMessage
import store.SourceUtils.SourceKV
import store._

import scala.concurrent.{ExecutionContext, Future}

case class Config(id: ConfigKey, value: JsValue) {

  def isAllowed = Key.isAllowed(id) _

}

object Config {

  type ConfigKey = Key

  implicit val format = Json.format[Config]

  def isAllowed(key: Key)(auth: Option[AuthInfo]) = Key.isAllowed(key)(auth)

  def importData(
      configStore: ConfigStore[Future]
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

trait ConfigStore[F[_]] extends DataStore[F, ConfigKey, Config]

class ConfigStoreImpl[F[_]: Monad](jsonStore: JsonDataStore[F], eventStore: EventStore[F])
    extends ConfigStore[F]
    with EitherTSyntax[F] {

  import cats.implicits._
  import libs.functional.syntax._
  import Config._
  import store.Result._
  import libs.functional.syntax._
  import domains.events.Events._

  override def create(id: ConfigKey, data: Config): F[Result[Config]] = {
    // format: off
    val r: EitherT[F, AppErrors, Config] = for {
      created     <- jsonStore.create(id, Config.format.writes(data))   |> liftFEither
      apikey      <- created.validate[Config]                           |> liftJsResult{ handleJsError }
      _           <- eventStore.publish(ConfigCreated(id, apikey))      |> liftF[AppErrors, Done]
    } yield apikey
    // format: on
    r.value
  }

  override def update(oldId: ConfigKey, id: ConfigKey, data: Config): F[Result[Config]] = {
    // format: off
    val r: EitherT[F, AppErrors, Config] = for {
      oldValue    <- getById(oldId)                                               |> liftFOption(AppErrors.error("error.data.missing", oldId.key))
      updated     <- jsonStore.update(oldId, id, Config.format.writes(data))      |> liftFEither
      experiment  <- updated.validate[Config]                                     |> liftJsResult{ handleJsError }
      _           <- eventStore.publish(ConfigUpdated(id, oldValue, experiment))  |> liftF[AppErrors, Done]
    } yield experiment
    // format: on
    r.value
  }

  override def delete(id: ConfigKey): F[Result[Config]] = {
    // format: off
    val r: EitherT[F, AppErrors, Config] = for {
      deleted <- jsonStore.delete(id)                               |> liftFEither
      experiment <- deleted.validate[Config]                        |> liftJsResult{ handleJsError }
      _       <- eventStore.publish(ConfigDeleted(id, experiment))  |> liftF[AppErrors, Done]
    } yield experiment
    // format: on
    r.value
  }

  override def deleteAll(patterns: Seq[String]): F[Result[Done]] =
    jsonStore.deleteAll(patterns)

  override def getById(id: ConfigKey): F[Option[Config]] =
    jsonStore.getById(id).map(_.flatMap(_.validate[Config].asOpt))

  override def getByIdLike(patterns: Seq[String], page: Int, nbElementPerPage: Int): F[PagingResult[Config]] =
    jsonStore
      .getByIdLike(patterns, page, nbElementPerPage)
      .map(jsons => JsonPagingResult(jsons))

  override def getByIdLike(patterns: Seq[String]): Source[(Key, Config), NotUsed] =
    jsonStore.getByIdLike(patterns).readsKV[Config]

  override def count(patterns: Seq[String]): F[Long] =
    jsonStore.count(patterns)

  private def handleJsError(err: Seq[(JsPath, Seq[JsonValidationError])]): AppErrors = {
    Logger.error(s"Error parsing json from database $err")
    AppErrors.error("error.json.parsing")
  }
}
