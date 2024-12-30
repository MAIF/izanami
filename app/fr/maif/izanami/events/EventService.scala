package fr.maif.izanami.events

import akka.NotUsed
import akka.stream.KillSwitches
import akka.stream.Materializer
import akka.stream.SharedKillSwitch
import akka.stream.scaladsl.BroadcastHub
import akka.stream.scaladsl.Keep
import akka.stream.scaladsl.Source
import fr.maif.izanami.env.Env
import fr.maif.izanami.env.pgimplicits.EnhancedRow
import fr.maif.izanami.env.pgimplicits.VertxFutureEnhancer
import fr.maif.izanami.events.EventAuthentication.eventAuthenticationReads
import fr.maif.izanami.events.EventOrigin.ORIGIN_NAME_MAP
import fr.maif.izanami.events.EventOrigin.eventOriginReads
import fr.maif.izanami.events.EventService.sourceEventWrites
import fr.maif.izanami.models.Feature
import fr.maif.izanami.models.Feature.lightweightFeatureRead
import fr.maif.izanami.models.Feature.lightweightFeatureWrite
import fr.maif.izanami.models.Feature.writeStrategiesForEvent
import fr.maif.izanami.models.FeatureWithOverloads
import fr.maif.izanami.models.FeatureWithOverloads.featureWithOverloadWrite
import fr.maif.izanami.models.LightWeightFeature
import fr.maif.izanami.models.RequestContext
import fr.maif.izanami.utils.syntax.implicits.BetterJsValue
import fr.maif.izanami.utils.syntax.implicits.BetterSyntax
import fr.maif.izanami.v1.V2FeatureEvents.createEventV2
import fr.maif.izanami.v1.V2FeatureEvents.deleteEventV2
import fr.maif.izanami.v1.V2FeatureEvents.updateEventV2
import io.vertx.pgclient.pubsub.PgSubscriber
import io.vertx.sqlclient.SqlConnection
import play.api.Logger
import play.api.libs.json.Format
import play.api.libs.json.JsError
import play.api.libs.json.JsNumber
import play.api.libs.json.JsObject
import play.api.libs.json.JsResult
import play.api.libs.json.JsString
import play.api.libs.json.JsSuccess
import play.api.libs.json.JsValue
import play.api.libs.json.Json
import play.api.libs.json.Reads
import play.api.libs.json.Writes

import java.time.Instant
import java.time.OffsetDateTime
import java.util.UUID
import scala.concurrent.ExecutionContext
import scala.concurrent.Future


sealed trait EventOrigin

object EventOrigin {
  val ORIGIN_NAME_MAP: Map[EventOrigin, String] = Map((ImportOrigin, "IMPORT"), (NormalOrigin, "NORMAL"))

  case object ImportOrigin extends EventOrigin;

  case object NormalOrigin extends EventOrigin;

  def eventOriginReads: Reads[EventOrigin] = json => {
    json.asOpt[String].map(_.toUpperCase)
      .flatMap(upperName => {
        ORIGIN_NAME_MAP.find { case (_, name) => name == upperName }.map(_._1)
      }).map(origin => JsSuccess(origin)).getOrElse(JsError(s"Unknown origin $json"))
  }

  def eventOriginWrites: Writes[EventOrigin] = o => JsString(ORIGIN_NAME_MAP(o))
}


sealed trait EventAuthentication;

object EventAuthentication {
  def authenticationName(authentication: EventAuthentication): String = {
    authentication match {
      case TokenAuthentication(_) => "TOKEN"
      case BackOfficeAuthentication => "BACKOFFICE"
      case _ => throw new RuntimeException(s"Unknown authentication $authentication")
    }
  }

  case class TokenAuthentication(tokenId: UUID) extends EventAuthentication;

  case object BackOfficeAuthentication extends EventAuthentication;

