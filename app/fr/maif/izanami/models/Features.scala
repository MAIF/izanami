package fr.maif.izanami.models

import fr.maif.izanami.env.Env
import fr.maif.izanami.errors.{InternalServerError, IzanamiError}
import fr.maif.izanami.models.Feature.{
  featureRead,
  featureWrite,
  lightweightFeatureRead,
  lightweightFeatureWrite,
  readFeature
}
import fr.maif.izanami.utils.syntax.implicits.{BetterJsValue, BetterSyntax}
import fr.maif.izanami.v1.{OldFeature, OldGlobalScriptFeature, OldScript}
import fr.maif.izanami.v1.OldFeature.{oldFeatureReads, oldFeatureWrites}
import fr.maif.izanami.wasm.{WasmConfig, WasmUtils}
import fr.maif.izanami.web.FeatureContextPath
import play.api.libs.json.MapWrites.mapWrites
import play.api.libs.json.Reads.{instantReads, mapReads}
import play.api.libs.json._
import play.api.mvc.QueryStringBindable

import java.time._
import java.time.format.{DateTimeFormatter, DateTimeParseException}
import java.util.UUID
import scala.concurrent.{ExecutionContext, Future}
import scala.util.hashing.MurmurHash3
import scala.util.matching.Regex
import scala.util.{Failure, Success, Try}

sealed trait PatchOperation
case class PatchPath(id: String, path: PatchPathField) {}
sealed trait PatchPathField

case object Replace extends PatchOperation
case object Remove  extends PatchOperation

case object Enabled        extends PatchPathField
case object ProjectFeature extends PatchPathField
case object TagsFeature    extends PatchPathField

case object RootFeature extends PatchPathField

sealed trait FeaturePatch {
  def op: PatchOperation
  def path: PatchPathField
  def id: String
}

case class EnabledFeaturePatch(value: Boolean, id: String) extends FeaturePatch {
  override def op: PatchOperation   = Replace
  override def path: PatchPathField = Enabled
}

case class ProjectFeaturePatch(value: String, id: String) extends FeaturePatch {
  override def op: PatchOperation   = Replace
  override def path: PatchPathField = ProjectFeature
}

case class TagsFeaturePatch(value: Set[String], id: String) extends FeaturePatch {
  override def op: PatchOperation   = Replace
  override def path: PatchPathField = TagsFeature
}

case class RemoveFeaturePatch(id: String) extends FeaturePatch {
  override def op: PatchOperation   = Remove
  override def path: PatchPathField = RootFeature
}

object FeaturePatch {
  val ENABLED_PATH_PATTERN: Regex = "^/(?<id>\\S+)/enabled$".r
  val PROJECT_PATH_PATTERN: Regex = "^/(?<id>\\S+)/project$".r
  val TAGS_PATH_PATTERN: Regex    = "^/(?<id>\\S+)/tags$".r
  val FEATURE_PATH_PATTERN: Regex = "^/(?<id>\\S+)$".r

  implicit val patchPathReads: Reads[PatchPath] = Reads[PatchPath] { json =>
    json
      .asOpt[String]
      .map {
        case ENABLED_PATH_PATTERN(id) =>
          PatchPath(id, Enabled)
        case PROJECT_PATH_PATTERN(id) => PatchPath(id, ProjectFeature)
        case TAGS_PATH_PATTERN(id)    => PatchPath(id, TagsFeature)
        case FEATURE_PATH_PATTERN(id) => PatchPath(id, RootFeature)
      }
      .map(path => JsSuccess(path))
      .getOrElse(JsError("Bad patch path"))
  }

  implicit val patchOpReads: Reads[PatchOperation] = Reads[PatchOperation] { json =>
    json
      .asOpt[String]
      .map {
        case "replace" => Replace
        case "remove"  => Remove
      }
      .map(op => JsSuccess(op))
      .getOrElse(JsError("Bad patch operation"))
  }

  implicit val featurePatchReads: Reads[FeaturePatch] = Reads[FeaturePatch] { json =>
    val maybeResult =
      for (
        op   <- (json \ "op").asOpt[PatchOperation];
        path <- (json \ "path").asOpt[PatchPath]
      ) yield (op, path) match {
        case (Replace, PatchPath(id, Enabled))        => (json \ "value").asOpt[Boolean].map(b => EnabledFeaturePatch(b, id))
        case (Replace, PatchPath(id, ProjectFeature)) =>
          (json \ "value").asOpt[String].map(b => ProjectFeaturePatch(b, id))
        case (Replace, PatchPath(id, TagsFeature))    =>
          (json \ "value").asOpt[Set[String]].map(b => TagsFeaturePatch(b, id))
        case (Remove, PatchPath(id, RootFeature))     => Some(RemoveFeaturePatch(id))
        case (_, _)                                   => None
      }
    maybeResult.flatten.map(r => JsSuccess(r)).getOrElse(JsError("Failed to read patch operation"))
  }
}

case class FeatureWithOverloads(featureMap: Map[String, LightWeightFeature]) {
  def id: String                                                                               = featureMap("").id
  def baseFeature(): LightWeightFeature                                                        = featureMap("")
  def setProject(project: String): FeatureWithOverloads                                        =
    copy(featureMap = featureMap.view.mapValues(f => f.withProject(project)).toMap)
  def setEnabling(enabling: Boolean): FeatureWithOverloads                                     = setEnablingForContext(enabling, "")
  def setFeature(feature: LightWeightFeature): FeatureWithOverloads                            = setFeatureForContext(feature, "")
  def setEnablingForContext(enabling: Boolean, context: String): FeatureWithOverloads          = featureMap
    .get(context)
    .map(f => f.withEnabled(enabling))
    .map(f => copy(featureMap + (context -> f)))
    .getOrElse(this)
  def setFeatureForContext(feature: LightWeightFeature, context: String): FeatureWithOverloads =
    copy(featureMap = featureMap + (context -> feature))
  def removeOverload(context: String): FeatureWithOverloads                                    = copy(featureMap = featureMap - context)
  def updateConditionsForContext(
      context: String,
      contextualFeatureStrategy: LightweightContextualStrategy
  ): FeatureWithOverloads                                                                      = featureMap
    .get(context)
    .orElse(featureMap.get(""))
    .map(f => f.withStrategy(strategy = contextualFeatureStrategy))
    .map(f => copy(featureMap = featureMap + (context -> f)))
    .getOrElse(this)
}

