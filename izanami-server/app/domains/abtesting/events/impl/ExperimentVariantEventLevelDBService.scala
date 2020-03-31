package domains.abtesting.events.impl

import java.io.File
import java.time.LocalDateTime
import java.time.temporal.ChronoUnit
import java.util.regex.Pattern

import akka.NotUsed
import akka.actor.ActorSystem
import akka.stream.ActorAttributes
import akka.stream.scaladsl.Source
import akka.util.ByteString
import domains.auth.AuthInfo
import domains.abtesting._
import domains.abtesting.events.ExperimentVariantEvent.eventAggregation
import domains.abtesting.events.impl.ExperimentVariantEventDbStores.BlockingIO
import domains.abtesting.events.{ExperimentVariantEventServiceContext, _}
import domains.configuration.PlayModule
import domains.errors.IzanamiErrors
import domains.events.EventStore
import env.configuration.IzanamiConfigModule
import env.{DbDomainConfig, LevelDbConfig}
import libs.logs.{IzanamiLogger, ZLogger}
import org.iq80.leveldb.impl.Iq80DBFactory.{asString, bytes, factory}
import org.iq80.leveldb.{DB, Options}
import play.api.inject.ApplicationLifecycle
import play.api.libs.json.Json
import store.datastore.DataStoreLayerContext
import zio.blocking.Blocking
import zio.{RIO, Task, UIO, ZIO, ZLayer, ZManaged}

import scala.collection.concurrent.TrieMap
import scala.concurrent.Future
import scala.util.Try

/////////////////////////////////////////////////////////////////////////////////////////
////////////////////////////////////    LEVEL DB     ////////////////////////////////////
//////////////////////////////////////////////////////////////////////////////////////////

object ExperimentVariantEventDbStores {
  val stores = TrieMap.empty[String, ExperimentVariantEventLevelDBService]

  type BlockingIO[A] = zio.RIO[zio.blocking.Blocking, A]
}

object ExperimentVariantEventLevelDBService {

  val live: ZLayer[DataStoreLayerContext, Throwable, ExperimentVariantEventService] = ZLayer.fromFunctionManaged {
    mix =>
      implicit val sys: ActorSystem    = mix.get[PlayModule.Service].system
      val izanamiConfig                = mix.get[IzanamiConfigModule.Service].izanamiConfig
      val configdb: DbDomainConfig     = izanamiConfig.experimentEvent.db
      val levelDbConfig: LevelDbConfig = izanamiConfig.db.leveldb.get
      val namespace                    = configdb.conf.namespace
      val parentPath                   = levelDbConfig.parentPath
      val dbPath: String               = parentPath + "/" + namespace.replaceAll(":", "_")
      ZManaged
        .make(ZLogger.info(s"Opening leveldb for path $dbPath") *> Task {
          val folder = new File(dbPath).getAbsoluteFile
          factory.open(folder, new Options().createIfMissing(true))
        })(db => ZLogger.info(s"Closing leveldb for path $dbPath") *> UIO(db.close()))
        .provide(mix)
        .map(db => new ExperimentVariantEventLevelDBService(db))
  }

  def apply(dbPath: String)(implicit actorSystem: ActorSystem): ExperimentVariantEventLevelDBService = {
    val folder = new File(dbPath).getAbsoluteFile
    val db     = factory.open(folder, new Options().createIfMissing(true))
    new ExperimentVariantEventLevelDBService(db)
  }

}

