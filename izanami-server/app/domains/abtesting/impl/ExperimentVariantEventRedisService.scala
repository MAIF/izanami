package domains.abtesting.impl

import java.time.{LocalDateTime, ZoneId}

import akka.{Done, NotUsed}
import akka.actor.ActorSystem
import akka.http.scaladsl.util.FastFuture
import akka.stream.ActorMaterializer
import akka.stream.scaladsl.{Sink, Source}
import cats.effect.Effect
import cats.syntax.option._
import domains.abtesting._
import domains.events.EventStore
import io.lettuce.core.{RedisClient, ScanArgs, ScanCursor, ScoredValue}
import io.lettuce.core.api.async.RedisAsyncCommands
import libs.functional.EitherTSyntax
import play.api.Logger
import play.api.libs.json.Json
import store.Result.{AppErrors, Result}
import store.redis.RedisWrapper
import store.Result

import scala.compat.java8.FutureConverters._
import scala.collection.JavaConverters._
import scala.collection.immutable
import scala.concurrent.Future
import scala.util.{Failure, Success}

//////////////////////////////////////////////////////////////////////////////////////
////////////////////////////////////    REDIS     ////////////////////////////////////
//////////////////////////////////////////////////////////////////////////////////////

object ExperimentVariantEventRedisService {
  def apply[F[_]: Effect](maybeRedis: Option[RedisWrapper], eventStore: EventStore[F])(
      implicit actorSystem: ActorSystem
  ): ExperimentVariantEventRedisService[F] =
    new ExperimentVariantEventRedisService[F](maybeRedis, eventStore)
}