object FeatureWithOverloads {
  def apply(feature: LightWeightFeature): FeatureWithOverloads = FeatureWithOverloads(Map("" -> feature))
  val featureWithOverloadWrite: Writes[FeatureWithOverloads]   = obj =>
    Json.toJson(obj.featureMap)(Writes.genericMapWrites(lightweightFeatureWrite))

  val featureWithOverloadRead: Reads[FeatureWithOverloads] = Reads[FeatureWithOverloads] { json =>
    json
      .asOpt[Map[String, LightWeightFeature]](Reads.map(lightweightFeatureRead))
      .map(m => FeatureWithOverloads(m))
      .fold(JsError("Failed to read FeatureWithOverloads"): JsResult[FeatureWithOverloads])(f => JsSuccess(f))
  }
}

case class FeaturePeriod(
    begin: Option[Instant] = None,
    end: Option[Instant] = None,
    hourPeriods: Set[HourPeriod] = Set(),
    days: Option[ActivationDayOfWeeks] = None,
    timezone: ZoneId = ZoneId.systemDefault()
) {
  def active(context: RequestContext): Boolean = {
    val now = context.now
    begin.forall(i => i.isBefore(now)) &&
    end.forall(i => i.isAfter(now)) &&
    (hourPeriods.isEmpty || hourPeriods.exists(_.active(context, timezone))) &&
    days.forall(_.active(context, timezone))
  }
  def empty: Boolean = {
    begin.isEmpty && end.isEmpty && hourPeriods.isEmpty && days.isEmpty
  }
}

sealed trait LegacyCompatibleCondition {
  def active(requestContext: RequestContext, featureId: String): Boolean
}
case class DateRangeActivationCondition(begin: Option[Instant] = None, end: Option[Instant] = None, timezone: ZoneId)
    extends LegacyCompatibleCondition  {
  def active(context: RequestContext, featureId: String): Boolean = {
    val now = context.now
    begin.forall(i => i.atZone(timezone).toInstant.isBefore(now)) && end.forall(i =>
      i.atZone(timezone).toInstant.isAfter(now)
    )
  }
}

case class ZonedHourPeriod(hourPeriod: HourPeriod, timezone: ZoneId) extends LegacyCompatibleCondition {
  def active(context: RequestContext, featureId: String): Boolean = {
    val zonedStart = LocalDateTime
      .of(LocalDate.now(), hourPeriod.startTime)
      .atZone(timezone)
      .toInstant

    val zonedEnd = LocalDateTime
      .of(LocalDate.now(), hourPeriod.endTime)
      .atZone(timezone)
      .toInstant

    zonedStart.isBefore(context.now) && zonedEnd.isAfter(context.now)
  }
}

case class HourPeriod(startTime: LocalTime, endTime: LocalTime) {
  def active(context: RequestContext, timezone: ZoneId): Boolean = {
    val zonedStart = LocalDateTime
      .of(LocalDate.now(), startTime)
      .atZone(timezone)
      .toInstant

    val zonedEnd = LocalDateTime
      .of(LocalDate.now(), endTime)
      .atZone(timezone)
      .toInstant

    zonedStart.isBefore(context.now) && zonedEnd.isAfter(context.now)
  }
}

case class ActivationDayOfWeeks(days: Set[DayOfWeek]) {
  def active(context: RequestContext, timezone: ZoneId): Boolean =
    days.contains(context.now.atZone(timezone).getDayOfWeek)
}

case class RequestContext(
    tenant: String,
    user: String,
    context: FeatureContextPath = FeatureContextPath(),
    now: Instant = Instant.now(),
    data: JsObject = Json.obj()
) {
  def wasmJson: JsValue       = Json.obj("tenant" -> tenant, "id" -> user, "now" -> now.toEpochMilli, "data" -> data)
  def contextAsString: String = context.elements.mkString("_")
}
sealed trait ActivationRule extends LegacyCompatibleCondition {
  override def active(context: RequestContext, featureId: String): Boolean
}
object All                                 extends ActivationRule {
  override def active(context: RequestContext, featureId: String): Boolean = true
}
case class UserList(users: Set[String])    extends ActivationRule {
  override def active(context: RequestContext, featureId: String): Boolean = users.contains(context.user)
}
case class UserPercentage(percentage: Int) extends ActivationRule {
  override def active(context: RequestContext, featureId: String): Boolean =
    Feature.isPercentageFeatureActive(s"${featureId}-${context.user}", percentage)
}

case class ActivationCondition(period: FeaturePeriod = FeaturePeriod(), rule: ActivationRule = All) {
  def active(requestContext: RequestContext, featureId: String): Boolean =
    period.active(requestContext) && rule.active(requestContext, featureId)
}

sealed trait CompleteFeature extends AbstractFeature {
  def active(requestContext: RequestContext, env: Env): Future[Either[IzanamiError, Boolean]]
  override def withProject(project: String): CompleteFeature
  override def withId(id: String): CompleteFeature
  override def withName(name: String): CompleteFeature
  override def withEnabled(enable: Boolean): CompleteFeature