  def eventAuthenticationReads: Reads[EventAuthentication] = json => {
    (json \ "authentication").asOpt[String].map(_.toUpperCase)
      .flatMap {
        case "TOKEN" => (json \ "token").asOpt[UUID].map(token => TokenAuthentication(token))
        case "BACKOFFICE" => Some(BackOfficeAuthentication)
      }.map(a => JsSuccess(a)).getOrElse(JsError(s"Unknown authentication $json"))
  }

  def eventAuthenticationWrites: Writes[EventAuthentication] = {
    case BackOfficeAuthentication => Json.obj("authentication" -> "BACKOFFICE")
    case TokenAuthentication(token) => Json.obj("authentication" -> "TOKEN", "token" -> token)
  }
}


sealed trait SourceIzanamiEvent {
  val user: String
  val dbEventType: String
  val entityId: String
  val origin: EventOrigin
  val authentication: EventAuthentication
}

sealed trait SourceFeatureEvent extends SourceIzanamiEvent {
  override val user: String
  val id: String
  val project: String
  val tenant: String
  val projectId: Option[String]
  val origin: EventOrigin
  val authentication: EventAuthentication

  def withProjectId(projectId: String): SourceFeatureEvent
}

case class SourceFeatureCreated(
                                 id: String,
                                 project: String,
                                 tenant: String,
                                 override val user: String,
                                 feature: FeatureWithOverloads,
                                 projectId: Option[String] = None,
                                 origin: EventOrigin,
                                 authentication: EventAuthentication
                               ) extends SourceFeatureEvent {
  override val dbEventType: String = "FEATURE_CREATED"
  override val entityId: String = id

  override def withProjectId(projectId: String): SourceFeatureEvent = copy(projectId = Some(projectId))
}

case class SourceFeatureUpdated(
                                 id: String,
                                 project: String,
                                 tenant: String,
                                 override val user: String,
                                 previous: FeatureWithOverloads,
                                 feature: FeatureWithOverloads,
                                 projectId: Option[String] = None,
                                 origin: EventOrigin,
                                 authentication: EventAuthentication
                               ) extends SourceFeatureEvent {
  override val dbEventType: String = "FEATURE_UPDATED"
  override val entityId: String = id

  override def withProjectId(projectId: String): SourceFeatureEvent = copy(projectId = Some(projectId))
}

case class SourceFeatureDeleted(
                                 id: String,
                                 project: String,
                                 tenant: String,
                                 override val user: String,
                                 projectId: Option[String] = None,
                                 name: String,
                                 origin: EventOrigin,
                                 authentication: EventAuthentication
                               ) extends SourceFeatureEvent {
  override val dbEventType: String = "FEATURE_DELETED"
  override val entityId: String = id

  override def withProjectId(projectId: String): SourceFeatureEvent = copy(projectId = Some(projectId))
}

case class SourceTenantDeleted(tenant: String, override val user: String, origin: EventOrigin,
                               authentication: EventAuthentication) extends SourceIzanamiEvent {
  override val dbEventType: String = "TENANT_DELETED"
  override val entityId: String = tenant
}

case class SourceTenantCreated(tenant: String, override val user: String, origin: EventOrigin,
                               authentication: EventAuthentication) extends SourceIzanamiEvent {
  override val dbEventType: String = "TENANT_CREATED"
  override val entityId: String = tenant
}

sealed trait IzanamiEvent {
  val eventId: Long
  val user: String
  val emittedAt: Option[Instant]
  val origin: EventOrigin
  val authentication: EventAuthentication
}

sealed trait FeatureEvent extends IzanamiEvent {
  override val eventId: Long
  val id: String
  val project: String
  val tenant: String
  override val user: String
  val origin: EventOrigin
  val authentication: EventAuthentication
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
                           conditionByContext: Option[Map[String, LightWeightFeature]] = None,
                           override val emittedAt: Option[Instant],
                           origin: EventOrigin,
                           authentication: EventAuthentication
                         ) extends ConditionFeatureEvent

case class FeatureUpdated(
                           override val eventId: Long,
                           id: String,
                           project: String,
                           tenant: String,
                           override val user: String,
                           conditionByContext: Option[Map[String, LightWeightFeature]] = None,
                           previous: Option[Map[String, LightWeightFeature]] = None,
                           override val emittedAt: Option[Instant],
                           origin: EventOrigin,
                           authentication: EventAuthentication
                         ) extends ConditionFeatureEvent

