package domains.abtesting.impl

import java.time.LocalDateTime
import java.time.format.DateTimeFormatter
import java.time.temporal.ChronoUnit

import akka.{Done, NotUsed}
import akka.actor.ActorSystem
import akka.http.scaladsl.util.FastFuture
import akka.stream.ActorMaterializer
import akka.stream.scaladsl.{Sink, Source}
import domains.Key
import domains.abtesting._
import domains.events.{EventStore}
import domains.events.Events.ExperimentVariantEventCreated
import elastic.api._
import env.{DbDomainConfig, ElasticConfig}
import libs.logs.IzanamiLogger
import play.api.libs.json.{JsObject, JsValue, Json}
import domains.errors.IzanamiErrors
import zio.{IO, RIO, Task, ZIO}

import scala.concurrent.{ExecutionContext, Future}
import libs.logs.Logger
import domains.AuthInfo
import domains.events.Events.ExperimentVariantEventsDeleted

//////////////////////////////////////////////////////////////////////////////////////////
////////////////////////////////////     ELASTIC      ////////////////////////////////////
//////////////////////////////////////////////////////////////////////////////////////////
object ExperimentVariantEventElasticService {

  def apply(
      elastic: Elastic[JsValue],
      elasticConfig: ElasticConfig,
      config: DbDomainConfig
  )(implicit actorSystem: ActorSystem): ExperimentVariantEventElasticService =
    new ExperimentVariantEventElasticService(elastic, elasticConfig, config)
}

