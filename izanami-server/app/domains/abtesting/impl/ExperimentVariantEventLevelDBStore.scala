package domains.abtesting.impl

import java.io.File

import akka.{Done, NotUsed}
import akka.actor.ActorSystem
import akka.http.scaladsl.util.FastFuture
import akka.stream.ActorMaterializer
import akka.stream.scaladsl.{Sink, Source}
import akka.util.ByteString
import domains.abtesting._
import domains.events.EventStore
import env.{DbDomainConfig, LevelDbConfig}
import org.iq80.leveldb.{DB, Options}
import org.iq80.leveldb.impl.Iq80DBFactory.factory
import play.api.Logger
import play.api.inject.ApplicationLifecycle
import play.api.libs.json.Json
import store.Result.Result
import store.Result
import store.leveldb.LevelDBRedisCommand

import scala.concurrent.{ExecutionContext, Future}

/////////////////////////////////////////////////////////////////////////////////////////
////////////////////////////////////    LEVEL DB     ////////////////////////////////////
//////////////////////////////////////////////////////////////////////////////////////////

object ExperimentVariantEventLevelDBStore {
  def apply(levelDbConfig: LevelDbConfig,
            configdb: DbDomainConfig,
            eventStore: EventStore[Future],
            actorSystem: ActorSystem,
            applicationLifecycle: ApplicationLifecycle): ExperimentVariantEventLevelDBStore =
    new ExperimentVariantEventLevelDBStore(levelDbConfig, configdb, eventStore, actorSystem, applicationLifecycle)
}

