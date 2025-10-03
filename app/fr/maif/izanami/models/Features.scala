package fr.maif.izanami.models

import fr.maif.izanami.env.Env
import fr.maif.izanami.errors.{InternalServerError, IzanamiError}
import fr.maif.izanami.models.Feature.{
  lightweightFeatureRead,
  lightweightFeatureWrite
}
import fr.maif.izanami.models.StaleStatus.staleStatusWrites
import fr.maif.izanami.models.features._
import fr.maif.izanami.utils.syntax.implicits.{BetterJsValue, BetterSyntax}
import fr.maif.izanami.v1.OldFeature.oldFeatureReads
import fr.maif.izanami.v1.{OldFeature, OldGlobalScriptFeature}
import fr.maif.izanami.wasm.{WasmConfig, WasmUtils}
import fr.maif.izanami.web.FeatureContextPath
import play.api.libs.json._
import play.api.mvc.QueryStringBindable

import java.time._
import java.time.format.DateTimeFormatter
import java.util.{Objects, UUID}
import scala.concurrent.{ExecutionContext, Future}
import scala.util.hashing.MurmurHash3
import scala.util.matching.Regex
import scala.util.{Failure, Success, Try}

case class FeatureWithOverloads(
    baseFeature: LightWeightFeature,
    overloads: Map[String, LightWeightFeature]
) {
  def id: String = baseFeature.id

  def project: String = baseFeature.project

  def setProject(project: String): FeatureWithOverloads =
    copy(overloads =
      overloads.view.mapValues(f => f.withProject(project)).toMap
    )

  def setEnabling(enabling: Boolean): FeatureWithOverloads =
    setEnablingForContext(enabling, "")

  def setFeature(feature: LightWeightFeature): FeatureWithOverloads =
    setFeatureForContext(feature, "")

  def setEnablingForContext(
      enabling: Boolean,
      context: String
  ): FeatureWithOverloads = {
    if (context == "") {
      setEnabling(enabling)
    }
    overloads
      .get(context)
      .map(f => f.withEnabled(enabling))
      .map(f => copy(overloads = overloads + (context -> f)))
      .getOrElse(this)
  }

  def setFeatureForContext(
      feature: LightWeightFeature,
      context: String
  ): FeatureWithOverloads =
    copy(overloads = overloads + (context -> feature))

  def removeOverload(context: String): FeatureWithOverloads =
    copy(overloads = overloads - context)

  def updateConditionsForContext(
      context: String,
      contextualFeatureStrategy: LightweightContextualStrategy
  ): FeatureWithOverloads = overloads
    .get(context)
    .orElse(overloads.get(""))
    .map(f => f.withStrategy(strategy = contextualFeatureStrategy))
    .map(f => copy(overloads = overloads + (context -> f)))
    .getOrElse(this)
}

object FeatureWithOverloads {
  def apply(feature: LightWeightFeature): FeatureWithOverloads =
    FeatureWithOverloads(
      baseFeature = feature,
      overloads = Map()
    )

  def featureWithOverloadWrite: Writes[FeatureWithOverloads] = obj =>
    Json.toJson(obj.overloads + ("" -> obj.baseFeature))(
      Writes.genericMapWrites(lightweightFeatureWrite)
    )

  def featureWithOverloadRead
      : Reads[FeatureWithOverloads] = Reads[FeatureWithOverloads] { json =>
    (for(
      map <- json.asOpt[Map[String, LightWeightFeature]](Reads.map(lightweightFeatureRead));
      baseFeature <- map.get("")
    ) yield {
      FeatureWithOverloads(baseFeature = baseFeature, overloads = map - "")
    }).fold(
      JsError("Failed to read FeatureWithOverloads"): JsResult[
        FeatureWithOverloads
      ]
    )(f => JsSuccess(f))
  }
}

