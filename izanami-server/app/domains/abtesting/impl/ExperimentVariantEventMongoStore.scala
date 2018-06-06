package domains.abtesting.impl

import akka.actor.ActorSystem
import akka.stream.ActorMaterializer
import akka.Done
import akka.stream.scaladsl.{Sink, Source}
import reactivemongo.akkastream._
import domains.abtesting.Experiment.ExperimentKey
import domains.abtesting._
import domains.events.EventStore
import domains.events.Events.ExperimentVariantEventCreated
import env.DbDomainConfig
import libs.mongo.MongoUtils
import play.api.Logger
import play.api.libs.json.Json
import play.modules.reactivemongo.ReactiveMongoApi
import reactivemongo.api.ReadPreference
import reactivemongo.api.indexes.{Index, IndexType}
import reactivemongo.play.json._
import reactivemongo.play.json.collection.JSONCollection
import store.{FindResult, Result, SourceFindResult}
import store.Result.Result

import scala.concurrent.duration.DurationInt
import scala.concurrent.{Await, Future}

case class Counter(id: String, value: Long)
object Counter {
  implicit val format = Json.format[Counter]
}

case class ExperimentVariantEventDocument(id: ExperimentVariantEventKey,
                                          experimentId: ExperimentKey,
                                          variantId: String,
                                          data: ExperimentVariantEvent)

object ExperimentVariantEventDocument {
  implicit val format = Json.format[ExperimentVariantEventDocument]
}

object ExperimentVariantEventMongoStore {
  def apply(config: DbDomainConfig,
            mongoApi: ReactiveMongoApi,
            eventStore: EventStore,
            system: ActorSystem): ExperimentVariantEventMongoStore =
    new ExperimentVariantEventMongoStore(config.conf.namespace, mongoApi, eventStore)(system)
}