class ExperimentVariantEventElasticService(client: Elastic[JsValue],
                                           elasticConfig: ElasticConfig,
                                           dbDomainConfig: DbDomainConfig)(implicit actorSystem: ActorSystem)
    extends ExperimentVariantEventService {

  import cats.implicits._
  import elastic.implicits._
  import elastic.codec.PlayJson._
  import ExperimentVariantEventInstances._

  private implicit val mat = ActorMaterializer()

  private val esIndex        = dbDomainConfig.conf.namespace.replaceAll(":", "_")
  private val esType         = "type"
  private val displayedIndex = s"${esIndex}_counter_displayed"
  private val wonIndex       = s"${esIndex}_counter_won"

  private val counter = Json.parse("""
                                     |{
                                     |   "settings" : { "number_of_shards" : 1 },
                                     |   "mappings" : {
                                     |     "type" : {
                                     |       "properties" : {
                                     |         "counter" : { "type" : "long" }
                                     |       }
                                     |     }
                                     | }
                                     |}
                                   """.stripMargin)

  private val mapping = Json.parse(s"""
                                      |{
                                      |   "mappings" : {
                                      |     "$esType" : {
                                      |       "properties" : {
                                      |         "id": { "type" : "keyword" },
                                      |         "clientId": { "type" : "keyword" },
                                      |         "@type": { "type" : "keyword" },
                                      |         "variant": {
                                      |           "properties" : {
                                      |             "id": { "type" : "keyword" },
                                      |             "name": { "type" : "keyword" },
                                      |             "description": { "type" : "text" },
                                      |             "traffic": { "type" : "double" },
                                      |             "currentPopulation": { "type" : "integer" }
                                      |           }
                                      |         },
                                      |         "date": { "type": "date", "format" : "date_time||date_hour_minute_second_millis" },
                                      |         "transformation": { "type" : "double" },
                                      |         "experimentId": { "type" : "keyword" },
                                      |         "variantId": { "type" : "keyword" }
                                      |       }
                                      |     }
                                      | }
                                      |}
    """.stripMargin)

  override def start: RIO[ExperimentVariantEventServiceModule, Unit] =
    Logger.info(s"Initializing index $esIndex with type $esType") *>
    Task.fromFuture { implicit ec =>
      client.verifyIndex(esIndex).flatMap {
        case true =>
          FastFuture.successful(Done)
        case _ =>
          client.createIndex(esIndex, mapping)
      }
    } *>
    Logger.info(s"Initializing index $displayedIndex with type type") *>
    Task.fromFuture { implicit ec =>
      client.verifyIndex(displayedIndex).flatMap {
        case true =>
          FastFuture.successful(Done)
        case _ =>
          client.createIndex(displayedIndex, counter)
      }
    } *>
    Logger.info(s"Initializing index $wonIndex with type type") *>
    Task.fromFuture { implicit ec =>
      client.verifyIndex(wonIndex).flatMap {
        case true =>
          FastFuture.successful(Done)
        case _ =>
          client.createIndex(wonIndex, counter)
      }
    }.unit

  private val index     = client.index(esIndex / esType)
  private val displayed = client.index(displayedIndex / "type")
  private val won       = client.index(wonIndex / "type")

  private val incrUpdateQuery =
    Json.parse("""
                 |{
                 |    "script" : {
                 |        "inline": "ctx._source.counter += params.count",
                 |        "lang": "painless",
                 |        "params" : {
                 |            "count" : 1
                 |        }
                 |    },
                 |    "upsert" : {
                 |        "counter" : 1
                 |    }
                 |}
               """.stripMargin)

  private def incrWon(experimentId: String, variantId: String): Task[Unit] = {
    val id = s"$experimentId.$variantId"
    Task.fromFuture { implicit ec =>
      won.update(incrUpdateQuery, id, retry_on_conflict = Some(5))
    }.unit
  }

  private def incrDisplayed(experimentId: String, variantId: String): Task[Unit] = {
    val id = s"$experimentId.$variantId"
    Task.fromFuture { implicit ec =>
      displayed
        .update(incrUpdateQuery, id, retry_on_conflict = Some(5))
    }.unit
  }

  private def getWon(experimentId: String, variantId: String): Task[Long] = {
    val id = s"$experimentId.$variantId"
    ZIO
      .fromFuture { implicit ec =>
        won
          .get(id)
          .map { resp =>
            (resp._source \ "counter").as[Long]
          }
          .recover {
            case EsException(_, 404, _) => 0L
          }
      }

  }

  private def getDisplayed(experimentId: String, variantId: String): Task[Long] = {
    val id = s"$experimentId.$variantId"
    ZIO
      .fromFuture { implicit ec =>
        displayed
          .get(id)
          .map { resp =>
            (resp._source \ "counter").as[Long]
          }
          .recover {
            case EsException(_, 404, _) => 0L
          }
      }
  }

  private def incrAndGetDisplayed(experimentId: String, variantId: String): IO[IzanamiErrors, Long] =
    incrDisplayed(experimentId, variantId)
      .flatMap { _ =>
        getDisplayed(experimentId, variantId)
      }
      .refineToOrDie[IzanamiErrors]

  private def incrAndGetWon(experimentId: String, variantId: String): IO[IzanamiErrors, Long] =
    incrWon(experimentId, variantId)
      .flatMap { _ =>
        getWon(experimentId, variantId)
      }
      .refineToOrDie[IzanamiErrors]

  override def create(
      id: ExperimentVariantEventKey,
      data: ExperimentVariantEvent
  ): ZIO[ExperimentVariantEventServiceModule, IzanamiErrors, ExperimentVariantEvent] =
    data match {
      case e: ExperimentVariantDisplayed =>
        for {
          displayed <- incrAndGetDisplayed(id.experimentId.key, id.variantId)                 // increment display counter
          won       <- getWon(id.experimentId.key, id.variantId).refineToOrDie[IzanamiErrors] // get won counter
          transformation = if (displayed != 0) (won * 100.0) / displayed
          else 0.0
          toSave   = e.copy(transformation = transformation)
          result   <- saveToEs(id, toSave) // add event
          authInfo <- AuthInfo.authInfo
          _        <- EventStore.publish(ExperimentVariantEventCreated(id, e, authInfo = authInfo))
        } yield result
      case e: ExperimentVariantWon =>
        for {
          won <- incrAndGetWon(id.experimentId.key, id.variantId) // increment won counter
          displayed <- getDisplayed(id.experimentId.key, id.variantId)
                        .refineToOrDie[IzanamiErrors] // get display counter
          transformation = if (displayed != 0) (won * 100.0) / displayed
          else 0.0
          toSave   = e.copy(transformation = transformation)
          result   <- saveToEs(id, toSave) // add event
          authInfo <- AuthInfo.authInfo
          _        <- EventStore.publish(ExperimentVariantEventCreated(id, e, authInfo = authInfo))
        } yield result
    }

  private def saveToEs(id: ExperimentVariantEventKey,
                       data: ExperimentVariantEvent): IO[IzanamiErrors, ExperimentVariantEvent] =
    Task
      .fromFuture { implicit ec =>
        index
          .index[ExperimentVariantEvent](
            data,
            Some(id.key.key),
            refresh = elasticConfig.automaticRefresh
          )
      }
      .map(_ => data)
      .refineToOrDie[IzanamiErrors]

  override def deleteEventsForExperiment(
      experiment: Experiment
  ): ZIO[ExperimentVariantEventServiceModule, IzanamiErrors, Unit] =
    ZIO
      .fromFuture { implicit ec =>
        Source(experiment.variants.toList)
          .flatMapMerge(
            4, { v =>
              index.scroll(
                Json.obj(
                  "query" -> Json.obj(
                    "bool" -> Json.obj(
                      "must" -> Json.arr(
                        Json.obj("term" -> Json.obj("experimentId" -> experiment.id.key)),
                        Json.obj("term" -> Json.obj("variantId"    -> v.id))
                      )
                    )
                  )
                ),
                "1s"
              )
            }
          )
          .mapConcat {
            _.hits.hits.map(doc => (doc._index, doc._id)).toList
          }
          .map {
            case (index, id) =>
              Bulk[ExperimentVariantEvent](BulkOpType(delete = Some(BulkOpDetail(Some(index), None, Some(id)))), None)
          }
          .via(index.bulkFlow[ExperimentVariantEvent](batchSize = 500))
          .mapAsync(1) { input =>
            if (elasticConfig.automaticRefresh) {
              client.refresh(esIndex).map(_ => input)
            } else {
              FastFuture.successful(input)
            }
          }
          .runWith(Sink.ignore)
      }
      .unit
      .refineToOrDie[IzanamiErrors] <* (AuthInfo.authInfo flatMap (
        authInfo => EventStore.publish(ExperimentVariantEventsDeleted(experiment, authInfo = authInfo))
    ))

  private def countUsers(experimentId: String, variant: String)(implicit ec: ExecutionContext): Future[Long] =
    index
      .search(
        Json.obj(
          "size" -> 0,
          "query" -> Json.obj(
            "bool" -> Json.obj(
              "must" -> Json.arr(
                Json.obj("term" -> Json.obj("experimentId" -> experimentId)),
                Json.obj("term" -> Json.obj("variantId"    -> variant))
              )
            )
          ),
          "aggs" -> Json.obj(
            "distinct_ids" -> Json.obj(
              "cardinality" -> Json.obj(
                "field" -> "clientId"
              )
            )
          )
        )
      )
      .map {
        case SearchResponse(_, _, _, _, _, Some(aggs)) =>
          (aggs \ "distinct_ids" \ "value").asOpt[Long].getOrElse(0L)
        case SearchResponse(_, _, _, _, _, None) =>
          0
      }

  private def aggRequest(experimentId: String, variant: String, interval: String): JsObject =
    Json.obj(
      "size" -> 0,
      "query" -> Json.obj(
        "bool" -> Json.obj(
          "must" -> Json.arr(
            Json.obj("term" -> Json.obj("experimentId" -> experimentId)),
            Json.obj("term" -> Json.obj("variantId"    -> variant))
          )
        )
      ),
      "aggs" -> Json.obj(
        "dates" -> Json.obj(
          "date_histogram" -> Json.obj(
            "field"    -> "date",
            "interval" -> interval
          ),
          "aggs" -> Json.obj(
            "events" -> Json.obj(
              "terms" -> Json.obj(
                "field" -> "@type"
              ),
              "aggs" -> Json.obj(
                "avg" -> Json.obj(
                  "avg" -> Json.obj(
                    "field" -> "transformation"
                  )
                )
              )
            )
          )
        )
      )
    )

  private def minOrMaxQuery(experimentId: String,
                            order: String): RIO[ExperimentVariantEventServiceModule, Option[LocalDateTime]] = {
    val query = Json.obj(
      "size"    -> 1,
      "_source" -> Json.arr("date"),
      "query"   -> Json.obj("term" -> Json.obj("experimentId" -> Json.obj("value" -> experimentId))),
      "sort"    -> Json.arr(Json.obj("date" -> Json.obj("order" -> order)))
    )
    Logger.debug(s"Querying ${Json.prettyPrint(query)}") *>
    ZIO
      .fromFuture { implicit ec =>
        index
          .search(
            query
          )
      }
      .map {
        case SearchResponse(_, _, _, hits, _, _) =>
          hits.hits.map(h => (h._source \ "date").as[LocalDateTime]).headOption
      }
  }

  private def min(experimentId: String): RIO[ExperimentVariantEventServiceModule, Option[LocalDateTime]] =
    minOrMaxQuery(experimentId, "asc")

  private def calcInterval(experimentId: String): RIO[ExperimentVariantEventServiceModule, String] =
    min(experimentId).map {
      case Some(min) =>
        val max = LocalDateTime.now()
        if (ChronoUnit.MONTHS.between(min, max) > 50) {
          "month"
        } else if (ChronoUnit.WEEKS.between(min, max) > 50) {
          "week"
        } else if (ChronoUnit.DAYS.between(min, max) > 50) {
          "day"
        } else if (ChronoUnit.HOURS.between(min, max) > 50) {
          "hour"
        } else if (ChronoUnit.MINUTES.between(min, max) > 50) {
          "minute"
        } else {
          "second"
        }
      case None => "second"
    }

  private def getVariantResult(
      experimentId: String,
      variant: Variant
  ): RIO[ExperimentVariantEventServiceModule, Source[VariantResult, NotUsed]] = {
    import actorSystem.dispatcher

    val variantId: String = variant.id

    ZIO.runtime[ExperimentVariantEventServiceModule].map { runtime =>
      val events: Source[Seq[ExperimentResultEvent], NotUsed] = Source
        .fromFuture(runtime.unsafeRunToFuture(calcInterval(experimentId)))
        .mapAsync(1) { interval =>
          val query = aggRequest(experimentId, variantId, interval)
          IzanamiLogger.debug(s"Querying ${Json.prettyPrint(query)}")
          index
            .search(query)
            .map {
              case SearchResponse(_, _, _, _, _, Some(aggs)) =>
                (aggs \ "dates" \ "buckets").as[Seq[JsObject]].flatMap { dates =>
                  val date =
                    LocalDateTime.parse((dates \ "key_as_string").as[String], DateTimeFormatter.ISO_DATE_TIME)

                  (dates \ "events" \ "buckets").as[Seq[JsObject]].map { event =>
                    val transformation =
                      (event \ "avg" \ "value").asOpt[Double].getOrElse(0d)
                    (event \ "key").as[String] match {
                      case "VariantDisplayedEvent" =>
                        ExperimentResultEvent(
                          Key(experimentId),
                          variant,
                          date,
                          transformation,
                          variantId
                        )
                      case "VariantWonEvent" =>
                        ExperimentResultEvent(Key(experimentId), variant, date, transformation, variantId)
                    }
                  }
                }
              case SearchResponse(_, _, _, _, _, None) =>
                Seq.empty[ExperimentResultEvent]
            }
        }

      val won: Source[Long, NotUsed] =
        Source.fromFuture(runtime.unsafeRunToFuture(getWon(experimentId, variantId)))
      val displayed: Source[Long, NotUsed] =
        Source.fromFuture(runtime.unsafeRunToFuture(getDisplayed(experimentId, variantId)))
      val users = Source.fromFuture(countUsers(experimentId, variantId))

      events.zip(won).zip(displayed).zip(users).map {
        case (((e, w), d), u) =>
          VariantResult(
            variant = Some(variant),
            displayed = d,
            won = w,
            users = u,
            transformation = if (d != 0) (w * 100.0) / d else 0.0,
            events = e
          )
      }
    }

  }

  override def findVariantResult(
      experiment: Experiment
  ): RIO[ExperimentVariantEventServiceModule, Source[VariantResult, NotUsed]] =
    ZIO
      .runtime[ExperimentVariantEventServiceModule]
      .map { runtime =>
        Source(experiment.variants.toList)
          .flatMapMerge(4,
                        v => Source.fromFutureSource(runtime.unsafeRunToFuture(getVariantResult(experiment.id.key, v))))
      }

  override def listAll(
      patterns: Seq[String]
  ): RIO[ExperimentVariantEventServiceModule, Source[ExperimentVariantEvent, NotUsed]] =
    Task.fromFuture(
      implicit ec =>
        FastFuture.successful(
          index
            .scroll(Json.obj("query" -> Json.obj("match_all" -> Json.obj())))
            .mapConcat(s => s.hitsAs[ExperimentVariantEvent].toList)
            .filter(e => e.id.key.matchAllPatterns(patterns: _*))
      )
    )

  override def check(): Task[Unit] =
    ZIO.fromFuture { implicit ec =>
      client
        .index(esIndex / esType)
        .get("test")
        .recover {
          case EsException(_, statusCode, _) if statusCode === 404 =>
            ()
        }
    }.unit
}