case class RequestContext(
    tenant: String,
    user: String,
    context: FeatureContextPath = FeatureContextPath(),
    now: Instant = Instant.now(),
    data: JsObject = Json.obj()
) {
  def wasmJson: JsValue = Json.obj(
    "tenant" -> tenant,
    "id" -> user,
    "now" -> now.toEpochMilli,
    "data" -> data
  )

  def contextAsString: String = context.elements.mkString("/")
}

sealed trait CompleteFeature extends AbstractFeature {
  def value(
      requestContext: RequestContext,
      env: Env
  ): Future[Either[IzanamiError, JsValue]]

  override def withProject(project: String): CompleteFeature

  override def withId(id: String): CompleteFeature

  override def withName(name: String): CompleteFeature

  override def withEnabled(enable: Boolean): CompleteFeature

  def toLightWeightFeature: LightWeightFeature = {
    this match {
      case CompleteWasmFeature(
            id,
            name,
            project,
            enabled,
            wasmConfig,
            tags,
            metadata,
            description,
            resultType
          ) =>
        LightWeightWasmFeature(
          id = id,
          name = name,
          project = project,
          enabled = enabled,
          wasmConfigName = wasmConfig.name,
          tags = tags,
          metadata = metadata,
          description = description,
          resultType = resultType
        )
      case f: LightWeightFeature => f
    }
  }
}

sealed trait StaleStatus

object StaleStatus {
  def staleStatusWrites: Writes[StaleStatus] = {
    case NoValueChange(since, value) => {
      Json.obj("because" -> "NoValueChange", "since" -> since, "value" -> value)
    }
    case NoCall(since) => {
      Json.obj("because" -> "NoCall", "since" -> since)
    }
    case NeverCalled(since) => {
      Json.obj("because" -> "NeverCalled", "since" -> since)
    }
  }
}

case class NeverCalled(since: Instant) extends StaleStatus
case class NoCall(since: Instant) extends StaleStatus
case class NoValueChange(since: Instant, value: JsValue) extends StaleStatus

case class LightWeightFeatureWithUsageInformation(
    feature: LightWeightFeature,
    staleStatus: Option[StaleStatus]
)

object LightWeightFeatureWithUsageInformation {
  def writeLightWeightFeatureWithUsageInformation
      : Writes[LightWeightFeatureWithUsageInformation] = feature => {
    val baseJson = lightweightFeatureWrite.writes(feature.feature).as[JsObject]
    baseJson.applyOnWithOpt(feature.staleStatus)((json, staleStatus) =>
      json + ("stale" -> Json.toJson(staleStatus)(staleStatusWrites))
    )
  }
}

case class FeatureUsage(
    lastCall: Option[Instant],
    creationDate: Instant,
    lastValues: Set[JsValue]
)

sealed trait LightWeightFeature extends AbstractFeature {
  override def withProject(project: String): LightWeightFeature

  override def withId(id: String): LightWeightFeature

  override def withName(name: String): LightWeightFeature

  override def withEnabled(enable: Boolean): LightWeightFeature