  def toLightWeightFeature: LightWeightFeature = {
    this match {
      case CompleteWasmFeature(id, name, project, enabled, wasmConfig, tags, metadata, description) =>
        LightWeightWasmFeature(
          id = id,
          name = name,
          project = project,
          enabled = enabled,
          wasmConfigName = wasmConfig.name,
          tags = tags,
          metadata = metadata,
          description = description
        )
      case f: LightWeightFeature                                                                    => f
    }
  }
}

sealed trait LightWeightFeature extends AbstractFeature {
  override def withProject(project: String): LightWeightFeature
  override def withId(id: String): LightWeightFeature
  override def withName(name: String): LightWeightFeature
  override def withEnabled(enable: Boolean): LightWeightFeature

  def toCompleteFeature(tenant: String, env: Env): Future[Either[IzanamiError, CompleteFeature]] = {
    this match {
      case f: LightWeightWasmFeature => {
        env.datastores.features
          .readWasmScript(tenant, f.wasmConfigName)
          .map {
            case Some(wasmConfig) =>
              Right(
                CompleteWasmFeature(
                  id = id,
                  name = name,
                  project = project,
                  enabled = enabled,
                  wasmConfig = wasmConfig,
                  tags = tags,
                  metadata = metadata,
                  description = description
                )
              )
            case None             => Left(InternalServerError(s"Failed to find wasm script config ${f.wasmConfigName}"))
          }(env.executionContext)
      }
      case feat: CompleteFeature     => Right(feat).future
    }
  }
  def withStrategy(strategy: LightweightContextualStrategy): LightWeightFeature = {
    strategy match {
      case ClassicalFeatureStrategy(enabled, conditions, _)       =>
        Feature(
          id = id,
          name = name,
          description = description,
          project = project,
          enabled = enabled,
          tags = tags,
          metadata = metadata,
          conditions = conditions
        )
      case LightWeightWasmFeatureStrategy(enabled, wasmConfig, _) =>
        LightWeightWasmFeature(
          id = id,
          name = name,
          description = description,
          project = project,
          enabled = enabled,
          tags = tags,
          metadata = metadata,
          wasmConfigName = wasmConfig
        )
    }
  }

  def hasSameActivationStrategy(another: AbstractFeature): Boolean = (this, another) match {
    case (f1, f2) if f1.name != f2.name                           => false
    case (f1, f2) if f1.enabled != f2.enabled                     => false
    case (f1: Feature, f2: Feature)                               => f1.conditions == f2.conditions
    case (f1: LightWeightWasmFeature, f2: LightWeightWasmFeature) => f1.wasmConfigName == f2.wasmConfigName
    case (f1: SingleConditionFeature, f2: SingleConditionFeature) => f1.condition == f2.condition
    case _                                                        => false
  }
}

sealed trait AbstractFeature {
  val id: String
  val name: String
  val description: String
  val project: String
  val enabled: Boolean
  val tags: Set[String]  = Set()
  val metadata: JsObject = JsObject.empty

  def withProject(project: String): AbstractFeature
  def withId(id: String): AbstractFeature
  def withName(name: String): AbstractFeature
  def withEnabled(enable: Boolean): AbstractFeature
}

case class SingleConditionFeature(
    override val id: String,
    override val name: String,
    override val project: String,
    condition: LegacyCompatibleCondition,
    override val enabled: Boolean,
    override val tags: Set[String] = Set(),
    override val metadata: JsObject = JsObject.empty,
    override val description: String
) extends CompleteFeature
    with LightWeightFeature {

  override def withEnabled(enabled: Boolean): SingleConditionFeature = copy(enabled = enabled)

  def toModernFeature: Feature = {
    val activationCondition = this.condition match {
      case DateRangeActivationCondition(begin, end, timezone)        =>
        ActivationCondition(period = FeaturePeriod(begin = begin, end = end, timezone = timezone))
      case ZonedHourPeriod(HourPeriod(startTime, endTime), timezone) =>
        ActivationCondition(period =
          FeaturePeriod(hourPeriods = Set(HourPeriod(startTime = startTime, endTime = endTime)), timezone = timezone)
        )
      case rule: ActivationRule                                      => ActivationCondition(rule = rule)
    }

    Feature(
      id = id,
      name = name,
      project = project,
      conditions = Set(activationCondition),
      enabled = enabled,
      tags = tags,
      metadata = metadata,
      description = description
    )
  }
  override def active(requestContext: RequestContext, env: Env): Future[Either[IzanamiError, Boolean]] = {
    if (enabled) Future.successful(Right(condition.active(requestContext, id))) else Future.successful(Right(false))
  }

  override def withProject(project: String): SingleConditionFeature = copy(project = project)

  override def withId(id: String): SingleConditionFeature = copy(id = id)

  override def withName(name: String): SingleConditionFeature = copy(name = name)
}

case class Feature(
    override val id: String,
    override val name: String,
    override val project: String,
    conditions: Set[ActivationCondition],
    override val enabled: Boolean,
    override val tags: Set[String] = Set(),
    override val metadata: JsObject = JsObject.empty,
    override val description: String
) extends LightWeightFeature
    with CompleteFeature {
  override def withEnabled(enabled: Boolean): Feature = copy(enabled = enabled)
  override def active(requestContext: RequestContext, env: Env): Future[Either[IzanamiError, Boolean]] = {
    implicit val ec: ExecutionContext = env.executionContext
    Future(Right { enabled && (conditions.isEmpty || conditions.exists(cond => cond.active(requestContext, name))) })
  }

  override def withProject(project: String): Feature = copy(project = project)
  override def withId(id: String): Feature           = copy(id = id)
  override def withName(name: String): Feature       = copy(name = name)
}

