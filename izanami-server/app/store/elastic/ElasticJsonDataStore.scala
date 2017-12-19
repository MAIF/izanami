package store.elastic

import java.time.LocalDateTime
import java.time.format.DateTimeFormatter
import java.time.temporal.{ChronoUnit, TemporalUnit}
import java.util.Date
import java.util.concurrent.TimeUnit

import akka.{Done, NotUsed}
import akka.http.scaladsl.util.FastFuture
import domains.Key
import elastic.api.{
  Bulk,
  BulkOpDetail,
  BulkOpType,
  Elastic,
  EsException,
  GetResponse,
  IndexResponse
}
import env.{DbDomainConfig, ElasticConfig}
import play.api.libs.json._
import store._
import _root_.elastic.implicits._
import _root_.elastic.codec.PlayJson._
import akka.actor.ActorSystem
import akka.stream.ActorMaterializer
import akka.stream.scaladsl.{Sink, Source}
import play.api.Logger
import store.Result.{ErrorMessage, Result}
import store.elastic.ElasticJsonDataStore.EsDocument

import scala.concurrent.{Await, Future}
import scala.concurrent.duration.{DurationDouble, FiniteDuration}

object ElasticJsonDataStore {
  def apply(elastic: Elastic[JsValue],
            elasticConfig: ElasticConfig,
            dbDomainConfig: DbDomainConfig,
            actorSystem: ActorSystem): ElasticJsonDataStore =
    new ElasticJsonDataStore(elastic,
                             elasticConfig,
                             dbDomainConfig,
                             actorSystem)

  case class EsDocument(key: Key,
                        value: JsValue,
                        deathDate: Option[LocalDateTime] = None,
                        lastUpdate: LocalDateTime = LocalDateTime.now())

  object EsDocument {
    import playjson.all._
    import shapeless.syntax.singleton._

    private val reads: Reads[EsDocument] = Json.reads[EsDocument]
//      jsonRead[EsDocument].withRules(
//      'created ->> read[Option[LocalDateTime]]
//    )
    private val writes = Json.writes[EsDocument]

    implicit val format = Format(reads, writes)
  }

}