class ExperimentVariantEventLevelDBStore(levelDbConfig: LevelDbConfig,
                                         configdb: DbDomainConfig,
                                         eventStore: EventStore[Future],
                                         actorSystem: ActorSystem,
                                         applicationLifecycle: ApplicationLifecycle)
    extends ExperimentVariantEventStore {

  import actorSystem.dispatcher
  import domains.events.Events._
  implicit private val s            = actorSystem
  implicit private val materializer = ActorMaterializer()
  implicit private val es           = eventStore

  private val client: LevelDBRedisCommand = {
    val namespace  = configdb.conf.namespace
    val parentPath = levelDbConfig.parentPath
    val dbPath: String = parentPath + "/" + namespace
      .replaceAll(":", "_") + "_try_to_use_this_one"

    val db: DB =
      factory.open(new File(dbPath), new Options().createIfMissing(true))
    applicationLifecycle.addStopHook { () =>
      Logger.info(s"Closing leveldb for path $parentPath")
      Future(db.close())
    }
    new LevelDBRedisCommand(db, actorSystem)
  }

  val experimentseventsdisplayedNamespace: String =
    "experimentseventsdisplayed:count"
  val experimentseventswonNamespace: String = "experimentseventswon:count"
  val experimentseventsNamespace: String    = "experimentsevents"

  private def buildData(
      eventsKey: String,
      displayed: Long,
      won: Long,
      data: ExperimentVariantEvent,
      function: (ExperimentVariantEvent, Double) => ExperimentVariantEvent
  )(implicit ec: ExecutionContext): Future[Result[ExperimentVariantEvent]] = {
    val transformation: Double = if (displayed != 0) {
      (won * 100.0) / displayed
    } else {
      0.0
    }

    val dataToSave = function(data, transformation)

    client
      .sadd(eventsKey, Json.stringify(ExperimentVariantEvent.format.writes(dataToSave)))
      .map { _ =>
        Result.ok(dataToSave)
      }
  }

  override def create(id: ExperimentVariantEventKey,
                      data: ExperimentVariantEvent): Future[Result[ExperimentVariantEvent]] = {
    // le compteur des displayed
    val displayedCounter: String =
      s"$experimentseventsdisplayedNamespace:${id.experimentId.key}:${id.variantId}"
    // le compteur des won
    val wonCounter: String =
      s"$experimentseventswonNamespace:${id.experimentId.key}:${id.variantId}"

    val eventsKey: String =
      s"$experimentseventsNamespace:${id.experimentId.key}:${id.variantId}" // le sorted set des events

    def transformationDisplayed(data: ExperimentVariantEvent, transformation: Double): ExperimentVariantEvent = {
      val dataToSave: ExperimentVariantDisplayed =
        data.asInstanceOf[ExperimentVariantDisplayed]
      dataToSave.copy(
        transformation = transformation
      )
    }

    def transformationWon(data: ExperimentVariantEvent, transformation: Double): ExperimentVariantEvent = {
      val dataToSave: ExperimentVariantWon =
        data.asInstanceOf[ExperimentVariantWon]
      dataToSave.copy(
        transformation = transformation
      )
    }

    data match {
      case _: ExperimentVariantDisplayed =>
        for {
          displayed <- client.incr(displayedCounter) // increment display counter
          maybeWon  <- client.get(wonCounter)        // get won counter
          result <- buildData(eventsKey,
                              displayed,
                              maybeWon.map(_.utf8String.toLong).getOrElse(0),
                              data,
                              transformationDisplayed) // add event
        } yield result
      case _: ExperimentVariantWon =>
        for {
          won            <- client.incr(wonCounter)      // increment won counter
          maybeDisplayed <- client.get(displayedCounter) // get display counter
          result <- buildData(eventsKey,
                              maybeDisplayed.map(_.utf8String.toLong).getOrElse(0),
                              won,
                              data,
                              transformationWon) // add event
        } yield result
      case _ =>
        Logger.error("Event not recognized")
        FastFuture.successful(Result.error("unknow.event.type"))
    }
  }.andPublishEvent(e => ExperimentVariantEventCreated(id, e))

  private def findEvents(eventVariantKey: String): Source[ExperimentVariantEvent, NotUsed] =
    Source
      .fromFuture(
        client
          .smembers(eventVariantKey)
          .map(
            res =>
              res
                .map(_.utf8String)
                .map(Json.parse)
                .map(
                  value =>
                    ExperimentVariantEvent.format
                      .reads(value)
                      .get
                )
                .sortWith((e1, e2) => e1.date.isBefore(e2.date))
                .toList
          )
      )
      .mapConcat(identity)

  override def findVariantResult(experiment: Experiment): Source[VariantResult, NotUsed] = {
    val eventualVariantResult: Future[List[VariantResult]] = for {
      variantKeys <- client.keys(s"$experimentseventsNamespace:${experiment.id.key}:*")
      variants <- Future.sequence {
                   variantKeys.map(
                     variantKey => {
                       val currentVariantId: String =
                         variantKey.replace(s"$experimentseventsNamespace:${experiment.id.key}:", "")
                       val maybeVariant: Option[Variant] =
                         experiment.variants.find(variant => {
                           variant.id == currentVariantId
                         })

                       val fEvents: Future[Seq[ExperimentVariantEvent]] = findEvents(variantKey).runWith(Sink.seq)
                       val fDisplayed: Future[Option[ByteString]] =
                         client.get(s"$experimentseventsdisplayedNamespace:${experiment.id.key}:$currentVariantId")
                       val fWon: Future[Option[ByteString]] =
                         client.get(s"$experimentseventswonNamespace:${experiment.id.key}:$currentVariantId")

                       for {
                         events         <- fEvents
                         maybeDisplayed <- fDisplayed
                         maybeWon       <- fWon
                       } yield {
                         val displayed: Long =
                           maybeDisplayed.map(_.utf8String.toLong).getOrElse(0)
                         val won: Long = maybeWon.map(_.utf8String.toLong).getOrElse(0)
                         val transformation: Double = if (displayed != 0) {
                           (won * 100) / displayed
                         } else {
                           0.0
                         }

                         VariantResult(
                           variant = maybeVariant,
                           events = events,
                           transformation = transformation,
                           displayed = displayed,
                           won = won
                         )
                       }
                     }
                   )
                 }
    } yield variants.toList

    Source.fromFuture(eventualVariantResult).mapConcat(identity)
  }

  override def deleteEventsForExperiment(experiment: Experiment): Future[Result[Done]] =
    (
      for {
        displayedCounterKey <- client.keys(s"$experimentseventsdisplayedNamespace:${experiment.id.key}:*")
        wonCounterKey       <- client.keys(s"$experimentseventswonNamespace:${experiment.id.key}:*")
        eventsKey           <- client.keys(s"$experimentseventsNamespace:${experiment.id.key}:*")
        _                   <- Future.sequence(displayedCounterKey.map(key => client.del(key))) // remove displayed counter
        _                   <- Future.sequence(wonCounterKey.map(key => client.del(key))) // remove won counter
        _                   <- Future.sequence(eventsKey.map(key => client.del(key))) // remove events list
      } yield Result.ok(Done)
    ).andPublishEvent(e => ExperimentVariantEventsDeleted(experiment))

  override def listAll(patterns: Seq[String]) =
    Source
      .fromFuture(client.keys(s"$experimentseventsNamespace:*"))
      .mapConcat(_.toList)
      .flatMapMerge(4, key => findEvents(key))
      .filter(e => e.id.key.matchPatterns(patterns: _*))

  override def check(): Future[Unit] = FastFuture.successful(())

}
