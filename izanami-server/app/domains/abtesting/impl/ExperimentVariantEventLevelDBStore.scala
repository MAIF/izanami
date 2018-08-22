package domains.abtesting.impl

import java.io.File
import java.util.regex.Pattern

import akka.{Done, NotUsed}
import akka.actor.ActorSystem
import akka.stream.ActorMaterializer
import akka.stream.scaladsl.{Sink, Source}
import akka.util.ByteString
import cats.Applicative
import cats.effect.{Async, Effect}
import domains.abtesting._
import domains.events.EventStore
import env.{DbDomainConfig, LevelDbConfig}
import org.iq80.leveldb.{DB, Options}
import org.iq80.leveldb.impl.Iq80DBFactory.{asString, bytes, factory}
import play.api.Logger
import play.api.inject.ApplicationLifecycle
import play.api.libs.json.Json
import store.Result.Result
import store.Result
import store.leveldb.LevelDBRedisCommand

import scala.collection.immutable
import scala.concurrent.{ExecutionContext, Future}
import scala.util.Try
import scala.util.control.NonFatal

/////////////////////////////////////////////////////////////////////////////////////////
////////////////////////////////////    LEVEL DB     ////////////////////////////////////
//////////////////////////////////////////////////////////////////////////////////////////

object ExperimentVariantEventLevelDBStore {
  def apply[F[_]: Effect](levelDbConfig: LevelDbConfig,
                          configdb: DbDomainConfig,
                          eventStore: EventStore[F],
                          actorSystem: ActorSystem,
                          applicationLifecycle: ApplicationLifecycle): ExperimentVariantEventLevelDBStore[F] =
    new ExperimentVariantEventLevelDBStore[F](levelDbConfig, configdb, eventStore, actorSystem, applicationLifecycle)
}

class ExperimentVariantEventLevelDBStore[F[_]: Effect](levelDbConfig: LevelDbConfig,
                                                       configdb: DbDomainConfig,
                                                       eventStore: EventStore[F],
                                                       actorSystem: ActorSystem,
                                                       applicationLifecycle: ApplicationLifecycle)
    extends ExperimentVariantEventStore[F] {

  import cats.implicits._
  import cats.effect.implicits._
  import libs.effects._
  import actorSystem.dispatcher
  import domains.events.Events._
  implicit private val s            = actorSystem
  implicit private val materializer = ActorMaterializer()
  implicit private val es           = eventStore

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

  private val client: LevelDBRedisCommand[F] = {
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
  )(implicit ec: ExecutionContext): F[Result[ExperimentVariantEvent]] = {
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
                      data: ExperimentVariantEvent): F[Result[ExperimentVariantEvent]] = {
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
          _ <- result.traverse(e => eventStore.publish(ExperimentVariantEventCreated(id, e)))
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
          _ <- result.traverse(e => eventStore.publish(ExperimentVariantEventCreated(id, e)))
        } yield result
      case _ =>
        Logger.error("Event not recognized")
        Result.error[ExperimentVariantEvent]("unknow.event.type").pure[F]
    }
  }

  private def findEvents(eventVariantKey: String): Source[ExperimentVariantEvent, NotUsed] =
    Source
      .fromFuture(
        client
          .smembers(eventVariantKey)
          .toIO
          .unsafeToFuture()
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
    val eventualVariantResult: F[List[VariantResult]] = for {
      variantKeys <- client.keys(s"$experimentseventsNamespace:${experiment.id.key}:*")
      variants <- variantKeys.toList.traverse { variantKey =>
                   val currentVariantId: String =
                     variantKey.replace(s"$experimentseventsNamespace:${experiment.id.key}:", "")
                   val maybeVariant: Option[Variant] =
                     experiment.variants.find(variant => {
                       variant.id == currentVariantId
                     })

                   val fEvents: F[immutable.Seq[ExperimentVariantEvent]] = findEvents(variantKey).runWith(Sink.seq).toF
                   val fDisplayed: F[Option[ByteString]] =
                     client.get(s"$experimentseventsdisplayedNamespace:${experiment.id.key}:$currentVariantId")
                   val fWon: F[Option[ByteString]] =
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
    } yield variants

    Source.fromFuture(eventualVariantResult.toIO.unsafeToFuture()).mapConcat(identity)
  }

  override def deleteEventsForExperiment(experiment: Experiment): F[Result[Done]] =
    for {
      displayedCounterKey <- client.keys(s"$experimentseventsdisplayedNamespace:${experiment.id.key}:*")
      wonCounterKey       <- client.keys(s"$experimentseventswonNamespace:${experiment.id.key}:*")
      eventsKey           <- client.keys(s"$experimentseventsNamespace:${experiment.id.key}:*")
      _                   <- displayedCounterKey.toList.traverse(key => client.del(key)) // remove displayed counter
      _                   <- wonCounterKey.toList.traverse(key => client.del(key)) // remove won counter
      _                   <- eventsKey.toList.traverse(key => client.del(key)) // remove events list
      _                   <- eventStore.publish(ExperimentVariantEventsDeleted(experiment))
    } yield Result.ok(Done)

  private def get(key: String): F[Option[ByteString]] =
    Async[F].async { cb =>
      try {
        val r = getValueAt(key).map(ByteString.apply)
        cb(Right(r))
      } catch {
        case NonFatal(e) => cb(Left(e))
      }
    }

  private def keys(pattern: String): F[Seq[String]] =
    Async[F].async { cb =>
      val regex = pattern.replaceAll("\\*", ".*")
      val pat   = Pattern.compile(regex)
      try {
        val r = getAllKeys().filter { k =>
          pat.matcher(k).find
        }
        cb(Right(r))
      } catch {
        case NonFatal(e) => cb(Left(e))
      }
    }

  private def getValueAt(key: String): Option[String] =
    Try(db.get(bytes(key))).toOption.flatMap(s => Option(asString(s)))

  private def getAllKeys(): Seq[String] = {
    var keys     = Seq.empty[String]
    val iterator = db.iterator()
    iterator.seekToFirst()
    while (iterator.hasNext) {
      val key = asString(iterator.peekNext.getKey)
      keys = keys :+ key
      iterator.next
    }
    keys
  }

  override def listAll(patterns: Seq[String]) =
    Source
      .fromFuture(client.keys(s"$experimentseventsNamespace:*").toIO.unsafeToFuture())
      .mapConcat(_.toList)
      .flatMapMerge(4, key => findEvents(key))
      .filter(e => e.id.key.matchPatterns(patterns: _*))

  override def check(): F[Unit] = Applicative[F].pure(())

}
