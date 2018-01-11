package domains.abtesting.impl

import java.time.{LocalDateTime, ZoneId}

import akka.{Done, NotUsed}
import akka.actor.ActorSystem
import akka.http.scaladsl.util.FastFuture
import akka.stream.ActorMaterializer
import akka.stream.scaladsl.{Sink, Source}
import domains.abtesting._
import domains.events.EventStore
import play.api.Logger
import play.api.libs.json.Json
import redis.RedisClientMasterSlaves
import store.Result.Result
import store.{FindResult, Result, SimpleFindResult, SourceFindResult}

import scala.concurrent.Future

//////////////////////////////////////////////////////////////////////////////////////
////////////////////////////////////    REDIS     ////////////////////////////////////
//////////////////////////////////////////////////////////////////////////////////////

object ExperimentVariantEventRedisStore {
  def apply(maybeRedis: Option[RedisClientMasterSlaves],
            eventStore: EventStore,
            actorSystem: ActorSystem): ExperimentVariantEventRedisStore =
    new ExperimentVariantEventRedisStore(maybeRedis, eventStore, actorSystem)
}

class ExperimentVariantEventRedisStore(maybeRedis: Option[RedisClientMasterSlaves],
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

  val client: RedisClientMasterSlaves = maybeRedis.get

  private def now(): Long =
    LocalDateTime.now().atZone(ZoneId.systemDefault()).toInstant.toEpochMilli

  private def calcTransformation(displayed: Double, won: Double) =
    if (displayed != 0) {
      (won * 100.0) / displayed
    } else 0.0

  private def incrAndGetDisplayed(experimentId: String, variantId: String): Future[Long] = {
    val displayedCounter: String =
      s"$experimentseventsdisplayedNamespace:$experimentId:$variantId"
    client.incr(displayedCounter)
  }

  private def incrAndGetWon(experimentId: String, variantId: String): Future[Long] = {
    val wonCounter: String =
      s"$experimentseventswonNamespace:$experimentId:$variantId"
    client.incr(wonCounter)
  }

  private def getWon(experimentId: String, variantId: String): Future[Long] = {
    val wonCounter: String =
      s"$experimentseventswonNamespace:$experimentId:$variantId"
    client.get(wonCounter).map { mayBeWon =>
      mayBeWon.map(_.utf8String.toLong).getOrElse(0L)
    }
  }

  private def getDisplayed(experimentId: String, variantId: String): Future[Long] = {
    val displayedCounter: String =
      s"$experimentseventsdisplayedNamespace:$experimentId:$variantId"
    client.get(displayedCounter).map { mayBeDisplayed =>
      mayBeDisplayed.map(_.utf8String.toLong).getOrElse(0L)
    }
  }

  private def findKeys(pattern: String): Source[String, NotUsed] =
    Source
      .unfoldAsync(0) { cursor =>
        client
          .scan(cursor, matchGlob = Some(pattern))
          .map { curs =>
            if (cursor == -1) {
              None
            } else if (curs.index == 0) {
              Some(-1, curs.data)
            } else {
              Some(curs.index, curs.data)
            }
          }
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
          result <- client
                     .zadd(
                       eventsKey,
                       (now(), Json.stringify(ExperimentVariantEvent.format.writes(dataToSave)))
                     )
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
          result <- client
                     .zadd(
                       eventsKey,
                       (now(), Json.stringify(ExperimentVariantEvent.format.writes(dataToSave)))
                     )
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
        client.zrange(eventVariantKey, lastPage, nextPage).map {
          case Nil => Option.empty
          case res =>
            Option(
              (nextPage,
               res
                 .map(_.utf8String)
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
    findKeys(s"$experimentseventsNamespace:*")
      .flatMapMerge(4, key => findEvents(key).stream)
      .alsoTo(Sink.foreach(e => println(s"Event $e")))
      .filter(e => e.id.key.matchPatterns(patterns: _*))
}
