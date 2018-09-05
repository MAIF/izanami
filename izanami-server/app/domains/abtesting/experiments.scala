package domains.abtesting

import java.time.LocalDateTime

import akka.actor.ActorSystem
import akka.http.scaladsl.util.FastFuture
import akka.stream.{ActorMaterializer, Materializer}
import akka.stream.scaladsl.{Flow, Sink, Source}
import akka.{Done, NotUsed}
import cats.data.NonEmptyList
import cats.effect.Effect
import domains.abtesting.Experiment.ExperimentKey
import domains.events.EventStore
import domains.{ImportResult, Key}
import libs.functional.EitherTSyntax
import play.api.Logger
import play.api.libs.json._
import store.Result.{AppErrors, Result, ValidatedResult}
import store.{Result, _}
import cats._
import cats.data._
import cats.implicits._
import cats.data.Validated._

import scala.concurrent.ExecutionContext

case class Traffic(traffic: Double) extends AnyVal

case class Variant(id: String,
                   name: String,
                   description: Option[String] = None,
                   traffic: Traffic,
                   currentPopulation: Option[Int] = None) {
  def incrementPopulation: Variant =
    copy(currentPopulation = currentPopulation.map(_ + 1).orElse(Some(1)))
}

sealed trait Campaign {
  def from: LocalDateTime
  def to: LocalDateTime
}
case class CurrentCampaign(from: LocalDateTime, to: LocalDateTime)             extends Campaign
case class ClosedCampaign(from: LocalDateTime, to: LocalDateTime, won: String) extends Campaign

case class Experiment(id: ExperimentKey,
                      name: String,
                      description: Option[String] = None,
                      enabled: Boolean,
                      campaign: Option[Campaign] = None,
                      variants: NonEmptyList[Variant]) {

  def addOrReplaceVariant(variant: Variant): Experiment =
    copy(variants = variants.map {
      case v if v.id == variant.id => variant
      case v                       => v
    })
}

object Experiment {
  type ExperimentKey = Key

  def validate(experiment: Experiment): Result[Experiment] = {
    import Result._
    val validations: ValidatedResult[Experiment] = (
      validateTraffic(experiment),
      validateCampaign(experiment)
    ).mapN { (_, _) =>
      experiment
    }
    validations.toEither
  }

  private def validateTraffic(experiment: Experiment): ValidatedResult[Experiment] = {
    val allTraffic = experiment.variants.map(_.traffic.traffic).reduceLeft(_ + _)
    Either.cond(allTraffic == 1, experiment, AppErrors.error("error.traffic.not.cent.percent")).toValidated
  }

  private def validateCampaign(experiment: Experiment): ValidatedResult[Experiment] =
    experiment.campaign.fold(
      experiment.valid[AppErrors]
    ) { c =>
      Either
        .cond(
          c.from.isBefore(c.to),
          experiment,
          AppErrors.error("error.campaign.date.invalid")
        )
        .toValidated
    }

  def isTrafficChanged(old: Experiment, data: Experiment): Boolean = {
    import ExperimentInstances._
    // The number of variant changes:
    old.variants.size != data.variants.size ||
    // Or
    old.variants
      .map { v =>
        data.variants
          .find(_.id === v.id)
          .forall { v1 =>
            v1.traffic =!= v.traffic
          }
      }
      .foldLeft(false) { _ || _ }
  }
}

case class VariantResult(variant: Option[Variant] = None,
                         displayed: Long = 0,
                         won: Long = 0,
                         transformation: Double = 0,
                         events: Seq[ExperimentVariantEvent] = Seq.empty)

object VariantResult {
  def transformation(displayed: Long, won: Long): Double = displayed match {
    case 0 => 0.0
    case _ => (won * 100) / displayed
  }
}

case class ExperimentResult(experiment: Experiment, results: Seq[VariantResult] = Seq.empty[VariantResult])

