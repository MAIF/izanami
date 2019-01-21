package domains.abtesting

import java.time.LocalDateTime
import java.time.temporal.ChronoUnit

import akka.stream.scaladsl.{Flow, Source}
import akka.{Done, NotUsed}
import cats.effect.Effect
import domains.{ImportResult, Key}
import domains.abtesting.Experiment.ExperimentKey
import libs.IdGenerator
import play.api.Logger
import play.api.libs.json._
import store.Result.{ErrorMessage, Result}

import scala.collection.immutable.HashSet
import scala.concurrent.ExecutionContext

/* ************************************************************************* */
/*                      ExperimentVariantEvent                               */
/* ************************************************************************* */

case class ExperimentVariantEventKey(experimentId: ExperimentKey,
                                     variantId: String,
                                     clientId: String,
                                     namespace: String,
                                     id: String) {
  def key: Key =
    Key.Empty / experimentId / variantId / clientId / namespace / id
}

object ExperimentVariantEventKey {

  private val idGenerator = IdGenerator(1024)

  def apply(key: Key): ExperimentVariantEventKey = {
    val id :: pattern :: clientId :: variantId :: experimentId =
      key.segments.toList.reverse
    ExperimentVariantEventKey(Key(experimentId.reverse), variantId, clientId, pattern, id)
  }

  def generateId: String = s"${idGenerator.nextId()}"
}

sealed trait ExperimentVariantEvent {
  def id: ExperimentVariantEventKey
  def variant: Variant
  def date: LocalDateTime
}

case class ExperimentVariantDisplayed(id: ExperimentVariantEventKey,
                                      experimentId: ExperimentKey,
                                      clientId: String,
                                      variant: Variant,
                                      date: LocalDateTime = LocalDateTime.now(),
                                      transformation: Double,
                                      variantId: String)
    extends ExperimentVariantEvent

case class ExperimentVariantWon(id: ExperimentVariantEventKey,
                                experimentId: ExperimentKey,
                                clientId: String,
                                variant: Variant,
                                date: LocalDateTime = LocalDateTime.now(),
                                transformation: Double,
                                variantId: String)
    extends ExperimentVariantEvent

object ExperimentVariantEvent {

  private def keepEvent(from: LocalDateTime, to: LocalDateTime, interval: ChronoUnit): Boolean =
    interval.between(from, to) >= 1

  def calcInterval(min: LocalDateTime, max: LocalDateTime): ChronoUnit = {
    Logger.debug(s"Calculating the best interval between $min and $max")
    if (ChronoUnit.MONTHS.between(min, max) > 50) {
      ChronoUnit.MONTHS
    } else if (ChronoUnit.WEEKS.between(min, max) > 50) {
      ChronoUnit.WEEKS
    } else if (ChronoUnit.DAYS.between(min, max) > 50) {
      ChronoUnit.DAYS
    } else if (ChronoUnit.HOURS.between(min, max) > 50) {
      ChronoUnit.HOURS
    } else if (ChronoUnit.MINUTES.between(min, max) > 50) {
      ChronoUnit.MINUTES
    } else {
      ChronoUnit.SECONDS
    }
  }

  def eventAggregation(experimentId: String,
                       nbVariant: Int,
                       interval: ChronoUnit = ChronoUnit.HOURS,
                       removeDouble: Boolean = false): Flow[ExperimentVariantEvent, VariantResult, NotUsed] = {
    Logger.debug(s"Building event results for $experimentId, interval = $interval")
    Flow[ExperimentVariantEvent]
      .groupBy(nbVariant, _.variant.id)
      .statefulMapConcat(() => {
        var first                                 = true
        var displayed                             = 0
        var won                                   = 0
        var ids                                   = HashSet.empty[String]
        var lastDateStored: Option[LocalDateTime] = None
        evt =>
          {
            val (newEvent, transformation) = evt match {
              case ExperimentVariantDisplayed(_, expId, clientId, variant, date, _, variantId) =>
                displayed += 1
                ids = ids + clientId
                val transformation = if (displayed != 0) {
                  (won * 100.0) / displayed
                } else 0.0

                (ExperimentResultEvent(expId, variant, date, transformation, variantId), transformation)
              case ExperimentVariantWon(_, expId, clientId, variant, date, _, variantId) =>
                won += 1
                ids = ids + clientId
                val transformation = if (displayed != 0) {
                  (won * 100.0) / displayed
                } else 0.0

                (ExperimentResultEvent(expId, variant, date, transformation, variantId), transformation)
            }

            val lastDate = lastDateStored.getOrElse {
              lastDateStored = Some(evt.date)
              evt.date
            }

            val currentDate = evt.date

            if (keepEvent(lastDate, currentDate, interval) || first) {
              first = false
              lastDateStored = Some(currentDate)
              List(
                (displayed,
                 won,
                 transformation,
                 Some(
                   VariantResult(Some(evt.variant), displayed, won, transformation, users = ids.size, Seq(newEvent))
                 ))
              )
            } else {
              List((displayed, won, transformation, None))
            }
          }
      })
      .fold(VariantResult()) {
        case (acc, (d, w, t, Some(r))) =>
          r.copy(events = acc.events ++ r.events, displayed = d, won = w, transformation = t)
        case (acc, (d, w, t, None)) =>
          acc.copy(displayed = d, won = w, transformation = t)
      }
      .mergeSubstreams
  }

}

trait ExperimentVariantEventService[F[_]] {

  def create(id: ExperimentVariantEventKey, data: ExperimentVariantEvent): F[Result[ExperimentVariantEvent]]

  def deleteEventsForExperiment(experiment: Experiment): F[Result[Done]]

  def findVariantResult(experiment: Experiment): Source[VariantResult, NotUsed]

  def listAll(patterns: Seq[String] = Seq("*")): Source[ExperimentVariantEvent, NotUsed]

  def check(): F[Unit]

  def importData(implicit ec: ExecutionContext, effect: Effect[F]): Flow[(String, JsValue), ImportResult, NotUsed] = {

    import cats.implicits._
    import libs.streams.syntax._
    import ExperimentVariantEventInstances._

    Flow[(String, JsValue)]
      .map { case (s, json) => (s, json.validate[ExperimentVariantEvent]) }
      .mapAsyncF(4) {
        case (_, JsSuccess(obj, _)) =>
          create(obj.id, obj).map { ImportResult.fromResult }
        case (s, JsError(_)) =>
          effect.pure(ImportResult.error(ErrorMessage("json.parse.error", s)))
      }
      .fold(ImportResult()) { _ |+| _ }
  }

}
