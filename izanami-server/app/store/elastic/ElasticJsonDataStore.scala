package store.elastic

import java.time.LocalDateTime
import java.time.format.DateTimeFormatter
import java.time.temporal.{ChronoUnit, TemporalUnit}
import java.util.Date
import java.util.concurrent.TimeUnit

import akka.{Done, NotUsed}
import akka.http.scaladsl.util.FastFuture
import domains.Key
import elastic.api.{Bulk, BulkOpDetail, BulkOpType, Elastic, EsException, GetResponse, IndexResponse}
import env.{DbDomainConfig, ElasticConfig}
import play.api.libs.json._
import store._
import _root_.elastic.implicits._
import _root_.elastic.codec.PlayJson._
import akka.actor.ActorSystem
import akka.stream.{ActorMaterializer, Materializer}
import akka.stream.scaladsl.{Sink, Source}
import cats.data.EitherT
import cats.effect.Effect
import libs.functional.EitherTSyntax
import libs.logs.IzanamiLogger
import store.Result.{AppErrors, ErrorMessage, Result}
import store.elastic.ElasticJsonDataStore.EsDocument

import scala.concurrent.{Await, Future}
import scala.concurrent.duration.{DurationDouble, FiniteDuration}

object ElasticJsonDataStore {
  def apply[F[_]: Effect](elastic: Elastic[JsValue], elasticConfig: ElasticConfig, dbDomainConfig: DbDomainConfig)(
      implicit actorSystem: ActorSystem
  ): ElasticJsonDataStore[F] =
    new ElasticJsonDataStore(elastic, elasticConfig, dbDomainConfig)

  case class EsDocument(key: Key, value: JsValue)

  object EsDocument {
    import playjson.all._
    import shapeless.syntax.singleton._

    private val reads: Reads[EsDocument] = Json.reads[EsDocument]

    private val writes = Json.writes[EsDocument]

    implicit val format = Format(reads, writes)
  }

}

