package domains.abtesting

import java.time.LocalDateTime

import akka.http.scaladsl.util.FastFuture
import akka.stream.scaladsl.{Flow, Source}
import akka.{Done, NotUsed}
import cats.effect.Effect
import domains.{ImportResult, Key}
import domains.abtesting.Experiment.ExperimentKey
import libs.IdGenerator
import play.api.libs.json._
import store.Result.{ErrorMessage, Result}

import scala.concurrent.{ExecutionContext, Future}

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

object ExperimentVariantEvent {}

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
