package domains.abtesting.impl

import java.time.LocalDateTime
import java.time.temporal.ChronoUnit

import akka.{Done, NotUsed}
import akka.actor.{Actor, ActorSystem, Props}
import akka.stream.scaladsl.Source
import domains.abtesting._
import env.DbDomainConfig
import domains.errors.IzanamiErrors
import ExperimentDataStoreActor._
import domains.abtesting.ExperimentVariantEvent.eventAggregation
import domains.events.EventStore
import domains.events.Events.{ExperimentVariantEventCreated, ExperimentVariantEventsDeleted}
import zio.{RIO, Task, ZIO}

import scala.concurrent.Future
import domains.AuthInfo

//////////////////////////////////////////////////////////////////////////////////////////
////////////////////////////////////    IN MEMORY     ////////////////////////////////////
//////////////////////////////////////////////////////////////////////////////////////////

object ExperimentVariantEventInMemoryService {
  def apply(
      configdb: DbDomainConfig
  )(implicit actorSystem: ActorSystem): ExperimentVariantEventInMemoryService =
    new ExperimentVariantEventInMemoryService(configdb.conf.namespace)
}

class ExperimentVariantEventInMemoryService(namespace: String)(
    implicit actorSystem: ActorSystem
) extends ExperimentVariantEventService {

  import actorSystem.dispatcher
  import akka.pattern._
  import akka.util.Timeout
  import cats.implicits._

  import scala.concurrent.duration.DurationInt

  private implicit val timeout: Timeout = Timeout(5.second)

  private val store = actorSystem.actorOf(Props[ExperimentDataStoreActor](new ExperimentDataStoreActor()),
                                          namespace + "_in_memory_exp_event")

  override def create(
      id: ExperimentVariantEventKey,
      data: ExperimentVariantEvent
  ): ZIO[ExperimentVariantEventServiceModule, IzanamiErrors, ExperimentVariantEvent] =
    ZIO
      .fromFuture { _ =>
        (store ? AddEvent(id.experimentId.key, id.variantId, data)).mapTo[ExperimentVariantEvent]
      }
      .refineToOrDie[IzanamiErrors] <* (AuthInfo.authInfo flatMap (
        authInfo =>
          EventStore.publish(
            ExperimentVariantEventCreated(id, data, authInfo = authInfo)
          )
    ))

  override def deleteEventsForExperiment(
      experiment: Experiment
  ): ZIO[ExperimentVariantEventServiceModule, IzanamiErrors, Unit] =
    ZIO
      .fromFuture { _ =>
        (store ? DeleteEvents(experiment.id.key)).mapTo[Done]
      }
      .unit
      .refineToOrDie[IzanamiErrors] <* (AuthInfo.authInfo flatMap (
        authInfo => EventStore.publish(ExperimentVariantEventsDeleted(experiment, authInfo = authInfo))
    ))

  override def findVariantResult(
      experiment: Experiment
  ): RIO[ExperimentVariantEventServiceModule, Source[VariantResult, NotUsed]] =
    ZIO.runtime[ExperimentVariantEventServiceModule].map { _ =>
      Source
        .fromFuture(
          experiment.variants.toList
            .traverse { variant =>
              findEvents(experiment, variant)
                .map(l => (l.headOption, l))
            }
        )
        .mapConcat(identity)
        .flatMapMerge(
          4, {
            case (first, evts) =>
              val interval = first
                .map(e => ExperimentVariantEvent.calcInterval(e.date, LocalDateTime.now()))
                .getOrElse(ChronoUnit.HOURS)
              Source(evts)
                .via(eventAggregation(experiment.id.key, experiment.variants.size, interval))
          }
        )
    }

  private def findEvents(experiment: Experiment, variant: Variant): Future[List[ExperimentVariantEvent]] =
    (store ? FindEvents(experiment.id.key, variant.id)).mapTo[List[ExperimentVariantEvent]]

  override def listAll(
      patterns: Seq[String]
  ): RIO[ExperimentVariantEventServiceModule, Source[ExperimentVariantEvent, NotUsed]] =
    Task(
      Source
        .fromFuture((store ? GetAll(patterns)).mapTo[Seq[ExperimentVariantEvent]])
        .mapConcat(_.toList)
    )

  override def check(): Task[Unit] = Task.succeed(())
}

private[abtesting] class ExperimentDataStoreActor extends Actor {

  private var datas: Map[String, List[ExperimentVariantEvent]] =
    Map.empty[String, List[ExperimentVariantEvent]]

  val experimentseventsNamespace: String = "experimentsevents"

  def transformation(displayed: Long, won: Long): Double =
    if (displayed != 0) {
      won * 100.0 / displayed
    } else 0.0

  override def receive: Receive = {
    case AddEvent(experimentId, variantId, event) =>
      val eventKey: String =
        s"$experimentseventsNamespace:$experimentId:$variantId"

      val events: List[ExperimentVariantEvent] =
        datas.getOrElse(eventKey, List.empty[ExperimentVariantEvent])

      datas = datas + (eventKey -> (event :: events))
      sender() ! event

    case FirstEvent(experimentId, variantId) =>
      val eventKey: String =
        s"$experimentseventsNamespace:$experimentId:$variantId"
      sender() ! datas
        .getOrElse(eventKey, List.empty[ExperimentVariantEvent])
        .sortWith((e1, e2) => e1.date.isBefore(e2.date))
        .headOption

    case FindEvents(experimentId, variantId) =>
      val eventKey: String =
        s"$experimentseventsNamespace:$experimentId:$variantId"
      sender() ! datas
        .getOrElse(eventKey, List.empty[ExperimentVariantEvent])
        .sortWith((e1, e2) => e1.date.isBefore(e2.date))

    case GetAll(patterns) =>
      sender() ! datas.values.flatten.filter(e => e.id.key.matchAllPatterns(patterns: _*))

    case DeleteEvents(experimentId) =>
      val eventKey: String = s"$experimentseventsNamespace:$experimentId:"

      datas.keys
        .filter(key => key.startsWith(eventKey))
        .foreach(key => datas = datas - key)

      sender() ! Done

    case m =>
      unhandled(m)
  }
}

private[abtesting] object ExperimentDataStoreActor {

  sealed trait ExperimentDataMessages

  case class AddEvent(experimentId: String, variantId: String, event: ExperimentVariantEvent)
      extends ExperimentDataMessages

  case class FindEvents(experimentId: String, variantId: String) extends ExperimentDataMessages
  case class FirstEvent(experimentId: String, variantId: String) extends ExperimentDataMessages

  case class GetAll(patterns: Seq[String]) extends ExperimentDataMessages

  case class DeleteEvents(experimentId: String) extends ExperimentDataMessages

}