class ElasticJsonDataStore[F[_]: Effect](elastic: Elastic[JsValue],
                                         elasticConfig: ElasticConfig,
                                         dbDomainConfig: DbDomainConfig)(implicit actorSystem: ActorSystem)
    extends JsonDataStore[F]
    with EitherTSyntax[F] {

  import cats.implicits._
  import libs.effects._
  import libs.streams.syntax._
  import libs.functional.syntax._
  import actorSystem.dispatcher
  import store.elastic.ElasticJsonDataStore.EsDocument._

  private implicit val mat: Materializer = ActorMaterializer()

  private val esIndex = dbDomainConfig.conf.namespace.replaceAll(":", "_")
  private val esType  = "type"

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

  IzanamiLogger.info(s"Initializing index $esIndex with type $esType")

  Await.result(elastic.verifyIndex(esIndex).flatMap {
    case true =>
      FastFuture.successful(Done)
    case _ =>
      elastic.createIndex(esIndex, Json.parse(mapping))
  }, 3.seconds)

  private val index = elastic.index(esIndex / esType)

  override def create(id: Key, data: JsValue): F[Result[JsValue]] =
    genCreate(id, data)

  override def update(oldId: Key, id: Key, data: JsValue): F[Result[JsValue]] =
    genUpdate(oldId, id, data)

  private def genCreate(id: Key, data: JsValue): F[Result[JsValue]] =
    index
      .index[EsDocument](EsDocument(id, data),
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
      .toF

  private def genUpdate(oldId: Key, id: Key, data: JsValue): F[Result[JsValue]] =
    if (oldId == id) {
      val res = for {
        _ <- getById(oldId) |> liftFOption[AppErrors, JsValue] {
              AppErrors.error(s"error.data.missing", id.key)
            }
        _ <- index
              .index[EsDocument](EsDocument(id, data), id = Some(id.key), refresh = elasticConfig.automaticRefresh)
              .toF
              .map { _ =>
                Result.ok(data)
              } |> liftFEither[AppErrors, JsValue]
      } yield data
      res.value
    } else {
      val res: EitherT[F, AppErrors, JsValue] = for {
        _ <- getById(oldId) |> liftFOption[AppErrors, JsValue] {
              AppErrors.error(s"error.data.missing", id.key)
            }
        _ <- delete(oldId) |> liftFEither[AppErrors, JsValue]
        _ <- create(id, data) |> liftFEither[AppErrors, JsValue]
      } yield data
      res.value
    }
//
//
//    getById(oldId).flatMap {
//    case Some(_) =>
//      val r: F[Result[JsValue]] = if (oldId == id) {
//        index
//          .index[EsDocument](EsDocument(id, data), id = Some(id.key), refresh = elasticConfig.automaticRefresh)
//          .toF
//          .map { _ =>
//            Result.ok(data)
//          }
//      } else {
//        delete(oldId) *> genCreate(id, data)
//      }
//      r
//    case None =>
//      Result.errors(ErrorMessage("error.data.missing", id.key)).pure[F]
//  }

  override def delete(id: Key): F[Result[JsValue]] =
    getById(id)
      .flatMap {
        case Some(value) =>
          index.delete(id.key, refresh = elasticConfig.automaticRefresh).toF.map { _ =>
            Result.ok(value)
          }
        case None =>
          Result.error[JsValue](s"error.data.missing").pure[F]
      }

  override def getById(id: Key): F[Option[JsValue]] = {
    import cats.implicits._
    index
      .get(id.key)
      .map {
        case r @ GetResponse(_, _, _, _, true, _) =>
          r.as[EsDocument]
            .some
            .map(_.value)
        case _ =>
          none[JsValue]
      }
      .recover {
        case EsException(_, statusCode, _) if statusCode == 404 =>
          none[JsValue]
      }
      .toF
  }

  private def buildSearchQuery(query: Query): JsObject = {

    val jsonQuery: List[JsValue] = query.ands.map { clauses =>
      val jsonClauses: List[JsValue] = clauses.patterns.toList.map {
        case StringPattern(str) =>
          Json.obj("wildcard" -> Json.obj("key" -> Json.obj("value" -> str)))
        case EmptyPattern =>
          JsNull
      }

      Json.obj(
        "bool" -> Json.obj(
          "should"               -> JsArray(jsonClauses),
          "minimum_should_match" -> 1
        )
      )
    }.toList

    Json.obj(
      "query" -> Json.obj(
        "bool" -> Json.obj(
          "must" -> JsArray(jsonQuery)
        )
      )
    )
  }

  override def findByQuery(q: Query, page: Int, nbElementPerPage: Int): F[PagingResult[JsValue]] = {
    val query = buildSearchQuery(q) ++ Json.obj(
      "from" -> (page - 1) * nbElementPerPage,
      "size" -> nbElementPerPage
    )
    IzanamiLogger.debug(s"Query to $esIndex : ${Json.prettyPrint(query)}")
    index.search(query).toF.map { s =>
      val count   = s.hits.total
      val results = s.hitsAs[EsDocument].map(_.value).toList
      DefaultPagingResult(results, page, nbElementPerPage, count)
    }
  }

  override def findByQuery(query: Query): Source[(Key, JsValue), NotUsed] = findByQuerySource(query).map {
    case (k, v) => (Key(k), v)
  }

  private def findByQuerySource(q: Query): Source[(String, JsValue), NotUsed] = {
    val query = buildSearchQuery(q)
    IzanamiLogger.debug(s"Query to $esIndex : ${Json.prettyPrint(query)}")
    index
      .scroll(query = query, scroll = "1s", size = 50)
      .mapConcat { s =>
        s.hitsAs[EsDocument].map(d => (d.key.key, d.value)).toList
      }
  }

  override def deleteAll(query: Query): F[Result[Done]] =
    findByQuerySource(query)
      .map {
        case (id, _) =>
          Bulk[EsDocument](BulkOpType(delete = Some(BulkOpDetail(None, None, Some(id)))), None)
      }
      .via(index.bulkFlow(50))
      .runWith(Sink.ignore)
      .toF
      .flatMap { _ =>
        if (elasticConfig.automaticRefresh) {
          elastic.refresh(esIndex).toF.map(_ => Result.ok(Done))
        } else {
          Effect[F].pure(Result.ok(Done))
        }
      }

  override def count(q: Query): F[Long] = {
    val query = buildSearchQuery(q) ++ Json.obj(
      "size" -> 0
    )
    index.search(query).toF.map { s =>
      s.hits.total
    }
  }

}
