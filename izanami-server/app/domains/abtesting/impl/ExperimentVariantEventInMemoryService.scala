package domains.abtesting.impl

import akka.{Done, NotUsed}
import akka.actor.{Actor, ActorSystem, Props}
import akka.stream.scaladsl.Source
import domains.abtesting._
import env.DbDomainConfig
import store.Result.Result
import store.Result
import cats.{Applicative, Monad}
import cats.effect.Effect
import domains.Key
import domains.events.EventStore
import domains.events.Events.{ExperimentVariantEventCreated, ExperimentVariantEventsDeleted}
import play.api.libs.json.JsValue

import scala.collection.concurrent.TrieMap

//////////////////////////////////////////////////////////////////////////////////////////
////////////////////////////////////    IN MEMORY     ////////////////////////////////////
//////////////////////////////////////////////////////////////////////////////////////////

object ExperimentVariantEventInMemoryService {
  def apply[F[_]: Effect](
      configdb: DbDomainConfig,
      eventStore: EventStore[F]
  )(implicit actorSystem: ActorSystem): ExperimentVariantEventInMemoryService[F] =
    new ExperimentVariantEventInMemoryService(eventStore = eventStore)
}

class ExperimentVariantEventInMemoryService[F[_]: Effect](
    wonCount: TrieMap[String, Long] = TrieMap.empty,
    displayCount: TrieMap[String, Long] = TrieMap.empty,
    datas: TrieMap[String, List[ExperimentVariantEvent]] = TrieMap.empty,
    eventStore: EventStore[F]
)(implicit actorSystem: ActorSystem)
    extends ExperimentVariantEventService[F] {

  import cats.implicits._
  import cats.effect.implicits._
  import ExperimentVariantEventInstances._

  override def create(id: ExperimentVariantEventKey,
                      event: ExperimentVariantEvent): F[Result[ExperimentVariantEvent]] = {
    val experimentId     = id.experimentId
    val variantId        = id.variantId
    val eventKey: String = s"$experimentId:$variantId"

    val events: List[ExperimentVariantEvent] =
      datas.getOrElse(eventKey, List.empty[ExperimentVariantEvent])
    val displayed: Long = displayCount.getOrElse(eventKey, 0)
    val won: Long       = wonCount.getOrElse(eventKey, 0)

    event match {
      case e: ExperimentVariantDisplayed =>
        val transfo: Double = transformation(displayed + 1, won)
        val eventToSave     = e.copy(transformation = transfo)
        displayCount.put(eventKey, displayed + 1)
        datas.put(eventKey, eventToSave :: events)

      case e: ExperimentVariantWon =>
        val transfo: Double = transformation(displayed, won + 1)
        val eventToSave     = e.copy(transformation = transfo)
        wonCount.put(eventKey, won + 1)
        datas.put(eventKey, eventToSave :: events)
    }
    eventStore.publish(ExperimentVariantEventCreated(id, event)) *>
    Result.ok(event).pure[F]
  }

  private def transformation(displayed: Long, won: Long): Double =
    if (displayed != 0) {
      won * 100.0 / displayed
    } else 0.0

  override def deleteEventsForExperiment(experiment: Experiment): F[Result[Done]] = {
    val experimentId                = experiment.id.key
    val eventKey: String            = s"$experimentId"
    val displayedCounterKey: String = s"$experimentId:"
    val wonCounterKey: String       = s"$experimentId:"

    datas.keys
      .filter(key => key.startsWith(eventKey))
      .foreach(key => datas.remove(key))

    displayCount.keys
      .filter(key => key.startsWith(displayedCounterKey))
      .foreach(key => datas.remove(key))
    wonCount.keys
      .filter(key => key.startsWith(wonCounterKey))
      .foreach(key => datas.remove(key))

    eventStore.publish(ExperimentVariantEventsDeleted(experiment)) *>
    Applicative[F].pure(Result.ok(Done))
  }

  private def events(id: String, variant: String): F[List[ExperimentVariantEvent]] = {
    val eventKey: String = s"$id:$variant"
    datas.get(eventKey).toList.flatten.pure[F]
  }

  private def display(id: String, variant: String): F[Long] = {
    val eventKey: String = s"$id:$variant"
    displayCount.getOrElse(eventKey, 0L).pure[F]
  }

  private def won(id: String, variant: String): F[Long] = {
    val eventKey: String = s"$id:$variant"
    wonCount.getOrElse(eventKey, 0L).pure[F]
  }

  override def findVariantResult(experiment: Experiment): Source[VariantResult, NotUsed] =
    Source(
      experiment.variants.toList
        .traverse { variant =>
          val fEvents: F[List[ExperimentVariantEvent]] = events(experiment.id.key, variant.id)
          val fDisplayed: F[Long]                      = display(experiment.id.key, variant.id)
          val fWon: F[Long]                            = won(experiment.id.key, variant.id)

          for {
            events    <- fEvents
            displayed <- fDisplayed
            won       <- fWon
          } yield {

            val transformation: Double = if (displayed != 0) {
              won * 100 / displayed
            } else 0.0

            VariantResult(
              variant = Some(variant),
              events = events,
              transformation = transformation,
              displayed = displayed,
              won = won
            )

          }
        }
        .toIO
        .unsafeRunSync()
    )

  override def listAll(patterns: Seq[String]) =
    Source(datas.values.flatten.filter(e => e.id.key.matchPatterns(patterns: _*)).toList)

  override def check(): F[Unit] = ().pure[F]
}
