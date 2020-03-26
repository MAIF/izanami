package domains

import java.time.LocalDateTime

import akka.stream.scaladsl.{Flow, Sink, Source}
import akka.NotUsed
import cats.data.NonEmptyList
import domains.abtesting.Experiment.ExperimentKey
import domains.events.EventStore
import domains.configuration.{AkkaModule, AuthInfoModule}
import libs.logs.ZLogger
import play.api.libs.json._
import domains.errors.{IzanamiErrors, ValidatedResult, ValidationError}
import store._
import store.datastore._
import cats.implicits._
import cats.data.Validated._
import domains.abtesting.events.ExperimentVariantEventService
import domains.script.GlobalScriptDataStore
import libs.ziohelper.JsResults.jsResultToError
import zio.blocking.Blocking
import zio.{RIO, URIO, ZIO}

import scala.util.hashing.MurmurHash3

package object abtesting {

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
            val traffic          = variant.traffic.traffic
            val newStack: Double = stack + (traffic * 100)
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

    def validate(experiment: Experiment): Either[IzanamiErrors, Experiment] = {
      import domains.errors._
      val validations: ValidatedResult[Experiment] = (
        validateTraffic(experiment),
        validateCampaign(experiment)
      ).mapN { (_, _) =>
        experiment
      }
      validations.toEither.leftMap(err => IzanamiErrors(err))
    }

    private def validateTraffic(experiment: Experiment): ValidatedResult[Experiment] = {
      val allTraffic = experiment.variants.map(_.traffic.traffic).reduceLeft(_ + _)
      Either.cond(allTraffic === 1, experiment, ValidationError.error("error.traffic.not.cent.percent")).toValidated
    }

    private def validateCampaign(experiment: Experiment): ValidatedResult[Experiment] =
      experiment.campaign.fold(
        experiment.valid[ValidationError]
      ) { c =>
        Either
          .cond(
            c.from.isBefore(c.to),
            experiment,
            ValidationError.error("error.campaign.date.invalid")
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

  type ExperimentDataStore = zio.Has[ExperimentDataStore.Service]

  object ExperimentDataStore extends JsonDataStoreHelper[ExperimentDataStore] {
    trait Service {
      def experimentDataStore: JsonDataStore.Service
    }

    override def getStore: URIO[ExperimentDataStore, JsonDataStore.Service] =
      ZIO.access[ExperimentDataStore](_.get.experimentDataStore)

  }

  type ExperimentContext = AkkaModule
    with ZLogger
    with ExperimentDataStore
    with ExperimentVariantEventService
    with EventStore
    with AuthInfoModule
    with Blocking

  object ExperimentService {

    import Experiment._
    import ExperimentInstances._
    import domains.events.Events.{ExperimentCreated, ExperimentDeleted, ExperimentUpdated}
    import domains.errors._
    import IzanamiErrors._

    def create(id: ExperimentKey, data: Experiment): ZIO[ExperimentContext, IzanamiErrors, Experiment] = {
      import ExperimentInstances._
      // format: off
      for {
        _           <- AuthorizedPatterns.isAllowed(id, PatternRights.C)
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
        _           <- AuthorizedPatterns.isAllowed(id, PatternRights.U)
        _           <- ZIO.fromEither(Experiment.validate(data))
        _           <- ZIO.when(oldId =!= id)(ZIO.fail(IdMustBeTheSame(oldId, id).toErrors))      
        mayBe       <- getById(oldId)
        oldValue    <- ZIO.fromOption(mayBe).mapError(_ => DataShouldExists(oldId).toErrors)
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
        _           <- AuthorizedPatterns.isAllowed(id, PatternRights.D)
        deleted     <- ExperimentDataStore.delete(id)
        experiment  <- jsResultToError(deleted.validate[Experiment])
        authInfo    <- AuthInfo.authInfo
        _           <- EventStore.publish(ExperimentDeleted(id, experiment, authInfo = authInfo))
      } yield experiment
      // format: on

    def deleteAll(q: Query): ZIO[ExperimentContext, IzanamiErrors, Unit] =
      ExperimentDataStore.deleteAll(q)

    def getById(id: ExperimentKey): ZIO[ExperimentContext, IzanamiErrors, Option[Experiment]] =
      AuthorizedPatterns.isAllowed(id, PatternRights.R) *> ExperimentDataStore
        .getById(id)
        .refineOrDie[IzanamiErrors](PartialFunction.empty)
        .map(_.flatMap(_.validate[Experiment].asOpt))

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

    def importData(
        strategy: ImportStrategy = ImportStrategy.Keep
    ): RIO[ExperimentContext, Flow[(String, JsValue), ImportResult, NotUsed]] =
      ImportData
        .importDataFlow[ExperimentContext, ExperimentKey, Experiment](
          strategy,
          _.id,
          key => getById(key),
          (key, data) => create(key, data),
          (key, data) => update(key, key, data)
        )(ExperimentInstances.format)

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
  
        mayBeExp    <- getById(experimentKey)
        experiment  <- ZIO.fromOption(mayBeExp).mapError(_ => ValidationError.error("error.experiment.missing").toErrors)
  
        variant <- experiment.campaign match {
  
          case Some(ClosedCampaign(_, _, won)) =>
            ZIO.fromOption(experiment.variants.find(_.id === won)).mapError(_ => ValidationError.error("error.variant.missing").toErrors)
  
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
        res <- AkkaModule.system.flatMap { implicit system =>
                getById(experimentKey).flatMap { mayBeExp =>
                  ZIO
                    .fromFuture { _ =>
                      Source(mayBeExp.toList)
                        .flatMapConcat { experiment =>
                          Source
                            .futureSource(runtime.unsafeRunToFuture {
                              ExperimentVariantEventService.findVariantResult(experiment)
                            })
                            .fold(Seq.empty[VariantResult])(_ :+ _)
                            .map(variantsResult => ExperimentResult(experiment, variantsResult))
                            .orElse(Source.single(ExperimentResult(experiment, Seq.empty)))
                        }
                        .runWith(Sink.head)
                    }
                    .refineOrDie[IzanamiErrors](PartialFunction.empty)
                }
              }
      } yield res

  }
}
