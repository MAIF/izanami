package domains.abtesting.events.impl

import java.time.LocalDateTime
import java.time.temporal.ChronoUnit

import akka.NotUsed
import akka.actor.ActorSystem
import akka.http.scaladsl.util.FastFuture
import akka.stream.scaladsl.Source
import domains.auth.AuthInfo
import domains.abtesting.Experiment.ExperimentKey
import domains.abtesting._
import domains.abtesting.events.{ExperimentVariantEventInstances, ExperimentVariantEventKeyInstances, _}
import domains.errors.IzanamiErrors
import domains.events.EventStore
import domains.events.Events.{ExperimentVariantEventCreated, ExperimentVariantEventsDeleted}
import env.DbDomainConfig
import libs.logs.ZLogger
import libs.mongo.MongoUtils
import play.api.libs.json.{JsObject, JsValue, Json}
import play.modules.reactivemongo.ReactiveMongoApi
import reactivemongo.akkastream._
import reactivemongo.api.ReadPreference
import reactivemongo.api.indexes.{Index, IndexType}
import reactivemongo.play.json.collection.JSONCollection
import reactivemongo.play.json.compat._
import zio.{RIO, Task, ZIO}

import scala.concurrent.{ExecutionContext, Future}

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
  def apply(config: DbDomainConfig, mongoApi: ReactiveMongoApi)(
      implicit system: ActorSystem
  ): ExperimentVariantEventMongoService =
    new ExperimentVariantEventMongoService(config.conf.namespace, mongoApi)
}

class ExperimentVariantEventMongoService(namespace: String, mongoApi: ReactiveMongoApi)(
    implicit system: ActorSystem
) extends ExperimentVariantEventService.Service {

  private implicit val mapi: ReactiveMongoApi = mongoApi

  private val collectionName = namespace.replaceAll(":", "_")

  private val indexesDefinition: Seq[Index.Default] = Seq(
    MongoUtils.createIndex(Seq("id"           -> IndexType.Ascending), unique = true),
    MongoUtils.createIndex(Seq("experimentId" -> IndexType.Ascending), unique = false),
    MongoUtils.createIndex(Seq("variantId"    -> IndexType.Ascending), unique = false)
  )

  override def start: RIO[ExperimentVariantEventServiceContext, Unit] =
    ZLogger.debug(s"Initializing mongo collection $collectionName") *>
    Task.fromFuture { implicit ec =>
      Future.sequence(
        Seq(
          MongoUtils.initIndexes(collectionName, indexesDefinition)
        )
      )
    }.unit

  override def create(
      id: ExperimentVariantEventKey,
      data: ExperimentVariantEvent
  ): ZIO[ExperimentVariantEventServiceContext, IzanamiErrors, ExperimentVariantEvent] =
    for {
      result   <- insert(id, data) // add event
      authInfo <- AuthInfo.authInfo
      _        <- EventStore.publish(ExperimentVariantEventCreated(id, result, authInfo = authInfo))
    } yield result

  private def insert(
      id: ExperimentVariantEventKey,
      data: ExperimentVariantEvent
  ): ZIO[ExperimentVariantEventServiceContext, IzanamiErrors, ExperimentVariantEvent] =
    ZIO
      .fromFuture { implicit ec =>
        mongoApi.database
          .flatMap(
            _.collection[JSONCollection](collectionName).insert
              .one(ExperimentVariantEventDocument(id, id.experimentId, id.variantId, data))
          )
      }
      .orDie
      .map(_ => data)

  override def deleteEventsForExperiment(
      experiment: Experiment
  ): ZIO[ExperimentVariantEventServiceContext, IzanamiErrors, Unit] =
    ZIO
      .fromFuture { implicit ec =>
        mongoApi.database
          .flatMap(
            _.collection[JSONCollection](collectionName).delete.one(Json.obj("experimentId" -> experiment.id.key))
          )
      }
      .unit
      .orDie <* (AuthInfo.authInfo flatMap (
        authInfo => EventStore.publish(ExperimentVariantEventsDeleted(experiment, authInfo = authInfo))
    ))

  override def findVariantResult(
      experiment: Experiment
  ): RIO[ExperimentVariantEventServiceContext, Source[VariantResult, NotUsed]] =
    Task.fromFuture { implicit ec =>
      FastFuture.successful(
        Source(experiment.variants.toList)
          .flatMapMerge(
            4, { v =>
              Source
                .future(firstEvent(experiment.id.key, v.id))
                .flatMapConcat { mayBeEvent =>
                  val interval = mayBeEvent
                    .map(e => ExperimentVariantEvent.calcInterval(e.date, LocalDateTime.now()))
                    .getOrElse(ChronoUnit.HOURS)
                  findEvents(experiment.id.key, v)
                    .via(ExperimentVariantEvent.eventAggregation(experiment.id.key, experiment.variants.size, interval))
                }

            }
          )
      )
    }

  private def findEvents(experimentId: String,
                         variant: Variant)(implicit ec: ExecutionContext): Source[ExperimentVariantEvent, NotUsed] =
    Source
      .futureSource(
        mongoApi.database.map { collection =>
          collection
            .collection[JSONCollection](collectionName)
            .find(Json.obj("experimentId" -> experimentId, "variantId" -> variant.id),
                  projection = Option.empty[JsObject])
            .sort(Json.obj("date" -> 1))
            .cursor[ExperimentVariantEventDocument](ReadPreference.primary)
            .documentSource()
            .map(_.data)
        }
      )
      .mapMaterializedValue(_ => NotUsed)

  private def firstEvent(experimentId: String,
                         variant: String)(implicit ec: ExecutionContext): Future[Option[ExperimentVariantEvent]] =
    mongoApi.database.flatMap { collection =>
      collection
        .collection[JSONCollection](collectionName)
        .find(Json.obj("experimentId" -> experimentId, "variantId" -> variant), projection = Option.empty[JsObject])
        .sort(Json.obj("date" -> 1))
        .one[ExperimentVariantEventDocument](ReadPreference.primary)
        .map(_.map(_.data))
    }

  override def listAll(
      patterns: Seq[String]
  ): RIO[ExperimentVariantEventServiceContext, Source[ExperimentVariantEvent, NotUsed]] =
    Task {
      Source
        .future(mongoApi.database)
        .map(_.collection[JSONCollection](collectionName))
        .flatMapConcat(
          _.find(Json.obj(), projection = Option.empty[JsObject])
            .cursor[ExperimentVariantEventDocument](ReadPreference.primary)
            .documentSource()
            .map(_.data)
        )
    }

  override def check(): Task[Unit] =
    ZIO.fromFuture { implicit ec =>
      mongoApi.database
        .flatMap(
          _.collection[JSONCollection](collectionName)
            .find(Json.obj(), projection = Option.empty[JsObject])
            .one[JsValue]
        )
    }.unit
}