class ExperimentVariantEventLevelDBService(db: DB)(implicit actorSystem: ActorSystem)
    extends ExperimentVariantEventService.Service {

  import actorSystem.dispatcher
  import cats.implicits._
  import domains.events.Events._

  private val experimentseventsNamespace: String = "experimentsevents"

  override def create(
      id: ExperimentVariantEventKey,
      data: ExperimentVariantEvent
  ): ZIO[ExperimentVariantEventServiceContext, IzanamiErrors, ExperimentVariantEvent] = {
    val eventsKey: String = s"$experimentseventsNamespace:${id.experimentId.key}:${id.variantId}"
    val strEvent          = Json.stringify(ExperimentVariantEventInstances.format.writes(data))
    for {
      _        <- sadd(eventsKey, strEvent).orDie
      authInfo <- AuthInfo.authInfo
      _        <- EventStore.publish(ExperimentVariantEventCreated(id, data, authInfo = authInfo))
    } yield data
  }

  private def findEvents(
      eventVariantKey: String
  ): RIO[ExperimentVariantEventServiceContext, Source[ExperimentVariantEvent, NotUsed]] =
    ZIO.runtime[ExperimentVariantEventServiceContext].map { runtime =>
      Source
        .future(
          runtime
            .unsafeRunToFuture(smembers(eventVariantKey))
            .map { res =>
              res
                .map(_.utf8String)
                .map(Json.parse)
                .map { value =>
                  ExperimentVariantEventInstances.format
                    .reads(value)
                    .get
                }
                .sortWith((e1, e2) => e1.date.isBefore(e2.date))
                .toList
            }
        )
        .mapConcat(identity)
    }

  override def findVariantResult(
      experiment: Experiment
  ): RIO[ExperimentVariantEventServiceContext, Source[VariantResult, NotUsed]] =
    ZIO.runtime[ExperimentVariantEventServiceContext].map { runtime =>
      Source(keys(s"$experimentseventsNamespace:${experiment.id.key}:*").toList)
        .addAttributes(ActorAttributes.dispatcher("izanami.blocking-dispatcher"))
        .flatMapMerge(
          4, { k =>
            Source
              .future(
                runtime.unsafeRunToFuture(smembers(k))
              )
              .map { res =>
                val r = res
                  .map(_.utf8String)
                  .map(Json.parse)
                  .map { value =>
                    ExperimentVariantEventInstances.format
                      .reads(value)
                      .get
                  }
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
    }

  override def deleteEventsForExperiment(
      experiment: Experiment
  ): ZIO[ExperimentVariantEventServiceContext, IzanamiErrors, Unit] =
    // format: off
    for {
      runtime   <- ZIO.runtime[ExperimentVariantEventServiceContext]
      eventsKey <- keysT(s"$experimentseventsNamespace:${experiment.id.key}:*").orDie
      _         <- {  implicit val r = runtime
                      import zio.interop.catz._
                      eventsKey.toList.traverse(key => del(key)).orDie // remove events list
                   }
      authInfo  <- AuthInfo.authInfo
      _         <- EventStore.publish(ExperimentVariantEventsDeleted(experiment, authInfo = authInfo))
    } yield ()
    // format: on

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

  override def listAll(
      patterns: Seq[String]
  ): RIO[ExperimentVariantEventServiceContext, Source[ExperimentVariantEvent, NotUsed]] =
    ZIO.runtime[ExperimentVariantEventServiceContext].map { runtime =>
      Source(keys(s"$experimentseventsNamespace:*").toList)
        .addAttributes(ActorAttributes.dispatcher("izanami.blocking-dispatcher"))
        .flatMapMerge(4, key => Source.futureSource(runtime.unsafeRunToFuture(findEvents(key))))
        .filter(e => e.id.key.matchAllPatterns(patterns: _*))
    }

  override def check(): Task[Unit] = Task.succeed(())

  private def getValueAt(key: String): Option[String] =
    Try(db.get(bytes(key))).toOption.flatMap(s => Option(asString(s)))

  private def getSetAt(key: String): Set[ByteString] =
    getValueAt(key)
      .map { set =>
        set.split(";;;").toSet.map((s: String) => ByteString(s))
      }
      .getOrElse(Set.empty[ByteString])

  private def sadd(key: String, members: String*): BlockingIO[Long] =
    saddBS(key, members.map(ByteString.apply): _*)

  private def setSetAt(key: String, set: Set[ByteString]): Unit =
    db.put(bytes(key), bytes(set.map(_.utf8String).mkString(";;;")))

  private def saddBS(key: String, members: ByteString*): BlockingIO[Long] =
    zio.blocking.blocking(Task {
      val seq    = getSetAt(key)
      val newSeq = seq ++ members
      setSetAt(key, newSeq)
      members.size
    })

  private def del(keys: String*): BlockingIO[Long] =
    zio.blocking.blocking(Task {
      keys
        .map { k =>
          db.delete(bytes(k))
          1L
        }
        .foldLeft(0L)((a, b) => a + b)
    })

  private def smembers(key: String): BlockingIO[Seq[ByteString]] =
    zio.blocking.blocking(Task {
      val seq = getSetAt(key)
      seq.toSeq
    })

  def keysT(pattern: String): RIO[Blocking, Seq[String]] =
    zio.blocking.blocking {
      Task(keys(pattern))
    }

  def keys(pattern: String): Seq[String] =
    getAllKeys().filter { k =>
      val regex = pattern.replaceAll("\\*", ".*")
      val pat   = Pattern.compile(regex)
      pat.matcher(k).find
    }
}
