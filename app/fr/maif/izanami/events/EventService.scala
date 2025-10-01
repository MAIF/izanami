package fr.maif.izanami.events

import org.apache.pekko.NotUsed
import org.apache.pekko.stream.scaladsl.{BroadcastHub, Keep, Source}
import org.apache.pekko.stream.{KillSwitches, Materializer, SharedKillSwitch}
import fr.maif.izanami.env.Env
import fr.maif.izanami.env.pgimplicits.{EnhancedRow, VertxFutureEnhancer}
import fr.maif.izanami.events.EventAuthentication.eventAuthenticationReads
import fr.maif.izanami.events.EventOrigin.{eventOriginReads, ORIGIN_NAME_MAP}
import fr.maif.izanami.events.EventService.{sourceEventWrites, IZANAMI_CHANNEL}
import fr.maif.izanami.models.{Feature, FeatureWithOverloads, LightWeightFeature, RequestContext, Tenant}
import io.vertx.pgclient.pubsub.PgSubscriber
import io.vertx.sqlclient.SqlConnection
import play.api.libs.json.{
  Format,
  JsError,
  JsNumber,
  JsObject,
  JsResult,
  JsString,
  JsSuccess,
  JsValue,
  Json,
  Reads,
  Writes
}
import fr.maif.izanami.models.Feature.{
  featureWrite,
  lightweightFeatureRead,
  lightweightFeatureWrite,
  writeStrategiesForEvent
}
import fr.maif.izanami.models.FeatureWithOverloads.featureWithOverloadWrite
import fr.maif.izanami.utils.syntax.implicits.{BetterJsValue, BetterSyntax}
import fr.maif.izanami.v1.V2FeatureEvents.{createEventV2, deleteEventV2, updateEventV2}
import play.api.Logger

import java.time.{Instant, OffsetDateTime}
import java.util.UUID
import scala.concurrent.{ExecutionContext, Future}

sealed trait EventOrigin

object EventOrigin {
  val ORIGIN_NAME_MAP: Map[EventOrigin, String] = Map((ImportOrigin, "IMPORT"), (NormalOrigin, "NORMAL"))

  case object ImportOrigin extends EventOrigin;

  case object NormalOrigin extends EventOrigin;

  def eventOriginReads: Reads[EventOrigin] = json => {
    json
      .asOpt[String]
      .map(_.toUpperCase)
      .flatMap(upperName => {
        ORIGIN_NAME_MAP.find { case (_, name) => name == upperName }.map(_._1)
      })
      .map(origin => JsSuccess(origin))
      .getOrElse(JsError(s"Unknown origin $json"))
  }

  def eventOriginWrites: Writes[EventOrigin] = o => JsString(ORIGIN_NAME_MAP(o))
}

sealed trait EventAuthentication;

object EventAuthentication {
  def authenticationName(authentication: EventAuthentication): String = {
    authentication match {
      case TokenAuthentication(_)   => "TOKEN"
      case BackOfficeAuthentication => "BACKOFFICE"
    }
  }

  case class TokenAuthentication(tokenId: UUID) extends EventAuthentication

  case object BackOfficeAuthentication extends EventAuthentication

  def eventAuthenticationReads: Reads[EventAuthentication] = json => {
    (json \ "authentication")
      .asOpt[String]
      .map(_.toUpperCase)
      .flatMap {
        case "TOKEN"      => (json \ "token").asOpt[UUID].map(token => TokenAuthentication(token))
        case "BACKOFFICE" => Some(BackOfficeAuthentication)
      }
      .map(a => JsSuccess(a))
      .getOrElse(JsError(s"Unknown authentication $json"))
  }

