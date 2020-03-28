package domains.abtesting

import java.time.LocalDateTime
import java.time.temporal.ChronoUnit

import akka.NotUsed
import akka.actor.ActorSystem
import akka.http.scaladsl.util.FastFuture
import akka.stream.scaladsl.{Flow, Source}
import domains.abtesting.Experiment.ExperimentKey
import domains.abtesting.events.ExperimentVariantEventService
import domains.abtesting.events.impl.{
  ExperimentVariantEventCassandraService,
  ExperimentVariantEventDynamoService,
  ExperimentVariantEventElasticService,
  ExperimentVariantEventInMemoryService,
  ExperimentVariantEventLevelDBService,
  ExperimentVariantEventMongoService,
  ExperimentVariantEventPostgresqlService,
  ExperimentVariantEventRedisService
}
import domains.abtesting.{Experiment, ExperimentResultEvent, Variant, VariantResult}
import domains.auth.AuthInfo
import domains.configuration.{AkkaModule, PlayModule}
import domains.errors.{ErrorMessage, IzanamiErrors}
import domains.events.EventStore
import domains.{ImportResult, Key}
import env._
import env.configuration.IzanamiConfigModule
import libs.IdGenerator
import libs.database.Drivers
import libs.logs.{IzanamiLogger, ZLogger}
import play.api.inject.ApplicationLifecycle
import play.api.libs.json.{JsError, JsSuccess, JsValue}
import zio.{ZIO, ZLayer}
import zio.blocking.Blocking

import scala.collection.immutable.HashSet

package object events {

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

  type ExperimentVariantEventService = zio.Has[ExperimentVariantEventService.Service]

  type ExperimentVariantEventServiceModule = ExperimentVariantEventService
    with ZLogger
    with EventStore
    with Blocking
    with AuthInfo

  type ExperimentVariantEventServiceContext = ZLogger with EventStore with Blocking with AuthInfo

  object ExperimentVariantEventService {

    trait Service {
      import zio._

      def create(
          id: ExperimentVariantEventKey,
          data: ExperimentVariantEvent
      ): ZIO[ExperimentVariantEventServiceContext, IzanamiErrors, ExperimentVariantEvent]

      def deleteEventsForExperiment(
          experiment: Experiment
      ): ZIO[ExperimentVariantEventServiceContext, IzanamiErrors, Unit]

      def findVariantResult(
          experiment: Experiment
      ): RIO[ExperimentVariantEventServiceContext, Source[VariantResult, NotUsed]]

      def listAll(
          patterns: Seq[String] = Seq("*")
      ): RIO[ExperimentVariantEventServiceContext, Source[ExperimentVariantEvent, NotUsed]]

      def check(): RIO[ExperimentVariantEventServiceContext, Unit]

      def start: RIO[ExperimentVariantEventServiceContext, Unit] = Task.succeed(())

      def importData: RIO[ExperimentVariantEventServiceContext, Flow[(String, JsValue), ImportResult, NotUsed]] = {
        import cats.implicits._
        import ExperimentVariantEventInstances._
        for {
          runtime <- ZIO.runtime[ExperimentVariantEventServiceContext]
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

    def create(
        id: ExperimentVariantEventKey,
        data: ExperimentVariantEvent
    ): zio.ZIO[ExperimentVariantEventServiceModule, IzanamiErrors, ExperimentVariantEvent] =
      ZIO.accessM[ExperimentVariantEventServiceModule](_.get.create(id, data))

    def deleteEventsForExperiment(
        experiment: Experiment
    ): zio.ZIO[ExperimentVariantEventServiceModule, IzanamiErrors, Unit] =
      ZIO.accessM[ExperimentVariantEventServiceModule](_.get.deleteEventsForExperiment(experiment))

    def findVariantResult(
        experiment: Experiment
    ): zio.RIO[ExperimentVariantEventServiceModule, Source[VariantResult, NotUsed]] =
      ZIO.accessM[ExperimentVariantEventServiceModule](_.get.findVariantResult(experiment))

    def listAll(
        patterns: Seq[String] = Seq("*")
    ): zio.RIO[ExperimentVariantEventServiceModule, Source[ExperimentVariantEvent, NotUsed]] =
      ZIO.accessM[ExperimentVariantEventServiceModule](_.get.listAll(patterns))

    def check(): zio.ZIO[ExperimentVariantEventServiceModule, IzanamiErrors, Unit] =
      ZIO.accessM[ExperimentVariantEventServiceModule](_.get.check().orDie)

    def start: zio.RIO[ExperimentVariantEventServiceModule, Unit] =
      ZIO.accessM[ExperimentVariantEventServiceModule](_.get.start)

    def importData(): zio.RIO[ExperimentVariantEventServiceModule, Flow[(String, JsValue), ImportResult, NotUsed]] =
      ZIO.accessM[ExperimentVariantEventServiceModule](_.get.importData)

    def value(store: ExperimentVariantEventService.Service): ZLayer[Any, Nothing, ExperimentVariantEventService] =
      ZLayer.succeed(store)

    val live: ZLayer[AkkaModule with PlayModule with Drivers with IzanamiConfigModule,
                     Nothing,
                     ExperimentVariantEventService] =
      ZLayer.fromFunction { mix =>
        implicit val actorSystem: ActorSystem = mix.get[AkkaModule.Service].system
        val playModule: PlayModule.Service    = mix.get[PlayModule.Service]
        val izanamiConfig: IzanamiConfig      = mix.get[IzanamiConfigModule.Service].izanamiConfig
        val drivers: Drivers.Service          = mix.get[Drivers.Service]
        ExperimentVariantEventService(izanamiConfig, drivers, playModule.applicationLifecycle)
      }

    def apply(izanamiConfig: IzanamiConfig, drivers: Drivers.Service, applicationLifecycle: ApplicationLifecycle)(
        implicit s: ActorSystem
    ): ExperimentVariantEventService.Service = {
      val conf = izanamiConfig.experimentEvent.db
      // format: off
  
        def getExperimentVariantEventStore(dbType: DbType): ExperimentVariantEventService.Service = dbType match {
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
  
}