case class LightWeightWasmFeature(
    override val id: String,
    override val name: String,
    override val project: String,
    override val enabled: Boolean,
    wasmConfigName: String,
    override val tags: Set[String] = Set(),
    override val metadata: JsObject = JsObject.empty,
    override val description: String
) extends LightWeightFeature {
  override def withEnabled(enabled: Boolean): LightWeightWasmFeature = copy(enabled = enabled)
  def toCompleteWasmFeature(tenant: String, env: Env): Future[Either[IzanamiError, CompleteWasmFeature]] = {
    env.datastores.features
      .readWasmScript(tenant, wasmConfigName)
      .map {
        case Some(wasmConfig) =>
          Right(
            CompleteWasmFeature(
              id = id,
              name = name,
              project = project,
              enabled = enabled,
              wasmConfig = wasmConfig,
              tags = tags,
              metadata = metadata,
              description = description
            )
          )
        case None             => Left(InternalServerError(s"Wasm script $wasmConfigName not found"))
      }(env.executionContext)
  }
  override def withProject(project: String): LightWeightWasmFeature  = copy(project = project)
  override def withId(id: String): LightWeightWasmFeature            = copy(id = id)
  override def withName(name: String): LightWeightWasmFeature        = copy(name = name)
}

case class CompleteWasmFeature(
    override val id: String,
    override val name: String,
    override val project: String,
    override val enabled: Boolean,
    wasmConfig: WasmConfig,
    override val tags: Set[String] = Set(),
    override val metadata: JsObject = JsObject.empty,
    override val description: String
) extends CompleteFeature {
  override def withEnabled(enabled: Boolean): CompleteWasmFeature = copy(enabled = enabled)
  override def active(requestContext: RequestContext, env: Env): Future[Either[IzanamiError, Boolean]] = {
    implicit val ec: ExecutionContext = env.executionContext
    if (!enabled) {
      Future { Right(false) }
    } else {
      WasmUtils.handle(wasmConfig, requestContext)(ec, env)
    }
  }
  override def withProject(project: String): CompleteWasmFeature  = copy(project = project)
  override def withId(id: String): CompleteWasmFeature            = copy(id = id)
  override def withName(name: String): CompleteWasmFeature        = copy(name = name)
}

object LightWeightWasmFeature {
  val lightWeightFormat: Format[LightWeightWasmFeature] = new Format[LightWeightWasmFeature] {
    override def writes(o: LightWeightWasmFeature): JsValue             = Json.obj(
      "id"          -> o.id,
      "name"        -> o.name,
      "enabled"     -> o.enabled,
      "project"     -> o.project,
      "config"      -> o.wasmConfigName,
      "metadata"    -> o.metadata,
      "description" -> o.description,
      "tags"        -> JsArray(o.tags.map(JsString.apply).toSeq)
    )
    override def reads(json: JsValue): JsResult[LightWeightWasmFeature] = Try {
      LightWeightWasmFeature(
        id = (json \ "id").as[String],
        name = (json \ "name").as[String],
        project = (json \ "project").as[String],
        enabled = (json \ "enabled").as[Boolean],
        wasmConfigName = (json \ "config").as[String],
        metadata = (json \ "metadata").asOpt[JsObject].getOrElse(Json.obj()),
        tags = (json \ "tags").asOpt[Set[String]].getOrElse(Set.empty[String]),
        description = (json \ "description").asOpt[String].getOrElse("")
      )
    } match {
      case Failure(ex)    => JsError(ex.getMessage)
      case Success(value) => JsSuccess(value)
    }
  }

  val completeFormat: Format[CompleteWasmFeature] = new Format[CompleteWasmFeature] {
    override def writes(o: CompleteWasmFeature): JsValue             = Json.obj(
      "id"          -> o.id,
      "name"        -> o.name,
      "enabled"     -> o.enabled,
      "project"     -> o.project,
      "config"      -> o.wasmConfig.json,
      "metadata"    -> o.metadata,
      "description" -> o.description,
      "tags"        -> JsArray(o.tags.map(JsString.apply).toSeq)
    )
    override def reads(json: JsValue): JsResult[CompleteWasmFeature] = Try {
      CompleteWasmFeature(
        id = (json \ "id").as[String],
        name = (json \ "name").as[String],
        project = (json \ "project").as[String],
        enabled = (json \ "enabled").as[Boolean],
        wasmConfig = (json \ "config").as(WasmConfig.format),
        metadata = (json \ "metadata").asOpt[JsObject].getOrElse(Json.obj()),
        tags = (json \ "tags").asOpt[Set[String]].getOrElse(Set.empty[String]),
        description = (json \ "description").asOpt[String].getOrElse("")
      )
    } match {
      case Failure(ex)    => JsError(ex.getMessage)
      case Success(value) => JsSuccess(value)
    }
  }
}

case class FeatureTagRequest(
    oneTagIn: Set[String] = Set(),
    allTagsIn: Set[String] = Set()
) {
  def isEmpty: Boolean  = oneTagIn.isEmpty && allTagsIn.isEmpty
  def tags: Set[String] = oneTagIn ++ allTagsIn
}

object FeatureTagRequest {
  def processInputSeqString(input: Seq[String]): Set[String] = {
    input.filter(str => str.nonEmpty).flatMap(str => str.split(",")).toSet
  }

  implicit def queryStringBindable(implicit
      seqBinder: QueryStringBindable[Seq[String]]
  ): QueryStringBindable[FeatureTagRequest] =
    new QueryStringBindable[FeatureTagRequest] {
      override def bind(key: String, params: Map[String, Seq[String]]): Option[Either[String, FeatureTagRequest]] = {
        for {
          eitherAllTagsIn <- seqBinder.bind("allTagsIn", params)
          eitherOneTagIn  <- seqBinder.bind("oneTagIn", params)
        } yield {
          Right(
            FeatureTagRequest(
              allTagsIn = processInputSeqString(eitherAllTagsIn.getOrElse(Seq())),
              oneTagIn = processInputSeqString(eitherOneTagIn.getOrElse(Seq()))
            )
          )
        }
      }
      override def unbind(key: String, request: FeatureTagRequest): String = {
        val params = request.allTagsIn
          .map(t => s"allTagsIn=${t}")
          .concat(request.oneTagIn.map(t => s"oneTagIn=${t}"))
        if (params.isEmpty)
          ""
        else
          "?" + params.mkString("&")
      }
    }
}

