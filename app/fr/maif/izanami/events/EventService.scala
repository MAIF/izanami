package fr.maif.izanami.events

import akka.NotUsed
import akka.stream.scaladsl.{BroadcastHub, Keep, Source}
import akka.stream.{KillSwitches, Materializer, SharedKillSwitch}
import fr.maif.izanami.env.Env
import fr.maif.izanami.env.pgimplicits.VertxFutureEnhancer
import fr.maif.izanami.errors.{BadEventFormat, IzanamiError}
import fr.maif.izanami.events.EventService.eventFormat
import fr.maif.izanami.models.AbstractFeature
import io.vertx.pgclient.pubsub.PgSubscriber
import io.vertx.sqlclient.SqlConnection
import play.api.libs.json.{Format, JsError, JsObject, JsResult, JsSuccess, JsValue, Json, Writes}
import fr.maif.izanami.models.Feature.featureWrite

import scala.concurrent.{ExecutionContext, Future}
sealed trait IzanamiEvent
sealed trait FeatureEvent                                              extends IzanamiEvent {
  val id: String
  val project: String
  val tenant: String
}
// Condition by contetx is an option since feature may have been deleted between emission and reception of the event
sealed trait ConditionFeatureEvent                                     extends FeatureEvent {
  val conditionByContext: Option[Map[String, AbstractFeature]]
}
case class FeatureCreated(
    id: String,
    project: String,
    tenant: String,
    conditionByContext: Option[Map[String, AbstractFeature]] = None
)                                                                      extends ConditionFeatureEvent
case class FeatureUpdated(
    id: String,
    project: String,
    tenant: String,
    conditionByContext: Option[Map[String, AbstractFeature]] = None
)                                                                      extends ConditionFeatureEvent
case class FeatureDeleted(id: String, project: String, tenant: String) extends FeatureEvent
case class TenantDeleted(tenant: String)                               extends IzanamiEvent

case class SourceDescriptor(
    source: Source[IzanamiEvent, NotUsed],
    killswitch: SharedKillSwitch,
    pgSubscriber: PgSubscriber
) {
  def close(implicit executionContext: ExecutionContext): Future[Unit] = {
    killswitch.shutdown()
    pgSubscriber.close().scala.map(_ => ())
  }
}

object EventService {
  implicit val featureWrites: Writes[AbstractFeature] = featureWrite
  implicit val eventFormat: Format[IzanamiEvent]      = new Format[IzanamiEvent] {
    override def writes(o: IzanamiEvent): JsValue = {
      o match {
        case FeatureCreated(id, project, tenant, conditions) =>
          Json.obj(
            "id"         -> id,
            "project"    -> project,
            "tenant"     -> tenant,
            "conditions" -> conditions,
            "type"       -> "FEATURE_CREATED"
          )
        case FeatureUpdated(id, project, tenant, conditions) =>
          Json.obj(
            "id"         -> id,
            "project"    -> project,
            "tenant"     -> tenant,
            "conditions" -> conditions,
            "type"       -> "FEATURE_UPDATED"
          )
        case FeatureDeleted(id, project, tenant)             =>
          Json.obj(
            "id"      -> id,
            "project" -> project,
            "tenant"  -> tenant,
            "type"    -> "FEATURE_DELETED"
          )
        case TenantDeleted(tenant)                           => Json.obj("tenant" -> tenant)
      }
    }

    override def reads(json: JsValue): JsResult[IzanamiEvent] = {
      (json \ "type").asOpt[String].map(_.toUpperCase).flatMap {
        case eventType @ ("FEATURE_CREATED" | "FEATURE_UPDATED") => {
          (for (
            id      <- (json \ "id").asOpt[String];
            tenant  <- (json \ "tenant").asOpt[String];
            project <- (json \ "project").asOpt[String]
          ) yield (id, tenant, project)).map { case (id, tenant, project) =>
            if (eventType == "FEATURE_CREATED") {
              FeatureCreated(id, project, tenant, None)
            } else {
              FeatureUpdated(id, project, tenant, None)
            }
          }
        }
        case "FEATURE_DELETED"                                   =>
          for (
            id      <- (json \ "id").asOpt[String];
            tenant  <- (json \ "tenant").asOpt[String];
            project <- (json \ "project").asOpt[String]
          ) yield FeatureDeleted(id, project, tenant)
        case "TENANT_DELETED"                                    =>
          (json \ "tenant")
            .asOpt[String]
            .map(tenant => TenantDeleted(tenant))
      }
    }.fold(JsError("Failed to read event"): JsResult[IzanamiEvent])(evt => JsSuccess(evt))
  }
}

