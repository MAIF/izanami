package domains.abtesting

import java.time.LocalDateTime
import java.time.temporal.ChronoUnit

import akka.actor.ActorSystem
import akka.http.scaladsl.util.FastFuture
import akka.stream.scaladsl.{Flow, Source}
import akka.NotUsed
import domains.{ImportResult, Key}
import domains.abtesting.Experiment.ExperimentKey
import domains.abtesting.impl.{
  ExperimentVariantEventCassandraService,
  ExperimentVariantEventDynamoService,
  ExperimentVariantEventElasticService,
  ExperimentVariantEventInMemoryService,
  ExperimentVariantEventLevelDBService,
  ExperimentVariantEventMongoService,
  ExperimentVariantEventPostgresqlService,
  ExperimentVariantEventRedisService
}
import domains.events.EventStoreContext
import env.{
  Cassandra,
  DbType,
  Dynamo,
  Elastic,
  InMemory,
  InMemoryWithDb,
  IzanamiConfig,
  LevelDB,
  Mongo,
  Postgresql,
  Redis
}
import libs.IdGenerator
import libs.database.Drivers
import libs.logs.IzanamiLogger
import play.api.inject.ApplicationLifecycle
import play.api.libs.json._
import store.Result.{ErrorMessage, IzanamiErrors}
import zio.blocking.Blocking
import zio.{RIO, Task, ZIO}

import scala.collection.immutable.HashSet
import store.DataStoreContext

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
    IzanamiLogger.debug(s"Calculating the best interval between $min and $max")
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

  def eventAggregation(
      experimentId: String,
      nbVariant: Int,
      interval: ChronoUnit = ChronoUnit.HOURS
  ): Flow[ExperimentVariantEvent, VariantResult, NotUsed] = {
    IzanamiLogger.debug(s"Building event results for $experimentId, interval = $interval")
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

trait ExperimentVariantEventModule extends ExperimentVariantEventServiceModule {
  def experimentVariantEventService: ExperimentVariantEventService
}

object ExperimentVariantEventService {

  def create(
      id: ExperimentVariantEventKey,
      data: ExperimentVariantEvent
  ): zio.ZIO[ExperimentVariantEventModule, IzanamiErrors, ExperimentVariantEvent] =
    ZIO.accessM(_.experimentVariantEventService.create(id, data))

  def deleteEventsForExperiment(experiment: Experiment): zio.ZIO[ExperimentVariantEventModule, IzanamiErrors, Unit] =
    ZIO.accessM(_.experimentVariantEventService.deleteEventsForExperiment(experiment))

  def findVariantResult(
      experiment: Experiment
  ): zio.RIO[ExperimentVariantEventModule, Source[VariantResult, NotUsed]] =
    ZIO.accessM(_.experimentVariantEventService.findVariantResult(experiment))

  def listAll(
      patterns: Seq[String] = Seq("*")
  ): zio.RIO[ExperimentVariantEventModule, Source[ExperimentVariantEvent, NotUsed]] =
    ZIO.accessM(_.experimentVariantEventService.listAll(patterns))

  def check(): zio.RIO[ExperimentVariantEventModule, Unit] =
    ZIO.accessM(_.experimentVariantEventService.check())

  def start: zio.RIO[ExperimentVariantEventModule, Unit] =
    ZIO.accessM(_.experimentVariantEventService.start)

  def importData: zio.RIO[ExperimentVariantEventModule, Flow[(String, JsValue), ImportResult, NotUsed]] =
    ZIO.accessM(_.experimentVariantEventService.importData)

  def apply(izanamiConfig: IzanamiConfig, drivers: Drivers, applicationLifecycle: ApplicationLifecycle)(
      implicit s: ActorSystem
  ): ExperimentVariantEventService = {
    val conf = izanamiConfig.experimentEvent.db
    // format: off

      def getExperimentVariantEventStore(dbType: DbType): ExperimentVariantEventService = dbType match {
        case InMemory  => ExperimentVariantEventInMemoryService(conf)
        case Redis     => ExperimentVariantEventRedisService(conf, drivers.redisClient)
        case LevelDB   => ExperimentVariantEventLevelDBService(izanamiConfig.db.leveldb.get, conf, applicationLifecycle)
        case Cassandra => ExperimentVariantEventCassandraService(drivers.cassandraClient.get._2, conf, izanamiConfig.db.cassandra.get)
        case Elastic   => ExperimentVariantEventElasticService(drivers.elasticClient.get, izanamiConfig.db.elastic.get, conf)
        case Mongo    =>  ExperimentVariantEventMongoService(conf, drivers.mongoApi.get)
        case Dynamo   =>  ExperimentVariantEventDynamoService(izanamiConfig.db.dynamo.get, drivers.dynamoClient.get)
        case Postgresql   =>  ExperimentVariantEventPostgresqlService(drivers.postgresqlClient.get, conf)
        case _ => throw new IllegalArgumentException("Unsupported store type ")
      }
      val store = conf.`type` match {
        case InMemoryWithDb => getExperimentVariantEventStore(izanamiConfig.db.inMemoryWithDb.get.db)
        case other => getExperimentVariantEventStore(other)
      }
      store
    }
}

trait ExperimentVariantEventServiceModule extends DataStoreContext with EventStoreContext with Blocking

trait ExperimentVariantEventService {
  import zio._

  def create(
      id: ExperimentVariantEventKey,
      data: ExperimentVariantEvent
  ): ZIO[ExperimentVariantEventServiceModule, IzanamiErrors, ExperimentVariantEvent]

  def deleteEventsForExperiment(experiment: Experiment): ZIO[ExperimentVariantEventServiceModule, IzanamiErrors, Unit]

  def findVariantResult(
      experiment: Experiment
  ): RIO[ExperimentVariantEventServiceModule, Source[VariantResult, NotUsed]]

  def listAll(
      patterns: Seq[String] = Seq("*")
  ): RIO[ExperimentVariantEventServiceModule, Source[ExperimentVariantEvent, NotUsed]]

  def check(): RIO[ExperimentVariantEventServiceModule, Unit]

  def start: RIO[ExperimentVariantEventServiceModule, Unit] = Task.succeed(())

  def importData: RIO[ExperimentVariantEventServiceModule, Flow[(String, JsValue), ImportResult, NotUsed]] = {
    import cats.implicits._
    import ExperimentVariantEventInstances._

    for {
      runtime <- ZIO.runtime[ExperimentVariantEventServiceModule]
      res <- Task(
              Flow[(String, JsValue)]
                .map { case (s, json) => (s, json.validate[ExperimentVariantEvent]) }
                .mapAsync(4) {
                  case (_, JsSuccess(obj, _)) =>
                    runtime.unsafeRunToFuture(create(obj.id, obj).either.map { either =>
                      ImportResult.fromResult(either)
                    })
                  case (s, JsError(_)) =>
                    FastFuture.successful(ImportResult.error(ErrorMessage("json.parse.error", s)))
                }
                .fold(ImportResult()) { _ |+| _ }
            )
    } yield res

  }

}