case class FeatureRequest(
    projects: Set[UUID] = Set(),
    features: Set[String] = Set(),
    oneTagIn: Set[UUID] = Set(),
    allTagsIn: Set[UUID] = Set(),
    noTagIn: Set[UUID] = Set(),
    context: Seq[String] = Seq()
) {
  def isEmpty: Boolean =
    projects.isEmpty && oneTagIn.isEmpty && allTagsIn.isEmpty && noTagIn.isEmpty && features.isEmpty
}

object FeatureRequest {

  def processInputSeqUUID(input: Seq[String]): Set[UUID] = {
    input.filter(str => str.nonEmpty).flatMap(str => str.split(",")).map(UUID.fromString).toSet
  }

  def processInputSeqString(input: Seq[String]): Set[String] = {
    input.filter(str => str.nonEmpty).flatMap(str => str.split(",")).toSet
  }

  implicit def queryStringBindable(implicit
      seqBinder: QueryStringBindable[Seq[String]]
  ): QueryStringBindable[FeatureRequest] =
    new QueryStringBindable[FeatureRequest] {
      override def bind(key: String, params: Map[String, Seq[String]]): Option[Either[String, FeatureRequest]] = {
        for {
          eitherProjects  <- seqBinder.bind("projects", params)
          eitherFeatures  <- seqBinder.bind("features", params)
          eitherAllTagsIn <- seqBinder.bind("allTagsIn", params)
          eitherOneTagIn  <- seqBinder.bind("oneTagIn", params)
          eitherNoTagIn   <- seqBinder.bind("noTagIn", params)
          eitherContext   <- seqBinder.bind("context", params)
        } yield {
          Right(
            FeatureRequest(
              features = processInputSeqString(eitherFeatures.getOrElse(Seq())),
              projects = processInputSeqUUID(eitherProjects.getOrElse(Seq())),
              allTagsIn = processInputSeqUUID(eitherAllTagsIn.getOrElse(Seq())),
              oneTagIn = processInputSeqUUID(eitherOneTagIn.getOrElse(Seq())),
              noTagIn = processInputSeqUUID(eitherNoTagIn.getOrElse(Seq())),
              context = (eitherContext
                .map(seq => seq.filter(str => str.nonEmpty).flatMap(str => str.split("/").filter(s => s.nonEmpty)))
                .getOrElse(Seq()))
            )
          )
        }
      }
      override def unbind(key: String, request: FeatureRequest): String = {
        val params = request.projects
          .map(p => s"projects=${p}")
          .concat(request.allTagsIn.map(t => s"allTagsIn=${t}"))
          .concat(request.oneTagIn.map(t => s"oneTagIn=${t}"))
        if (params.isEmpty)
          ""
        else
          "?" + params.mkString("&")
      }
    }
}

object Feature {

  def isPercentageFeatureActive(source: String, percentage: Int): Boolean = {
    val hash = (Math.abs(MurmurHash3.bytesHash(source.getBytes, 42)) % 100) + 1
    hash <= percentage
  }

  def writeStrategiesForEvent(strategyByCtx: Map[String, LightWeightFeature]): JsObject = {
    Json
      .toJson(strategyByCtx.map {
        case (ctx, feature) => {
          (
            ctx.replace("_", "/"),
            (feature match {
              case lf: SingleConditionFeature =>
                Feature.featureWrite
                  .writes(lf.toModernFeature)
                  .as[JsObject] - "tags" - "name" - "description" - "id" - "project"
              case f                          => Feature.featureWrite.writes(f).as[JsObject]
            }) - "metadata" - "tags" - "name" - "description" - "id" - "project"
          )
        }
      })
      .as[JsObject]
  }

  def processMultipleStrategyResult(
      strategyByCtx: Map[String, LightWeightFeature],
      requestContext: RequestContext,
      conditions: Boolean,
      env: Env
  ): Future[Either[IzanamiError, JsObject]] = {
    val context       = requestContext.context.elements.mkString("_")
    val strategyToUse = if (context.isBlank) {
      strategyByCtx("")
    } else {
      strategyByCtx
        .filter { case (ctx, f) => context.startsWith(ctx) }
        .toSeq
        .sortWith {
          case ((c1, _), (c2, _)) if c1.length < c2.length => false
          case _                                           => true
        }
        .headOption
        .map(_._2)
        .getOrElse(strategyByCtx(""))
    }

    val jsonStrategies = writeStrategiesForEvent(strategyByCtx)

    strategyToUse
      .toCompleteFeature(tenant = requestContext.tenant, env = env)
      .flatMap {
        case Left(value)          => Left(value).future
        case Right(strategyToUse) => {
          writeFeatureForCheck(strategyToUse, requestContext, env = env)
            .map {
              case Left(err)                 => Left(err)
              case Right(json) if conditions => Right(json ++ Json.obj("conditions" -> jsonStrategies))
              case Right(json)               => Right(json)
            }(env.executionContext)
        }
      }(env.executionContext)

  }

  def writeFeatureForCheck(
      feature: CompleteFeature,
      context: RequestContext,
      env: Env
  ): Future[Either[IzanamiError, JsObject]] = {
    feature
      .active(context, env)
      .map(either => {
        either.map(active => {
          Json.obj(
            "name"    -> feature.name,
            "active"  -> active,
            "project" -> feature.project
          )
        })
      })(env.executionContext)
  }

