package domains.abtesting.impl

import java.time.{LocalDateTime, ZoneId}

import akka.{Done, NotUsed}
import akka.actor.ActorSystem
import akka.http.scaladsl.util.FastFuture
import akka.stream.ActorMaterializer
import akka.stream.scaladsl.{Sink, Source}
import cats.syntax.option._
import domains.abtesting._
import domains.events.EventStore
import io.lettuce.core.{RedisClient, ScanArgs, ScanCursor}
import io.lettuce.core.api.async.RedisAsyncCommands
import play.api.Logger
import play.api.libs.json.Json
import store.Result.Result
import store.redis.RedisWrapper
import store.{FindResult, Result, SimpleFindResult, SourceFindResult}

import scala.compat.java8.FutureConverters._
import scala.collection.JavaConverters._
import scala.concurrent.Future

//////////////////////////////////////////////////////////////////////////////////////
////////////////////////////////////    REDIS     ////////////////////////////////////
//////////////////////////////////////////////////////////////////////////////////////

object ExperimentVariantEventRedisStore {
  def apply(maybeRedis: Option[RedisWrapper],
            eventStore: EventStore,
            actorSystem: ActorSystem): ExperimentVariantEventRedisStore =
    new ExperimentVariantEventRedisStore(maybeRedis, eventStore, actorSystem)
}

class ExperimentVariantEventRedisStore(maybeRedis: Option[RedisWrapper],
                                       eventStore: EventStore,
                                       actorSystem: ActorSystem)
    extends ExperimentVariantEventStore {

  import actorSystem.dispatcher
  import domains.events.Events._

  implicit private val es   = eventStore
  implicit private val s    = actorSystem
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

  private def incrAndGetDisplayed(experimentId: String, variantId: String): Future[Long] = {
    val displayedCounter: String =
      s"$experimentseventsdisplayedNamespace:$experimentId:$variantId"
    command().incr(displayedCounter).toScala.map(_.longValue())
  }

  private def incrAndGetWon(experimentId: String, variantId: String): Future[Long] = {
    val wonCounter: String =
      s"$experimentseventswonNamespace:$experimentId:$variantId"
    command().incr(wonCounter).toScala.map(_.longValue())
  }

  private def getWon(experimentId: String, variantId: String): Future[Long] = {
    val wonCounter: String =
      s"$experimentseventswonNamespace:$experimentId:$variantId"
    get(wonCounter).map { mayBeWon =>
      mayBeWon.map(_.toLong).getOrElse(0L)
    }
  }

  private def get(id: String): Future[Option[String]] =
    command().get(id).toScala.map { s =>
      Option(s)
    }

  private def getDisplayed(experimentId: String, variantId: String): Future[Long] = {
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
            .scan(c, ScanArgs.Builder.matches(s"$pattern:*"))
            .toScala
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

  override def create(id: ExperimentVariantEventKey,
                      data: ExperimentVariantEvent): Future[Result[ExperimentVariantEvent]] = {

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
                       (now(), Json.stringify(ExperimentVariantEvent.format.writes(dataToSave)))
                     )
                     .toScala
                     .map { _ =>
                       Result.ok(dataToSave)
                     } // add event
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
                       (now(), Json.stringify(ExperimentVariantEvent.format.writes(dataToSave)))
                     )
                     .toScala
                     .map { _ =>
                       Result.ok(dataToSave)
                     } // add event
        } yield result
      case _ =>
        Logger.error("Event not recognized")
        FastFuture.successful(Result.error("unknow.event.type"))
    }
  }.andPublishEvent(e => ExperimentVariantEventCreated(id, e))

  private def findEvents(eventVariantKey: String): FindResult[ExperimentVariantEvent] = {
    val source: Source[ExperimentVariantEvent, NotUsed] = Source
      .unfoldAsync(0L) { (lastPage: Long) =>
        val nextPage: Long = lastPage + 50
        command().zrange(eventVariantKey, lastPage, nextPage).toScala.map(_.asScala.toList).map {
          case Nil => Option.empty
          case res =>
            Option(
              (nextPage,
               res
                 .map { Json.parse }
                 .map { value =>
                   value.validate[ExperimentVariantEvent].get
                 }
                 .toList)
            )
        }
      }
      .mapConcat(l => l)

    SourceFindResult(source)
  }

  override def findVariantResult(experiment: Experiment): FindResult[VariantResult] = {
    val eventualVariantResult: Future[List[VariantResult]] = for {
      variantKeys <- findKeys(s"$experimentseventsNamespace:${experiment.id.key}:*")
                      .runFold(Seq.empty[String])(_ :+ _)
      variants <- Future.sequence {
                   variantKeys.map(
                     variantKey => {
                       val currentVariantId: String =
                         variantKey.replace(s"$experimentseventsNamespace:${experiment.id.key}:", "")
                       val maybeVariant: Option[Variant] =
                         experiment.variants.find(variant => {
                           variant.id == currentVariantId
                         })

                       val fEvents: Future[Seq[ExperimentVariantEvent]] = findEvents(variantKey).list
                       val fDisplayed: Future[Long]                     = getDisplayed(experiment.id.key, currentVariantId)
                       val fWon: Future[Long]                           = getWon(experiment.id.key, currentVariantId)

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
                   )
                 }
    } yield variants.toList

    SimpleFindResult(eventualVariantResult)
  }

  override def deleteEventsForExperiment(experiment: Experiment): Future[Result[Done]] =
    findKeys(s"$experimentseventsdisplayedNamespace:${experiment.id.key}:*")
      .merge(findKeys(s"$experimentseventswonNamespace:${experiment.id.key}:*"))
      .merge(findKeys(s"$experimentseventsNamespace:${experiment.id.key}:*"))
      .grouped(100)
      .mapAsync(10) { keys =>
        command().del(keys: _*).toScala
      }
      .runWith(Sink.ignore)
      .map(_ => Result.ok(Done))
      .andPublishEvent(e => ExperimentVariantEventsDeleted(experiment))

  override def listAll(patterns: Seq[String]) =
    findKeys(s"$experimentseventsNamespace:*")
      .flatMapMerge(4, key => findEvents(key).stream)
      .alsoTo(Sink.foreach(e => println(s"Event $e")))
      .filter(e => e.id.key.matchPatterns(patterns: _*))
}
