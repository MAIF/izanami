package domains.abtesting

import akka.actor.ActorSystem
import akka.http.scaladsl.util.FastFuture
import akka.stream.scaladsl.{Flow, Source}
import akka.{Done, NotUsed}
import cats.Monad
import domains.abtesting.Experiment.ExperimentKey
import domains.events.EventStore
import domains.feature.Feature
import domains.feature.Feature.format
import domains.{AuthInfo, ImportResult, Key}
import libs.functional.EitherTSyntax
import play.api.Logger
import play.api.libs.json._
import store.Result.ErrorMessage
import store._
import store.SourceUtils._

import scala.concurrent.{ExecutionContext, Future}

case class Variant(id: String,
                   name: String,
                   description: String,
                   traffic: Double,
                   currentPopulation: Option[Int] = Some(0)) {
  def incrementPopulation: Variant =
    copy(currentPopulation = currentPopulation.map(_ + 1).orElse(Some(1)))
}

object Variant {
  implicit val format = Json.format[Variant]
}

case class Experiment(id: ExperimentKey, name: String, description: String, enabled: Boolean, variants: Seq[Variant]) {
  def isAllowed = Key.isAllowed(id) _

  def addOrReplaceVariant(variant: Variant): Experiment =
    copy(variants = variants.map {
      case v if v.id == variant.id => variant
      case v                       => v
    })
}

object Experiment {

  type ExperimentKey = Key

  implicit val format = Json.format[Experiment]

  def importData(
      experimentStore: ExperimentStore[Future]
  )(implicit ec: ExecutionContext): Flow[(String, JsValue), ImportResult, NotUsed] = {
    import cats.implicits._
    import store.Result.AppErrors._

    Flow[(String, JsValue)]
      .map { case (s, json) => (s, json.validate[Experiment]) }
      .mapAsync(4) {
        case (_, JsSuccess(obj, _)) =>
          experimentStore.create(obj.id, obj) map { ImportResult.fromResult }
        case (s, JsError(_)) =>
          FastFuture.successful(ImportResult.error(ErrorMessage("json.parse.error", s)))
      }
      .fold(ImportResult()) { _ |+| _ }
  }

  def toGraph(clientId: String)(
      implicit ec: ExecutionContext,
      experimentStore: ExperimentStore[Future],
      variantBindingStore: VariantBindingStore[Future]
  ): Flow[Experiment, JsObject, NotUsed] = {
    import VariantBinding._
    Flow[Experiment]
      .filter(_.enabled)
      .mapAsyncUnordered(2) { experiment =>
        variantBindingStore
          .getById(VariantBindingKey(experiment.id, clientId))
          .flatMap {
            case Some(v) =>
              FastFuture.successful(
                (experiment.id.jsPath \ "variant")
                  .write[String]
                  .writes(v.variantId)
              )
            case None =>
              VariantBinding
                .createVariantForClient(experiment.id, clientId)
                .map {
                  case Right(v) =>
                    (experiment.id.jsPath \ "variant")
                      .write[String]
                      .writes(v.id)
                  case Left(e) =>
                    Json.obj()
                }
          }
      }
      .fold(Json.obj()) { (acc, js) =>
        acc.deepMerge(js.as[JsObject])
      }
  }

  def isAllowed(key: Key)(auth: Option[AuthInfo]) = Key.isAllowed(key)(auth)

}

trait ExperimentStore[F[_]] extends DataStore[F, ExperimentKey, Experiment]

class ExperimentStoreImpl[F[_]: Monad](jsonStore: JsonDataStore[F], eventStore: EventStore[F], system: ActorSystem)
    extends ExperimentStore[F]
    with EitherTSyntax[F] {

  import cats.data._
  import cats.implicits._
  import libs.functional.syntax._
  import Experiment._
  import domains.events.Events.{ExperimentCreated, ExperimentDeleted, ExperimentUpdated}
  import store.Result._

  override def create(id: ExperimentKey, data: Experiment): F[Result[Experiment]] = {
    // format: off
    val r: EitherT[F, AppErrors, Experiment] = for {
      created     <- jsonStore.create(id, Experiment.format.writes(data))   |> liftFEither
      experiment  <- created.validate[Experiment]                           |> liftJsResult{ handleJsError }
      _           <- eventStore.publish(ExperimentCreated(id, experiment))  |> liftF[AppErrors, Done]
    } yield experiment
    // format: on
    r.value
  }

  override def update(oldId: ExperimentKey, id: ExperimentKey, data: Experiment): F[Result[Experiment]] = {
    // format: off
    val r: EitherT[F, AppErrors, Experiment] = for {
      oldValue    <- getById(oldId)                                                   |> liftFOption(AppErrors.error("error.data.missing", oldId.key))
      updated     <- jsonStore.update(oldId, id, Experiment.format.writes(data))      |> liftFEither
      experiment  <- updated.validate[Experiment]                                     |> liftJsResult{ handleJsError }
      _           <- eventStore.publish(ExperimentUpdated(id, oldValue, experiment))  |> liftF[AppErrors, Done]
    } yield experiment
    // format: on
    r.value
  }

  override def delete(id: ExperimentKey): F[Result[Experiment]] = {
    // format: off
    val r: EitherT[F, AppErrors, Experiment] = for {
      deleted <- jsonStore.delete(id)                            |> liftFEither
      experiment <- deleted.validate[Experiment]                       |> liftJsResult{ handleJsError }
      _       <- eventStore.publish(ExperimentDeleted(id, experiment)) |> liftF[AppErrors, Done]
    } yield experiment
    // format: on
    r.value
  }

  override def deleteAll(patterns: Seq[String]): F[Result[Done]] =
    jsonStore.deleteAll(patterns)

  override def getById(id: ExperimentKey): F[Option[Experiment]] =
    jsonStore.getById(id).map(_.flatMap(_.validate[Experiment].asOpt))

  override def getByIdLike(patterns: Seq[String], page: Int, nbElementPerPage: Int): F[PagingResult[Experiment]] =
    jsonStore
      .getByIdLike(patterns, page, nbElementPerPage)
      .map(jsons => JsonPagingResult(jsons))

  override def getByIdLike(patterns: Seq[String]): Source[(Key, Experiment), NotUsed] =
    jsonStore
      .getByIdLike(patterns)
      .map {
        case (k, v) => (k, v.validate[Experiment].get)
      }

  override def count(patterns: Seq[String]): F[Long] =
    jsonStore.count(patterns)

  private def handleJsError(err: Seq[(JsPath, Seq[JsonValidationError])]): AppErrors = {
    Logger.error(s"Error parsing json from database $err")
    AppErrors.error("error.json.parsing")
  }
}

case class VariantResult(variant: Option[Variant] = None,
                         displayed: Long = 0,
                         won: Long = 0,
                         transformation: Double = 0,
                         events: Seq[ExperimentVariantEvent] = Seq.empty)

object VariantResult {
  implicit val format = Json.format[VariantResult]

  def transformation(displayed: Long, won: Long): Double = displayed match {
    case 0 => 0.0
    case _ => (won * 100) / displayed
  }
}

case class ExperimentResult(experiment: Experiment, results: Seq[VariantResult] = Seq.empty[VariantResult])

object ExperimentResult {
  implicit val format = Json.format[ExperimentResult]
}