case class FeatureDeleted(
                           override val eventId: Long,
                           id: String,
                           project: String,
                           tenant: String,
                           override val user: String,
                           override val emittedAt: Option[Instant],
                           name: String,
                           origin: EventOrigin,
                           authentication: EventAuthentication
                         ) extends FeatureEvent

case class TenantDeleted(
                          override val eventId: Long,
                          tenant: String,
                          override val user: String,
                          override val emittedAt: Option[Instant],
                          origin: EventOrigin,
                          authentication: EventAuthentication
                        ) extends IzanamiEvent

case class TenantCreated(
                          override val eventId: Long,
                          tenant: String,
                          override val user: String,
                          override val emittedAt: Option[Instant],
                          origin: EventOrigin,
                          authentication: EventAuthentication
                        ) extends IzanamiEvent

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
  val IZANAMI_CHANNEL = "izanami"
  //implicit val fWrite: Writes[FeatureWithOverloads] = featureWithOverloadWrite

  sealed trait FeatureEventType {
    def name: String
  }

  case object FeatureCreatedType extends FeatureEventType {
    override def name: String = "FEATURE_CREATED"
  }

  case object FeatureUpdatedType extends FeatureEventType {
    override def name: String = "FEATURE_UPDATED"
  }

  case object FeatureDeletedType extends FeatureEventType {
    override def name: String = "FEATURE_DELETED"
  }

  def parseFeatureEventType(typeStr: String): Option[FeatureEventType] = {
    Option(typeStr).map(_.toUpperCase).collect {
      case "FEATURE_UPDATED" => FeatureUpdatedType
      case "FEATURE_CREATED" => FeatureCreatedType
      case "FEATURE_DELETED" => FeatureDeletedType
    }
  }




  implicit val sourceEventWrites: Writes[SourceIzanamiEvent] = {
    case SourceFeatureCreated(id, project, tenant, user, feature, projectId, origin, authentication) => {
      Json
        .obj(
          "id" -> id,
          "project" -> project,
          "tenant" -> tenant,
          "user" -> user,
          "type" -> "FEATURE_CREATED",
          "feature" -> Json.toJson(feature)(featureWithOverloadWrite),
          "origin" -> EventOrigin.eventOriginWrites.writes(origin),
        ).applyOnWithOpt(projectId)((jsObj, id) => jsObj + ("projectId" -> JsString(id))) ++ EventAuthentication.eventAuthenticationWrites.writes(authentication).as[JsObject]
    }
    case SourceFeatureUpdated(id, project, tenant, user, previousFeature, feature, projectId, origin, authentication) =>
      Json
        .obj(
          "id" -> id,
          "project" -> project,
          "tenant" -> tenant,
          "user" -> user,
          "type" -> "FEATURE_UPDATED",
          "feature" -> Json.toJson(feature)(featureWithOverloadWrite),
          "previous" -> Json.toJson(previousFeature)(featureWithOverloadWrite),
          "origin" -> EventOrigin.eventOriginWrites.writes(origin)
        )
        .applyOnWithOpt(projectId)((jsObj, id) => jsObj + ("projectId" -> JsString(id))) ++ EventAuthentication.eventAuthenticationWrites.writes(authentication).as[JsObject]
    case SourceFeatureDeleted(id, project, tenant, user, projectId, name, origin, authentication) =>
      Json
        .obj(
          "id" -> id,
          "project" -> project,
          "tenant" -> tenant,
          "user" -> user,
          "type" -> "FEATURE_DELETED",
          "name" -> name,
          "origin" -> EventOrigin.eventOriginWrites.writes(origin)
        )
        .applyOnWithOpt(projectId)((jsObj, id) => jsObj + ("projectId" -> JsString(id))) ++ EventAuthentication.eventAuthenticationWrites.writes(authentication).as[JsObject]
    case SourceTenantDeleted(tenant, user, origin, authentication) => Json.obj("tenant" -> tenant, "type" -> "TENANT_DELETED", "user" -> user, "origin" -> EventOrigin.eventOriginWrites.writes(origin)) ++ EventAuthentication.eventAuthenticationWrites.writes(authentication).as[JsObject]
    case SourceTenantCreated(tenant, user, origin, authentication) => Json.obj("tenant" -> tenant, "type" -> "TENANT_CREATED", "user" -> user, "origin" -> EventOrigin.eventOriginWrites.writes(origin)) ++ EventAuthentication.eventAuthenticationWrites.writes(authentication).as[JsObject]
  }
  implicit val lighweightFeatureWrites: Writes[LightWeightFeature] = lightweightFeatureWrite
  implicit val eventFormat: Format[IzanamiEvent] = new Format[IzanamiEvent] {
    override def writes(o: IzanamiEvent): JsValue = {
      o match {
        case FeatureCreated(eventId, id, project, tenant, user, conditions, emittedAt, origin, authentication) =>
          Json
            .obj(
              "eventId" -> eventId,
              "id" -> id,
              "project" -> project,
              "tenant" -> tenant,
              "user" -> user,
              "conditions" -> conditions,
              "type" -> "FEATURE_CREATED",
              "origin" -> EventOrigin.eventOriginWrites.writes(origin)
            )
            .applyOnWithOpt(emittedAt)((obj, instant) => obj + ("emittedAt" -> JsString(instant.toString))) ++ EventAuthentication.eventAuthenticationWrites.writes(authentication).as[JsObject]
        case FeatureUpdated(eventId, id, project, tenant, user, conditions, previous, emittedAt, origin, authentication) =>
          Json
            .obj(
              "eventId" -> eventId,
              "id" -> id,
              "project" -> project,
              "tenant" -> tenant,
              "user" -> user,
              "conditions" -> conditions,
              "previousConditions" -> previous,
              "type" -> "FEATURE_UPDATED",
              "origin" -> EventOrigin.eventOriginWrites.writes(origin)
            )
            .applyOnWithOpt(emittedAt)((obj, instant) => obj + ("emittedAt" -> JsString(instant.toString))) ++ EventAuthentication.eventAuthenticationWrites.writes(authentication).as[JsObject]
        case FeatureDeleted(eventId, id, project, tenant, user, emittedAt, name, origin, authentication) =>
          Json
            .obj(
              "eventId" -> eventId,
              "id" -> id,
              "project" -> project,
              "tenant" -> tenant,
              "user" -> user,
              "type" -> "FEATURE_DELETED",
              "name" -> name,
              "origin" -> EventOrigin.eventOriginWrites.writes(origin)
            )
            .applyOnWithOpt(emittedAt)((obj, instant) => obj + ("emittedAt" -> JsString(instant.toString))) ++ EventAuthentication.eventAuthenticationWrites.writes(authentication).as[JsObject]
        case TenantDeleted(eventId, tenant, user, emittedAt, origin, authentication) =>
          Json
            .obj("eventId" -> eventId, "tenant" -> tenant, "type" -> "TENANT_DELETED", "user" -> user, "origin" -> EventOrigin.eventOriginWrites.writes(origin))
            .applyOnWithOpt(emittedAt)((obj, instant) => obj + ("emittedAt" -> JsString(instant.toString))) ++ EventAuthentication.eventAuthenticationWrites.writes(authentication).as[JsObject]
        case TenantCreated(eventId, tenant, user, emittedAt, origin, authentication) =>
          Json
            .obj("eventId" -> eventId, "tenant" -> tenant, "type" -> "TENANT_CREATED", "user" -> user, "origin" -> EventOrigin.eventOriginWrites.writes(origin))
            .applyOnWithOpt(emittedAt)((obj, instant) => obj + ("emittedAt" -> JsString(instant.toString))) ++ EventAuthentication.eventAuthenticationWrites.writes(authentication).as[JsObject]
      }
    }

    override def reads(json: JsValue): JsResult[IzanamiEvent] = {
      val emissionDate = (json \ "emittedAt").asOpt[Instant]
      (json \ "type").asOpt[String].map(_.toUpperCase).flatMap {
        case eventType@("FEATURE_CREATED" | "FEATURE_UPDATED") => {
          (for (
            eventId <- (json \ "eventId").asOpt[Long];
            id <- (json \ "id").asOpt[String];
            tenant <- (json \ "tenant").asOpt[String];
            user <- (json \ "user").asOpt[String];
            project <- (json \ "project").asOpt[String];
            conditions <-
              (json \ "feature").asOpt[Map[String, LightWeightFeature]](Reads.map(lightweightFeatureRead));
            authentication <- json.asOpt[EventAuthentication](eventAuthenticationReads);
            origin <- (json \ "origin").asOpt[EventOrigin](eventOriginReads)
          ) yield (eventId, id, tenant, project, user, conditions, origin, authentication)).map {
            case (eventId, id, tenant, project, user, conditions, origin, authentication) =>
              if (eventType == "FEATURE_CREATED") {
                FeatureCreated(eventId, id, project, tenant, user, Some(conditions), emissionDate, origin = origin, authentication = authentication)
              } else {
                FeatureUpdated(
                  eventId,
                  id,
                  project,
                  tenant,
                  user,
                  Some(conditions),
                  previous =
                    (json \ "previous").asOpt[Map[String, LightWeightFeature]](Reads.map(lightweightFeatureRead)),
                  emittedAt = emissionDate,
                  origin = origin,
                  authentication = authentication
                )
              }
          }
        }
        case "FEATURE_DELETED" =>
          for (
            eventId <- (json \ "eventId").asOpt[Long];
            id <- (json \ "id").asOpt[String];
            tenant <- (json \ "tenant").asOpt[String];
            user <- (json \ "user").asOpt[String];
            project <- (json \ "project").asOpt[String];
            authentication <- json.asOpt[EventAuthentication](eventAuthenticationReads);
            origin <- (json \ "origin").asOpt[EventOrigin](eventOriginReads)
          )
          yield FeatureDeleted(
            eventId,
            id,
            project,
            tenant,
            user,
            emittedAt = emissionDate,
            name = (json \ "name").asOpt[String].getOrElse(""),
            origin = origin,
            authentication = authentication
          )
        case "TENANT_DELETED" =>
          for (
            eventId <- (json \ "eventId").asOpt[Long];
            user <- (json \ "user").asOpt[String];
            tenant <- (json \ "tenant").asOpt[String];
            authentication <- json.asOpt[EventAuthentication](eventAuthenticationReads);
            origin <- (json \ "origin").asOpt[EventOrigin](eventOriginReads)
          ) yield TenantDeleted(eventId, tenant, user, emittedAt = emissionDate, origin = origin, authentication = authentication)
        case "TENANT_CREATED" =>
          for (
            eventId <- (json \ "eventId").asOpt[Long];
            user <- (json \ "user").asOpt[String];
            tenant <- (json \ "tenant").asOpt[String];
            authentication <- json.asOpt[EventAuthentication](eventAuthenticationReads);
            origin <- (json \ "origin").asOpt[EventOrigin](eventOriginReads)
          ) yield TenantCreated(eventId, tenant, user, emittedAt = emissionDate, origin = origin, authentication = authentication)
      }
    }.fold(JsError("Failed to read event"): JsResult[IzanamiEvent])(evt => JsSuccess(evt))
  }

  def internalToExternalEvent(
                               event: IzanamiEvent,
                               context: RequestContext,
                               conditions: Boolean,
                               env: Env
                             ): Future[Option[JsObject]] = {
    val logger = env.logger
    val user = event.user
    implicit val executionContext: ExecutionContext = env.executionContext
    event match {
      case FeatureDeleted(_, id, _, _, _, _, _, _, _) => Future.successful(Some(deleteEventV2(id, user)))
      case f: ConditionFeatureEvent => {
        val maybeContextmap = f match {
          case FeatureCreated(_, _, _, _, _, map, _, _, _) => map
          case FeatureUpdated(_, _, _, _, _, map, _, _, _, _) => map
        }
        Feature.processMultipleStrategyResult(maybeContextmap.get, context, conditions, env).map {
          case Left(error) => {
            logger.error(s"Failed to write feature : ${error.message}")
            None
          }
          case Right(json) => {
            f match {
              case FeatureCreated(_, id, _, _, _, _, _, _, _) => Some(createEventV2(json, user) ++ Json.obj("id" -> id))
              case FeatureUpdated(_, id, _, _, _, _, previous, _, _, _) => {
                val maybePrevious = previous
                  .filter(_ => conditions)
                  .map(ctxs => writeStrategiesForEvent(ctxs))
                val finalJson = maybePrevious
                  .map(js => json ++ Json.obj("previousConditions" -> js))
                  .getOrElse(json)
                val finalEvent = updateEventV2(finalJson, user) ++ Json.obj("id" -> id)
                Some(finalEvent)
              }
            }
          }
        }
      }
      case _ => Future.successful(None)
    }
  }
}

