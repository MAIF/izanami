package domains.abtesting.impl

import java.time.LocalDateTime
import java.time.temporal.ChronoUnit

import akka.actor.ActorSystem
import akka.stream.ActorMaterializer
import akka.NotUsed
import akka.http.scaladsl.util.FastFuture
import akka.stream.scaladsl.Source
import reactivemongo.akkastream._
import domains.abtesting.Experiment.ExperimentKey
import domains.abtesting._
import domains.events.EventStore
import domains.events.Events.ExperimentVariantEventCreated
import env.DbDomainConfig
import libs.mongo.MongoUtils
import play.api.libs.json.{JsObject, JsValue, Json}
import play.modules.reactivemongo.ReactiveMongoApi
import reactivemongo.api.ReadPreference
import reactivemongo.api.indexes.{Index, IndexType}
import reactivemongo.play.json._
import reactivemongo.play.json.collection.JSONCollection
import store.Result.IzanamiErrors
import zio.{RIO, Task, ZIO}

import scala.concurrent.{ExecutionContext, Future}
import libs.logs.Logger
import domains.AuthInfo
import domains.events.Events.ExperimentVariantEventsDeleted

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
) extends ExperimentVariantEventService {

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

  override def start: RIO[ExperimentVariantEventServiceModule, Unit] =
    Logger.debug(s"Initializing mongo collection $collectionName") *>
    Task.fromFuture { implicit ec =>
      Future.sequence(
        Seq(
          MongoUtils.initIndexes(collectionName, indexesDefinition)
        )
      )
    }.unit

  private def getCounter(collName: String, experimentId: String, variantId: String): Task[Long] = {
    val id = s"$experimentId.$variantId"
    ZIO
      .fromFuture { implicit ec =>
        mongoApi.database
          .flatMap(
            _.collection[JSONCollection](collName)
              .find(Json.obj("id" -> id), projection = Option.empty[JsObject])
              .one[Counter]
          )
      }
      .map(_.map(_.value).getOrElse(0))
  }

  override def create(
      id: ExperimentVariantEventKey,
      data: ExperimentVariantEvent
  ): ZIO[ExperimentVariantEventServiceModule, IzanamiErrors, ExperimentVariantEvent] =
    for {
      result   <- insert(id, data) // add event
      authInfo <- AuthInfo.authInfo
      _        <- EventStore.publish(ExperimentVariantEventCreated(id, result, authInfo = authInfo))
    } yield result

  private def insert(
      id: ExperimentVariantEventKey,
      data: ExperimentVariantEvent
  ): ZIO[ExperimentVariantEventServiceModule, IzanamiErrors, ExperimentVariantEvent] =
    ZIO
      .fromFuture { implicit ec =>
        mongoApi.database
          .flatMap(
            _.collection[JSONCollection](collectionName).insert
              .one(ExperimentVariantEventDocument(id, id.experimentId, id.variantId, data))
          )
      }
      .refineToOrDie[IzanamiErrors]
      .map(_ => data)

  override def deleteEventsForExperiment(
      experiment: Experiment
  ): ZIO[ExperimentVariantEventServiceModule, IzanamiErrors, Unit] =
    ZIO
      .fromFuture { implicit ec =>
        mongoApi.database
          .flatMap(
            _.collection[JSONCollection](collectionName).delete.one(Json.obj("experimentId" -> experiment.id.key))
          )
      }
      .unit
      .refineToOrDie[IzanamiErrors] <* (AuthInfo.authInfo >>= (
        authInfo => EventStore.publish(ExperimentVariantEventsDeleted(experiment, authInfo = authInfo))
    ))

  override def findVariantResult(
      experiment: Experiment
  ): RIO[ExperimentVariantEventServiceModule, Source[VariantResult, NotUsed]] =
    Task.fromFuture { implicit ec =>
      FastFuture.successful(
        Source(experiment.variants.toList)
          .flatMapMerge(
            4, { v =>
              Source
                .fromFuture(firstEvent(experiment.id.key, v.id))
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
      .fromFutureSource(
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
  ): RIO[ExperimentVariantEventServiceModule, Source[ExperimentVariantEvent, NotUsed]] =
    Task {
      Source
        .fromFuture(mongoApi.database)
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
