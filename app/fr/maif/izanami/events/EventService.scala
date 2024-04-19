package fr.maif.izanami.events

import akka.NotUsed
import akka.stream.scaladsl.{BroadcastHub, Keep, Source}
import akka.stream.{KillSwitches, Materializer, SharedKillSwitch}
import fr.maif.izanami.env.Env
import fr.maif.izanami.env.pgimplicits.{EnhancedRow, VertxFutureEnhancer}
import fr.maif.izanami.errors.{EventNotFound, FailedToReadEvent, IzanamiError}
import fr.maif.izanami.events.EventService.{eventFormat, sourceEventWrites, IZANAMI_CHANNEL}
import fr.maif.izanami.models.{AbstractFeature, Feature, FeatureWithOverloads, LightWeightFeature, RequestContext}
import io.vertx.pgclient.pubsub.PgSubscriber
import io.vertx.sqlclient.SqlConnection
import play.api.libs.json.{Format, JsError, JsNumber, JsObject, JsResult, JsSuccess, JsValue, Json, Reads, Writes}
import fr.maif.izanami.models.Feature.{
  featureWrite,
  lightweightFeatureRead,
  lightweightFeatureWrite,
  writeStrategiesForEvent
}
import fr.maif.izanami.models.FeatureWithOverloads.featureWithOverloadWrite
import fr.maif.izanami.utils.syntax.implicits.BetterJsValue
import fr.maif.izanami.v1.V2FeatureEvents.{createEventV2, deleteEventV2, updateEventV2}
import play.api.Logger

import scala.concurrent.{ExecutionContext, Future}

sealed trait SourceIzanamiEvent {
  val user: String
  val dbEventType: String
  val entityId: String
}
sealed trait SourceFeatureEvent extends SourceIzanamiEvent {
  override val user: String
  val id: String
  val project: String
  val tenant: String
}

case class SourceFeatureCreated(
    id: String,
    project: String,
    tenant: String,
    override val user: String,
    feature: FeatureWithOverloads
)                                                                         extends SourceFeatureEvent {
  override val dbEventType: String = "FEATURE_CREATED"
  override val entityId: String    = id
}
case class SourceFeatureUpdated(
    id: String,
    project: String,
    tenant: String,
    override val user: String,
    previous: FeatureWithOverloads,
    feature: FeatureWithOverloads
)                                                                         extends SourceFeatureEvent {
  override val dbEventType: String = "FEATURE_UPDATED"
  override val entityId: String    = id
}
case class SourceFeatureDeleted(id: String, project: String, tenant: String, override val user: String)
    extends SourceFeatureEvent {
  override val dbEventType: String = "FEATURE_DELETED"
  override val entityId: String    = id
}
case class SourceTenantDeleted(tenant: String, override val user: String) extends SourceIzanamiEvent {
  override val dbEventType: String = "TENANT_DELETED"
  override val entityId: String    = tenant
}

case class SourceTenantCreated(tenant: String, override val user: String) extends SourceIzanamiEvent {
  override val dbEventType: String = "TENANT_CREATED"
  override val entityId: String    = tenant
}

sealed trait IzanamiEvent {
  val eventId: Long
  val user: String
}

sealed trait FeatureEvent          extends IzanamiEvent {
  override val eventId: Long
  val id: String
  val project: String
  val tenant: String
  override val user: String
}
// Condition by contetx is an option since feature may have been deleted between emission and reception of the event
sealed trait ConditionFeatureEvent extends FeatureEvent {
  override val eventId: Long
  override val user: String
  val conditionByContext: Option[Map[String, LightWeightFeature]]
}
case class FeatureCreated(
    override val eventId: Long,
    id: String,
    project: String,
    tenant: String,
    override val user: String,
    conditionByContext: Option[Map[String, LightWeightFeature]] = None
)                                  extends ConditionFeatureEvent
case class FeatureUpdated(
    override val eventId: Long,
    id: String,
    project: String,
    tenant: String,
    override val user: String,
    conditionByContext: Option[Map[String, LightWeightFeature]] = None,
    previous: Option[Map[String, LightWeightFeature]] = None
)                                  extends ConditionFeatureEvent

case class FeatureDeleted(
    override val eventId: Long,
    id: String,
    project: String,
    tenant: String,
    override val user: String
)                                                                                               extends FeatureEvent
case class TenantDeleted(override val eventId: Long, tenant: String, override val user: String) extends IzanamiEvent