class ExperimentVariantEventMongoStore(namespace: String, mongoApi: ReactiveMongoApi, eventStore: EventStore)(
    implicit system: ActorSystem
) extends ExperimentVariantEventStore {

  import system.dispatcher
  import cats.implicits._

  private implicit val mapi: ReactiveMongoApi = mongoApi
  private implicit val mat: ActorMaterializer = ActorMaterializer()
  private implicit val es: EventStore         = eventStore

  private val collectionName          = namespace.replaceAll(":", "_")
  private val displayedCollectionName = s"${collectionName}_counter_displayed"
  private val wonCollectionName       = s"${collectionName}_counter_won"

  private val indexesDefinition: Seq[Index] = Seq(
    Index(Seq("id"           -> IndexType.Ascending), unique = true),
    Index(Seq("experimentId" -> IndexType.Ascending), unique = false),
    Index(Seq("variantId"    -> IndexType.Ascending), unique = false)
  )

  private val counterIndexesDefinition: Seq[Index] = Seq(
    Index(Seq("id" -> IndexType.Ascending), unique = true)
  )
  Logger.debug(s"Initializing mongo collection $collectionName")
  Logger.debug(s"Initializing mongo collection $wonCollectionName")
  Logger.debug(s"Initializing mongo collection $displayedCollectionName")

  Await.result(
    Future.sequence(
      Seq(
        MongoUtils.initIndexes(collectionName, indexesDefinition),
        MongoUtils.initIndexes(wonCollectionName, counterIndexesDefinition),
        MongoUtils.initIndexes(displayedCollectionName, counterIndexesDefinition)
      )
    ),
    5.seconds
  )

  private def getCounter(collName: String, experimentId: String, variantId: String): Future[Long] = {
    val id = s"$experimentId.$variantId"
    mongoApi.database
      .flatMap(
        _.collection[JSONCollection](collName)
          .find(Json.obj("id" -> id))
          .one[Counter]
      )
      .map(_.map(_.value).getOrElse(0))
  }

  private def getWon(experimentId: String, variantId: String): Future[Long] =
    getCounter(wonCollectionName, experimentId, variantId)

  private def getDisplayed(experimentId: String, variantId: String): Future[Long] =
    getCounter(displayedCollectionName, experimentId, variantId)

  private def incrAndGet(collName: String, experimentId: String, variantId: String): Future[Long] = {
    val id = s"$experimentId.$variantId"
    mongoApi.database
      .flatMap { db =>
        db.collection[JSONCollection](collName)
          .findAndUpdate(
            Json.obj("id" -> id),
            Json.obj("$inc" -> Json.obj("value" -> 1)),
            upsert = true,
            fetchNewObject = true
          )
          .map(_.result[Counter])
          .map(_.map(_.value).getOrElse(0L))
      }
  }

  private def incrAndGetDisplayed(experimentId: String, variantId: String): Future[Long] =
    incrAndGet(displayedCollectionName, experimentId, variantId)

  private def incrAndGetWon(experimentId: String, variantId: String): Future[Long] =
    incrAndGet(wonCollectionName, experimentId, variantId)

  override def create(id: ExperimentVariantEventKey,
                      data: ExperimentVariantEvent): Future[Result[ExperimentVariantEvent]] = {
    val res: Future[Result[ExperimentVariantEvent]] = data match {
      case e: ExperimentVariantDisplayed =>
        for {
          displayed <- incrAndGetDisplayed(id.experimentId.key, id.variantId) // increment display counter
          won       <- getWon(id.experimentId.key, id.variantId)              // get won counter
          transformation = if (displayed != 0) (won * 100.0) / displayed
          else 0.0
          toSave = e.copy(transformation = transformation)
          result <- insert(id, toSave) // add event
        } yield result
      case e: ExperimentVariantWon =>
        for {
          won       <- incrAndGetWon(id.experimentId.key, id.variantId) // increment won counter
          displayed <- getDisplayed(id.experimentId.key, id.variantId)  // get display counter
          transformation = if (displayed != 0) (won * 100.0) / displayed
          else 0.0
          toSave = e.copy(transformation = transformation)
          result <- insert(id, toSave) // add event
        } yield result
    }
    res.andPublishEvent(e => ExperimentVariantEventCreated(id, e))
  }

  private def insert(id: ExperimentVariantEventKey,
                     data: ExperimentVariantEvent): Future[Result[ExperimentVariantEvent]] =
    mongoApi.database
      .flatMap(
        _.collection[JSONCollection](collectionName)
          .insert(ExperimentVariantEventDocument(id, id.experimentId, id.variantId, data))
      )
      .map(_ => Result.ok(data))

  override def deleteEventsForExperiment(experiment: Experiment): Future[Result[Done]] =
    mongoApi.database
      .flatMap(
        _.collection[JSONCollection](collectionName)
          .remove(Json.obj("experimentId" -> experiment.id.key))
      )
      .map(_ => Result.ok(Done))

  private def getVariantResult(experimentId: String, variant: Variant): Future[VariantResult] = {
    val variantId: String = variant.id
    mongoApi.database
      .flatMap { collection =>
        val events: Future[Seq[ExperimentVariantEvent]] = collection
          .collection[JSONCollection](collectionName)
          .find(Json.obj("experimentId" -> experimentId, "variantId" -> variantId))
          .cursor[ExperimentVariantEventDocument](ReadPreference.primary)
          .documentSource()
          .map(_.data)
          .runWith(Sink.seq)

        val wonCount       = getWon(experimentId, variantId)
        val displayedCount = getDisplayed(experimentId, variantId)

        (events, wonCount, displayedCount).mapN { (e, w, d) =>
          val transformation = (w * 100) / d
          VariantResult(Some(variant), d, w, transformation, e)
        }
      }
  }

  override def findVariantResult(experiment: Experiment): FindResult[VariantResult] =
    SourceFindResult(
      Source(experiment.variants.toList)
        .mapAsync(4)(v => getVariantResult(experiment.id.key, v))
    )

  override def listAll(patterns: Seq[String]) =
    Source
      .fromFuture(mongoApi.database)
      .map(_.collection[JSONCollection](collectionName))
      .flatMapConcat(
        _.find(Json.obj())
          .cursor[ExperimentVariantEventDocument](ReadPreference.primary)
          .documentSource()
          .map(_.data)
      )
}
