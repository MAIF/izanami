package domains.abtesting

import java.time.LocalDateTime

import akka.http.scaladsl.util.FastFuture
import akka.stream.scaladsl.{Flow, Sink, Source}
import akka.NotUsed
import cats.data.NonEmptyList
import domains.abtesting.Experiment.ExperimentKey
import domains.events.{EventStore, EventStoreContext}
import domains.{AkkaModule, ImportResult, Key}
import libs.logs.LoggerModule
import play.api.libs.json._
import store.Result.{AppErrors, Result, ValidatedResult}
import store.{Result, _}
import cats.implicits._
import cats.data.Validated._
import libs.ziohelper.JsResults.jsResultToError
import zio.{RIO, ZIO}
import domains.AuthInfoModule

import scala.util.hashing.MurmurHash3
import domains.AuthInfo

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
      case v if v.id === variant.id => variant
      case v                        => v
    })
}

object Experiment {
  type ExperimentKey = Key

  def findVariant(experiment: Experiment, id: String): Variant = {
    val variants: NonEmptyList[Variant] = experiment.variants.sortBy(_.id)
    val percentage: Int                 = Math.abs(MurmurHash3.stringHash(id)) % 100
    val allPercentage: Seq[(Variant, Double)] = variants
      .foldLeft((0.0, Seq.empty[(Variant, Double)])) {
        case ((stack, seq), variant) =>
          val traffic  = variant.traffic.traffic
          val newStack = stack + (traffic * 100)
          (newStack, seq :+ variant -> newStack)
      }
      ._2
    allPercentage
      .foldLeft(none[Variant]) {
        case (None, (variant, perc)) if percentage <= perc => Some(variant)
        case (any, _)                                      => any
      }
      .getOrElse(variants.head)
  }

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
    Either.cond(allTraffic === 1, experiment, AppErrors.error("error.traffic.not.cent.percent")).toValidated
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
                         users: Double = 0,
                         events: Seq[ExperimentResultEvent] = Seq.empty)

case class ExperimentResultEvent(experimentId: ExperimentKey,
                                 variant: Variant,
                                 date: LocalDateTime = LocalDateTime.now(),
                                 transformation: Double,
                                 variantId: String)

object VariantResult {
  def transformation(displayed: Long, won: Long): Double = displayed match {
    case 0 => 0.0
    case _ => (won * 100) / displayed
  }
}

case class ExperimentResult(experiment: Experiment, results: Seq[VariantResult] = Seq.empty[VariantResult])

trait ExperimentDataStoreModule {
  def experimentDataStore: JsonDataStore
}

trait ExperimentContext
    extends AkkaModule
    with LoggerModule
    with DataStoreContext
    with ExperimentDataStoreModule
    with ExperimentVariantEventModule
    with EventStoreContext
    with AuthInfoModule[ExperimentContext]

object ExperimentDataStore extends JsonDataStoreHelper[ExperimentContext] {
  override def accessStore = _.experimentDataStore
}

object ExperimentService {

  import Experiment._
  import ExperimentInstances._
  import domains.events.Events.{ExperimentCreated, ExperimentDeleted, ExperimentUpdated}
  import store.Result._

  def create(id: ExperimentKey, data: Experiment): ZIO[ExperimentContext, IzanamiErrors, Experiment] = {
    import ExperimentInstances._
    // format: off
    for {
      _           <- ZIO.fromEither(Experiment.validate(data))
      created     <- ExperimentDataStore.create(id, Json.toJson(data))
      experiment  <- jsResultToError(created.validate[Experiment])
      authInfo    <- AuthInfo.authInfo
      _           <- EventStore.publish(ExperimentCreated(id, experiment, authInfo = authInfo))
    } yield experiment
    // format: on
  }

  def update(oldId: ExperimentKey,
             id: ExperimentKey,
             data: Experiment): ZIO[ExperimentContext, IzanamiErrors, Experiment] =
    // format: off
    for {
      _           <- ZIO.fromEither(Experiment.validate(data))
      _           <- ZIO.when(oldId =!= id)(ZIO.fail(IdMustBeTheSame(oldId, id)))      
      mayBe       <- getById(oldId).refineToOrDie[IzanamiErrors]
      oldValue    <- ZIO.fromOption(mayBe).mapError(_ => DataShouldExists(oldId))
      _           <- ZIO.when(Experiment.isTrafficChanged(oldValue, data)) {
                       ExperimentVariantEventService.deleteEventsForExperiment(oldValue)
                     }
      experiment  <- rawUpdate(oldId, oldValue, id, data)
    } yield experiment
    // format: on

  def rawUpdate(oldId: ExperimentKey,
                oldValue: Experiment,
                id: ExperimentKey,
                data: Experiment): ZIO[ExperimentContext, IzanamiErrors, Experiment] = {
    import ExperimentInstances._
    // format: off
    for {
      updated     <- ExperimentDataStore.update(oldId, id, Json.toJson(data))
      experiment  <- jsResultToError(updated.validate[Experiment])
      authInfo    <- AuthInfo.authInfo
      _           <- EventStore.publish(ExperimentUpdated(id, oldValue, experiment, authInfo = authInfo))
    } yield experiment
    // format: on
  }