trait ExperimentService[F[_]] {
  def create(id: ExperimentKey, data: Experiment): F[Result[Experiment]]
  def update(oldId: ExperimentKey, id: ExperimentKey, data: Experiment)(
      implicit
      variantBindingStore: VariantBindingService[F],
      eVariantEventStore: ExperimentVariantEventService[F]
  ): F[Result[Experiment]]
  def rawUpdate(oldId: ExperimentKey, oldValue: Experiment, id: ExperimentKey, data: Experiment): F[Result[Experiment]]
  def delete(id: ExperimentKey): F[Result[Experiment]]
  def deleteAll(patterns: Seq[String]): F[Result[Done]]
  def getById(id: ExperimentKey): F[Option[Experiment]]
  def getByIdLike(patterns: Seq[String], page: Int = 1, nbElementPerPage: Int = 15): F[PagingResult[Experiment]]
  def getByIdLike(patterns: Seq[String]): Source[(ExperimentKey, Experiment), NotUsed]
  def count(patterns: Seq[String]): F[Long]
  def importData(implicit ec: ExecutionContext): Flow[(String, JsValue), ImportResult, NotUsed]
  def toGraph(clientId: String)(implicit ec: ExecutionContext,
                                variantBindingStore: VariantBindingService[F]): Flow[Experiment, JsObject, NotUsed]
  def variantFor(experimentKey: ExperimentKey, clientId: String)(
      implicit ec: ExecutionContext,
      VariantBindingStore: VariantBindingService[F],
      eVariantEventStore: ExperimentVariantEventService[F]
  ): F[Result[Variant]]

  def experimentResult(experimentKey: ExperimentKey)(
      implicit eVariantEventStore: ExperimentVariantEventService[F]
  ): F[ExperimentResult]
}