  def writeFeatureForCheckInLegacyFormat(
      feature: CompleteFeature,
      context: RequestContext,
      env: Env
  ): Future[Either[IzanamiError, Option[JsObject]]] = {
    feature
      .active(context, env)
      .map {
        case Left(error)   => Left(error)
        case Right(active) => Right(Some(writeFeatureInLegacyFormat(feature) ++ Json.obj("active" -> active)))
      }(env.executionContext)
  }

  def writeFeatureInLegacyFormat(feature: AbstractFeature): JsObject = {
    feature match {
      case s: SingleConditionFeature =>
        Json.toJson(OldFeature.fromModernFeature(s))(OldFeature.oldFeatureWrites).as[JsObject]
      // Transforming modern feature to script feature is a little hacky, however it's a format that legacy client
      // can understand, moreover due to the script nature of the feature, there won't be cache client side, which
      // is what we want since legacy client can't evaluate modern feeature locally
      case f: Feature                =>
        Json
          .toJson(
            OldGlobalScriptFeature(
              id = f.id,
              name = f.name,
              enabled = f.enabled,
              description = Option(f.description),
              tags = f.tags,
              ref = "fake-script-feature"
            )
          )(OldFeature.oldGlobalScriptWrites)
          .as[JsObject]
      case w: LightWeightWasmFeature =>
        Json.toJson(OldFeature.fromScriptFeature(w))(OldFeature.oldFeatureWrites).as[JsObject]
      case w: CompleteWasmFeature    =>
        Json.toJson(OldFeature.fromScriptFeature(w))(OldFeature.oldFeatureWrites).as[JsObject]
    }
  }

  implicit val offsetTimeWrites: Writes[OffsetTime] = Writes[OffsetTime] { time =>
    Json.toJson(time.format(DateTimeFormatter.ISO_OFFSET_TIME))
  }

  implicit val offsetTimeReads: Reads[OffsetTime]   = Reads[OffsetTime] { json =>
    try {
      JsSuccess(OffsetTime.parse(json.as[String], DateTimeFormatter.ISO_OFFSET_TIME))
    } catch {
      case e: DateTimeParseException => JsError("Invalid time format")
    }
  }
  val hourFormatter: DateTimeFormatter              = DateTimeFormatter.ofPattern("HH:mm:ss")
  implicit val hourPeriodWrites: Writes[HourPeriod] = Writes[HourPeriod] { p =>
    Json.obj(
      "startTime" -> p.startTime.format(hourFormatter),
      "endTime"   -> p.endTime.format(hourFormatter)
    )
  }

  implicit val hourPeriodReads: Reads[HourPeriod] = Reads[HourPeriod] { json =>
    (for (
      start <- (json \ "startTime").asOpt[LocalTime];
      end   <- (json \ "endTime").asOpt[LocalTime]
    )
      yield JsSuccess(
        HourPeriod(
          startTime = start,
          endTime = end
        )
      )).getOrElse(JsError("Failed to parse hour period"))

  }

  implicit val dayOfWeekWrites: Writes[DayOfWeek] = Writes[DayOfWeek] { d =>
    Json.toJson(d.name)
  }

  implicit val dayOfWeekReads: Reads[DayOfWeek] = Reads[DayOfWeek] { json =>
    json.asOpt[String].map(DayOfWeek.valueOf).map(JsSuccess(_)).getOrElse(JsError(s"Incorrect day of week : ${json}"))
  }

  implicit val activationDayOfWeekWrites: Writes[ActivationDayOfWeeks] = Writes[ActivationDayOfWeeks] { a =>
    Json.obj(
      "days" -> a.days
    )
  }

  implicit val activationDayOfWeekReads: Reads[ActivationDayOfWeeks] = Reads[ActivationDayOfWeeks] { json =>
    (for (days <- (json \ "days").asOpt[Set[DayOfWeek]]) yield JsSuccess(ActivationDayOfWeeks(days = days)))
      .getOrElse(JsError("Failed to parse day of week period"))
  }

  implicit val featurePeriodeWrite: Writes[FeaturePeriod] = Writes[FeaturePeriod] { period =>
    if (period.empty) {
      JsNull
    } else {
      Json.obj(
        "begin"          -> period.begin,
        "end"            -> period.end,
        "hourPeriods"    -> period.hourPeriods,
        "activationDays" -> period.days,
        "timezone"       -> period.timezone
      )
    }
  }

  implicit val activationRuleWrite: Writes[ActivationRule] = Writes[ActivationRule] {
    case All                        =>
      Json.obj(
      )
    case UserList(users)            =>
      Json.obj(
        "users" -> users
      )
    case UserPercentage(percentage) =>
      Json.obj(
        "percentage" -> percentage
      )
  }

  implicit val activationConditionWrite: Writes[ActivationCondition] = Writes[ActivationCondition] { cond =>
    Json.obj(
      "period" -> cond.period,
      "rule"   -> cond.rule
    )
  }

  val lightweightFeatureRead: Reads[LightWeightFeature] = json => {
    readFeature(json).flatMap {
      case feature: LightWeightFeature => JsSuccess(feature)
      case _                           => JsError("CompleteFeature can't be read as LightWeightFeature")
    }
  }

  val featureRead: Reads[AbstractFeature] = json => {
    readFeature(json)
  }

  val lightweightFeatureWrite: Writes[LightWeightFeature] = f => featureWrite.writes(f)

