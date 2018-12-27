package domains.abtesting.impl

import java.time.temporal.ChronoUnit
import java.time.{LocalDateTime, ZoneId}

import akka.{Done, NotUsed}
import akka.actor.ActorSystem
import akka.http.scaladsl.util.FastFuture
import akka.stream.{ActorMaterializer, Materializer}
import akka.stream.scaladsl.{Sink, Source}
import cats.data.OptionT
import cats.effect.Effect
import domains.abtesting._
import domains.events.EventStore
import env.DbDomainConfig
import io.lettuce.core.{ScanArgs, ScanCursor, ScoredValue}
import io.lettuce.core.api.async.RedisAsyncCommands
import libs.functional.EitherTSyntax
import play.api.Logger
import play.api.libs.json.Json
import store.Result.Result
import store.redis.RedisWrapper
import store.Result

import scala.collection.JavaConverters._

object ExperimentVariantEventRedisService {
  def apply[F[_]: Effect](configdb: DbDomainConfig, maybeRedis: Option[RedisWrapper], eventStore: EventStore[F])(
      implicit actorSystem: ActorSystem
  ): ExperimentVariantEventRedisService[F] =
    new ExperimentVariantEventRedisService[F](configdb.conf.namespace, maybeRedis, eventStore)
}

class ExperimentVariantEventRedisService[F[_]: Effect](namespace: String,
                                                       maybeRedis: Option[RedisWrapper],
                                                       eventStore: EventStore[F])(
    implicit actorSystem: ActorSystem
) extends ExperimentVariantEventService[F]
    with EitherTSyntax[F] {

  import actorSystem.dispatcher
  import domains.events.Events._
  import libs.effects._
  import libs.streams.syntax._
  import cats.implicits._
  import cats.effect.implicits._
  import ExperimentVariantEventInstances._

  implicit private val es: EventStore[F]  = eventStore
  implicit val materializer: Materializer = ActorMaterializer()

  val experimentseventsNamespace: String = namespace

  val client: RedisWrapper = maybeRedis.get

  private def command(): RedisAsyncCommands[String, String] = client.connection.async()

  private def now(): Long =
    LocalDateTime.now().atZone(ZoneId.systemDefault()).toInstant.toEpochMilli

  private def get(id: String): F[Option[String]] =
    command().get(id).toF.map { s =>
      Option(s)
    }

  private def findKeys(pattern: String): Source[String, NotUsed] =
    Source
      .unfoldAsync(ScanCursor.INITIAL.some) {
        case Some(c) =>
          command()
            .scan(c, ScanArgs.Builder.matches(s"$pattern").limit(500))
            .toF
            .map { curs =>
              if (curs.isFinished) {
                Some((None, curs.getKeys.asScala))
              } else {
                Some(Some(curs), curs.getKeys.asScala)
              }
            }
            .toIO
            .unsafeToFuture()
        case None =>
          FastFuture.successful(None)
      }
      .mapConcat(_.toList)

  override def create(id: ExperimentVariantEventKey,
                      data: ExperimentVariantEvent): F[Result[ExperimentVariantEvent]] = {
    val eventsKey: String =
      s"$experimentseventsNamespace:${id.experimentId.key}:${id.variantId}" // le sorted set des events
    for {
      result <- command()
                 .zadd(
                   eventsKey,
                   ScoredValue.just(
                     now(),
                     Json.stringify(ExperimentVariantEventInstances.format.writes(data))
                   )
                 )
                 .toF
                 .map { _ =>
                   Result.ok(data)
                 } // add event
      _ <- result.traverse(f => eventStore.publish(ExperimentVariantEventCreated(id, f)))
    } yield result
  }

  private def findEvents(eventVariantKey: String): Source[ExperimentVariantEvent, NotUsed] =
    Source
      .unfoldAsync(0L) { lastPage: Long =>
        val nextPage: Long = lastPage + 50
        command()
          .zrange(eventVariantKey, lastPage, nextPage - 1)
          .toF
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
          .toIO
          .unsafeToFuture()
      }
      .mapConcat(l => l)

  private def firstFirstEvent(eventVariantKey: String): F[Option[ExperimentVariantEvent]] =
    command()
      .zrange(eventVariantKey, 0, 1)
      .toF
      .map(_.asScala.headOption)
      .map {
        _.map { evt =>
          Json.parse(evt).validate[ExperimentVariantEvent].get
        }
      }

  private def interval(eventVariantKey: String): F[ChronoUnit] =
    (for {
      evt <- OptionT(firstFirstEvent(eventVariantKey))
      min = evt.date
      max = LocalDateTime.now()
    } yield {
      ExperimentVariantEvent.calcInterval(min, max)
    }).value.map(_.getOrElse(ChronoUnit.HOURS))

  override def findVariantResult(experiment: Experiment): Source[VariantResult, NotUsed] =
    findKeys(s"$experimentseventsNamespace:${experiment.id.key}:*")
      .flatMapMerge(
        4,
        key =>
          Source
            .fromFuture(interval(key).toIO.unsafeToFuture())
            .flatMapConcat(
              interval =>
                findEvents(key)
                  .via(ExperimentVariantEvent.eventAggregation(experiment.id.key, experiment.variants.size, interval))
          )
      )

  override def deleteEventsForExperiment(experiment: Experiment): F[Result[Done]] = {
    val deletes: F[Result[Done]] =
      findKeys(s"$experimentseventsNamespace:${experiment.id.key}:*")
        .grouped(100)
        .mapAsyncF(10) { keys =>
          command().del(keys: _*).toF
        }
        .runWith(Sink.ignore)
        .toF
        .map(_ => Result.ok(Done))

    for {
      r <- deletes
      _ <- r.traverse(_ => eventStore.publish(ExperimentVariantEventsDeleted(experiment)))
    } yield r
  }

  override def listAll(patterns: Seq[String]): Source[ExperimentVariantEvent, NotUsed] =
    findKeys(s"$experimentseventsNamespace:*")
      .flatMapMerge(4, key => findEvents(key))
      .filter(e => e.id.key.matchPatterns(patterns: _*))

  override def check(): F[Unit] = command().get("test").toF.map(_ => ())
}
