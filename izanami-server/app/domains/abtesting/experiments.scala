package domains.abtesting

import akka.actor.ActorSystem
import akka.http.scaladsl.util.FastFuture
import akka.stream.scaladsl.{Flow, Source}
import akka.{Done, NotUsed}
import cats.effect.Effect
import domains.abtesting.Experiment.ExperimentKey
import domains.events.EventStore
import domains.{ImportResult, Key}
import libs.functional.EitherTSyntax
import play.api.Logger
import play.api.libs.json._
import store.Result.Result
import store._

import scala.concurrent.ExecutionContext

case class Variant(id: String,
                   name: String,
                   description: String,
                   traffic: Double,
                   currentPopulation: Option[Int] = Some(0)) {
  def incrementPopulation: Variant =
    copy(currentPopulation = currentPopulation.map(_ + 1).orElse(Some(1)))
}

case class Experiment(id: ExperimentKey, name: String, description: String, enabled: Boolean, variants: Seq[Variant]) {

  def addOrReplaceVariant(variant: Variant): Experiment =
    copy(variants = variants.map {
      case v if v.id == variant.id => variant
      case v                       => v
    })
}

object Experiment {
  type ExperimentKey = Key
}

trait ExperimentStore[F[_]] {
  def create(id: ExperimentKey, data: Experiment): F[Result[Experiment]]
  def update(oldId: ExperimentKey, id: ExperimentKey, data: Experiment): F[Result[Experiment]]
  def delete(id: ExperimentKey): F[Result[Experiment]]
  def deleteAll(patterns: Seq[String]): F[Result[Done]]
  def getById(id: ExperimentKey): F[Option[Experiment]]
  def getByIdLike(patterns: Seq[String], page: Int = 1, nbElementPerPage: Int = 15): F[PagingResult[Experiment]]
  def getByIdLike(patterns: Seq[String]): Source[(ExperimentKey, Experiment), NotUsed]
  def count(patterns: Seq[String]): F[Long]
  def importData(implicit ec: ExecutionContext): Flow[(String, JsValue), ImportResult, NotUsed]
  def toGraph(clientId: String)(implicit ec: ExecutionContext,
                                variantBindingStore: VariantBindingStore[F]): Flow[Experiment, JsObject, NotUsed]
  def variantFor(experimentKey: ExperimentKey, clientId: String)(
      implicit ec: ExecutionContext,
      VariantBindingStore: VariantBindingStore[F]
  ): F[Result[Variant]]
}

class ExperimentStoreImpl[F[_]: Effect](jsonStore: JsonDataStore[F], eventStore: EventStore[F], system: ActorSystem)
    extends ExperimentStore[F]
    with EitherTSyntax[F] {

  import cats.data._
  import cats.implicits._
  import libs.functional.syntax._
  import Experiment._
  import ExperimentInstances._
  import domains.events.Events.{ExperimentCreated, ExperimentDeleted, ExperimentUpdated}
  import store.Result._

  override def create(id: ExperimentKey, data: Experiment): F[Result[Experiment]] = {
    // format: off
    val r: EitherT[F, AppErrors, Experiment] = for {
      created     <- jsonStore.create(id, ExperimentInstances.format.writes(data))   |> liftFEither
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
      updated     <- jsonStore.update(oldId, id, ExperimentInstances.format.writes(data))      |> liftFEither
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

  def importData(implicit ec: ExecutionContext): Flow[(String, JsValue), ImportResult, NotUsed] = {
    import cats.implicits._
    import cats.effect.implicits._

    Flow[(String, JsValue)]
      .map { case (s, json) => (s, ExperimentInstances.format.reads(json)) }
      .mapAsync(4) {
        case (_, JsSuccess(obj, _)) =>
          create(obj.id, obj)
            .map { ImportResult.fromResult }
            .toIO
            .unsafeToFuture()
        case (s, JsError(_)) =>
          FastFuture.successful(ImportResult.error(ErrorMessage("json.parse.error", s)))
      }
      .fold(ImportResult()) { _ |+| _ }
  }

  def toGraph(clientId: String)(
      implicit ec: ExecutionContext,
      variantBindingStore: VariantBindingStore[F]
  ): Flow[Experiment, JsObject, NotUsed] = {
    import VariantBinding._
    import cats.implicits._
    import libs.streams.syntax._

    implicit val es = this
    Flow[Experiment]
      .filter(_.enabled)
      .mapAsyncUnorderedF(2) { experiment =>
        variantBindingStore
          .getById(VariantBindingKey(experiment.id, clientId))
          .flatMap {
            case Some(v) =>
              Effect[F].pure(
                (experiment.id.jsPath \ "variant")
                  .write[String]
                  .writes(v.variantId)
              )
            case None =>
              variantBindingStore
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

  override def variantFor(experimentKey: ExperimentKey, clientId: String)(
      implicit ec: ExecutionContext,
      VariantBindingStore: VariantBindingStore[F]
  ): F[Result[Variant]] = {
    import cats.implicits._
    implicit val es = this
    VariantBindingStore
      .getById(VariantBindingKey(experimentKey, clientId))
      .flatMap {
        case None =>
          VariantBindingStore.createVariantForClient(experimentKey, clientId)
        case Some(v) =>
          getById(experimentKey).map {
            case Some(e) =>
              e.variants
                .find(_.id == v.variantId)
                .map(Result.ok)
                .getOrElse(Result.error("error.variant.missing"))
            case None =>
              Result.error("error.experiment.missing")
          }
      }
  }

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
  import ExperimentInstances._
  implicit val eveF                          = ExperimentVariantEventInstances.format
  implicit val format: Format[VariantResult] = Json.format[VariantResult]

  def transformation(displayed: Long, won: Long): Double = displayed match {
    case 0 => 0.0
    case _ => (won * 100) / displayed
  }
}

case class ExperimentResult(experiment: Experiment, results: Seq[VariantResult] = Seq.empty[VariantResult])

object ExperimentResult {
  implicit val ef                               = ExperimentInstances.format
  implicit val format: Format[ExperimentResult] = Json.format[ExperimentResult]

}