  def toCompleteFeature(
      tenant: String,
      env: Env
  ): Future[Either[IzanamiError, CompleteFeature]] = {
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
                  description = description,
                  resultType = resultType
                )
              )
            case None =>
              Left(
                InternalServerError(
                  s"Failed to find wasm script config ${f.wasmConfigName}"
                )
              )
          }(env.executionContext)
      }
      case feat: CompleteFeature => Right(feat).future
    }
  }

  def withStrategy(
      strategy: LightweightContextualStrategy
  ): LightWeightFeature = {
    // TODO handle resultType difference
    strategy match {
      case ClassicalFeatureStrategy(enabled, _, resultDescriptor) =>
        Feature(
          id = id,
          name = name,
          description = description,
          project = project,
          enabled = enabled,
          tags = tags,
          metadata = metadata,
          resultDescriptor = resultDescriptor
        )
      case LightWeightWasmFeatureStrategy(enabled, wasmConfig, _, resultType) =>
        LightWeightWasmFeature(
          id = id,
          name = name,
          description = description,
          project = project,
          enabled = enabled,
          tags = tags,
          metadata = metadata,
          wasmConfigName = wasmConfig,
          resultType = resultType
        )
    }
  }

  def hasSameActivationStrategy[F](another: AbstractFeature): Boolean =
    (this, another) match {
      case (f1, f2) if f1.resultType != f2.resultType => false
      case (f1, f2) if f1.name != f2.name             => false
      case (f1, f2) if f1.enabled != f2.enabled       => false
      case (f1: Feature, f2: Feature)                 =>
        f1.resultDescriptor == f2.resultDescriptor
      case (f1: LightWeightWasmFeature, f2: LightWeightWasmFeature) =>
        f1.wasmConfigName == f2.wasmConfigName
      case (f1: SingleConditionFeature, f2: SingleConditionFeature) =>
        f1.condition == f2.condition
      case _ => false
    }
}

sealed trait AbstractFeature {
  val id: String
  val name: String
  val description: String
  val project: String
  val enabled: Boolean
  val tags: Set[String] = Set()
  val metadata: JsObject = JsObject.empty

  def resultType: ResultType

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
  override val resultType: ResultType = BooleanResult

  override def withEnabled(enabled: Boolean): SingleConditionFeature =
    copy(enabled = enabled)

  def toModernFeature: Feature = {
    val activationCondition = this.condition match {
      case DateRangeActivationCondition(begin, end, timezone) =>
        BooleanActivationCondition(
          period = FeaturePeriod(begin = begin, end = end, timezone = timezone)
        )
      case ZonedHourPeriod(HourPeriod(startTime, endTime), timezone) =>
        BooleanActivationCondition(
          period = FeaturePeriod(
            hourPeriods =
              Set(HourPeriod(startTime = startTime, endTime = endTime)),
            timezone = timezone
          )
        )
      case rule: ActivationRule => BooleanActivationCondition(rule = rule)
    }

    Feature(
      id = id,
      name = name,
      project = project,
      enabled = enabled,
      tags = tags,
      metadata = metadata,
      description = description,
      resultDescriptor = BooleanResultDescriptor(Seq(activationCondition))
    )
  }

  override def value(
      requestContext: RequestContext,
      env: Env
  ): Future[Either[IzanamiError, JsValue]] = {
    val res = if (enabled) condition.active(requestContext, id) else false
    Future.successful(Right(JsBoolean(res)))
  }

  override def withProject(project: String): SingleConditionFeature =
    copy(project = project)

  override def withId(id: String): SingleConditionFeature = copy(id = id)

  override def withName(name: String): SingleConditionFeature =
    copy(name = name)
}

case class Feature(
    override val id: String,
    override val name: String,
    override val project: String,
    override val enabled: Boolean,
    override val tags: Set[String] = Set(),
    override val metadata: JsObject = JsObject.empty,
    override val description: String,
    resultDescriptor: ResultDescriptor
) extends LightWeightFeature
    with CompleteFeature {
  override def withEnabled(enabled: Boolean): Feature = copy(enabled = enabled)

  override def value(
      requestContext: RequestContext,
      env: Env
  ): Future[Either[IzanamiError, JsValue]] = {
    implicit val ec: ExecutionContext = env.executionContext
    Future.successful(Right((enabled, resultDescriptor) match {
      case (false, r: BooleanResultDescriptor)         => JsFalse
      case (false, _)                                  => JsNull
      case (true, BooleanResultDescriptor(conditions)) =>
        JsBoolean(
          conditions.isEmpty || conditions.exists(c =>
            c.active(requestContext, name)
          )
        )
      case (true, v: ValuedResultDescriptor) => {
        v.conditions
          .find(condition => condition.active(requestContext, name))
          .map(condition => condition.jsonValue)
          .getOrElse(v.jsonValue)
      }
    }))
  }

  override def withProject(project: String): Feature = copy(project = project)

  override def withId(id: String): Feature = copy(id = id)

  override def withName(name: String): Feature = copy(name = name)

  override def resultType: ResultType = resultDescriptor.resultType
}