case class TenantCreated(override val eventId: Long, tenant: String, override val user: String) extends IzanamiEvent

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
  val IZANAMI_CHANNEL                               = "izanami"
  implicit val fWrite: Writes[FeatureWithOverloads] = featureWithOverloadWrite

  implicit val sourceEventWrites: Writes[SourceIzanamiEvent]       = {
    case SourceFeatureCreated(id, project, tenant, user, feature)                  =>
      Json.obj(
        "id"      -> id,
        "project" -> project,
        "tenant"  -> tenant,
        "user"    -> user,
        "type"    -> "FEATURE_CREATED",
        "feature" -> feature
      )
    case SourceFeatureUpdated(id, project, tenant, user, previousFeature, feature) =>
      Json.obj(
        "id"       -> id,
        "project"  -> project,
        "tenant"   -> tenant,
        "user"     -> user,
        "type"     -> "FEATURE_UPDATED",
        "feature"  -> feature,
        "previous" -> previousFeature
      )
    case SourceFeatureDeleted(id, project, user, tenant)                           =>
      Json.obj(
        "id"      -> id,
        "project" -> project,
        "tenant"  -> tenant,
        "user"    -> user,
        "type"    -> "FEATURE_DELETED"
      )
    case SourceTenantDeleted(tenant, user)                                         => Json.obj("tenant" -> tenant, "type" -> "TENANT_DELETED", "user" -> user)
    case SourceTenantCreated(tenant, user)                                         => Json.obj("tenant" -> tenant, "type" -> "TENANT_CREATED", "user" -> user)
  }
  implicit val lighweightFeatureWrites: Writes[LightWeightFeature] = lightweightFeatureWrite
  implicit val eventFormat: Format[IzanamiEvent]                   = new Format[IzanamiEvent] {
    override def writes(o: IzanamiEvent): JsValue = {
      o match {
        case FeatureCreated(eventId, id, project, tenant, user, conditions)           =>
          Json.obj(
            "eventId"    -> eventId,
            "id"         -> id,
            "project"    -> project,
            "tenant"     -> tenant,
            "user"       -> user,
            "conditions" -> conditions,
            "type"       -> "FEATURE_CREATED"
          )
        case FeatureUpdated(eventId, id, project, tenant, user, conditions, previous) =>
          Json.obj(
            "eventId"            -> eventId,
            "id"                 -> id,
            "project"            -> project,
            "tenant"             -> tenant,
            "user"               -> user,
            "conditions"         -> conditions,
            "previousConditions" -> previous,
            "type"               -> "FEATURE_UPDATED"
          )
        case FeatureDeleted(eventId, id, project, tenant, user)                       =>
          Json.obj(
            "eventId" -> eventId,
            "id"      -> id,
            "project" -> project,
            "tenant"  -> tenant,
            "user"    -> user,
            "type"    -> "FEATURE_DELETED"
          )
        case TenantDeleted(eventId, tenant, user)                                     =>
          Json.obj("eventId" -> eventId, "tenant" -> tenant, "type" -> "TENANT_DELETED", "user" -> user)
        case TenantCreated(eventId, tenant, user)                                     =>
          Json.obj("eventId" -> eventId, "tenant" -> tenant, "type" -> "TENANT_CREATED", "user" -> user)
      }
    }

    override def reads(json: JsValue): JsResult[IzanamiEvent] = {
      (json \ "type").asOpt[String].map(_.toUpperCase).flatMap {
        case eventType @ ("FEATURE_CREATED" | "FEATURE_UPDATED") => {
          (for (
            eventId    <- (json \ "eventId").asOpt[Long];
            id         <- (json \ "id").asOpt[String];
            tenant     <- (json \ "tenant").asOpt[String];
            user       <- (json \ "user").asOpt[String];
            project    <- (json \ "project").asOpt[String];
            conditions <- (json \ "feature").asOpt[Map[String, LightWeightFeature]](Reads.map(lightweightFeatureRead))
          ) yield (eventId, id, tenant, project, user, conditions)).map {
            case (eventId, id, tenant, project, user, conditions) =>
              if (eventType == "FEATURE_CREATED") {
                FeatureCreated(eventId, id, project, tenant, user, Some(conditions))
              } else {
                FeatureUpdated(
                  eventId,
                  id,
                  project,
                  tenant,
                  user,
                  Some(conditions),
                  previous =
                    (json \ "previous").asOpt[Map[String, LightWeightFeature]](Reads.map(lightweightFeatureRead))
                )
              }
          }
        }
        case "FEATURE_DELETED"                                   =>
          for (
            eventId <- (json \ "eventId").asOpt[Long];
            id      <- (json \ "id").asOpt[String];
            tenant  <- (json \ "tenant").asOpt[String];
            user    <- (json \ "user").asOpt[String];
            project <- (json \ "project").asOpt[String]
          ) yield FeatureDeleted(eventId, id, project, tenant, user)
        case "TENANT_DELETED"                                    =>
          for (
            eventId <- (json \ "eventId").asOpt[Long];
            user    <- (json \ "user").asOpt[String];
            tenant  <- (json \ "tenant").asOpt[String]
          ) yield TenantDeleted(eventId, tenant, user)
        case "TENANT_CREATED"                                    =>
          for (
            eventId <- (json \ "eventId").asOpt[Long];
            user    <- (json \ "user").asOpt[String];
            tenant  <- (json \ "tenant").asOpt[String]
          ) yield TenantCreated(eventId, tenant, user)
      }
    }.fold(JsError("Failed to read event"): JsResult[IzanamiEvent])(evt => JsSuccess(evt))
  }

  def internalToExternalEvent(
      event: IzanamiEvent,
      context: RequestContext,
      conditions: Boolean,
      env: Env
  ): Future[Option[JsObject]] = {
    val logger                                      = env.logger
    val user                                        = event.user
    implicit val executionContext: ExecutionContext = env.executionContext
    event match {
      case FeatureDeleted(_, id, _, _, _) => Future.successful(Some(deleteEventV2(id, user)))
      case f: ConditionFeatureEvent       => {
        val maybeContextmap = f match {
          case FeatureCreated(_, _, _, _, _, map)    => map
          case FeatureUpdated(_, _, _, _, _, map, _) => map
        }
        Feature.processMultipleStrategyResult(maybeContextmap.get, context, conditions, env).map {
          case Left(error) => {
            logger.error(s"Failed to write feature : ${error.message}")
            None
          }
          case Right(json) => {
            f match {
              case FeatureCreated(_, id, _, _, _, _)           => Some(createEventV2(json, user) ++ Json.obj("id" -> id))
              case FeatureUpdated(_, id, _, _, _, _, previous) => {
                val maybePrevious = previous
                  .filter(_ => conditions)
                  .map(ctxs => writeStrategiesForEvent(ctxs))
                val finalJson     = maybePrevious
                  .map(js => json ++ Json.obj("previousConditions" -> js))
                  .getOrElse(json)
                val finalEvent    = updateEventV2(finalJson, user) ++ Json.obj("id" -> id)
                Some(finalEvent)
              }
            }
          }
        }
      }
      case _                              => Future.successful(None)
    }
  }
}