  def eventAuthenticationWrites: Writes[EventAuthentication] = {
    case BackOfficeAuthentication   => Json.obj("authentication" -> "BACKOFFICE")
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
  override val entityId: String    = id

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
  override val entityId: String    = id

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
  override val entityId: String    = id

  override def withProjectId(projectId: String): SourceFeatureEvent = copy(projectId = Some(projectId))
}

case class SourceTenantDeleted(
    tenant: String,
    override val user: String,
    origin: EventOrigin,
    authentication: EventAuthentication
) extends SourceIzanamiEvent {
  override val dbEventType: String = "TENANT_DELETED"
  override val entityId: String    = tenant
}

case class SourceTenantCreated(
    tenant: String,
    override val user: String,
    origin: EventOrigin,
    authentication: EventAuthentication
) extends SourceIzanamiEvent {
  override val dbEventType: String = "TENANT_CREATED"
  override val entityId: String    = tenant
}

case class SourceProjectCreated(
    tenant: String,
    id: String,
    name: String,
    override val user: String,
    origin: EventOrigin,
    authentication: EventAuthentication
) extends SourceIzanamiEvent {
  override val dbEventType: String = "PROJECT_CREATED"
  override val entityId: String    = id
}

case class SourceProjectDeleted(
    tenant: String,
    id: String,
    name: String,
    override val user: String,
    origin: EventOrigin,
    authentication: EventAuthentication
) extends SourceIzanamiEvent {
  override val dbEventType: String = "PROJECT_DELETED"
  override val entityId: String    = id
}

case class PreviousProject(name: String)

case class SourceProjectUpdated(
    tenant: String,
    id: String,
    name: String,
    previous: PreviousProject,
    override val user: String,
    origin: EventOrigin,
    authentication: EventAuthentication
) extends SourceIzanamiEvent {
  override val dbEventType: String = "PROJECT_UPDATED"
  override val entityId: String    = id
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

case class ProjectCreated(
    override val eventId: Long,
    tenant: String,
    id: String,
    name: String,
    override val user: String,
    override val emittedAt: Option[Instant],
    origin: EventOrigin,
    authentication: EventAuthentication
) extends IzanamiEvent

case class ProjectDeleted(
    override val eventId: Long,
    tenant: String,
    id: String,
    name: String,
    override val user: String,
    override val emittedAt: Option[Instant],
    origin: EventOrigin,
    authentication: EventAuthentication
) extends IzanamiEvent

case class ProjectUpdated(
    override val eventId: Long,
    tenant: String,
    id: String,
    name: String,
    previous: PreviousProject,
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

  case object ProjectDeletedType extends FeatureEventType {
    override def name: String = "PROJECT_DELETED"
  }

  case object ProjectCreatedType extends FeatureEventType {
    override def name: String = "PROJECT_CREATED"
  }

  case object ProjectUpdatedType extends FeatureEventType {
    override def name: String = "PROJECT_UPDATED"
  }

  def parseEventType(typeStr: String): Option[FeatureEventType] = {
    Option(typeStr).map(_.toUpperCase).collect {
      case "FEATURE_UPDATED" => FeatureUpdatedType
      case "FEATURE_CREATED" => FeatureCreatedType
      case "FEATURE_DELETED" => FeatureDeletedType
      case "PROJECT_CREATED" => ProjectCreatedType
      case "PROJECT_DELETED" => ProjectDeletedType
      case "PROJECT_UPDATED" => ProjectUpdatedType
    }
  }

  implicit val sourceEventWrites: Writes[SourceIzanamiEvent]       = {
    case SourceProjectUpdated(tenant, id, name, previous, user, origin, authentication)                               => {
      val previousJson = Json.obj("name" -> previous.name)
      Json
        .obj(
          "id"       -> id,
          "name"     -> name,
          "tenant"   -> tenant,
          "user"     -> user,
          "type"     -> "PROJECT_UPDATED",
          "previous" -> previousJson,
          "origin"   -> EventOrigin.eventOriginWrites.writes(origin)
        ) ++ EventAuthentication.eventAuthenticationWrites.writes(authentication).as[JsObject]
    }
    case SourceProjectDeleted(tenant, id, name, user, origin, authentication)                                         => {
      Json
        .obj(
          "id"     -> id,
          "name"   -> name,
          "tenant" -> tenant,
          "user"   -> user,
          "type"   -> "PROJECT_DELETED",
          "origin" -> EventOrigin.eventOriginWrites.writes(origin)
        ) ++ EventAuthentication.eventAuthenticationWrites.writes(authentication).as[JsObject]
    }
    case SourceProjectCreated(tenant, id, name, user, origin, authentication)                                         => {
      Json
        .obj(
          "id"     -> id,
          "name"   -> name,
          "tenant" -> tenant,
          "user"   -> user,
          "type"   -> "PROJECT_CREATED",
          "origin" -> EventOrigin.eventOriginWrites.writes(origin)
        ) ++ EventAuthentication.eventAuthenticationWrites.writes(authentication).as[JsObject]
    }
    case SourceFeatureCreated(id, project, tenant, user, feature, projectId, origin, authentication)                  => {
      Json
        .obj(
          "id"      -> id,
          "project" -> project,
          "tenant"  -> tenant,
          "user"    -> user,
          "type"    -> "FEATURE_CREATED",
          "feature" -> Json.toJson(feature)(featureWithOverloadWrite),
          "origin"  -> EventOrigin.eventOriginWrites.writes(origin)
        )
        .applyOnWithOpt(projectId)((jsObj, id) =>
          jsObj + ("projectId" -> JsString(id))
        ) ++ EventAuthentication.eventAuthenticationWrites.writes(authentication).as[JsObject]
    }
    case SourceFeatureUpdated(id, project, tenant, user, previousFeature, feature, projectId, origin, authentication) =>
      Json
        .obj(
          "id"       -> id,
          "project"  -> project,
          "tenant"   -> tenant,
          "user"     -> user,
          "type"     -> "FEATURE_UPDATED",
          "feature"  -> Json.toJson(feature)(featureWithOverloadWrite),
          "previous" -> Json.toJson(previousFeature)(featureWithOverloadWrite),
          "origin"   -> EventOrigin.eventOriginWrites.writes(origin)
        )
        .applyOnWithOpt(projectId)((jsObj, id) =>
          jsObj + ("projectId" -> JsString(id))
        ) ++ EventAuthentication.eventAuthenticationWrites.writes(authentication).as[JsObject]
    case SourceFeatureDeleted(id, project, tenant, user, projectId, name, origin, authentication)                     =>
      Json
        .obj(
          "id"      -> id,
          "project" -> project,
          "tenant"  -> tenant,
          "user"    -> user,
          "type"    -> "FEATURE_DELETED",
          "name"    -> name,
          "origin"  -> EventOrigin.eventOriginWrites.writes(origin)
        )
        .applyOnWithOpt(projectId)((jsObj, id) =>
          jsObj + ("projectId" -> JsString(id))
        ) ++ EventAuthentication.eventAuthenticationWrites.writes(authentication).as[JsObject]
    case SourceTenantDeleted(tenant, user, origin, authentication)                                                    =>
      Json.obj(
        "tenant" -> tenant,
        "type"   -> "TENANT_DELETED",
        "user"   -> user,
        "origin" -> EventOrigin.eventOriginWrites.writes(origin)
      ) ++ EventAuthentication.eventAuthenticationWrites.writes(authentication).as[JsObject]
    case SourceTenantCreated(tenant, user, origin, authentication)                                                    =>
      Json.obj(
        "tenant" -> tenant,
        "type"   -> "TENANT_CREATED",
        "user"   -> user,
        "origin" -> EventOrigin.eventOriginWrites.writes(origin)
      ) ++ EventAuthentication.eventAuthenticationWrites.writes(authentication).as[JsObject]
  }
  implicit val lighweightFeatureWrites: Writes[LightWeightFeature] = lightweightFeatureWrite
  implicit val eventFormat: Format[IzanamiEvent]                   = new Format[IzanamiEvent] {
    override def writes(o: IzanamiEvent): JsValue = {
      o match {
        case ProjectUpdated(eventId, tenant, id, name, previous, user, emittedAt, origin, authentication)      => {
          val previousJson = Json.obj("name" -> previous.name)
          Json
            .obj(
              "eventId"  -> eventId,
              "id"       -> id,
              "name"     -> name,
              "tenant"   -> tenant,
              "user"     -> user,
              "previous" -> previousJson,
              "type"     -> "PROJECT_UPDATED",
              "origin"   -> EventOrigin.eventOriginWrites.writes(origin)
            )
            .applyOnWithOpt(emittedAt)((obj, instant) =>
              obj + ("emittedAt" -> JsString(instant.toString))
            ) ++ EventAuthentication.eventAuthenticationWrites.writes(authentication).as[JsObject]
        }
        case ProjectDeleted(eventId, tenant, id, name, user, emittedAt, origin, authentication)                =>
          Json
            .obj(
              "eventId" -> eventId,
              "id"      -> id,
              "name"    -> name,
              "tenant"  -> tenant,
              "user"    -> user,
              "type"    -> "PROJECT_DELETED",
              "origin"  -> EventOrigin.eventOriginWrites.writes(origin)
            )
            .applyOnWithOpt(emittedAt)((obj, instant) =>
              obj + ("emittedAt" -> JsString(instant.toString))
            ) ++ EventAuthentication.eventAuthenticationWrites.writes(authentication).as[JsObject]
        case ProjectCreated(eventId, tenant, id, name, user, emittedAt, origin, authentication)                =>
          Json
            .obj(
              "eventId" -> eventId,
              "id"      -> id,
              "name"    -> name,
              "tenant"  -> tenant,
              "user"    -> user,
              "type"    -> "PROJECT_CREATED",
              "origin"  -> EventOrigin.eventOriginWrites.writes(origin)
            )
            .applyOnWithOpt(emittedAt)((obj, instant) =>
              obj + ("emittedAt" -> JsString(instant.toString))
            ) ++ EventAuthentication.eventAuthenticationWrites.writes(authentication).as[JsObject]
        case FeatureCreated(eventId, id, project, tenant, user, conditions, emittedAt, origin, authentication) =>
          Json
            .obj(
              "eventId"    -> eventId,
              "id"         -> id,
              "project"    -> project,
              "tenant"     -> tenant,
              "user"       -> user,
              "conditions" -> conditions,
              "type"       -> "FEATURE_CREATED",
              "origin"     -> EventOrigin.eventOriginWrites.writes(origin)
            )
            .applyOnWithOpt(emittedAt)((obj, instant) =>
              obj + ("emittedAt" -> JsString(instant.toString))
            ) ++ EventAuthentication.eventAuthenticationWrites.writes(authentication).as[JsObject]
        case FeatureUpdated(
              eventId,
              id,
              project,
              tenant,
              user,
              conditions,
              previous,
              emittedAt,
              origin,
              authentication
            ) =>
          Json
            .obj(
              "eventId"            -> eventId,
              "id"                 -> id,
              "project"            -> project,
              "tenant"             -> tenant,
              "user"               -> user,
              "conditions"         -> conditions,
              "previousConditions" -> previous,
              "type"               -> "FEATURE_UPDATED",
              "origin"             -> EventOrigin.eventOriginWrites.writes(origin)
            )
            .applyOnWithOpt(emittedAt)((obj, instant) =>
              obj + ("emittedAt" -> JsString(instant.toString))
            ) ++ EventAuthentication.eventAuthenticationWrites.writes(authentication).as[JsObject]
        case FeatureDeleted(eventId, id, project, tenant, user, emittedAt, name, origin, authentication)       =>
          Json
            .obj(
              "eventId" -> eventId,
              "id"      -> id,
              "project" -> project,
              "tenant"  -> tenant,
              "user"    -> user,
              "type"    -> "FEATURE_DELETED",
              "name"    -> name,
              "origin"  -> EventOrigin.eventOriginWrites.writes(origin)
            )
            .applyOnWithOpt(emittedAt)((obj, instant) =>
              obj + ("emittedAt" -> JsString(instant.toString))
            ) ++ EventAuthentication.eventAuthenticationWrites.writes(authentication).as[JsObject]
        case TenantDeleted(eventId, tenant, user, emittedAt, origin, authentication)                           =>
          Json
            .obj(
              "eventId" -> eventId,
              "tenant"  -> tenant,
              "type"    -> "TENANT_DELETED",
              "user"    -> user,
              "origin"  -> EventOrigin.eventOriginWrites.writes(origin)
            )
            .applyOnWithOpt(emittedAt)((obj, instant) =>
              obj + ("emittedAt" -> JsString(instant.toString))
            ) ++ EventAuthentication.eventAuthenticationWrites.writes(authentication).as[JsObject]
        case TenantCreated(eventId, tenant, user, emittedAt, origin, authentication)                           =>
          Json
            .obj(
              "eventId" -> eventId,
              "tenant"  -> tenant,
              "type"    -> "TENANT_CREATED",
              "user"    -> user,
              "origin"  -> EventOrigin.eventOriginWrites.writes(origin)
            )
            .applyOnWithOpt(emittedAt)((obj, instant) =>
              obj + ("emittedAt" -> JsString(instant.toString))
            ) ++ EventAuthentication.eventAuthenticationWrites.writes(authentication).as[JsObject]
      }
    }

    override def reads(json: JsValue): JsResult[IzanamiEvent] = {
      val emissionDate = (json \ "emittedAt").asOpt[Instant]
      (json \ "type").asOpt[String].map(_.toUpperCase).flatMap {
        case eventType @ ("FEATURE_CREATED" | "FEATURE_UPDATED") => {
          (for (
            eventId        <- (json \ "eventId").asOpt[Long];
            id             <- (json \ "id").asOpt[String];
            tenant         <- (json \ "tenant").asOpt[String];
            user           <- (json \ "user").asOpt[String];
            project        <- (json \ "project").asOpt[String];
            conditions     <- (json \ "feature").asOpt[Map[String, LightWeightFeature]](Reads.map(lightweightFeatureRead));
            authentication <- json.asOpt[EventAuthentication](eventAuthenticationReads);
            origin         <- (json \ "origin").asOpt[EventOrigin](eventOriginReads)
          ) yield (eventId, id, tenant, project, user, conditions, origin, authentication)).map {
            case (eventId, id, tenant, project, user, conditions, origin, authentication) =>
              if (eventType == "FEATURE_CREATED") {
                FeatureCreated(
                  eventId,
                  id,
                  project,
                  tenant,
                  user,
                  Some(conditions),
                  emissionDate,
                  origin = origin,
                  authentication = authentication
                )
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
        case "FEATURE_DELETED"                                   =>
          for (
            eventId        <- (json \ "eventId").asOpt[Long];
            id             <- (json \ "id").asOpt[String];
            tenant         <- (json \ "tenant").asOpt[String];
            user           <- (json \ "user").asOpt[String];
            project        <- (json \ "project").asOpt[String];
            authentication <- json.asOpt[EventAuthentication](eventAuthenticationReads);
            origin         <- (json \ "origin").asOpt[EventOrigin](eventOriginReads)
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
        case "TENANT_DELETED"                                    =>
          for (
            eventId        <- (json \ "eventId").asOpt[Long];
            user           <- (json \ "user").asOpt[String];
            tenant         <- (json \ "tenant").asOpt[String];
            authentication <- json.asOpt[EventAuthentication](eventAuthenticationReads);
            origin         <- (json \ "origin").asOpt[EventOrigin](eventOriginReads)
          )
            yield TenantDeleted(
              eventId,
              tenant,
              user,
              emittedAt = emissionDate,
              origin = origin,
              authentication = authentication
            )
        case "TENANT_CREATED"                                    =>
          for (
            eventId        <- (json \ "eventId").asOpt[Long];
            user           <- (json \ "user").asOpt[String];
            tenant         <- (json \ "tenant").asOpt[String];
            authentication <- json.asOpt[EventAuthentication](eventAuthenticationReads);
            origin         <- (json \ "origin").asOpt[EventOrigin](eventOriginReads)
          )
            yield TenantCreated(
              eventId,
              tenant,
              user,
              emittedAt = emissionDate,
              origin = origin,
              authentication = authentication
            )
        case "PROJECT_CREATED"                                   =>
          for (
            eventId        <- (json \ "eventId").asOpt[Long];
            user           <- (json \ "user").asOpt[String];
            id             <- (json \ "id").asOpt[String];
            project        <- (json \ "name").asOpt[String];
            tenant         <- (json \ "tenant").asOpt[String];
            authentication <- json.asOpt[EventAuthentication](eventAuthenticationReads);
            origin         <- (json \ "origin").asOpt[EventOrigin](eventOriginReads)
          )
            yield ProjectCreated(
              eventId,
              tenant,
              id = id,
              user = user,
              emittedAt = emissionDate,
              origin = origin,
              authentication = authentication,
              name = project
            )
        case "PROJECT_DELETED"                                   =>
          for (
            eventId        <- (json \ "eventId").asOpt[Long];
            user           <- (json \ "user").asOpt[String];
            id             <- (json \ "id").asOpt[String];
            project        <- (json \ "name").asOpt[String];
            tenant         <- (json \ "tenant").asOpt[String];
            authentication <- json.asOpt[EventAuthentication](eventAuthenticationReads);
            origin         <- (json \ "origin").asOpt[EventOrigin](eventOriginReads)
          )
            yield ProjectDeleted(
              eventId,
              tenant,
              id = id,
              user = user,
              emittedAt = emissionDate,
              origin = origin,
              authentication = authentication,
              name = project
            )
        case "PROJECT_UPDATED"                                   => {
          val oldName = (json \ "previous" \ "name").asOpt[String]
          for (
            eventId        <- (json \ "eventId").asOpt[Long];
            user           <- (json \ "user").asOpt[String];
            id             <- (json \ "id").asOpt[String];
            project        <- (json \ "name").asOpt[String];
            tenant         <- (json \ "tenant").asOpt[String];
            authentication <- json.asOpt[EventAuthentication](eventAuthenticationReads);
            origin         <- (json \ "origin").asOpt[EventOrigin](eventOriginReads);
            oldName        <- (json \ "previous" \ "name").asOpt[String]
          )
            yield ProjectUpdated(
              eventId,
              tenant,
              id = id,
              user = user,
              emittedAt = emissionDate,
              origin = origin,
              authentication = authentication,
              name = project,
              previous = PreviousProject(oldName)
            )
        }
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
      case FeatureDeleted(_, id, _, _, _, _, _, _, _) => Future.successful(Some(deleteEventV2(id, user)))
      case f: ConditionFeatureEvent                   => {
        val maybeContextmap = f match {
          case FeatureCreated(_, _, _, _, _, map, _, _, _)    => map
          case FeatureUpdated(_, _, _, _, _, map, _, _, _, _) => map
        }
        Feature.processMultipleStrategyResult(maybeContextmap.get, context, conditions, env).map {
          case Left(error) => {
            logger.error(s"Failed to write feature : ${error.message}")
            None
          }
          case Right(json) => {
            f match {
              case FeatureCreated(_, id, _, _, _, _, _, _, _)           => Some(createEventV2(json, user) ++ Json.obj("id" -> id))
              case FeatureUpdated(_, id, _, _, _, _, previous, _, _, _) => {
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
      case _                                          => Future.successful(None)
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
    Tenant.isTenantValid(channel)
    val global = channel.equalsIgnoreCase("izanami") || event.isInstanceOf[SourceTenantDeleted]
    val now    = OffsetDateTime.now()

    val futureEvt: Future[SourceIzanamiEvent] = event match {
      case event: SourceFeatureEvent =>
        env.datastores.projects
          .findProjectId(event.tenant, event.project, conn = Some(conn))
          .map(maybeId => maybeId.map(_.toString).orNull)
          .map(id => event.withProjectId(id))
      case e                         => Future.successful(e)
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
               |INSERT INTO  ${if (global) "izanami.global_events" else s""""${channel}".events"""} (id, event, event_type, entity_id, emitted_at, origin, authentication, username)
               |SELECT gid.next_id as id, (jsonb_build_object('eventId', gid.next_id) || $$1::jsonb) as event, $$2::${if (
              global
            ) "izanami.GLOBAL_EVENT_TYPES"
            else "izanami.LOCAL_EVENT_TYPES"}, $$3, $$4, $$5, $$6, $$7
               |FROM generated_id gid
               |RETURNING id;
               |""".stripMargin,
            params = List(
              jsonEvent.vertxJsValue,
              event.dbEventType,
              event.entityId,
              now,
              ORIGIN_NAME_MAP(event.origin),
              EventAuthentication.authenticationName(event.authentication),
              event.user
            ),
            conn = Some(conn)
          ) { r =>
            {
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
        case None                  => Future.successful(())
      }
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

      lazy val subscriber = PgSubscriber
        .subscriber(env.postgresql.vertx, env.postgresql.connectOptions)
        .reconnectPolicy(retryCount => Math.min(30_000, retryCount * 3_000))
      subscriber
        .connect()
        .onComplete(ar => {
          if (ar.succeeded()) {
            subscriber
              .channel(channel)
              .handler(payload => {
                val eventId = (Json.parse(payload) \ "eventId").asOpt[Long]
                eventId.fold(logger.error(s"Failed to read event id : $payload"))(id => {
                  env.datastores.events
                    .readEventFromDb(channel, id)
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

  def killSource(tenant: String): Future[Unit] = {
    sourceMap
      .get(tenant)
      .map {
        case SourceDescriptor(_, killswitch, pgSuscriber) => {
          sourceMap.remove(tenant)
          logger.info(s"Closing SSE source for $tenant")
          killswitch.shutdown()
          pgSuscriber
            .close()
            .onComplete(_ => {
              logger.info(s"Done closing PG suscriber for $tenant")
            })
            .scala
            .map(_ => ())
        }
      }
      .getOrElse(Future.successful(()))
  }

  def killAllSources(excludeIzanamiChannel: Boolean = false): Future[Unit] = {
    Future
      .sequence(sourceMap.keys.filterNot(name => excludeIzanamiChannel && name == IZANAMI_CHANNEL).map(killSource))
      .map(_ => ())
  }
}