  def delete(id: ExperimentKey): ZIO[ExperimentContext, IzanamiErrors, Experiment] =
    // format: off
    for {
      deleted     <- ExperimentDataStore.delete(id)
      experiment  <- jsResultToError(deleted.validate[Experiment])
      authInfo    <- AuthInfo.authInfo
      _           <- EventStore.publish(ExperimentDeleted(id, experiment, authInfo = authInfo))
    } yield experiment
    // format: on

  def deleteAll(q: Query): ZIO[ExperimentContext, IzanamiErrors, Unit] =
    ExperimentDataStore.deleteAll(q)

  def getById(id: ExperimentKey): RIO[ExperimentContext, Option[Experiment]] =
    ExperimentDataStore.getById(id).map(_.flatMap(_.validate[Experiment].asOpt))

  def findByQuery(query: Query, page: Int, nbElementPerPage: Int): RIO[ExperimentContext, PagingResult[Experiment]] =
    ExperimentDataStore
      .findByQuery(query, page, nbElementPerPage)
      .map(jsons => JsonPagingResult(jsons))

  def findByQuery(query: Query): RIO[ExperimentContext, Source[(Key, Experiment), NotUsed]] =
    ExperimentDataStore
      .findByQuery(query)
      .map {
        _.map {
          case (k, v) => (k, v.validate[Experiment].get)
        }
      }

  def count(query: Query): RIO[ExperimentContext, Long] =
    ExperimentDataStore.count(query)

  def importData: RIO[ExperimentContext, Flow[(String, JsValue), ImportResult, NotUsed]] = {
    import cats.implicits._
    for {
      runtime <- ZIO.runtime[ExperimentContext]
      res <- ZIO.access[ExperimentContext] { ctx =>
              Flow[(String, JsValue)]
                .map { case (s, json) => (s, ExperimentInstances.format.reads(json)) }
                .mapAsync(4) {
                  case (_, JsSuccess(obj, _)) =>
                    runtime.unsafeRunToFuture(
                      create(obj.id, obj).either.map { either =>
                        ImportResult.fromResult(either)
                      }
                    )
                  case (s, JsError(_)) => FastFuture.successful(ImportResult.error(ErrorMessage("json.parse.error", s)))
                }
                .fold(ImportResult()) {
                  _ |+| _
                }
            }
    } yield res
  }

  def toGraph(clientId: String): Flow[Experiment, JsObject, NotUsed] =
    Flow[Experiment]
      .filter(_.enabled)
      .map { experiment =>
        val variant = findVariant(experiment, clientId)
        (experiment.id.jsPath \ "variant")
          .write[String]
          .writes(variant.id)
      }
      .fold(Json.obj()) { (acc, js) =>
        acc.deepMerge(js.as[JsObject])
      }

  def variantFor(
      experimentKey: ExperimentKey,
      clientId: String
  ): ZIO[ExperimentContext, IzanamiErrors, Variant] = {
    import cats.implicits._
    val now = LocalDateTime.now()
    // format: off
    for {

      mayBeExp    <- getById(experimentKey).refineToOrDie[IzanamiErrors]
      experiment  <- ZIO.fromOption(mayBeExp).mapError(_ => AppErrors.error("error.experiment.missing"))

      variant <- experiment.campaign match {

        case Some(ClosedCampaign(_, _, won)) =>
          ZIO.fromOption(experiment.variants.find(_.id === won)).mapError(_ => AppErrors.error("error.variant.missing"))

        case Some(CurrentCampaign(from, to)) if to.isBefore(now) || to.isEqual(now) =>
          for {
            expResult   <- experimentResult(experimentKey)
            won         =  expResult.results.maxBy(_.transformation).variant.getOrElse(experiment.variants.head)
            id          =  experiment.id
            toUpdate    =  experiment.copy(campaign = ClosedCampaign(from, to, won.id).some)
            _           <- rawUpdate(id, experiment, id, toUpdate)
          } yield won

        case _ =>
          ZIO.succeed(findVariant(experiment, clientId))
      }

    } yield variant
    // format: on
  }

  def experimentResult(
      experimentKey: ExperimentKey
  ): ZIO[ExperimentContext, IzanamiErrors, ExperimentResult] =
    for {
      runtime <- ZIO.runtime[ExperimentContext]
      res <- ZIO
              .accessM[ExperimentContext] { ctx =>
                import ctx._
                getById(experimentKey).flatMap { mayBeExp =>
                  ZIO
                    .fromFuture { implicit ec =>
                      Source(mayBeExp.toList)
                        .flatMapConcat { experiment =>
                          Source
                            .fromFutureSource(runtime.unsafeRunToFuture {
                              ExperimentVariantEventService.findVariantResult(experiment)
                            })
                            .fold(Seq.empty[VariantResult])(_ :+ _)
                            .map(variantsResult => ExperimentResult(experiment, variantsResult))
                            .orElse(Source.single(ExperimentResult(experiment, Seq.empty)))
                        }
                        .runWith(Sink.head)
                    }

                }
              }
              .refineToOrDie[IzanamiErrors]
    } yield res

}