class EventService(env: Env) {
  implicit val executionContext: ExecutionContext = env.executionContext
  implicit val materializer: Materializer = env.materializer
  val logger: Logger = env.logger
  val sourceMap: scala.collection.mutable.Map[String, SourceDescriptor] =
    scala.collection.mutable.Map()

  def emitEvent(channel: String, event: SourceIzanamiEvent)(implicit conn: SqlConnection): Future[Unit] = {
    val global = channel.equalsIgnoreCase("izanami") || event.isInstanceOf[SourceTenantDeleted]
    val now = OffsetDateTime.now()

    val futureEvt: Future[SourceIzanamiEvent] = event match {
      case event: SourceFeatureEvent =>
        env.datastores.projects
          .findProjectId(event.tenant, event.project, conn = Some(conn))
          .map(maybeId => maybeId.map(_.toString).orNull)
          .map(id => event.withProjectId(id))
      case e: SourceTenantDeleted => Future.successful(e)
      case e: SourceTenantCreated => Future.successful(e)
    }
    futureEvt
      .flatMap(evt => {
        val jsonEvent = Json.toJson(evt)(sourceEventWrites).as[JsObject] + ("emittedAt" -> JsString(now.toString))
        env.postgresql
          .queryOne(
            s"""
               |WITH generated_id AS (
               |    SELECT nextval('izanami.eventid') as next_id
               |)
               |INSERT INTO  ${if (global) "izanami.global_events" else "events"} (id, event, event_type, entity_id, emitted_at, origin, authentication, username)
               |SELECT gid.next_id as id, (jsonb_build_object('eventId', gid.next_id) || $$1::jsonb) as event, $$2::${
              if (
                global
              ) "izanami.GLOBAL_EVENT_TYPES"
              else "izanami.LOCAL_EVENT_TYPES"
            }, $$3, $$4, $$5, $$6, $$7
               |FROM generated_id gid
               |RETURNING id;
               |""".stripMargin,
            params = List(jsonEvent.vertxJsValue, event.dbEventType, event.entityId, now, ORIGIN_NAME_MAP(event.origin), EventAuthentication.authenticationName(event.authentication), event.user),
            conn = Some(conn),
            schemas = if (global) Set() else Set(channel)
          ) { r => {
            r.optLong("id").map(id => (id, jsonEvent))
          }
          }
      })
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
        case None => Future.successful(())
      }
  }


  def consume(channel: String): SourceDescriptor = {
    if (sourceMap.contains(channel)) {
      sourceMap(channel)
    } else {
      logger.info(s"Creating event source for $channel")
      val sharedKillSwitch = KillSwitches.shared(s"$channel-killswitch")
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
                  env.datastores.events.readEventFromDb(channel, id)
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
      val descriptor = SourceDescriptor(source = source, killswitch = sharedKillSwitch, pgSubscriber = subscriber)
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
