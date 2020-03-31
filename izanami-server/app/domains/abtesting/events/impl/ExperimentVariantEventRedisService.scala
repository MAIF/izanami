package domains.abtesting.events.impl

import java.time.temporal.ChronoUnit
import java.time.{LocalDateTime, ZoneId}
import java.util.concurrent.CompletionStage

import akka.NotUsed
import akka.actor.ActorSystem
import akka.http.scaladsl.util.FastFuture
import akka.stream.scaladsl.{Sink, Source}
import cats.data.OptionT
import domains.auth.AuthInfo
import domains.abtesting._
import domains.abtesting.events._
import domains.configuration.PlayModule
import domains.errors.IzanamiErrors
import domains.events.EventStore
import env.DbDomainConfig
import env.configuration.IzanamiConfigModule
import io.lettuce.core.api.async.RedisAsyncCommands
import io.lettuce.core.{ScanArgs, ScanCursor, ScoredValue}
import libs.database.Drivers.RedisDriver
import play.api.libs.json.Json
import store.datastore.DataStoreLayerContext
import store.redis.RedisWrapper
import zio.{RIO, Task, ZIO, ZLayer}

import scala.concurrent.Future
import scala.jdk.CollectionConverters._

object ExperimentVariantEventRedisService {

  val live: ZLayer[RedisDriver with DataStoreLayerContext, Throwable, ExperimentVariantEventService] =
    ZLayer.fromFunction { mix =>
      implicit val sys: ActorSystem = mix.get[PlayModule.Service].system
      val configdb: DbDomainConfig  = mix.get[IzanamiConfigModule.Service].izanamiConfig.experimentEvent.db
      ExperimentVariantEventRedisService(configdb, mix.get[Option[RedisWrapper]])
    }

  def apply(configdb: DbDomainConfig, maybeRedis: Option[RedisWrapper])(
      implicit actorSystem: ActorSystem
  ): ExperimentVariantEventRedisService =
    new ExperimentVariantEventRedisService(configdb.conf.namespace, maybeRedis)
}

class ExperimentVariantEventRedisService(namespace: String, maybeRedis: Option[RedisWrapper])(
    implicit actorSystem: ActorSystem
) extends ExperimentVariantEventService.Service {

  import ExperimentVariantEventInstances._
  import actorSystem.dispatcher
  import cats.implicits._
  import domains.events.Events._
  import libs.effects._

  val experimentseventsNamespace: String = namespace

  val client: RedisWrapper = maybeRedis.get

  private def command(): RedisAsyncCommands[String, String] = client.connection.async()

  private def now(): Long =
    LocalDateTime.now().atZone(ZoneId.systemDefault()).toInstant.toEpochMilli

  private def zioFromCs[T](cs: => CompletionStage[T]): Task[T] =
    Task.effectAsync { cb =>
      cs.whenComplete((ok, e) => {
        if (e != null) {
          cb(Task.fail(e))
        } else {
          cb(Task.succeed(ok))
        }
      })
    }

  private def findKeys(pattern: String): Source[String, NotUsed] =
    Source
      .unfoldAsync(ScanCursor.INITIAL.some) {
        case Some(c) =>
          command()
            .scan(c, ScanArgs.Builder.matches(s"$pattern").limit(500))
            .toFuture
            .map { curs =>
              if (curs.isFinished) {
                Some((None, curs.getKeys.asScala))
              } else {
                Some(Some(curs), curs.getKeys.asScala)
              }
            }
        case None =>
          FastFuture.successful(None)
      }
      .mapConcat(_.toList)

  override def create(
      id: ExperimentVariantEventKey,
      data: ExperimentVariantEvent
  ): ZIO[ExperimentVariantEventServiceContext, IzanamiErrors, ExperimentVariantEvent] = {
    val eventsKey: String =
      s"$experimentseventsNamespace:${id.experimentId.key}:${id.variantId}" // le sorted set des events
    for {
      result <- zioFromCs {
                 command()
                   .zadd(
                     eventsKey,
                     ScoredValue.just(
                       now(),
                       Json.stringify(ExperimentVariantEventInstances.format.writes(data))
                     )
                   )
               }.orDie
                 .map { _ =>
                   data
                 } // add event
      authInfo <- AuthInfo.authInfo
      _        <- EventStore.publish(ExperimentVariantEventCreated(id, data, authInfo = authInfo))
    } yield result
  }

  private def findEvents(eventVariantKey: String): Source[ExperimentVariantEvent, NotUsed] =
    Source
      .unfoldAsync(0L) { lastPage: Long =>
        val nextPage: Long = lastPage + 50
        command()
          .zrange(eventVariantKey, lastPage, nextPage - 1)
          .toFuture
          .map(_.asScala.toList)
          .map {
            case Nil => Option.empty
            case res =>
              Option(
                (nextPage,
                 res
                   .map { Json.parse }
                   .map { value =>
                     value.validate[ExperimentVariantEvent].get
                   })
              )
          }
      }
      .mapConcat(l => l)

  private def firstFirstEvent(eventVariantKey: String): Future[Option[ExperimentVariantEvent]] =
    command()
      .zrange(eventVariantKey, 0, 1)
      .toFuture
      .map(_.asScala.headOption)
      .map {
        _.map { evt =>
          Json.parse(evt).validate[ExperimentVariantEvent].get
        }
      }

  private def interval(eventVariantKey: String): Future[ChronoUnit] =
    (for {
      evt <- OptionT(firstFirstEvent(eventVariantKey))
      min = evt.date
      max = LocalDateTime.now()
    } yield {
      ExperimentVariantEvent.calcInterval(min, max)
    }).value.map(_.getOrElse(ChronoUnit.HOURS))

  override def findVariantResult(
      experiment: Experiment
  ): RIO[ExperimentVariantEventServiceContext, Source[VariantResult, NotUsed]] =
    Task {
      findKeys(s"$experimentseventsNamespace:${experiment.id.key}:*")
        .flatMapMerge(
          4,
          key =>
            Source
              .future(interval(key))
              .flatMapConcat(
                interval =>
                  findEvents(key)
                    .via(ExperimentVariantEvent.eventAggregation(experiment.id.key, experiment.variants.size, interval))
            )
        )
    }

  override def deleteEventsForExperiment(
      experiment: Experiment
  ): ZIO[ExperimentVariantEventServiceContext, IzanamiErrors, Unit] = {
    val deletes =
      ZIO
        .fromFuture { _ =>
          findKeys(s"$experimentseventsNamespace:${experiment.id.key}:*")
            .grouped(100)
            .mapAsync(10) { keys =>
              command().del(keys: _*).toFuture
            }
            .runWith(Sink.ignore)
        }
        .unit
        .orDie

    for {
      r        <- deletes
      authInfo <- AuthInfo.authInfo
      _        <- EventStore.publish(ExperimentVariantEventsDeleted(experiment, authInfo = authInfo))
    } yield r
  }

  override def listAll(
      patterns: Seq[String]
  ): RIO[ExperimentVariantEventServiceContext, Source[ExperimentVariantEvent, NotUsed]] =
    Task {
      findKeys(s"$experimentseventsNamespace:*")
        .flatMapMerge(4, key => findEvents(key))
        .filter(e => e.id.key.matchAllPatterns(patterns: _*))
    }

  override def check(): Task[Unit] =
    zioFromCs {
      command().get("test")
    }.unit
}