class EventService(env: Env) {
  implicit val executionContext: ExecutionContext                       = env.executionContext
  implicit val materializer: Materializer                               = env.materializer
  val logger: Logger                                                    = env.logger
  val sourceMap: scala.collection.mutable.Map[String, SourceDescriptor] =
    scala.collection.mutable.Map()

  def emitEvent(channel: String, event: SourceIzanamiEvent)(implicit conn: SqlConnection): Future[Unit] = {
    val global    = channel.equalsIgnoreCase("izanami") || event.isInstanceOf[SourceTenantDeleted]
    val jsonEvent = Json.toJson(event)(sourceEventWrites).as[JsObject]
    env.postgresql
      .queryOne(
        s"""
         |WITH generated_id AS (
         |    SELECT nextval('izanami.eventid') as next_id
         |)
         |INSERT INTO  ${if (global) "izanami.global_events" else "events"} (id, event, event_type, entity_id)
         |SELECT gid.next_id as id, (jsonb_build_object('eventId', gid.next_id) || $$1::jsonb) as event, $$2::${if (
          global
        ) "GLOBAL_EVENTS"
        else "LOCAL_EVENTS"}, $$3
         |FROM generated_id gid
         |RETURNING id;
         |""".stripMargin,
        params = List(jsonEvent.vertxJsValue, event.dbEventType, event.entityId),
        conn = Some(conn),
        schemas = if (global) Set() else Set(channel)
      ) { r =>
        {
          r.optLong("id").map(id => (id, jsonEvent))
        }
      }
      .flatMap {
        case Some((id, jsonEvent)) => {
          val lightEvent = jsonEvent + ("eventId" -> JsNumber(id)) - "feature"
          env.postgresql
            .queryOne(
              s"""SELECT pg_notify($$1, $$2)""",
              List(channel, lightEvent.toString()),
              conn = Some(conn)
            ) { _ => Some(()) }
            .map(_ => ())
        }
        case None                  => Future.successful(())
      }
  }

  private def readEventFromDb(tenant: String, id: Long): Future[Either[IzanamiError, IzanamiEvent]] = {
    val global = tenant.equals(IZANAMI_CHANNEL)
    env.postgresql
      .queryOne(
        s"""
         |SELECT event FROM ${if (global) "izanami.global_events" else "events"} WHERE id=$$1
         |""".stripMargin,
        List(java.lang.Long.valueOf(id)),
        schemas = if (global) Set() else Set(tenant)
      ) { r => r.optJsObject("event") }
      .map(o => o.toRight(EventNotFound(tenant, id)))
      .map(e => e.flatMap(js => eventFormat.reads(js).asOpt.toRight(FailedToReadEvent(js.toString()))))

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
                val eventId = (Json.parse(payload) \ "eventId").asOpt[Long]
                eventId.fold(logger.error(s"Failed to read event id : $payload"))(id => {
                  readEventFromDb(channel, id)
                    .map(e =>
                      e.fold(
                        err => {
                          logger.error(err.message)
                          throw err
                        },
                        evt => evt
                      )
                    )
                    .foreach(value => queue.offer(value))
                })
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
}