  val featureWrite: Writes[AbstractFeature] = Writes[AbstractFeature] {
    case Feature(id, name, project, conditions, enabled, tags, metadata, description)                => {
      Json.obj(
        "name"        -> name,
        "enabled"     -> enabled,
        "metadata"    -> metadata,
        "tags"        -> tags,
        "conditions"  -> conditions,
        "id"          -> id,
        "project"     -> project,
        "description" -> description
      )
    }
    case LightWeightWasmFeature(id, name, project, enabled, wasmConfig, tags, metadata, description) => {
      Json.obj(
        "name"        -> name,
        "enabled"     -> enabled,
        "metadata"    -> metadata,
        "tags"        -> tags,
        "wasmConfig"  -> wasmConfig,
        "id"          -> id,
        "project"     -> project,
        "description" -> description
      )
    }
    case CompleteWasmFeature(id, name, project, enabled, wasmConfig, tags, metadata, description)    => {
      Json.obj(
        "name"        -> name,
        "enabled"     -> enabled,
        "metadata"    -> metadata,
        "tags"        -> tags,
        "wasmConfig"  -> WasmConfig.format.writes(wasmConfig),
        "id"          -> id,
        "project"     -> project,
        "description" -> description
      )
    }
    case SingleConditionFeature(id, name, project, condition, enabled, tags, metadata, description)  => {
      Json.obj(
        "name"        -> name,
        "enabled"     -> enabled,
        "metadata"    -> metadata,
        "tags"        -> tags,
        "conditions"  -> condition,
        "id"          -> id,
        "project"     -> project,
        "description" -> description
      )
    }
  }

  implicit val legacyCompatibleConditionWrites: Writes[LegacyCompatibleCondition] = {
    case DateRangeActivationCondition(begin, end, timezone) => {
      Json
        .obj(
          "timezone" -> timezone
        )
        .applyOnWithOpt(begin) { (json, begin) => json ++ Json.obj("begin" -> begin) }
        .applyOnWithOpt(end) { (json, end) => json ++ Json.obj("end" -> end) }
    }
    case ZonedHourPeriod(hourPeriod, timezone)              =>
      hourPeriodWrites.writes(hourPeriod).as[JsObject] ++ Json.obj("timezone" -> timezone)
    case All                                                => Json.obj()
    case u: UserList                                        => activationRuleWrite.writes(u)
    case u: UserPercentage                                  => activationRuleWrite.writes(u)
  }

  val NAME_REGEXP_PATTERN: Regex = "^[a-zA-Z0-9:_-]+$".r

  implicit val activationPeriodRead: Reads[FeaturePeriod] = json => {
    val maybeHourPeriod     = (json \ "hourPeriods").asOpt[Set[HourPeriod]].getOrElse(Set())
    val maybeActivationDays = (json \ "activationDays").asOpt[ActivationDayOfWeeks]
    val maybeBegin          = (json \ "begin").asOpt[Instant](instantReads(DateTimeFormatter.ISO_OFFSET_DATE_TIME))
    val maybeEnd            = (json \ "end").asOpt[Instant](instantReads(DateTimeFormatter.ISO_OFFSET_DATE_TIME))
    val maybeZone           = (json \ "timezone").asOpt[String].map(str => ZoneId.of(str))

    JsSuccess(
      FeaturePeriod(
        begin = maybeBegin,
        end = maybeEnd,
        hourPeriods = maybeHourPeriod,
        days = maybeActivationDays,
        timezone = maybeZone.getOrElse(ZoneId.systemDefault()) // TODO should this be allowed ?
      )
    )
  }

  implicit val activationRuleRead: Reads[ActivationRule] = json => {
    if (json.equals(Json.obj())) {
      JsSuccess(All)
    } else {
      (for (percentage <- (json \ "percentage").asOpt[Int]) yield UserPercentage(percentage = percentage))
        .orElse(
          for (users <- (json \ "users").asOpt[Seq[String]]) yield UserList(users = users.toSet)
        )
        .map(JsSuccess(_))
        .getOrElse(JsError("Invalid activation rule"))
    }
  }

  implicit val activationConditionRead: Reads[ActivationCondition] = json => {
    val maybeRule   = (json \ "rule").asOpt[ActivationRule];
    val maybePeriod = (json \ "period").asOpt[FeaturePeriod];

    if (maybeRule.isDefined || maybePeriod.isDefined) {
      JsSuccess(ActivationCondition(rule = maybeRule.getOrElse(All), period = maybePeriod.getOrElse(FeaturePeriod())))
    } else {
      JsError("Invalid activation condition")
    }
  }

  implicit val legacyActivationConditionRead: Reads[LegacyCompatibleCondition] = json => {
    (json \ "percentage")
      .asOpt[Int]
      .map(p => UserPercentage(p))
      .orElse { (json \ "users").asOpt[Seq[String]].map(s => UserList(s.toSet)) }
      .orElse {
        val from = (json \ "begin").asOpt[Instant](instantReads(DateTimeFormatter.ISO_OFFSET_DATE_TIME))
        val to   = (json \ "end").asOpt[Instant](instantReads(DateTimeFormatter.ISO_OFFSET_DATE_TIME))

        (json \ "timezone")
          .asOpt[ZoneId]
          .flatMap(zone => {
            (from, to) match {
              case (f @ Some(_), t @ Some(_)) => Some(DateRangeActivationCondition(begin = f, end = t, timezone = zone))
              case (f @ Some(_), None)        => Some(DateRangeActivationCondition(begin = f, end = None, timezone = zone))
              case (None, t @ Some(_))        => Some(DateRangeActivationCondition(begin = None, end = t, timezone = zone))
              case _                          => None
            }
          })
      }
      .orElse {
        for (
          from     <- (json \ "startTime").asOpt[LocalTime];
          to       <- (json \ "endTime").asOpt[LocalTime];
          timezone <- (json \ "timezone").asOpt[ZoneId]
        ) yield ZonedHourPeriod(HourPeriod(startTime = from, endTime = to), timezone)
      }
      .map(cond => JsSuccess(cond))
      .getOrElse(
        if (json.asOpt[JsObject].exists(obj => obj.value.isEmpty)) JsSuccess(All)
        else JsError("Failed to read condition")
      )
  }

