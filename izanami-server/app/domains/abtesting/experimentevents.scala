package domains.abtesting

import java.time.LocalDateTime

import akka.stream.scaladsl.{Flow, Source}
import akka.{Done, NotUsed}
import cats.effect.Effect
import domains.{ImportResult, Key}
import domains.abtesting.Experiment.ExperimentKey
import libs.IdGenerator
import play.api.libs.json._
import store.Result.{ErrorMessage, Result}

import scala.collection.immutable.HashSet
import scala.concurrent.{ExecutionContext}

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

  def eventAggregation(experiment: Experiment,
                       removeDouble: Boolean = false): Flow[ExperimentVariantEvent, VariantResult, NotUsed] =
    Flow[ExperimentVariantEvent]
      .groupBy(experiment.variants.size, _.variant.id)
      .statefulMapConcat(() => {
        var displayed = 0
        var won       = 0
        var ids       = HashSet.empty[String]
        evt =>
          {
            evt match {
              case e: ExperimentVariantDisplayed =>
                displayed += 1
                ids = ids + e.clientId
                val transformation = if (displayed != 0) {
                  (won * 100.0) / displayed
                } else 0.0
                List(
                  VariantResult(Some(e.variant),
                                displayed,
                                won,
                                transformation,
                                users = ids.size,
                                Seq(e.copy(transformation = transformation)))
                )
              case e: ExperimentVariantWon =>
                won += 1
                ids = ids + e.clientId
                val transformation = if (displayed != 0) {
                  (won * 100.0) / displayed
                } else 0.0
                List(
                  VariantResult(Some(e.variant),
                                displayed,
                                won,
                                transformation,
                                users = ids.size,
                                Seq(e.copy(transformation = transformation)))
                )
            }
          }
      })
      .fold(VariantResult()) { (acc, r) =>
        r.copy(events = acc.events ++ r.events)
      }
      .mergeSubstreams

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