class ExperimentVariantEventRedisService[F[_]: Effect](maybeRedis: Option[RedisWrapper], eventStore: EventStore[F])(
    implicit actorSystem: ActorSystem
) extends ExperimentVariantEventService[F]
    with EitherTSyntax[F] {

  import actorSystem.dispatcher
  import domains.events.Events._
  import libs.functional.syntax._
  import libs.effects._
  import libs.streams.syntax._
  import cats.implicits._
  import cats.effect.implicits._
  import ExperimentVariantEventInstances._

  implicit private val es   = eventStore
  implicit val materializer = ActorMaterializer()

  val experimentseventsdisplayedNamespace: String =
    "experimentseventsdisplayed:count"
  val experimentseventswonNamespace: String = "experimentseventswon:count"
  val experimentseventsNamespace: String    = "experimentsevents"

  val client: RedisWrapper = maybeRedis.get

  private def command(): RedisAsyncCommands[String, String] = client.connection.async()

  private def now(): Long =
    LocalDateTime.now().atZone(ZoneId.systemDefault()).toInstant.toEpochMilli

  private def calcTransformation(displayed: Double, won: Double) =
    if (displayed != 0) {
      (won * 100.0) / displayed
    } else 0.0

  private def incrAndGetDisplayed(experimentId: String, variantId: String): F[Long] = {
    val displayedCounter: String =
      s"$experimentseventsdisplayedNamespace:$experimentId:$variantId"
    command().incr(displayedCounter).toF[F].map(_.longValue())
  }

  private def incrAndGetWon(experimentId: String, variantId: String): F[Long] = {
    val wonCounter: String =
      s"$experimentseventswonNamespace:$experimentId:$variantId"
    command().incr(wonCounter).toF.map(_.longValue())
  }

  private def getWon(experimentId: String, variantId: String): F[Long] = {
    val wonCounter: String =
      s"$experimentseventswonNamespace:$experimentId:$variantId"
    get(wonCounter).map { mayBeWon =>
      mayBeWon.map(_.toLong).getOrElse(0L)
    }
  }

  private def get(id: String): F[Option[String]] =
    command().get(id).toF.map { s =>
      Option(s)
    }

  private def getDisplayed(experimentId: String, variantId: String): F[Long] = {
    val displayedCounter: String =
      s"$experimentseventsdisplayedNamespace:$experimentId:$variantId"
    get(displayedCounter).map { mayBeDisplayed =>
      mayBeDisplayed.map(_.toLong).getOrElse(0L)
    }
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

    data match {
      case e: ExperimentVariantDisplayed =>
        for {
          displayed      <- incrAndGetDisplayed(id.experimentId.key, id.variantId) // increment display counter
          won            <- getWon(id.experimentId.key, id.variantId) // get won counter
          transformation = calcTransformation(displayed, won)
          dataToSave     = e.copy(transformation = transformation)
          result <- command()
                     .zadd(
                       eventsKey,
                       ScoredValue.just(
                         now(),
                         Json.stringify(ExperimentVariantEventInstances.format.writes(dataToSave))
                       )
                     )
                     .toF
                     .map { _ =>
                       Result.ok(dataToSave)
                     } // add event
          _ <- result.traverse(f => eventStore.publish(ExperimentVariantEventCreated(id, f)))
        } yield result
      case e: ExperimentVariantWon =>
        for {
          won            <- incrAndGetWon(id.experimentId.key, id.variantId) // increment won counter
          displayed      <- getDisplayed(id.experimentId.key, id.variantId) // get display counter
          transformation = calcTransformation(displayed, won)
          dataToSave     = e.copy(transformation = transformation)
          result <- command()
                     .zadd(
                       eventsKey,
                       ScoredValue.just(
                         now(),
                         Json.stringify(ExperimentVariantEventInstances.format.writes(dataToSave))
                       )
                     )
                     .toF
                     .map { _ =>
                       Result.ok(dataToSave)
                     } // add event
          _ <- result.traverse(f => eventStore.publish(ExperimentVariantEventCreated(id, f)))
        } yield result
      case _ =>
        Logger.error("Event not recognized")
        Effect[F].pure(Result.error("unknow.event.type"))
    }
  }

  private def findEvents(eventVariantKey: String): Source[ExperimentVariantEvent, NotUsed] =
    Source
      .unfoldAsync(0L) { (lastPage: Long) =>
        val nextPage: Long = lastPage + 50
        command()
          .zrange(eventVariantKey, lastPage, nextPage)
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

  override def findVariantResult(experiment: Experiment): Source[VariantResult, NotUsed] = {
    val eventualVariantResult: F[List[VariantResult]] = for {
      variantKeys <- findKeys(s"$experimentseventsNamespace:${experiment.id.key}:*")
                      .runFold(Seq.empty[String])(_ :+ _)
                      .toF
      variants <- variantKeys.toList.traverse { variantKey =>
                   val currentVariantId: String =
                     variantKey.replace(s"$experimentseventsNamespace:${experiment.id.key}:", "")
                   val maybeVariant: Option[Variant] =
                     experiment.variants.find(variant => {
                       variant.id == currentVariantId
                     })

                   val fEvents: F[immutable.Seq[ExperimentVariantEvent]] = findEvents(variantKey).runWith(Sink.seq).toF
                   val fDisplayed: F[Long]                               = getDisplayed(experiment.id.key, currentVariantId)
                   val fWon: F[Long]                                     = getWon(experiment.id.key, currentVariantId)

                   for {
                     events    <- fEvents
                     displayed <- fDisplayed
                     won       <- fWon
                   } yield {
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

  override def deleteEventsForExperiment(experiment: Experiment): F[Result[Done]] = {
    val deletes: F[Result[Done]] =
      findKeys(s"$experimentseventsdisplayedNamespace:${experiment.id.key}:*")
        .merge(findKeys(s"$experimentseventswonNamespace:${experiment.id.key}:*"))
        .merge(findKeys(s"$experimentseventsNamespace:${experiment.id.key}:*"))
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

  override def listAll(patterns: Seq[String]) =
    findKeys(s"$experimentseventsNamespace:*")
      .flatMapMerge(4, key => findEvents(key))
      .alsoTo(Sink.foreach(e => println(s"Event $e")))
      .filter(e => e.id.key.matchPatterns(patterns: _*))

  override def check(): F[Unit] = command().get("test").toF.map(_ => ())
}
