package domains.abtesting.impl

import akka.actor.ActorSystem
import akka.stream.ActorMaterializer
import akka.{Done, NotUsed}
import akka.stream.scaladsl.Source
import cats.effect.Effect
import reactivemongo.akkastream._
import domains.abtesting.Experiment.ExperimentKey
import domains.abtesting._
import domains.events.EventStore
import domains.events.Events.ExperimentVariantEventCreated
import env.DbDomainConfig
import libs.mongo.MongoUtils
import play.api.Logger
import play.api.libs.json.{JsValue, Json}
import play.modules.reactivemongo.ReactiveMongoApi
import reactivemongo.api.ReadPreference
import reactivemongo.api.indexes.{Index, IndexType}
import reactivemongo.play.json._
import reactivemongo.play.json.collection.JSONCollection
import store.Result
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
  implicit val eveKF  = ExperimentVariantEventKeyInstances.format
  implicit val eveF   = ExperimentVariantEventInstances.format
  implicit val format = Json.format[ExperimentVariantEventDocument]
}

object ExperimentVariantEventMongoService {
  def apply[F[_]: Effect](config: DbDomainConfig, mongoApi: ReactiveMongoApi, eventStore: EventStore[F])(
      implicit system: ActorSystem
  ): ExperimentVariantEventMongoService[F] =
    new ExperimentVariantEventMongoService(config.conf.namespace, mongoApi, eventStore)
}

class ExperimentVariantEventMongoService[F[_]: Effect](namespace: String,
                                                       mongoApi: ReactiveMongoApi,
                                                       eventStore: EventStore[F])(
    implicit system: ActorSystem
) extends ExperimentVariantEventService[F] {

  import system.dispatcher
  import cats.implicits._
  import libs.effects._

  private implicit val mapi: ReactiveMongoApi = mongoApi
  private implicit val mat: ActorMaterializer = ActorMaterializer()

  private val collectionName = namespace.replaceAll(":", "_")

  private val indexesDefinition: Seq[Index] = Seq(
    Index(Seq("id"           -> IndexType.Ascending), unique = true),
    Index(Seq("experimentId" -> IndexType.Ascending), unique = false),
    Index(Seq("variantId"    -> IndexType.Ascending), unique = false)
  )

  private val counterIndexesDefinition: Seq[Index] = Seq(
    Index(Seq("id" -> IndexType.Ascending), unique = true)
  )
  Logger.debug(s"Initializing mongo collection $collectionName")

  Await.result(
    Future.sequence(
      Seq(
        MongoUtils.initIndexes(collectionName, indexesDefinition)
      )
    ),
    5.seconds
  )

  private def getCounter(collName: String, experimentId: String, variantId: String): F[Long] = {
    val id = s"$experimentId.$variantId"
    mongoApi.database
      .flatMap(
        _.collection[JSONCollection](collName)
          .find(Json.obj("id" -> id))
          .one[Counter]
      )
      .toF
      .map(_.map(_.value).getOrElse(0))
  }

  override def create(id: ExperimentVariantEventKey, data: ExperimentVariantEvent): F[Result[ExperimentVariantEvent]] =
    for {
      result <- insert(id, data) // add event
      _      <- result.traverse(e => eventStore.publish(ExperimentVariantEventCreated(id, e)))
    } yield result

  private def insert(id: ExperimentVariantEventKey, data: ExperimentVariantEvent): F[Result[ExperimentVariantEvent]] =
    mongoApi.database
      .flatMap(
        _.collection[JSONCollection](collectionName)
          .insert(ExperimentVariantEventDocument(id, id.experimentId, id.variantId, data))
      )
      .toF
      .map(_ => Result.ok(data))

  override def deleteEventsForExperiment(experiment: Experiment): F[Result[Done]] =
    mongoApi.database
      .flatMap(
        _.collection[JSONCollection](collectionName)
          .remove(Json.obj("experimentId" -> experiment.id.key))
      )
      .toF
      .map(_ => Result.ok(Done))

  override def findVariantResult(experiment: Experiment): Source[VariantResult, NotUsed] =
    Source(experiment.variants.toList)
      .flatMapMerge(4, { v =>
        findEvents(experiment.id.key, v)
      })
      .via(ExperimentVariantEvent.eventAggregation(experiment))

  private def findEvents(experimentId: String, variant: Variant): Source[ExperimentVariantEvent, NotUsed] =
    Source
      .fromFutureSource(
        mongoApi.database.map { collection =>
          collection
            .collection[JSONCollection](collectionName)
            .find(Json.obj("experimentId" -> experimentId, "variantId" -> variant.id))
            .cursor[ExperimentVariantEventDocument](ReadPreference.primary)
            .documentSource()
            .map(_.data)
        }
      )
      .mapMaterializedValue(_ => NotUsed)

  override def listAll(patterns: Seq[String]): Source[ExperimentVariantEvent, NotUsed] =
    Source
      .fromFuture(mongoApi.database)
      .map(_.collection[JSONCollection](collectionName))
      .flatMapConcat(
        _.find(Json.obj())
          .cursor[ExperimentVariantEventDocument](ReadPreference.primary)
          .documentSource()
          .map(_.data)
      )

  override def check(): F[Unit] =
    mongoApi.database
      .flatMap(
        _.collection[JSONCollection](collectionName)
          .find(Json.obj())
          .one[JsValue]
      )
      .toF
      .map(_ => ())
}