class ElasticJsonDataStore(elastic: Elastic[JsValue],
                           elasticConfig: ElasticConfig,
                           dbDomainConfig: DbDomainConfig,
                           actorSystem: ActorSystem)
    extends JsonDataStore {

  import actorSystem.dispatcher
  import store.elastic.ElasticJsonDataStore.EsDocument._

  private implicit val s = actorSystem
  private implicit val mat = ActorMaterializer()

  private val esIndex = dbDomainConfig.conf.namespace.replaceAll(":", "_")
  private val esType = "type"

  private val mapping =
    s"""
      |{
      |   "settings" : { "number_of_shards" : 1 },
      |   "mappings" : {
      |     "$esType" : {
      |       "properties" : {
      |         "key" : { "type" : "keyword" },
      |         "value" : { "type" : "object" },
      |         "deathDate": { "type": "date", "format" : "date_hour_minute_second_millis||date_hour_minute_second" },
      |         "lastUpdate": { "type": "date", "format" : "date_hour_minute_second_millis||date_hour_minute_second" }
      |       }
      |     }
      | }
      |}
    """.stripMargin

  Logger.info(s"Initializing index $esIndex with type $esType")

  Await.result(elastic.verifyIndex(esIndex).flatMap {
    case true =>
      FastFuture.successful(Done)
    case _ =>
      elastic.createIndex(esIndex, Json.parse(mapping))
  }, 3.seconds)

  private val index = elastic.index(esIndex / esType)

  override def createWithTTL(id: Key,
                             data: JsValue,
                             ttl: FiniteDuration): Future[Result[JsValue]] =
    genCreate(id, data, Some(ttl))

  override def updateWithTTL(oldId: Key,
                             id: Key,
                             data: JsValue,
                             ttl: FiniteDuration): Future[Result[JsValue]] =
    genUpdate(oldId, id, data, Some(ttl))

  override def create(id: Key, data: JsValue): Future[Result[JsValue]] =
    genCreate(id, data, None)

  override def update(oldId: Key,
                      id: Key,
                      data: JsValue): Future[Result[JsValue]] =
    genUpdate(oldId, id, data, None)

  private def genCreate(
      id: Key,
      data: JsValue,
      mayBeTtl: Option[FiniteDuration]): Future[Result[JsValue]] = {
    val mayBeDeathDate = mayBeTtl.map { ttl =>
      LocalDateTime.now().plus(ttl.toMillis, ChronoUnit.MILLIS)
    }
    index
      .index[EsDocument](EsDocument(id, data, deathDate = mayBeDeathDate),
                         id = Some(id.key),
                         create = true,
                         refresh = elasticConfig.automaticRefresh)
      .map { _ =>
        Result.ok(data)
      }
      .recover {
        case EsException(json, 409, _) =>
          Result.errors(ErrorMessage("error.data.exists", id.key))
      }
  }

  private def genUpdate(
      oldId: Key,
      id: Key,
      data: JsValue,
      mayBeTtl: Option[FiniteDuration]): Future[Result[JsValue]] = {
    val mayBeDeathDate = mayBeTtl.map { ttl =>
      LocalDateTime.now().plus(ttl.toMillis, ChronoUnit.MILLIS)
    }
    if (oldId == id) {
      index
        .index[EsDocument](EsDocument(id, data, deathDate = mayBeDeathDate),
                           id = Some(id.key),
                           refresh = elasticConfig.automaticRefresh)
        .map { _ =>
          Result.ok(data)
        }
    } else {
      for {
        _ <- delete(id)
        created <- genCreate(id, data, mayBeTtl)
      } yield created
    }
  }

  override def delete(id: Key): Future[Result[JsValue]] =
    getById(id).one
      .flatMap {
        case Some(value) =>
          index.delete(id.key, refresh = elasticConfig.automaticRefresh).map {
            _ =>
              Result.ok(value)
          }
        case None =>
          FastFuture.successful(Result.error(s"error.data.missing"))
      }

  override def deleteAll(patterns: Seq[String]): Future[Result[Done]] =
    getByIdLikeSource(patterns)
      .map {
        case (id, _) =>
          Bulk[EsDocument](
            BulkOpType(delete = Some(BulkOpDetail(None, None, Some(id)))),
            None)
      }
      .via(index.bulkFlow(50))
      .runWith(Sink.ignore)
      .flatMap { _ =>
        if (elasticConfig.automaticRefresh) {
          elastic.refresh(esIndex).map(_ => Result.ok(Done))
        } else {
          FastFuture.successful(Result.ok(Done))
        }
      }

  override def getById(id: Key): FindResult[JsValue] =
    SimpleFindResult(
      index
        .get(id.key)
        .map {
          case r @ GetResponse(_, _, _, _, true, source) =>
            List(r.as[EsDocument])
              .filter(d =>
                d.deathDate.forall(d => LocalDateTime.now().isBefore(d)))
              .map(_.value)
          case _ =>
            List.empty
        }
        .recover {
          case EsException(_, statusCode, _) if statusCode == 404 => List.empty
        }
    )

  private def buildSearchQuery(patterns: Seq[String]): JsObject = {

    val queryWithTTL = Json.obj(
      "bool" -> Json.obj(
        "must" -> (Json.arr(
          Json.obj("exists" -> Json.obj("field" -> "deathDate")),
          Json.obj(
            "range" -> Json
              .obj(
                "deathDate" -> Json.obj("gte" -> DateTimeFormatter.ISO_DATE_TIME
                  .format(LocalDateTime.now())))
          ),
        ) ++ JsArray(
          patterns.map { pattern =>
            Json.obj(
              "wildcard" -> Json.obj("key" -> Json.obj("value" -> pattern)))
          }
        ))
      )
    )

    val queryWithoutTTL = Json.obj(
      "bool" -> Json.obj(
        "must_not" -> Json.obj("exists" -> Json.obj("field" -> "deathDate")),
        "must" -> JsArray(
          patterns.map { pattern =>
            Json.obj(
              "wildcard" -> Json.obj("key" -> Json.obj("value" -> pattern)))
          }
        )
      )
    )

    Json.obj(
      "query" -> Json.obj(
        "bool" -> Json.obj(
          "should" -> Json.arr(queryWithTTL, queryWithoutTTL),
          "minimum_should_match" -> 1
        )
      )
    )
  }

  override def getByIdLike(
      patterns: Seq[String],
      page: Int,
      nbElementPerPage: Int): Future[PagingResult[JsValue]] = {
    val query = buildSearchQuery(patterns) ++ Json.obj(
      "from" -> (page - 1) * nbElementPerPage,
      "size" -> nbElementPerPage
    )
    Logger.debug(s"Query to $esIndex : ${Json.prettyPrint(query)}")
    index.search(query).map { s =>
      val count = s.hits.total
      val results = s.hitsAs[EsDocument].map(_.value).toList
      DefaultPagingResult(results, page, nbElementPerPage, count)
    }
  }

  override def getByIdLike(patterns: Seq[String]): FindResult[JsValue] =
    SourceFindResult(getByIdLikeSource(patterns).map(_._2))

  private def getByIdLikeSource(
      patterns: Seq[String]): Source[(String, JsValue), NotUsed] = {
    val query = buildSearchQuery(patterns)
    Logger.debug(s"Query to $esIndex : ${Json.prettyPrint(query)}")
    index
      .scroll(query = query, scroll = "1s", size = 50)
      .mapConcat { s =>
        s.hitsAs[EsDocument].map(d => (d.key.key, d.value)).toList
      }
  }

  override def count(patterns: Seq[String]): Future[Long] = {
    val query = buildSearchQuery(patterns) ++ Json.obj(
      "size" -> 0
    )
    index.search(query).map { s =>
      s.hits.total
    }
  }
}
