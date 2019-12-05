package store.elastic

import elastic.codec.PlayJson._
import elastic.implicits._
import akka.{Done, NotUsed}
import akka.actor.ActorSystem
import akka.http.scaladsl.util.FastFuture
import akka.stream.{ActorMaterializer, Materializer}
import akka.stream.scaladsl.{Sink, Source}
import cats.implicits._
import domains.Key
import elastic.api.{Bulk, BulkOpDetail, BulkOpType, Elastic, EsException, GetResponse}
import env.{DbDomainConfig, ElasticConfig}
import libs.logs.IzanamiLogger
import libs.logs.Logger
import play.api.libs.json._

import scala.concurrent.ExecutionContext
import store._
import store.elastic.ElasticJsonDataStore.EsDocument
import domains.errors.{DataShouldExists, DataShouldNotExists, IzanamiErrors}

object ElasticJsonDataStore {
  def apply(elastic: Elastic[JsValue], elasticConfig: ElasticConfig, dbDomainConfig: DbDomainConfig)(
      implicit actorSystem: ActorSystem
  ): ElasticJsonDataStore =
    new ElasticJsonDataStore(elastic, elasticConfig, dbDomainConfig)

  case class EsDocument(key: Key, value: JsValue)

  object EsDocument {

    private val reads: Reads[EsDocument] = Json.reads[EsDocument]

    private val writes = Json.writes[EsDocument]

    implicit val format = Format(reads, writes)
  }
}

class ElasticJsonDataStore(elastic: Elastic[JsValue], elasticConfig: ElasticConfig, dbDomainConfig: DbDomainConfig)(
    implicit actorSystem: ActorSystem
) extends JsonDataStore {

  import zio._
  import store.elastic.ElasticJsonDataStore.EsDocument._
  import IzanamiErrors._

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

  override def start: RIO[DataStoreContext, Unit] =
    Logger.info(s"Initializing index $esIndex with type $esType") *>
    Task.fromFuture { implicit ec =>
      elastic.verifyIndex(esIndex).flatMap {
        case true =>
          FastFuture.successful(Done)
        case _ =>
          elastic.createIndex(esIndex, Json.parse(mapping))
      }
    }.unit

  private val index = elastic.index(esIndex / esType)

  override def create(id: Key, data: JsValue): IO[IzanamiErrors, JsValue] =
    genCreate(id, data)

  override def update(oldId: Key, id: Key, data: JsValue): IO[IzanamiErrors, JsValue] =
    genUpdate(oldId, id, data)

  private def genCreate(id: Key, data: JsValue): IO[IzanamiErrors, JsValue] =
    IO.fromFuture { implicit ec =>
        index
          .index[EsDocument](EsDocument(id, data),
                             id = Some(id.key),
                             create = true,
                             refresh = elasticConfig.automaticRefresh)
          .map { _ =>
            data
          }
      }
      .catchAll {
        case EsException(_, 409, _) => IO.fail(DataShouldNotExists(id).toErrors)
      }

  private def genUpdate(oldId: Key, id: Key, data: JsValue): IO[IzanamiErrors, JsValue] =
    if (oldId === id) {
      // format: off
      for {
        mayBe <- getById(id).refineToOrDie[IzanamiErrors]
        _     <- IO.fromOption(mayBe).mapError(_ => DataShouldExists(oldId).toErrors)
        _     <- IO.fromFuture { implicit ec => index.index[EsDocument](EsDocument(id, data),
                                                                        id = Some(id.key),
                                                                        refresh = elasticConfig.automaticRefresh)
                  }
                  .map { _ => data }
                  .refineToOrDie[IzanamiErrors]
      } yield data
      // format: on
    } else {
      for {
        mayBe <- getById(oldId).refineToOrDie[IzanamiErrors]
        _     <- IO.fromOption(mayBe).mapError(_ => DataShouldExists(oldId).toErrors)
        _     <- delete(oldId)
        _     <- create(id, data)
      } yield data
    }

  override def delete(id: Key): IO[IzanamiErrors, JsValue] =
    for {
      mayBe <- getById(id).refineToOrDie[IzanamiErrors]
      value <- IO.fromOption(mayBe).mapError(_ => DataShouldExists(id).toErrors)
      _ <- IO
            .fromFuture(implicit ec => index.delete(id.key, refresh = elasticConfig.automaticRefresh))
            .refineToOrDie[IzanamiErrors]
    } yield value

  override def getById(id: Key): Task[Option[JsValue]] = {
    import cats.implicits._
    IO.fromFuture { implicit ec =>
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
    }
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

  override def findByQuery(q: Query, page: Int, nbElementPerPage: Int): RIO[DataStoreContext, PagingResult[JsValue]] = {
    val query = buildSearchQuery(q) ++ Json.obj(
      "from" -> (page - 1) * nbElementPerPage,
      "size" -> nbElementPerPage
    )
    Logger.debug(s"Query to $esIndex : ${Json.prettyPrint(query)}") *>
    Task
      .fromFuture { implicit ec =>
        index.search(query)
      }
      .map { s =>
        val count   = s.hits.total
        val results = s.hitsAs[EsDocument].map(_.value).toList
        DefaultPagingResult(results, page, nbElementPerPage, count)
      }
  }

  override def findByQuery(query: Query): Task[Source[(Key, JsValue), NotUsed]] =
    Task.fromFuture { implicit ec =>
      FastFuture.successful(findByQuerySource(query).map { case (k, v) => (Key(k), v) })
    }

  private def findByQuerySource(q: Query)(implicit ec: ExecutionContext): Source[(String, JsValue), NotUsed] = {
    val query = buildSearchQuery(q)
    IzanamiLogger.debug(s"Query to $esIndex : ${Json.prettyPrint(query)}")
    index
      .scroll(query = query, scroll = "1s", size = 50)
      .mapConcat { s =>
        s.hitsAs[EsDocument].map(d => (d.key.key, d.value)).toList
      }
  }

  override def deleteAll(query: Query): IO[IzanamiErrors, Unit] =
    IO.fromFuture { implicit ec =>
        findByQuerySource(query)
          .map {
            case (id, _) =>
              Bulk[EsDocument](BulkOpType(delete = Some(BulkOpDetail(None, None, Some(id)))), None)
          }
          .via(index.bulkFlow[EsDocument](50))
          .runWith(Sink.ignore)
      }
      .flatMap { _ =>
        if (elasticConfig.automaticRefresh) {
          IO.fromFuture { implicit ec =>
            elastic.refresh(esIndex)
          }.unit
        } else {
          IO.succeed(())
        }
      }
      .refineToOrDie[IzanamiErrors]

  override def count(q: Query): Task[Long] = {
    val query = buildSearchQuery(q) ++ Json.obj(
      "size" -> 0
    )
    Task.fromFuture { implicit ec =>
      index.search(query).map { s =>
        s.hits.total
      }
    }
  }

}