case class LightWeightWasmFeature(
    override val id: String,
    override val name: String,
    override val project: String,
    override val enabled: Boolean,
    wasmConfigName: String,
    override val tags: Set[String] = Set(),
    override val metadata: JsObject = JsObject.empty,
    override val description: String,
    override val resultType: ResultType
) extends LightWeightFeature {
  override def withEnabled(enabled: Boolean): LightWeightWasmFeature =
    copy(enabled = enabled)

  def toCompleteWasmFeature(
      tenant: String,
      env: Env
  ): Future[Either[IzanamiError, CompleteWasmFeature]] = {
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
              description = description,
              resultType = resultType
            )
          )
        case None =>
          Left(InternalServerError(s"Wasm script $wasmConfigName not found"))
      }(env.executionContext)
  }

  override def withProject(project: String): LightWeightWasmFeature =
    copy(project = project)

  override def withId(id: String): LightWeightWasmFeature = copy(id = id)

  override def withName(name: String): LightWeightWasmFeature =
    copy(name = name)
}

case class CompleteWasmFeature(
    override val id: String,
    override val name: String,
    override val project: String,
    override val enabled: Boolean,
    wasmConfig: WasmConfig,
    override val tags: Set[String] = Set(),
    override val metadata: JsObject = JsObject.empty,
    override val description: String,
    override val resultType: ResultType
) extends CompleteFeature {
  override def withEnabled(enabled: Boolean): CompleteWasmFeature =
    copy(enabled = enabled)

  override def value(
      requestContext: RequestContext,
      env: Env
  ): Future[Either[IzanamiError, JsValue]] = {
    implicit val ec: ExecutionContext = env.executionContext
    if (!enabled) {
      Future {
        Right(JsNull)
      }
    } else {
      WasmUtils.handle(wasmConfig, requestContext, resultType)(ec, env)
    }
  }

  override def withProject(project: String): CompleteWasmFeature =
    copy(project = project)

  override def withId(id: String): CompleteWasmFeature = copy(id = id)

  override def withName(name: String): CompleteWasmFeature = copy(name = name)
}

object LightWeightWasmFeature {
  val lightWeightFormat: Format[LightWeightWasmFeature] =
    new Format[LightWeightWasmFeature] {
      override def writes(o: LightWeightWasmFeature): JsValue = Json.obj(
        "id" -> o.id,
        "name" -> o.name,
        "enabled" -> o.enabled,
        "project" -> o.project,
        "config" -> o.wasmConfigName,
        "metadata" -> o.metadata,
        "description" -> o.description,
        "tags" -> JsArray(o.tags.map(JsString.apply).toSeq)
      )

      override def reads(json: JsValue): JsResult[LightWeightWasmFeature] =
        Try {
          LightWeightWasmFeature(
            id = (json \ "id").as[String],
            name = (json \ "name").as[String],
            project = (json \ "project").as[String],
            enabled = (json \ "enabled").as[Boolean],
            wasmConfigName = (json \ "config").as[String],
            metadata =
              (json \ "metadata").asOpt[JsObject].getOrElse(Json.obj()),
            tags =
              (json \ "tags").asOpt[Set[String]].getOrElse(Set.empty[String]),
            description = (json \ "description").asOpt[String].getOrElse(""),
            resultType =
              (json \ "resultType").as[ResultType](ResultType.resultTypeReads)
          )
        } match {
          case Failure(ex)    => JsError(ex.getMessage)
          case Success(value) => JsSuccess(value)
        }
    }