  // This read is used both for parsing inputs and DB results, it may be wise to split it ...
  def readFeature(json: JsValue, project: String = null): JsResult[AbstractFeature] = {
    val metadata    = json.select("metadata").asOpt[JsObject].getOrElse(JsObject.empty)
    val id          = json.select("id").asOpt[String].orNull
    val description = json.select("description").asOpt[String].getOrElse("")
    val tags        = (json \ "tags")
      .asOpt[Set[String]]
      .getOrElse(Set())
    val maybeArray  = (json \ "conditions").toOption
      .flatMap(conds => conds.asOpt[JsArray])

    val maybeWasmConfig = (json \ "wasmConfig").asOpt[WasmConfig](WasmConfig.format)

    val maybeLightWeightConfig = (json \ "wasmConfig").asOpt[String]

    val jsonProject = json
      .select("project")
      .asOpt[String]
      .getOrElse(project)

    val parsedConditions =
      if ((maybeArray.isEmpty && (json \ "activationStrategy").isEmpty) || maybeArray.exists(v => v.value.isEmpty)) {
        JsSuccess(Set[ActivationCondition]())
      } else if (maybeArray.isEmpty) {
        JsError("Incorrect condition format")
      } else {
        val result = maybeArray.get.value.map(v => activationConditionRead.reads(v)).toSet
        if (result.exists(r => r.isError)) {
          JsError("Incorrect condition format")
        } else {
          JsSuccess(result.map(r => r.get))
        }
      }

    val maybeLegacyCompatibleCondition: Option[LegacyCompatibleCondition] = (json \ "conditions")
      .asOpt[LegacyCompatibleCondition]

    val maybeFeature: Option[JsResult[AbstractFeature]] =
      for (
        enabled <- json.select("enabled").asOpt[Boolean];
        name    <- json.select("name").asOpt[String].filter(name => NAME_REGEXP_PATTERN.pattern.matcher(name).matches())
      )
        yield {
          (parsedConditions, maybeWasmConfig, maybeLightWeightConfig, maybeLegacyCompatibleCondition) match {
            case (_, _, _, Some(legacyCondition))       =>
              JsSuccess(
                SingleConditionFeature(
                  id = id,
                  name = name,
                  enabled = enabled,
                  condition = legacyCondition,
                  tags = tags,
                  metadata = metadata,
                  project = jsonProject,
                  description = description
                )
              )
            case (_, Some(wasmConfig), _, _)            => {
              JsSuccess(
                CompleteWasmFeature(
                  id = id,
                  name = name,
                  project = jsonProject,
                  enabled = enabled,
                  wasmConfig = wasmConfig,
                  tags = tags,
                  metadata = metadata,
                  description = description
                )
              )
            }
            case (_, _, Some(wasmConfigName), _)        => {
              JsSuccess(
                LightWeightWasmFeature(
                  id = id,
                  name = name,
                  project = jsonProject,
                  enabled = enabled,
                  wasmConfigName = wasmConfigName,
                  tags = tags,
                  metadata = metadata,
                  description = description
                )
              )
            }
            case (JsSuccess(conditions, _), None, _, _) => {
              val jsonProject = json.select("project").asOpt[String].getOrElse(project)

              JsSuccess(
                Feature(
                  id = id,
                  name = name,
                  enabled = enabled,
                  conditions = conditions,
                  tags = tags,
                  metadata = metadata,
                  project = jsonProject,
                  description = description
                )
              )
            }
            case _                                      => {
              oldFeatureReads
                .reads(json)
                .flatMap(f => {
                  // TODO handle missing timezon
                  f.toFeature(project, (json \ "timezone").asOpt[ZoneId].orNull, Map()) match {
                    case Left(err)           => JsError(err)
                    case Right((feature, _)) => JsSuccess(feature)
                  }
                })
            }
          }
        }
    maybeFeature
      .getOrElse(JsError("Incorrect feature format"))

  }

  def readCompleteFeature(json: JsValue, project: String = null): JsResult[CompleteFeature] = {
    readFeature(json, project).flatMap {
      case f: SingleConditionFeature                                                                       => JsSuccess(f)
      case f: Feature                                                                                      => JsSuccess(f)
      case LightWeightWasmFeature(id, name, project, enabled, wasmConfigName, tags, metadata, description) =>
        JsError("LightWeightWasmFeature can't be evaluated")
      case f: CompleteWasmFeature                                                                          => JsSuccess(f)
    }
  }

  def readLightWeightFeature(json: JsValue, project: String = null): JsResult[LightWeightFeature] = {
    readFeature(json, project).flatMap {
      case f: SingleConditionFeature                                                                    => JsSuccess(f)
      case f: Feature                                                                                   => JsSuccess(f)
      case CompleteWasmFeature(id, name, project, enabled, wasmConfigName, tags, metadata, description) =>
        JsError("Expected light feature, got complete")
      case f: LightWeightWasmFeature                                                                    => JsSuccess(f)
    }
  }
}

object CustomBinders {
  implicit def instantQueryStringBindable(implicit
      seqBinder: QueryStringBindable[String]
  ): QueryStringBindable[Instant] =
    new QueryStringBindable[Instant] {
      override def bind(key: String, params: Map[String, Seq[String]]): Option[Either[String, Instant]] = {
        seqBinder
          .bind("date", params)
          .map(e => e.map(v => Instant.from(DateTimeFormatter.ISO_OFFSET_DATE_TIME.parse(v))))
      }
      override def unbind(key: String, request: Instant): String = {
        DateTimeFormatter.ISO_OFFSET_TIME.format(request)
      }
    }
}
