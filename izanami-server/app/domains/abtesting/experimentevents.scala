package domains.abtesting

import java.io.File
import java.time.format.DateTimeFormatter
import java.time.temporal.ChronoUnit
import java.time.{LocalDateTime, ZoneId}

import akka.actor.{Actor, ActorSystem, Props}
import akka.http.scaladsl.util.FastFuture
import akka.stream.ActorMaterializer
import akka.stream.alpakka.cassandra.scaladsl.CassandraSource
import akka.stream.scaladsl.{Sink, Source}
import akka.{Done, NotUsed}
import cats.data.OptionT
import com.datastax.driver.core.{Cluster, Session, SimpleStatement}
import domains.Key
import domains.abtesting.ExperimentDataStoreActor._
import domains.abtesting.Experiment.ExperimentKey
import domains.events.EventStore
import domains.events.Events.{
  ExperimentVariantEventCreated,
  ExperimentVariantEventsDeleted
}
import env.{CassandraConfig, DbDomainConfig, ElasticConfig, LevelDbConfig}
import libs.IdGenerator
import org.iq80.leveldb.impl.Iq80DBFactory.factory
import org.iq80.leveldb.{DB, Options}
import play.api.Logger
import play.api.libs.json._
import redis.RedisClientMasterSlaves
import store.Result.Result
import store.{FindResult, Result, SimpleFindResult, SourceFindResult, StoreOps}
import elastic.api._
import play.api.inject.ApplicationLifecycle
import store.cassandra.Cassandra
import store.leveldb.LevelDBRedisCommand

import scala.concurrent.duration.DurationLong
import scala.concurrent.{Await, ExecutionContext, Future}

