package domains.abtesting.impl

import java.io.File
import java.time.LocalDateTime
import java.time.temporal.ChronoUnit
import java.util.regex.Pattern

import akka.{Done, NotUsed}
import akka.actor.ActorSystem
import akka.stream.ActorMaterializer
import akka.stream.scaladsl.{Sink, Source}
import akka.util.ByteString
import cats.Applicative
import cats.effect.{Async, Effect}
import domains.abtesting.ExperimentVariantEvent.eventAggregation
import domains.abtesting._
import domains.events.EventStore
import env.{DbDomainConfig, LevelDbConfig}
import org.iq80.leveldb.{DB, Options}
import org.iq80.leveldb.impl.Iq80DBFactory.{asString, bytes, factory}
import libs.logs.IzanamiLogger
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

object ExperimentVariantEventLevelDBService {
  def apply[F[_]: Effect](
      levelDbConfig: LevelDbConfig,
      configdb: DbDomainConfig,
      eventStore: EventStore[F],
      applicationLifecycle: ApplicationLifecycle
  )(implicit actorSystem: ActorSystem): ExperimentVariantEventLevelDBService[F] =
    new ExperimentVariantEventLevelDBService[F](levelDbConfig, configdb, eventStore, applicationLifecycle)
}

class ExperimentVariantEventLevelDBService[F[_]: Effect](
    levelDbConfig: LevelDbConfig,
    configdb: DbDomainConfig,
    eventStore: EventStore[F],
    applicationLifecycle: ApplicationLifecycle
)(implicit actorSystem: ActorSystem)
    extends ExperimentVariantEventService[F] {

  import cats.implicits._
  import cats.effect.implicits._
  import libs.effects._
  import actorSystem.dispatcher
  import domains.events.Events._
  import ExperimentVariantEventInstances._

  implicit private val materializer = ActorMaterializer()
  implicit private val es           = eventStore

  val namespace  = configdb.conf.namespace
  val parentPath = levelDbConfig.parentPath
  val dbPath: String = parentPath + "/" + namespace
    .replaceAll(":", "_") + "_try_to_use_this_one"

  val db: DB =
    factory.open(new File(dbPath), new Options().createIfMissing(true))
  applicationLifecycle.addStopHook { () =>
    IzanamiLogger.info(s"Closing leveldb for path $parentPath")
    Future(db.close())
  }

  private val client: LevelDBRedisCommand[F] = {
    new LevelDBRedisCommand(db, actorSystem)
  }

  val experimentseventsNamespace: String = "experimentsevents"

  override def create(id: ExperimentVariantEventKey,
                      data: ExperimentVariantEvent): F[Result[ExperimentVariantEvent]] = {
    val eventsKey: String =
      s"$experimentseventsNamespace:${id.experimentId.key}:${id.variantId}"
    for {
      result <- client
                 .sadd(eventsKey, Json.stringify(ExperimentVariantEventInstances.format.writes(data)))
                 .map(l => Result.ok(data))
      _ <- result.traverse(e => eventStore.publish(ExperimentVariantEventCreated(id, e)))
    } yield result
  }

  private def findEvents(eventVariantKey: String): Source[ExperimentVariantEvent, NotUsed] =
    Source
      .fromFuture(
        client
          .smembers(eventVariantKey)
          .toIO
          .unsafeToFuture()
          .map { res =>
            res
              .map(_.utf8String)
              .map(Json.parse)
              .map(
                value =>
                  ExperimentVariantEventInstances.format
                    .reads(value)
                    .get
              )
              .sortWith((e1, e2) => e1.date.isBefore(e2.date))
              .toList
          }
      )
      .mapConcat(identity)

  override def findVariantResult(experiment: Experiment): Source[VariantResult, NotUsed] =
    Source
      .fromFuture(client.keys(s"$experimentseventsNamespace:${experiment.id.key}:*").toIO.unsafeToFuture())
      .mapConcat(k => k.toList)
      .flatMapMerge(
        4, { k =>
          Source
            .fromFuture(
              client
                .smembers(k)
                .toIO
                .unsafeToFuture()
            )
            .map { res =>
              val r = res
                .map(_.utf8String)
                .map(Json.parse)
                .map(
                  value =>
                    ExperimentVariantEventInstances.format
                      .reads(value)
                      .get
                )
                .sortWith((e1, e2) => e1.date.isBefore(e2.date))
                .toList
              (r.headOption, r)
            }
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
      )

  override def deleteEventsForExperiment(experiment: Experiment): F[Result[Done]] =
    for {
      eventsKey <- client.keys(s"$experimentseventsNamespace:${experiment.id.key}:*")
      _         <- eventsKey.toList.traverse(key => client.del(key)) // remove events list
      _         <- eventStore.publish(ExperimentVariantEventsDeleted(experiment))
    } yield Result.ok(Done)

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