class ExperimentServiceImpl[F[_]: Effect](jsonStore: JsonDataStore[F], eventStore: EventStore[F])(
    implicit system: ActorSystem
) extends ExperimentService[F]
    with EitherTSyntax[F] {

  import system.dispatcher
  import libs.functional.syntax._
  import Experiment._
  import ExperimentInstances._
  import domains.events.Events.{ExperimentCreated, ExperimentDeleted, ExperimentUpdated}
  import store.Result._

  implicit val materializer: Materializer = ActorMaterializer()

  override def create(id: ExperimentKey, data: Experiment): F[Result[Experiment]] = {
    import ExperimentInstances._
    // format: off
    val r: EitherT[F, AppErrors, Experiment] = for {
      _           <- Experiment.validate(data)                              |> liftEither
      created     <- jsonStore.create(id, Json.toJson(data))                |> liftFEither
      experiment  <- created.validate[Experiment]                           |> liftJsResult{ handleJsError }
      _           <- eventStore.publish(ExperimentCreated(id, experiment))  |> liftF[AppErrors, Done]
    } yield experiment
    // format: on
    r.value
  }

  override def update(oldId: ExperimentKey, id: ExperimentKey, data: Experiment)(
      implicit
      variantBindingStore: VariantBindingService[F],
      eVariantEventStore: ExperimentVariantEventService[F]
  ): F[Result[Experiment]] = {
    // format: off
    val r: EitherT[F, AppErrors, Experiment] = for {
      _           <- Experiment.validate(data)    |> liftEither
      _           <-  if (oldId != id) {
                        EitherT.leftT[F, Unit](AppErrors.error("error.id.not.same", oldId.key, id.key))
                      } else {
                        EitherT.rightT[F, AppErrors](())
                      }
      oldValue    <- getById(oldId)               |> liftFOption(AppErrors.error("error.data.missing", oldId.key))
      _           <- if (Experiment.isTrafficChanged(oldValue, data)) {
                        val deletes: F[Result[Done]] =
                              variantBindingStore.deleteAll(Seq(s"${oldId.key}*")) *>
                              eVariantEventStore.deleteEventsForExperiment(oldValue)
                        deletes |> liftFEither
                     } else {
                        EitherT.rightT(Done)
                     }
      experiment  <- rawUpdate(oldId, oldValue, id, data)   |> liftFEither
    } yield experiment
    // format: on
    r.value
  }

  override def rawUpdate(oldId: ExperimentKey,
                         oldValue: Experiment,
                         id: ExperimentKey,
                         data: Experiment): F[Result[Experiment]] = {
    import ExperimentInstances._
    // format: off
    val r: EitherT[F, AppErrors, Experiment] = for {
      updated     <- jsonStore.update(oldId, id, Json.toJson(data))                   |> liftFEither
      experiment  <- updated.validate[Experiment]                                     |> liftJsResult{ handleJsError }
      _           <- eventStore.publish(ExperimentUpdated(id, oldValue, experiment))  |> liftF[AppErrors, Done]
    } yield experiment
    // format: on
    r.value
  }

  override def delete(id: ExperimentKey): F[Result[Experiment]] = {
    // format: off
    val r: EitherT[F, AppErrors, Experiment] = for {
      deleted     <- jsonStore.delete(id)                                   |> liftFEither
      experiment  <- deleted.validate[Experiment]                           |> liftJsResult{ handleJsError }
      _           <- eventStore.publish(ExperimentDeleted(id, experiment))  |> liftF[AppErrors, Done]
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
      variantBindingStore: VariantBindingService[F]
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
      VariantBindingStore: VariantBindingService[F],
      eVariantEventStore: ExperimentVariantEventService[F]
  ): F[Result[Variant]] = {
    import cats.implicits._
    implicit val es = this
    val now         = LocalDateTime.now()
    // format: off
    val r: EitherT[F, AppErrors, Variant] = for {

      experiment <- getById(experimentKey) |> liftFOption(Result.AppErrors.error("error.experiment.missing"))

      variant <- experiment.campaign match {

          case Some(ClosedCampaign(_, _, won)) =>
            experiment.variants.find(_.id == won) |> liftOption(Result.AppErrors.error("error.variant.missing"))

          case Some(CurrentCampaign(from, to)) if to.isAfter(now) | to.isEqual(now) =>
            for {
              expResult   <- experimentResult(experimentKey)  |> liftF
              won         =  expResult.results.maxBy(_.transformation).variant.getOrElse(experiment.variants.head)
              id          =  experiment.id
              toUpdate    =  experiment.copy(campaign = ClosedCampaign(from, to, won.id).some)
              _           <- rawUpdate(id, experiment, id, toUpdate)      |> liftFEither
            } yield won

          case _ =>
            for {
              mayBeVariant <- VariantBindingStore.getById(VariantBindingKey(experimentKey, clientId)) |> liftF
              variant      <- mayBeVariant match {
                case None     => VariantBindingStore.createVariantForClient(experimentKey, clientId)      |> liftFEither
                case Some(v)  => experiment.variants.find(_.id == v) |> liftOption(Result.AppErrors.error("error.variant.missing"))
              }
            } yield variant
      }

    } yield variant
    // format: on
    r.value
  }

  override def experimentResult(
      experimentKey: ExperimentKey
  )(implicit eVariantEventStore: ExperimentVariantEventService[F]): F[ExperimentResult] = {
    import cats.effect.implicits._
    import libs.effects._
    Source
      .fromFuture(getById(experimentKey).toIO.unsafeToFuture())
      .mapConcat {
        _.toList
      }
      .flatMapConcat { experiment =>
        eVariantEventStore
          .findVariantResult(experiment)
          .fold(Seq.empty[VariantResult])(_ :+ _)
          .map(variantsResult => ExperimentResult(experiment, variantsResult))
          .orElse(Source.single(ExperimentResult(experiment, Seq.empty)))
      }
      .runWith(Sink.head)
      .toF
  }

  private def handleJsError(err: Seq[(JsPath, Seq[JsonValidationError])]): AppErrors = {
    Logger.error(s"Error parsing json from database $err")
    AppErrors.error("error.json.parsing")
  }
}