/* ************************************************************************* */
/*                      ExperimentVariantEvent                               */
/* ************************************************************************* */

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

  implicit val format: Format[ExperimentVariantEventKey] = Format(
    Key.format.map { k =>
      ExperimentVariantEventKey(k)
    },
    Writes[ExperimentVariantEventKey](vk => Key.format.writes(vk.key))
  )

  def apply(key: Key): ExperimentVariantEventKey = {
    val id :: pattern :: clientId :: variantId :: experimentId =
      key.segments.toList.reverse
    ExperimentVariantEventKey(Key(experimentId.reverse),
                              variantId,
                              clientId,
                              pattern,
                              id)
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

object ExperimentVariantDisplayed {
  implicit val format = Json.format[ExperimentVariantDisplayed]
}

case class ExperimentVariantWon(id: ExperimentVariantEventKey,
                                experimentId: ExperimentKey,
                                clientId: String,
                                variant: Variant,
                                date: LocalDateTime = LocalDateTime.now(),
                                transformation: Double,
                                variantId: String)
    extends ExperimentVariantEvent

object ExperimentVariantWon {
  implicit val format = Json.format[ExperimentVariantWon]
}

object ExperimentVariantEvent {

  private val reads: Reads[ExperimentVariantEvent] =
    Reads[ExperimentVariantEvent] {
      case event
          if (event \ "@type")
            .asOpt[String]
            .contains("VariantDisplayedEvent") =>
        ExperimentVariantDisplayed.format.reads(event)
      case event
          if (event \ "@type").asOpt[String].contains("VariantWonEvent") =>
        ExperimentVariantWon.format.reads(event)
      case other => JsError("error.bad.format")
    }

  private val writes: Writes[ExperimentVariantEvent] =
    Writes[ExperimentVariantEvent] {
      case e: ExperimentVariantDisplayed =>
        ExperimentVariantDisplayed.format.writes(e) ++ Json.obj(
          "@type" -> "VariantDisplayedEvent")
      case e: ExperimentVariantWon =>
        ExperimentVariantWon.format.writes(e).as[JsObject] ++ Json.obj(
          "@type" -> "VariantWonEvent")
    }

  implicit val format = Format(reads, writes)
}

trait ExperimentVariantEventStore extends StoreOps {

  def create(
      id: ExperimentVariantEventKey,
      data: ExperimentVariantEvent): Future[Result[ExperimentVariantEvent]]

  def deleteEventsForExperiment(experiment: Experiment): Future[Result[Done]]

  def findVariantResult(experiment: Experiment): FindResult[VariantResult]

  def listAll(
      patterns: Seq[String] = Seq("*")): Source[ExperimentVariantEvent, NotUsed]

}

//////////////////////////////////////////////////////////////////////////////////////////
////////////////////////////////////     ELASTIC      ////////////////////////////////////
//////////////////////////////////////////////////////////////////////////////////////////
object ExperimentVariantEventElasticStore {

  def apply(elastic: Elastic[JsValue],
            elasticConfig: ElasticConfig,
            config: DbDomainConfig,
            eventStore: EventStore,
            actorSystem: ActorSystem): ExperimentVariantEventElasticStore =
    new ExperimentVariantEventElasticStore(elastic,
                                           elasticConfig,
                                           config,
                                           eventStore,
                                           actorSystem)
}

class ExperimentVariantEventElasticStore(client: Elastic[JsValue],
                                         elasticConfig: ElasticConfig,
                                         dbDomainConfig: DbDomainConfig,
                                         eventStore: EventStore,
                                         actorSystem: ActorSystem)
    extends ExperimentVariantEventStore {

  import elastic.implicits._
  import elastic.codec.PlayJson._
  import actorSystem.dispatcher

  private implicit val s = actorSystem
  private implicit val mat = ActorMaterializer()
  private implicit val es = eventStore

  private val esIndex = dbDomainConfig.conf.namespace.replaceAll(":", "_")
  private val esType = "type"
  private val displayedIndex = s"${esIndex}_counter_displayed"
  private val wonIndex = s"${esIndex}_counter_won"

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
      |         "date": { "type": "date", "format" : "date_hour_minute_second_millis" },
      |         "transformation": { "type" : "double" },
      |         "experimentId": { "type" : "keyword" },
      |         "variantId": { "type" : "keyword" }
      |       }
      |     }
      | }
      |}
    """.stripMargin)

  Logger.info(s"Initializing index $esIndex with type $esType")
  Await.result(client.verifyIndex(esIndex).flatMap {
    case true =>
      FastFuture.successful(Done)
    case _ =>
      client.createIndex(esIndex, mapping)
  }, 3.seconds)

  Logger.info(s"Initializing index $displayedIndex with type type")
  Await.result(client.verifyIndex(displayedIndex).flatMap {
    case true =>
      FastFuture.successful(Done)
    case _ =>
      client.createIndex(displayedIndex, counter)
  }, 3.seconds)

  Logger.info(s"Initializing index $wonIndex with type type")
  Await.result(client.verifyIndex(wonIndex).flatMap {
    case true =>
      FastFuture.successful(Done)
    case _ =>
      client.createIndex(wonIndex, counter)
  }, 3.seconds)

  private val index = client.index(esIndex / esType)
  private val displayed = client.index(displayedIndex / "type")
  private val won = client.index(wonIndex / "type")

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

  private def incrWon(experimentId: String, variantId: String): Future[Done] = {
    val id = s"$experimentId.$variantId"
    won.update(incrUpdateQuery, id, retry_on_conflict = Some(5)).map(_ => Done)
  }

  private def incrDisplayed(experimentId: String,
                            variantId: String): Future[Done] = {
    val id = s"$experimentId.$variantId"
    displayed
      .update(incrUpdateQuery, id, retry_on_conflict = Some(5))
      .map(_ => Done)
  }

  private def getWon(experimentId: String, variantId: String): Future[Long] = {
    val id = s"$experimentId.$variantId"
    won
      .get(id)
      .map { resp =>
        (resp._source \ "counter").as[Long]
      }
      .recover {
        case EsException(_, 404, _) => 0
      }
  }
  private def getDisplayed(experimentId: String,
                           variantId: String): Future[Long] = {
    val id = s"$experimentId.$variantId"
    displayed
      .get(id)
      .map { resp =>
        (resp._source \ "counter").as[Long]
      }
      .recover {
        case EsException(_, 404, _) => 0
      }
  }
  private def incrAndGetDisplayed(experimentId: String,
                                  variantId: String): Future[Long] =
    incrDisplayed(experimentId, variantId)
      .flatMap { _ =>
        getDisplayed(experimentId, variantId)
      }
  private def incrAndGetWon(experimentId: String,
                            variantId: String): Future[Long] =
    incrWon(experimentId, variantId)
      .flatMap { _ =>
        getWon(experimentId, variantId)
      }

  override def create(
      id: ExperimentVariantEventKey,
      data: ExperimentVariantEvent): Future[Result[ExperimentVariantEvent]] = {
    val res: Future[Result[ExperimentVariantEvent]] = data match {
      case e: ExperimentVariantDisplayed =>
        for {
          displayed <- incrAndGetDisplayed(id.experimentId.key, id.variantId) // increment display counter
          won <- getWon(id.experimentId.key, id.variantId) // get won counter
          transformation = if (displayed != 0) (won * 100.0) / displayed
          else 0.0
          toSave = e.copy(transformation = transformation)
          result <- saveToEs(id, toSave) // add event
        } yield result
      case e: ExperimentVariantWon =>
        for {
          won <- incrAndGetWon(id.experimentId.key, id.variantId) // increment won counter
          displayed <- getDisplayed(id.experimentId.key, id.variantId) // get display counter
          transformation = if (displayed != 0) (won * 100.0) / displayed
          else 0.0
          toSave = e.copy(transformation = transformation)
          result <- saveToEs(id, toSave) // add event
        } yield result
    }
    res.andPublishEvent(e => ExperimentVariantEventCreated(id, e))
  }

  private def saveToEs(
      id: ExperimentVariantEventKey,
      data: ExperimentVariantEvent): Future[Result[ExperimentVariantEvent]] =
    index
      .index[ExperimentVariantEvent](
        data,
        Some(id.id),
        refresh = elasticConfig.automaticRefresh
      )
      .map(_ => Result.ok(data))

  override def deleteEventsForExperiment(
      experiment: Experiment): Future[Result[Done]] =
    Source(experiment.variants.toList)
      .flatMapMerge(
        4, { v =>
          index.scroll(
            Json.obj(
              "query" -> Json.obj(
                "bool" -> Json.obj(
                  "must" -> Json.arr(
                    Json.obj("term" -> Json.obj("id" -> experiment.id.key)),
                    Json.obj("term" -> Json.obj("variantId" -> v.id))
                  )
                )
              )
            ),
            "1s"
          )
        }
      )
      .mapConcat { _.hits.hits.map(_._id).toList }
      .map { id =>
        Bulk[ExperimentVariantEvent](
          BulkOpType(delete = Some(BulkOpDetail(None, None, Some(id)))),
          None)
      }
      .via(client.bulkFlow(batchSize = 500))
      .runWith(Sink.ignore)
      .map(_ => Result.ok(Done))

  private def aggRequest(experimentId: String,
                         variant: String,
                         interval: String): JsObject =
    Json.obj(
      "size" -> 0,
      "query" -> Json.obj(
        "bool" -> Json.obj(
          "must" -> Json.arr(
            Json.obj("term" -> Json.obj("experimentId" -> experimentId)),
            Json.obj("term" -> Json.obj("variantId" -> variant))
          )
        )
      ),
      "aggs" -> Json.obj(
        "dates" -> Json.obj(
          "date_histogram" -> Json.obj(
            "field" -> "date",
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
                            order: String): Future[Option[LocalDateTime]] = {
    val query = Json.obj(
      "size" -> 1,
      "_source" -> Json.arr("date"),
      "query" -> Json.obj(
        "term" -> Json.obj("id" -> Json.obj("value" -> experimentId))),
      "sort" -> Json.arr(Json.obj("date" -> Json.obj("order" -> order)))
    )
    Logger.debug(s"Querying ${Json.prettyPrint(query)}")
    index
      .search(
        query
      )
      .map {
        case SearchResponse(_, _, _, hits, _, _) =>
          hits.hits.map(h => (h._source \ "date").as[LocalDateTime]).headOption
      }
  }

  private def max(experimentId: String): Future[Option[LocalDateTime]] =
    minOrMaxQuery(experimentId, "desc")
  private def min(experimentId: String): Future[Option[LocalDateTime]] =
    minOrMaxQuery(experimentId, "asc")

  private def calcInterval(experimentId: String): Future[String] = {
    import cats.instances.future._

    val minDate: Future[Option[LocalDateTime]] = min(experimentId)
    val maxDate: Future[Option[LocalDateTime]] = max(experimentId)

    (for {
      min <- OptionT(minDate)
      max <- OptionT(maxDate)
    } yield {
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
    }).value.map(_.getOrElse("second"))
  }

  private def getVariantResult(
      experimentId: String,
      variant: Variant): Source[VariantResult, NotUsed] = {

    val variantId: String = variant.id

    val events: Source[Seq[ExperimentVariantEvent], NotUsed] = Source
      .fromFuture(calcInterval(experimentId))
      .mapAsync(1) { interval =>
        val query = aggRequest(experimentId, variantId, interval)
        Logger.debug(s"Querying ${Json.prettyPrint(query)}")
        index
          .search(query)
          .map {
            case SearchResponse(_, _, _, _, _, Some(aggs)) =>
              (aggs \ "dates" \ "buckets").as[Seq[JsObject]].flatMap { dates =>
                val date =
                  LocalDateTime.parse((dates \ "key_as_string").as[String],
                                      DateTimeFormatter.ISO_DATE_TIME)

                (dates \ "events" \ "buckets").as[Seq[JsObject]].map { event =>
                  val transformation =
                    (event \ "avg" \ "value").asOpt[Double].getOrElse(0d)
                  (event \ "key").as[String] match {
                    case "VariantDisplayedEvent" =>
                      ExperimentVariantDisplayed(
                        ExperimentVariantEventKey(Key(experimentId),
                                                  variantId,
                                                  "NA",
                                                  "displayed",
                                                  "NA"),
                        Key(experimentId),
                        "NA",
                        variant,
                        date,
                        transformation,
                        variantId
                      )
                    case "VariantWonEvent" =>
                      ExperimentVariantWon(
                        ExperimentVariantEventKey(Key(experimentId),
                                                  variantId,
                                                  "NA",
                                                  "won",
                                                  "NA"),
                        Key(experimentId),
                        "NA",
                        variant,
                        date,
                        transformation,
                        variantId)
                  }
                }
              }
            case SearchResponse(_, _, _, hits, _, None) =>
              Seq.empty[ExperimentVariantEvent]
          }
      }

    val won: Source[Long, NotUsed] =
      Source.fromFuture(getWon(experimentId, variantId))
    val displayed: Source[Long, NotUsed] =
      Source.fromFuture(getDisplayed(experimentId, variantId))

    events.zip(won).zip(displayed).map {
      case ((e, w), d) =>
        VariantResult(
          variant = Some(variant),
          displayed = d,
          won = w,
          transformation = if (d != 0) (w * 100.0) / d else 0.0,
          events = e
        )
    }
  }

  override def findVariantResult(
      experiment: Experiment): FindResult[VariantResult] =
    SourceFindResult(
      Source(experiment.variants.toList)
        .flatMapMerge(4, v => getVariantResult(experiment.id.key, v))
    )

  override def listAll(patterns: Seq[String]) =
    index
      .scroll(Json.obj("query" -> Json.obj("match_all" -> Json.obj())))
      .mapConcat(s => s.hitsAs[ExperimentVariantEvent].toList)
      .filter(e => e.id.key.matchPatterns(patterns: _*))
}

//////////////////////////////////////////////////////////////////////////////////////////
////////////////////////////////////    CASSANDRA     ////////////////////////////////////
//////////////////////////////////////////////////////////////////////////////////////////

object ExperimentVariantEventCassandreStore {
  def apply(maybeCluster: Cluster,
            config: DbDomainConfig,
            cassandraConfig: CassandraConfig,
            eventStore: EventStore,
            actorSystem: ActorSystem): ExperimentVariantEventCassandreStore =
    new ExperimentVariantEventCassandreStore(maybeCluster,
                                             config,
                                             cassandraConfig,
                                             eventStore,
                                             actorSystem)
}

class ExperimentVariantEventCassandreStore(cluster: Cluster,
                                           config: DbDomainConfig,
                                           cassandraConfig: CassandraConfig,
                                           eventStore: EventStore,
                                           actorSystem: ActorSystem)
    extends ExperimentVariantEventStore {

  private val namespaceFormatted = config.conf.namespace.replaceAll(":", "_")
  private val keyspace = cassandraConfig.keyspace
  import Cassandra._
  import domains.events.Events._

  implicit private val s = actorSystem
  implicit private val mat = ActorMaterializer()
  implicit private val c = cluster
  implicit private val es = eventStore

  import actorSystem.dispatcher

  Logger.info(s"Creating table ${keyspace}.$namespaceFormatted if not exists")

  //Events table
  cluster
    .connect()
    .execute(
      s"""
    | CREATE TABLE IF NOT EXISTS ${keyspace}.$namespaceFormatted (
    |   experimentId text,
    |   variantId text,
    |   clientId text,
    |   namespace text,
    |   id text,
    |   value text,
    |   PRIMARY KEY ((experimentId, variantId), clientId, namespace, id)
    | )
    | """.stripMargin
    )

  //Won table
  cluster
    .connect()
    .execute(
      s"""
         | CREATE TABLE IF NOT EXISTS ${keyspace}.${namespaceFormatted}_won
         |  (counter_value counter,
         |  experimentId text,
         |  variantId text,
         |  PRIMARY KEY (experimentId, variantId)
         |)
         | """.stripMargin
    )

  //Displayed table
  cluster
    .connect()
    .execute(
      s"""
         | CREATE TABLE IF NOT EXISTS ${keyspace}.${namespaceFormatted}_displayed
         |  (counter_value counter,
         |  experimentId text,
         |  variantId text,
         |  PRIMARY KEY (experimentId, variantId)
         |)
         | """.stripMargin
    )

  private def incrWon(experimentId: String, variantId: String)(
      implicit session: Session): Future[Done] =
    executeWithSession(
      s"UPDATE ${keyspace}.${namespaceFormatted}_won SET counter_value = counter_value + 1 WHERE experimentId = ? AND variantId = ? ",
      experimentId,
      variantId
    ).map(_ => Done)

  private def getWon(experimentId: String, variantId: String)(
      implicit session: Session): Future[Long] =
    executeWithSession(
      s"SELECT counter_value FROM ${keyspace}.${namespaceFormatted}_won WHERE experimentId = ? AND variantId = ? ",
      experimentId,
      variantId
    ).map(
      rs =>
        Option(rs.one())
          .flatMap(o => Option(o.getLong("counter_value")))
          .getOrElse(0))

  private def incrAndGetWon(experimentId: String, variantId: String)(
      implicit session: Session): Future[Long] =
    incrWon(experimentId, variantId).flatMap(_ =>
      getWon(experimentId, variantId))

  private def incrDisplayed(experimentId: String, variantId: String)(
      implicit session: Session): Future[Done] =
    executeWithSession(
      s"UPDATE ${keyspace}.${namespaceFormatted}_displayed SET counter_value = counter_value + 1 WHERE experimentId = ? AND variantId = ? ",
      experimentId,
      variantId
    ).map(_ => Done)

  private def getDisplayed(experimentId: String, variantId: String)(
      implicit session: Session): Future[Long] =
    executeWithSession(
      s"SELECT counter_value FROM ${keyspace}.${namespaceFormatted}_displayed WHERE experimentId = ? AND variantId = ? ",
      experimentId,
      variantId
    ).map(
      rs =>
        Option(rs.one())
          .flatMap(o => Option(o.getLong("counter_value")))
          .getOrElse(0))

  private def incrAndGetDisplayed(experimentId: String, variantId: String)(
      implicit session: Session): Future[Long] =
    incrDisplayed(experimentId, variantId).flatMap(_ =>
      getDisplayed(experimentId, variantId))

  private def saveToCassandra(
      id: ExperimentVariantEventKey,
      data: ExperimentVariantEvent)(implicit session: Session) = {
    val query =
      s"INSERT INTO ${keyspace}.$namespaceFormatted (experimentId, variantId, clientId, namespace, id, value) values (?, ?, ?, ?, ?, ?) IF NOT EXISTS "
    Logger.debug(s"Running query $query")
    executeWithSession(
      query,
      id.experimentId.key,
      id.variantId,
      id.clientId,
      id.namespace,
      id.id,
      Json.stringify(Json.toJson(data))
    ).map(_ => Result.ok(data))
  }

  override def create(
      id: ExperimentVariantEventKey,
      data: ExperimentVariantEvent): Future[Result[ExperimentVariantEvent]] =
    session()
      .flatMap { implicit session =>
        data match {
          case e: ExperimentVariantDisplayed =>
            for {
              displayed <- incrAndGetDisplayed(
                id.experimentId.key,
                id.variantId) // increment display counter
              won <- getWon(id.experimentId.key, id.variantId) // get won counter
              transformation = if (displayed != 0) (won * 100.0) / displayed
              else 0.0
              toSave = e.copy(transformation = transformation)
              result <- saveToCassandra(id, toSave) // add event
            } yield result
          case e: ExperimentVariantWon =>
            for {
              won <- incrAndGetWon(id.experimentId.key, id.variantId) // increment won counter
              displayed <- getDisplayed(id.experimentId.key, id.variantId) // get display counter
              transformation = if (displayed != 0) (won * 100.0) / displayed
              else 0.0
              toSave = e.copy(transformation = transformation)
              result <- saveToCassandra(id, toSave) // add event
            } yield result
        }
      }
      .andPublishEvent(e => ExperimentVariantEventCreated(id, e))

  override def deleteEventsForExperiment(
      experiment: Experiment): Future[Result[Done]] =
    session()
      .flatMap { implicit session =>
        Future.sequence(experiment.variants.map { variant =>
          executeWithSession(
            s" DELETE FROM ${keyspace}.$namespaceFormatted  WHERE experimentId = ? AND variantId = ?",
            experiment.id.key,
            variant.id)
            .map { r =>
              Result.ok(r.asInstanceOf[Any])
            }
        })
      }
      .map(r => Result.ok(Done))
      .andPublishEvent(e => ExperimentVariantEventsDeleted(experiment))

  def getVariantResult(experimentId: String, variant: Variant)(
      implicit session: Session): Source[VariantResult, NotUsed] = {
    val variantId: String = variant.id
    val events: Source[Seq[ExperimentVariantEvent], NotUsed] = CassandraSource(
      new SimpleStatement(
        s"SELECT value FROM ${keyspace}.$namespaceFormatted WHERE experimentId = ? and variantId = ? ",
        experimentId,
        variantId
      )
    ).map(r => r.getString("value"))
      .map(Json.parse)
      .mapConcat(_.validate[ExperimentVariantEvent].asOpt.toList)
      .fold(Seq.empty[ExperimentVariantEvent])(_ :+ _)

    val won: Source[Long, NotUsed] =
      Source.fromFuture(getWon(experimentId, variantId))
    val displayed: Source[Long, NotUsed] =
      Source.fromFuture(getDisplayed(experimentId, variantId))

    events.zip(won).zip(displayed).map {
      case ((e, w), d) =>
        VariantResult(
          variant = Some(variant),
          displayed = d,
          won = w,
          transformation = if (d != 0) (w * 100.0) / d else 0.0,
          events = e
        )
    }
  }

  override def findVariantResult(
      experiment: Experiment): FindResult[VariantResult] =
    SourceFindResult(
      Source.fromFuture(session()).flatMapConcat { implicit session =>
        Source(experiment.variants.toList)
          .flatMapMerge(4, v => getVariantResult(experiment.id.key, v))
      })

  override def listAll(patterns: Seq[String]) =
    Source
      .fromFuture(session())
      .flatMapConcat { implicit session =>
        CassandraSource(
          new SimpleStatement(
            s"SELECT value FROM ${keyspace}.$namespaceFormatted "
          )
        ).map(r => r.getString("value"))
          .map(Json.parse)
          .mapConcat(_.validate[ExperimentVariantEvent].asOpt.toList)
          .filter(e => e.id.key.matchPatterns(patterns: _*))
      }
}

//////////////////////////////////////////////////////////////////////////////////////
////////////////////////////////////    REDIS     ////////////////////////////////////
//////////////////////////////////////////////////////////////////////////////////////

object ExperimentVariantEventRedisStore {
  def apply(maybeRedis: Option[RedisClientMasterSlaves],
            eventStore: EventStore,
            actorSystem: ActorSystem): ExperimentVariantEventRedisStore =
    new ExperimentVariantEventRedisStore(maybeRedis, eventStore, actorSystem)
}

class ExperimentVariantEventRedisStore(
    maybeRedis: Option[RedisClientMasterSlaves],
    eventStore: EventStore,
    actorSystem: ActorSystem)
    extends ExperimentVariantEventStore {

  import actorSystem.dispatcher
  import domains.events.Events._

  implicit private val es = eventStore
  implicit private val s = actorSystem
  implicit val materializer = ActorMaterializer()

  val experimentseventsdisplayedNamespace: String =
    "experimentseventsdisplayed:count"
  val experimentseventswonNamespace: String = "experimentseventswon:count"
  val experimentseventsNamespace: String = "experimentsevents"

  val client: RedisClientMasterSlaves = maybeRedis.get

  private def now(): Long =
    LocalDateTime.now().atZone(ZoneId.systemDefault()).toInstant.toEpochMilli

  private def calcTransformation(displayed: Double, won: Double) =
    if (displayed != 0) {
      (won * 100.0) / displayed
    } else 0.0

  private def incrAndGetDisplayed(experimentId: String,
                                  variantId: String): Future[Long] = {
    val displayedCounter: String =
      s"$experimentseventsdisplayedNamespace:$experimentId:$variantId"
    client.incr(displayedCounter)
  }

  private def incrAndGetWon(experimentId: String,
                            variantId: String): Future[Long] = {
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

  private def getDisplayed(experimentId: String,
                           variantId: String): Future[Long] = {
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

  override def create(
      id: ExperimentVariantEventKey,
      data: ExperimentVariantEvent): Future[Result[ExperimentVariantEvent]] = {

    val eventsKey: String =
      s"$experimentseventsNamespace:${id.experimentId.key}:${id.variantId}" // le sorted set des events

    data match {
      case e: ExperimentVariantDisplayed =>
        for {
          displayed <- incrAndGetDisplayed(id.experimentId.key, id.variantId) // increment display counter
          won <- getWon(id.experimentId.key, id.variantId) // get won counter
          transformation = calcTransformation(displayed, won)
          dataToSave = e.copy(transformation = transformation)
          result <- client
            .zadd(
              eventsKey,
              (now(),
               Json.stringify(ExperimentVariantEvent.format.writes(dataToSave)))
            )
            .map { _ =>
              Result.ok(dataToSave)
            } // add event
        } yield result
      case e: ExperimentVariantWon =>
        for {
          won <- incrAndGetWon(id.experimentId.key, id.variantId) // increment won counter
          displayed <- getDisplayed(id.experimentId.key, id.variantId) // get display counter
          transformation = calcTransformation(displayed, won)
          dataToSave = e.copy(transformation = transformation)
          result <- client
            .zadd(
              eventsKey,
              (now(),
               Json.stringify(ExperimentVariantEvent.format.writes(dataToSave)))
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

  private def findEvents(
      eventVariantKey: String): FindResult[ExperimentVariantEvent] = {
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

  override def findVariantResult(
      experiment: Experiment): FindResult[VariantResult] = {
    val eventualVariantResult: Future[List[VariantResult]] = for {
      variantKeys <- findKeys(
        s"$experimentseventsNamespace:${experiment.id.key}:*")
        .runFold(Seq.empty[String])(_ :+ _)
      variants <- Future.sequence {
        variantKeys.map(
          variantKey => {
            val currentVariantId: String =
              variantKey.replace(
                s"$experimentseventsNamespace:${experiment.id.key}:",
                "")
            val maybeVariant: Option[Variant] =
              experiment.variants.find(variant => {
                variant.id == currentVariantId
              })

            import cats.instances.all._
            import cats.syntax.cartesian._

            (
              findEvents(variantKey).list |@|
                getDisplayed(experiment.id.key, currentVariantId) |@|
                getWon(experiment.id.key, currentVariantId)
            ).map { (events, displayed, won) =>
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

  override def deleteEventsForExperiment(
      experiment: Experiment): Future[Result[Done]] =
    (
      for {
        displayedCounterKey <- client.keys(
          s"$experimentseventsdisplayedNamespace:${experiment.id.key}:*")
        wonCounterKey <- client.keys(
          s"$experimentseventswonNamespace:${experiment.id.key}:*")
        eventsKey <- client.keys(
          s"$experimentseventsNamespace:${experiment.id.key}:*")
        _ <- Future.sequence(displayedCounterKey.map(key => client.del(key))) // remove displayed counter
        _ <- Future.sequence(wonCounterKey.map(key => client.del(key))) // remove won counter
        _ <- Future.sequence(eventsKey.map(key => client.del(key))) // remove events list
      } yield Result.ok(Done)
    ).andPublishEvent(e => ExperimentVariantEventsDeleted(experiment))

  override def listAll(patterns: Seq[String]) =
    findKeys(s"$experimentseventsNamespace:*")
      .flatMapMerge(4, key => findEvents(key).stream)
      .alsoTo(Sink.foreach(e => println(s"Event $e")))
      .filter(e => e.id.key.matchPatterns(patterns: _*))
}

/////////////////////////////////////////////////////////////////////////////////////////
////////////////////////////////////    LEVEL DB     ////////////////////////////////////
//////////////////////////////////////////////////////////////////////////////////////////

object ExperimentVariantEventLevelDBStore {
  def apply(levelDbConfig: LevelDbConfig,
            configdb: DbDomainConfig,
            eventStore: EventStore,
            actorSystem: ActorSystem,
            applicationLifecycle: ApplicationLifecycle)
    : ExperimentVariantEventLevelDBStore =
    new ExperimentVariantEventLevelDBStore(levelDbConfig,
                                           configdb,
                                           eventStore,
                                           actorSystem,
                                           applicationLifecycle)
}

class ExperimentVariantEventLevelDBStore(
    levelDbConfig: LevelDbConfig,
    configdb: DbDomainConfig,
    eventStore: EventStore,
    actorSystem: ActorSystem,
    applicationLifecycle: ApplicationLifecycle)
    extends ExperimentVariantEventStore {

  import actorSystem.dispatcher
  import domains.events.Events._
  implicit private val s = actorSystem
  implicit private val materializer = ActorMaterializer()
  implicit private val es = eventStore

  private val client: LevelDBRedisCommand = {
    val namespace = configdb.conf.namespace
    val parentPath = levelDbConfig.parentPath
    val dbPath: String = parentPath + "/" + namespace
      .replaceAll(":", "_") + "_try_to_use_this_one"

    val db: DB =
      factory.open(new File(dbPath), new Options().createIfMissing(true))
    applicationLifecycle.addStopHook { () =>
      Logger.info(s"Closing leveldb for path $parentPath")
      Future(db.close())
    }
    new LevelDBRedisCommand(db, actorSystem)
  }

  val experimentseventsdisplayedNamespace: String =
    "experimentseventsdisplayed:count"
  val experimentseventswonNamespace: String = "experimentseventswon:count"
  val experimentseventsNamespace: String = "experimentsevents"

  private def buildData(
      eventsKey: String,
      displayed: Long,
      won: Long,
      data: ExperimentVariantEvent,
      function: (ExperimentVariantEvent, Double) => ExperimentVariantEvent
  )(implicit ec: ExecutionContext): Future[Result[ExperimentVariantEvent]] = {
    val transformation: Double = if (displayed != 0) {
      (won * 100.0) / displayed
    } else {
      0.0
    }

    val dataToSave = function(data, transformation)

    client
      .sadd(
        eventsKey,
        Json.stringify(Json.format[ExperimentVariantEvent].writes(dataToSave)))
      .map { _ =>
        Result.ok(dataToSave)
      }
  }

  override def create(
      id: ExperimentVariantEventKey,
      data: ExperimentVariantEvent): Future[Result[ExperimentVariantEvent]] = {
    // le compteur des displayed
    val displayedCounter: String =
      s"$experimentseventsdisplayedNamespace:${id.experimentId.key}:${id.variantId}"
    // le compteur des won
    val wonCounter: String =
      s"$experimentseventswonNamespace:${id.experimentId.key}:${id.variantId}"

    val eventsKey: String =
      s"$experimentseventsNamespace:${id.experimentId.key}:${id.variantId}" // le sorted set des events

    def transformationDisplayed(
        data: ExperimentVariantEvent,
        transformation: Double): ExperimentVariantEvent = {
      val dataToSave: ExperimentVariantDisplayed =
        data.asInstanceOf[ExperimentVariantDisplayed]
      dataToSave.copy(
        transformation = transformation
      )
    }

    def transformationWon(data: ExperimentVariantEvent,
                          transformation: Double): ExperimentVariantEvent = {
      val dataToSave: ExperimentVariantWon =
        data.asInstanceOf[ExperimentVariantWon]
      dataToSave.copy(
        transformation = transformation
      )
    }

    data match {
      case _: ExperimentVariantDisplayed =>
        for {
          displayed <- client.incr(displayedCounter) // increment display counter
          maybeWon <- client.get(wonCounter) // get won counter
          result <- buildData(eventsKey,
                              displayed,
                              maybeWon.map(_.utf8String.toLong).getOrElse(0),
                              data,
                              transformationDisplayed) // add event
        } yield result
      case _: ExperimentVariantWon =>
        for {
          won <- client.incr(wonCounter) // increment won counter
          maybeDisplayed <- client.get(displayedCounter) // get display counter
          result <- buildData(
            eventsKey,
            maybeDisplayed.map(_.utf8String.toLong).getOrElse(0),
            won,
            data,
            transformationWon) // add event
        } yield result
      case _ =>
        Logger.error("Event not recognized")
        FastFuture.successful(Result.error("unknow.event.type"))
    }
  }.andPublishEvent(e => ExperimentVariantEventCreated(id, e))

  private def findEvents(
      eventVariantKey: String): FindResult[ExperimentVariantEvent] =
    SimpleFindResult(
      client
        .smembers(eventVariantKey)
        .map(
          res =>
            res
              .map(_.utf8String)
              .map(Json.parse)
              .map(value => Json.reads[ExperimentVariantEvent].reads(value).get)
              .sortWith((e1, e2) => e1.date.isBefore(e2.date))
              .toList
        )
    )

  override def findVariantResult(
      experiment: Experiment): FindResult[VariantResult] = {
    val eventualVariantResult: Future[List[VariantResult]] = for {
      variantKeys <- client.keys(
        s"$experimentseventsNamespace:${experiment.id.key}:*")
      variants <- Future.sequence {
        variantKeys.map(
          variantKey => {
            val currentVariantId: String =
              variantKey.replace(
                s"$experimentseventsNamespace:${experiment.id.key}:",
                "")
            val maybeVariant: Option[Variant] =
              experiment.variants.find(variant => {
                variant.id == currentVariantId
              })

            import cats.instances.all._
            import cats.syntax.cartesian._

            (
              findEvents(variantKey).list |@|
                client.get(
                  s"$experimentseventsdisplayedNamespace:${experiment.id.key}:$currentVariantId") |@|
                client.get(
                  s"$experimentseventswonNamespace:${experiment.id.key}:$currentVariantId")
            ).map {
              (events, maybeDisplayed, maybeWon) =>
                val displayed: Long =
                  maybeDisplayed.map(_.utf8String.toLong).getOrElse(0)
                val won: Long = maybeWon.map(_.utf8String.toLong).getOrElse(0)
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

  override def deleteEventsForExperiment(
      experiment: Experiment): Future[Result[Done]] =
    (
      for {
        displayedCounterKey <- client.keys(
          s"$experimentseventsdisplayedNamespace:${experiment.id.key}:*")
        wonCounterKey <- client.keys(
          s"$experimentseventswonNamespace:${experiment.id.key}:*")
        eventsKey <- client.keys(
          s"$experimentseventsNamespace:${experiment.id.key}:*")
        _ <- Future.sequence(displayedCounterKey.map(key => client.del(key))) // remove displayed counter
        _ <- Future.sequence(wonCounterKey.map(key => client.del(key))) // remove won counter
        _ <- Future.sequence(eventsKey.map(key => client.del(key))) // remove events list
      } yield Result.ok(Done)
    ).andPublishEvent(e => ExperimentVariantEventsDeleted(experiment))

  override def listAll(patterns: Seq[String]) =
    Source
      .fromFuture(client.keys(s"$experimentseventsNamespace:*"))
      .mapConcat(_.toList)
      .flatMapMerge(4, key => findEvents(key).stream)
      .filter(e => e.id.key.matchPatterns(patterns: _*))

}

//////////////////////////////////////////////////////////////////////////////////////////
////////////////////////////////////    IN MEMORY     ////////////////////////////////////
//////////////////////////////////////////////////////////////////////////////////////////

object ExperimentVariantEventInMemoryStore {
  def apply(configdb: DbDomainConfig,
            actorSystem: ActorSystem): ExperimentVariantEventInMemoryStore =
    new ExperimentVariantEventInMemoryStore(configdb, actorSystem)
}

class ExperimentVariantEventInMemoryStore(configdb: DbDomainConfig,
                                          actorSystem: ActorSystem)
    extends ExperimentVariantEventStore {

  import actorSystem.dispatcher
  import akka.pattern._
  import akka.util.Timeout

  import scala.concurrent.duration.DurationInt

  private implicit val timeout = Timeout(1.second)

  private val store = actorSystem.actorOf(
    Props[ExperimentDataStoreActor](new ExperimentDataStoreActor()),
    configdb.conf.namespace + "_try_to_use_this_one")

  override def create(
      id: ExperimentVariantEventKey,
      data: ExperimentVariantEvent): Future[Result[ExperimentVariantEvent]] =
    (store ? AddEvent(id.experimentId.key, id.variantId, data))
      .mapTo[ExperimentVariantEvent]
      .map[Result[ExperimentVariantEvent]](res => Result.ok(res))

  override def deleteEventsForExperiment(
      experiment: Experiment): Future[Result[Done]] =
    (store ? DeleteEvents(experiment.id.key))
      .mapTo[Done]
      .map[Result[Done]](res => Result.ok(res))

  override def findVariantResult(
      experiment: Experiment): FindResult[VariantResult] =
    SimpleFindResult {
      Future.sequence {
        experiment.variants
          .map(variant => {
            import cats.instances.all._
            import cats.syntax.cartesian._
            (
              (store ? FindEvents(experiment.id.key, variant.id))
                .mapTo[List[ExperimentVariantEvent]] |@|
                (store ? FindCounterDisplayed(experiment.id.key, variant.id))
                  .mapTo[Long] |@|
                (store ? FindCounterWon(experiment.id.key, variant.id))
                  .mapTo[Long]
            ).map { (events, displayed, won) =>
              {
                val transformation: Double = if (displayed != 0) {
                  won * 100 / displayed
                } else 0.0

                VariantResult(
                  variant = Some(variant),
                  events = events,
                  transformation = transformation,
                  displayed = displayed,
                  won = won
                )
              }
            }
          })
          .toList
      }
    }

  override def listAll(patterns: Seq[String]) =
    Source
      .fromFuture((store ? GetAll(patterns)).mapTo[Seq[ExperimentVariantEvent]])
      .mapConcat(_.toList)
}

private[abtesting] class ExperimentDataStoreActor extends Actor {

  private var datas: Map[String, List[ExperimentVariantEvent]] =
    Map.empty[String, List[ExperimentVariantEvent]]
  private var counters: Map[String, Long] = Map.empty[String, Long]

  val experimentseventsdisplayedNamespace: String =
    "experimentseventsdisplayed:count"
  val experimentseventswonNamespace: String = "experimentseventswon:count"
  val experimentseventsNamespace: String = "experimentsevents"

  def transformation(displayed: Long, won: Long): Double =
    if (displayed != 0) {
      won * 100.0 / displayed
    } else 0.0

  override def receive: Receive = {
    case AddEvent(experimentId, variantId, event) =>
      val eventKey: String =
        s"$experimentseventsNamespace:$experimentId:$variantId"
      val displayedCounterKey: String =
        s"$experimentseventsdisplayedNamespace:$experimentId:$variantId"
      val wonCounterKey: String =
        s"$experimentseventswonNamespace:$experimentId:$variantId"

      val events: List[ExperimentVariantEvent] =
        datas.getOrElse(eventKey, List.empty[ExperimentVariantEvent])
      val displayed: Long = counters.getOrElse(displayedCounterKey, 0)
      val won: Long = counters.getOrElse(wonCounterKey, 0)

      event match {
        case e: ExperimentVariantDisplayed =>
          val transfo: Double = transformation(displayed + 1, won)
          val eventToSave = e.copy(transformation = transfo)

          counters = counters + (displayedCounterKey -> (displayed + 1))
          datas = datas + (eventKey -> (eventToSave :: events))
          sender() ! eventToSave

        case e: ExperimentVariantWon =>
          val transfo: Double = transformation(displayed, won + 1)
          val eventToSave = e.copy(transformation = transfo)
          counters = counters + (wonCounterKey -> (won + 1))
          datas = datas + (eventKey -> (eventToSave :: events))
          sender() ! eventToSave
      }

    case FindEvents(experimentId, variantId) =>
      val eventKey: String =
        s"$experimentseventsNamespace:$experimentId:$variantId"
      sender() ! datas
        .getOrElse(eventKey, List.empty[ExperimentVariantEvent])
        .sortWith((e1, e2) => e1.date.isBefore(e2.date))

    case GetAll(patterns) =>
      sender() ! datas.values.flatten.filter(e =>
        e.id.key.matchPatterns(patterns: _*))

    case DeleteEvents(experimentId) =>
      val eventKey: String = s"$experimentseventsNamespace:$experimentId:"
      val displayedCounterKey: String =
        s"$experimentseventsdisplayedNamespace:$experimentId:"
      val wonCounterKey: String =
        s"$experimentseventswonNamespace:$experimentId:"

      datas.keys
        .filter(key => key.startsWith(eventKey))
        .foreach(key => datas = datas - key)
      counters.keys
        .filter(key =>
          key.startsWith(displayedCounterKey) || key.startsWith(wonCounterKey))
        .foreach(key => datas = datas - key)

      sender() ! Done

    case FindCounterDisplayed(experimentId, variantId) =>
      sender() ! counters.getOrElse(
        s"$experimentseventsdisplayedNamespace:$experimentId:$variantId",
        0)

    case FindCounterWon(experimentId, variantId) =>
      sender() ! counters.getOrElse(
        s"$experimentseventswonNamespace:$experimentId:$variantId",
        0)

    case m =>
      unhandled(m)
  }
}

private[abtesting] object ExperimentDataStoreActor {

  sealed trait ExperimentDataMessages

  case class AddEvent(experimentId: String,
                      variantId: String,
                      event: ExperimentVariantEvent)
      extends ExperimentDataMessages

  case class FindEvents(experimentId: String, variantId: String)
      extends ExperimentDataMessages

  case class GetAll(patterns: Seq[String]) extends ExperimentDataMessages

  case class DeleteEvents(experimentId: String) extends ExperimentDataMessages

  case class FindCounterDisplayed(experimentId: String, variantId: String)
      extends ExperimentDataMessages

  case class FindCounterWon(experimentId: String, variantId: String)
      extends ExperimentDataMessages

}