class EventService(env: Env) {
  implicit val executionContext: ExecutionContext                       = env.executionContext
  implicit val materializer: Materializer                               = env.materializer
  val logger                                                            = env.logger
  val sourceMap: scala.collection.mutable.Map[String, SourceDescriptor] =
    scala.collection.mutable.Map()
  def emitEvent(channel: String, event: IzanamiEvent)(implicit conn: SqlConnection): Future[Unit] = {
    env.postgresql
      .queryOne(
        s"""SELECT pg_notify($$1, $$2)""",
        List(channel, Json.toJson(event)(eventFormat.writes).toString()),
        conn = Some(conn)
      ) { _ => Some(()) }
      .map(_ => ())
  }

  def consume(channel: String): SourceDescriptor = {
    if (sourceMap.contains(channel)) {
      sourceMap(channel)
    } else {
      logger.info(s"Creating event source for $channel")
      val sharedKillSwitch     = KillSwitches.shared(s"$channel-killswitch")
      lazy val (queue, source) = Source
        .queue[IzanamiEvent](bufferSize = 1024)
        .toMat(BroadcastHub.sink(bufferSize = 1024))(Keep.both)
        .run()

      lazy val subscriber = PgSubscriber.subscriber(env.postgresql.vertx, env.postgresql.connectOptions)
      subscriber
        .connect()
        .onComplete(ar => {
          if (ar.succeeded()) {
            subscriber
              .channel(channel)
              .handler(payload => {
                eventFormat.reads(Json.parse(payload)) match {
                  case JsError(errors)        => logger.error(s"Failed to read event $payload")
                  case JsSuccess(value, path) => {
                    val futureEvent: Future[IzanamiEvent] = value match {
                      case f @ FeatureCreated(id, _, tenant, _) => {
                        env.datastores.features
                          .findActivationStrategiesForFeature(tenant, id)
                          .map(maybeConditions => f.copy(conditionByContext = maybeConditions))
                      }
                      case f @ FeatureUpdated(id, _, tenant, _) => {
                        env.datastores.features
                          .findActivationStrategiesForFeature(tenant, id)
                          .map(maybeConditions => f.copy(conditionByContext = maybeConditions))
                      }
                      case e@TenantDeleted(tenant) => {
                        killSource(tenant)
                        Future.successful(e)
                      }
                      case evt                                  => Future.successful(evt)
                    }
                    futureEvent.foreach(value => queue.offer(value))
                  }
                }
              })
          }
        })
      val descriptor      = SourceDescriptor(source = source, killswitch = sharedKillSwitch, pgSubscriber = subscriber)
      sourceMap.put(channel, descriptor)
      descriptor
    }
  }

  def killSource(tenant: String): Unit = {
    sourceMap.get(tenant).map {
      case SourceDescriptor(_, killswitch, pgSuscriber) => {
        sourceMap.remove(tenant)
        logger.info(s"Closing SSE source for $tenant")
        killswitch.shutdown()
        pgSuscriber
          .close()
          .onComplete(_ => {
            logger.info(s"Done closing PG suscriber for $tenant")
          })
      }
    }
  }

  def killAllSources(): Unit = {
    sourceMap.keys.foreach(killSource)
  }
  /*
  private def parseEvent(payload: String): Future[Either[IzanamiError, IzanamiEvent]] = {
    (for (
      json      <- Json.parse(payload).asOpt[JsObject];
      eventType <- (json \ "type").asOpt[String]
    ) yield eventType.toUpperCase match {
      case eventType @ ("FEATURE_CREATED" | "FEATURE_UPDATED") => {
        (for (
          id      <- (json \ "id").asOpt[String];
          tenant  <- (json \ "tenant").asOpt[String];
          project <- (json \ "project").asOpt[String]
        ) yield (id, tenant, project)) match {
          case None                        => Future.successful(Left(BadEventFormat(s"Missing id and/or tenant for $eventType")))
          case Some((id, tenant, project)) =>
            env.datastores.features
              .findActivationStrategiesForFeature(tenant, id)
              .map(maybeMap =>
                Right(
                  if (eventType == "FEATURE_CREATED") { FeatureCreated(id, project, maybeMap) }
                  else { FeatureUpdated(id, project, maybeMap) }
                )
              )
        }
      }
      case "FEATURE_DELETED"                                   =>
        Future.successful(
          (for (
            id      <- (json \ "id").asOpt[String];
            project <- (json \ "project").asOpt[String]
          ) yield FeatureDeleted(id, project))
            .toRight(BadEventFormat(s"Missing id and/or project for event FEATURE_DELETED"))
        )
      case "TENANT_DELETED"                                    =>
        Future.successful(
          (json \ "tenant")
            .asOpt[String]
            .toRight(BadEventFormat(s"Missing id for event TENANT_DELETED"))
            .map(tenant => TenantDeleted(tenant))
        )
    }).getOrElse(Future.successful(Left(BadEventFormat(s"Event $payload is not a json object with a type field"))))
  }*/
}