  val completeFormat: Format[CompleteWasmFeature] =
    new Format[CompleteWasmFeature] {
      override def writes(o: CompleteWasmFeature): JsValue = Json.obj(
        "id" -> o.id,
        "name" -> o.name,
        "enabled" -> o.enabled,
        "project" -> o.project,
        "config" -> o.wasmConfig.json,
        "metadata" -> o.metadata,
        "description" -> o.description,
        "tags" -> JsArray(o.tags.map(JsString.apply).toSeq)
      )

      override def reads(json: JsValue): JsResult[CompleteWasmFeature] = Try {
        CompleteWasmFeature(
          id = (json \ "id").as[String],
          name = (json \ "name").as[String],
          project = (json \ "project").as[String],
          enabled = (json \ "enabled").as[Boolean],
          wasmConfig = (json \ "config").as(WasmConfig.format),
          metadata = (json \ "metadata").asOpt[JsObject].getOrElse(Json.obj()),
          tags =
            (json \ "tags").asOpt[Set[String]].getOrElse(Set.empty[String]),
          description = (json \ "description").asOpt[String].getOrElse(""),
          resultType =
            (json \ "resultType").as[ResultType](ResultType.resultTypeReads)
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
  def isEmpty: Boolean = oneTagIn.isEmpty && allTagsIn.isEmpty

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
      override def bind(
          key: String,
          params: Map[String, Seq[String]]
      ): Option[Either[String, FeatureTagRequest]] = {
        for {
          eitherAllTagsIn <- seqBinder.bind("allTagsIn", params)
          eitherOneTagIn <- seqBinder.bind("oneTagIn", params)
        } yield {
          Right(
            FeatureTagRequest(
              allTagsIn =
                processInputSeqString(eitherAllTagsIn.getOrElse(Seq())),
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
  def isEmpty: Boolean = {
    projects.isEmpty && oneTagIn.isEmpty && allTagsIn.isEmpty && noTagIn.isEmpty && features.isEmpty
  }
}

object FeatureRequest {

  def processInputSeqUUID(input: Seq[String]): Set[UUID] = {
    input
      .filter(str => str.nonEmpty)
      .flatMap(str => str.split(","))
      .map(UUID.fromString)
      .toSet
  }

  def processInputSeqString(input: Seq[String]): Set[String] = {
    input.filter(str => str.nonEmpty).flatMap(str => str.split(",")).toSet
  }

  implicit def queryStringBindable(implicit
      seqBinder: QueryStringBindable[Seq[String]]
  ): QueryStringBindable[FeatureRequest] =
    new QueryStringBindable[FeatureRequest] {
      override def bind(
          key: String,
          params: Map[String, Seq[String]]
      ): Option[Either[String, FeatureRequest]] = {
        for {
          eitherProjects <- seqBinder.bind("projects", params)
          eitherFeatures <- seqBinder.bind("features", params)
          eitherAllTagsIn <- seqBinder.bind("allTagsIn", params)
          eitherOneTagIn <- seqBinder.bind("oneTagIn", params)
          eitherNoTagIn <- seqBinder.bind("noTagIn", params)
          eitherContext <- seqBinder.bind("context", params)
        } yield {
          Right(
            FeatureRequest(
              features = processInputSeqString(eitherFeatures.getOrElse(Seq())),
              projects = processInputSeqUUID(eitherProjects.getOrElse(Seq())),
              allTagsIn = processInputSeqUUID(eitherAllTagsIn.getOrElse(Seq())),
              oneTagIn = processInputSeqUUID(eitherOneTagIn.getOrElse(Seq())),
              noTagIn = processInputSeqUUID(eitherNoTagIn.getOrElse(Seq())),
              context = (eitherContext
                .map(seq =>
                  seq
                    .filter(str => str.nonEmpty)
                    .flatMap(str => str.split("/").filter(s => s.nonEmpty))
                )
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

  def writeStrategiesForEvent(
      strategyByCtx: Map[String, LightWeightFeature]
  ): JsObject = {
    Json
      .toJson(strategyByCtx.map {
        case (ctx, feature) => {
          (
            ctx,
            (feature match {
              case lf: SingleConditionFeature =>
                Feature.featureWrite
                  .writes(lf.toModernFeature)
                  .as[
                    JsObject
                  ] - "tags" - "name" - "description" - "id" - "project"
              case f => Feature.featureWrite.writes(f).as[JsObject]
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
    val context = requestContext.context.elements.mkString("_")
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
              case Right(json) if conditions =>
                Right(json ++ Json.obj("conditions" -> jsonStrategies))
              case Right(json) => Right(json)
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
      .value(context, env)
      .map(either => {
        either.map(active => {
          Json.obj(
            "name" -> feature.name,
            "active" -> active,
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
      .value(context, env)
      .map {
        case Left(error)   => Left(error)
        case Right(active) =>
          Right(
            Some(
              writeFeatureInLegacyFormat(feature) ++ Json
                .obj("active" -> active)
            )
          )
      }(env.executionContext)
  }

  def writeFeatureInLegacyFormat(feature: AbstractFeature): JsObject = {
    feature match {
      case s: SingleConditionFeature =>
        Json
          .toJson(OldFeature.fromModernFeature(s))(OldFeature.oldFeatureWrites)
          .as[JsObject]
      // Transforming modern feature to script feature is a little hacky, however it's a format that legacy client
      // can understand, moreover due to the script nature of the feature, there won't be cache client side, which
      // is what we want since legacy client can't evaluate modern feeature locally
      case f: Feature =>
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
        Json
          .toJson(OldFeature.fromScriptFeature(w))(OldFeature.oldFeatureWrites)
          .as[JsObject]
      case w: CompleteWasmFeature =>
        Json
          .toJson(OldFeature.fromScriptFeature(w))(OldFeature.oldFeatureWrites)
          .as[JsObject]
    }
  }

  def lightweightFeatureRead: Reads[LightWeightFeature] = json => {
    readFeature(json).flatMap {
      case feature: LightWeightFeature => JsSuccess(feature)
      case _ => JsError("CompleteFeature can't be read as LightWeightFeature")
    }
  }

  def featureRead: Reads[AbstractFeature] = json => {
    readFeature(json)
  }

  def lightweightFeatureWrite: Writes[LightWeightFeature] = f =>
    featureWrite.writes(f)

  def featureWrite: Writes[AbstractFeature] = Writes[AbstractFeature] {
    case Feature(
          id,
          name,
          project,
          enabled,
          tags,
          metadata,
          description,
          resultDescriptor
        ) => {
      val base = Json.obj(
        "name" -> name,
        "enabled" -> enabled,
        "metadata" -> metadata,
        "tags" -> tags,
        "conditions" -> resultDescriptor.conditions,
        "id" -> id,
        "project" -> project,
        "description" -> description,
        "resultType" -> Json.toJson(resultDescriptor.resultType)(
          ResultType.resultTypeWrites
        )
      )
      resultDescriptor match {
        case v: ValuedResultDescriptor => base + ("value" -> v.jsonValue)
        case BooleanResultDescriptor(conditions) => base
      }
    }
    case LightWeightWasmFeature(
          id,
          name,
          project,
          enabled,
          wasmConfig,
          tags,
          metadata,
          description,
          resultType
        ) => {
      Json.obj(
        "name" -> name,
        "enabled" -> enabled,
        "metadata" -> metadata,
        "tags" -> tags,
        "wasmConfig" -> wasmConfig,
        "id" -> id,
        "project" -> project,
        "description" -> description,
        "resultType" -> Json.toJson(resultType)(ResultType.resultTypeWrites)
      )
    }
    case CompleteWasmFeature(
          id,
          name,
          project,
          enabled,
          wasmConfig,
          tags,
          metadata,
          description,
          resultType
        ) => {
      Json.obj(
        "name" -> name,
        "enabled" -> enabled,
        "metadata" -> metadata,
        "tags" -> tags,
        "wasmConfig" -> WasmConfig.format.writes(wasmConfig),
        "id" -> id,
        "project" -> project,
        "description" -> description,
        "resultType" -> Json.toJson(resultType)(ResultType.resultTypeWrites)
      )
    }
    case SingleConditionFeature(
          id,
          name,
          project,
          condition,
          enabled,
          tags,
          metadata,
          description
        ) => {
      Json.obj(
        "name" -> name,
        "enabled" -> enabled,
        "metadata" -> metadata,
        "tags" -> tags,
        "conditions" -> condition,
        "id" -> id,
        "project" -> project,
        "description" -> description,
        "resultType" -> BooleanResult.toDatabaseName
      )
    }
  }

  val NAME_REGEXP_PATTERN: Regex = "^[ a-zA-Z0-9:_-]+$".r

  // This read is used both for parsing inputs and DB results, it may be wise to split it ...
  def readFeature(
      json: JsValue,
      project: String = null
  ): JsResult[AbstractFeature] = {
    val metadata =
      json.select("metadata").asOpt[JsObject].getOrElse(JsObject.empty)
    val id = json.select("id").asOpt[String].orNull
    val description = json.select("description").asOpt[String].getOrElse("")
    val lastCall = json.select("lastCall").asOpt[Instant]
    val tags = (json \ "tags")
      .asOpt[Set[String]]
      .getOrElse(Set())
    val maybeArray = (json \ "conditions").toOption
      .flatMap(conds => conds.asOpt[JsArray])

    val maybeWasmConfig =
      (json \ "wasmConfig").asOpt[WasmConfig](WasmConfig.format)

    val maybeLightWeightConfig = (json \ "wasmConfig").asOpt[String]

    val jsonProject = json
      .select("project")
      .asOpt[String]
      .getOrElse(project)

    val maybeLegacyCompatibleCondition: Option[LegacyCompatibleCondition] =
      (json \ "conditions")
        .asOpt[LegacyCompatibleCondition]

    val maybeFeature: Option[JsResult[AbstractFeature]] =
      for (
        enabled <- json.select("enabled").asOpt[Boolean];
        name <- json
          .select("name")
          .asOpt[String]
          .filter(name => NAME_REGEXP_PATTERN.pattern.matcher(name).matches());
        resultType <- json
          .select("resultType")
          .asOpt[ResultType](ResultType.resultTypeReads)
          .orElse(
            json
              .select("result_type")
              .asOpt[ResultType](ResultType.resultTypeReads)
          );
        if Objects.isNull(id) || id.nonEmpty
      )
        yield {
          val maybeConditionJsArray =
            if (
              (maybeArray.isEmpty && (json \ "activationStrategy").isEmpty) || maybeArray
                .exists(v => v.value.isEmpty)
            ) {
              Some(JsArray())
            } else if (maybeArray.isEmpty) {
              None
            } else {
              maybeArray
            }
          (
            maybeConditionJsArray,
            maybeWasmConfig,
            maybeLightWeightConfig,
            maybeLegacyCompatibleCondition
          ) match {
            case (_, _, _, Some(legacyCondition)) =>
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
            case (_, Some(wasmConfig), _, _) => {
              JsSuccess(
                CompleteWasmFeature(
                  id = id,
                  name = name,
                  project = jsonProject,
                  enabled = enabled,
                  wasmConfig = wasmConfig,
                  tags = tags,
                  metadata = metadata,
                  description = description,
                  resultType = resultType
                )
              )
            }
            case (_, _, Some(wasmConfigName), _) => {
              JsSuccess(
                LightWeightWasmFeature(
                  id = id,
                  name = name,
                  project = jsonProject,
                  enabled = enabled,
                  wasmConfigName = wasmConfigName,
                  tags = tags,
                  metadata = metadata,
                  description = description,
                  resultType = resultType
                )
              )
            }
            case (Some(jsonConditions), None, _, _) => {
              val jsonProject =
                json.select("project").asOpt[String].getOrElse(project)
              resultType match {
                case resultType: ValuedResultType => {
                  json
                    .asOpt[ValuedResultDescriptor](
                      ValuedResultDescriptor.valuedDescriptorReads
                    )
                    .fold(
                      JsError(
                        "Failed to read ValuedResultDescriptor"
                      ): JsResult[Feature]
                    )(rd =>
                      JsSuccess(
                        Feature(
                          id = id,
                          name = name,
                          enabled = enabled,
                          tags = tags,
                          metadata = metadata,
                          project = jsonProject,
                          description = description,
                          resultDescriptor = rd
                        )
                      )
                    )

                }
                case BooleanResult => {
                  val maybeBooleanConditions = jsonConditions.value.toSeq
                    .map(json =>
                      json.asOpt[BooleanActivationCondition](
                        ActivationCondition.booleanActivationConditionRead
                      )
                    )
                  if (maybeBooleanConditions.exists(_.isEmpty)) {
                    JsError("Invalid condition")
                  } else {
                    JsSuccess(
                      Feature(
                        id = id,
                        name = name,
                        enabled = enabled,
                        tags = tags,
                        metadata = metadata,
                        project = jsonProject,
                        description = description,
                        resultDescriptor = BooleanResultDescriptor(
                          maybeBooleanConditions.flatMap(_.toSeq)
                        )
                      )
                    )
                  }
                }
              }
            }
            case _ => {
              oldFeatureReads
                .reads(json)
                .flatMap(f => {
                  // TODO handle missing timezon
                  f.toFeature(
                    project,
                    (json \ "timezone").asOpt[ZoneId].orNull,
                    Map()
                  ) match {
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

  def readCompleteFeature(
      json: JsValue,
      project: String = null
  ): JsResult[CompleteFeature] = {
    readFeature(json, project).flatMap {
      case f: SingleConditionFeature => JsSuccess(f)
      case f: Feature                => JsSuccess(f)
      case _: LightWeightWasmFeature =>
        JsError("LightWeightWasmFeature can't be evaluated")
      case f: CompleteWasmFeature => JsSuccess(f)
    }
  }

  def readLightWeightFeature(
      json: JsValue,
      project: String = null
  ): JsResult[LightWeightFeature] = {
    readFeature(json, project).flatMap {
      case f: SingleConditionFeature => JsSuccess(f)
      case f: Feature                => JsSuccess(f)
      case _: CompleteWasmFeature    =>
        JsError("Expected light feature, got complete")
      case f: LightWeightWasmFeature => JsSuccess(f)
    }
  }
}

object CustomBinders {
  implicit def instantQueryStringBindable(implicit
      seqBinder: QueryStringBindable[String]
  ): QueryStringBindable[Instant] =
    new QueryStringBindable[Instant] {
      override def bind(
          key: String,
          params: Map[String, Seq[String]]
      ): Option[Either[String, Instant]] = {
        seqBinder
          .bind("date", params)
          .map(e =>
            e.map(v =>
              Instant.from(DateTimeFormatter.ISO_OFFSET_DATE_TIME.parse(v))
            )
          )
      }

      override def unbind(key: String, request: Instant): String = {
        DateTimeFormatter.ISO_OFFSET_TIME.format(request)
      }
    }

  implicit def durationQueryStringBindable(implicit
      seqBinder: QueryStringBindable[String]
  ): QueryStringBindable[Duration] =
    new QueryStringBindable[Duration] {
      override def bind(
          key: String,
          params: Map[String, Seq[String]]
      ): Option[Either[String, Duration]] = {
        seqBinder
          .bind("for", params)
          .map(e => e.map(v => Duration.parse(v)))
      }

      override def unbind(key: String, request: Duration): String = {
        request.toString
      }
    }
}
