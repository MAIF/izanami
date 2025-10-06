package fr.maif.izanami.models

import fr.maif.izanami.env.Env
import fr.maif.izanami.errors.IzanamiError
import fr.maif.izanami.models.features.{
  ActivationCondition,
  BooleanActivationCondition,
  BooleanResult,
  BooleanResultDescriptor,
  ResultDescriptor,
  ResultType,
  ValuedActivationCondition,
  ValuedResultDescriptor,
  ValuedResultType
}
import fr.maif.izanami.wasm.{WasmConfig, WasmUtils}
import fr.maif.izanami.web.FeatureContextPath
import play.api.libs.json._

import scala.concurrent.Future
import scala.util.matching.Regex

sealed trait ContextHolder {
  def context: Context
}

case object ContextHolder {
  def writes: Writes[ContextHolder] = {
    case c: Context              => Context.writes.writes(c)
    case c: ContextWithOverloads => ContextWithOverloads.writes.writes(c)
  }
}

sealed trait Context extends ContextHolder {
  def name: String
  def path: FeatureContextPath
  def global: Boolean
  def isProtected: Boolean
  def fullyQualifiedName: FeatureContextPath =
    Option(path).map(path => path.append(name)).getOrElse(FeatureContextPath())
  def context: Context = this
}

case class LocalContext(
    name: String,
    path: FeatureContextPath,
    isProtected: Boolean,
    project: String
) extends Context {
  override def global: Boolean = false
}

case class GlobalContext(
    name: String,
    path: FeatureContextPath,
    isProtected: Boolean
) extends Context {
  override def global: Boolean = true
}

case object Context {
  def writes: Writes[Context] = { context =>
    val project = context match {
      case l: LocalContext => JsString(l.project)
      case _               => JsNull
    }
    Json.obj(
      "name" -> context.name,
      "global" -> context.global,
      "protected" -> context.isProtected,
      "project" -> project
    )
  }
}

case class ContextWithOverloads(
    context: Context,
    overloads: Seq[AbstractFeature] = Seq()
) extends ContextHolder

case object ContextWithOverloads {
  def writes: Writes[ContextWithOverloads] = { c =>
    val json = Context.writes.writes(c.context).as[JsObject]
    json ++
      Json.obj(
        "overloads" -> c.overloads.map(f => Feature.featureWrite.writes(f))
      )
  }
}

case class ContextNode(
    underlying: ContextHolder,
    children: Seq[ContextNode]
) {
  def context: Context = underlying.context
}

case object ContextNode {
  def writes: Writes[ContextNode] = { c =>
    val json = ContextHolder.writes.writes(c.underlying).as[JsObject]
    json ++
      Json.obj(
        "children" -> c.children.map(f => ContextNode.writes.writes(f))
      )
  }
}

sealed trait FeatureContextCreationRequest {
  def name: String
  def parent: FeatureContextPath
  def global: Boolean
  def isProtected: Boolean
  def withProtected(b: Boolean): FeatureContextCreationRequest
}
case class LocalFeatureContextCreationRequest(
    name: String,
    parent: FeatureContextPath,
    global: Boolean,
    project: String,
    isProtected: Boolean
) extends FeatureContextCreationRequest {
  override def withProtected(b: Boolean): FeatureContextCreationRequest =
    copy(isProtected = b)
}
case class GlobalFeatureContextCreationRequest(
    name: String,
    parent: FeatureContextPath,
    global: Boolean,
    isProtected: Boolean
) extends FeatureContextCreationRequest {
  override def withProtected(b: Boolean): FeatureContextCreationRequest =
    copy(isProtected = b)
}

sealed trait LightweightContextualStrategy extends ContextualFeatureStrategy

sealed trait CompleteContextualStrategy extends ContextualFeatureStrategy {
  def value(
      requestContext: RequestContext,
      env: Env
  ): Future[Either[IzanamiError, JsValue]]
  def toLightWeightContextualStrategy: LightweightContextualStrategy = {
    this match {
      case f: ClassicalFeatureStrategy => f
      case CompleteWasmFeatureStrategy(
            enabled,
            wasmConfig,
            feature,
            resultType
          ) =>
        LightWeightWasmFeatureStrategy(
          enabled = enabled,
          wasmConfigName = wasmConfig.name,
          feature = feature,
          resultType = resultType
        )
    }
  }
}

sealed trait ContextualFeatureStrategy {
  val enabled: Boolean
  val feature: String
  val resultType: ResultType
}

case class ClassicalFeatureStrategy(
    enabled: Boolean,
    feature: String,
    resultDescriptor: ResultDescriptor
) extends CompleteContextualStrategy
    with LightweightContextualStrategy {
  def value(
      requestContext: RequestContext,
      env: Env
  ): Future[Either[IzanamiError, JsValue]] = {
    Future.successful(Right((enabled, resultDescriptor) match {
      case (false, r: BooleanResultDescriptor)         => JsFalse
      case (false, _)                                  => JsNull
      case (true, BooleanResultDescriptor(conditions)) =>
        JsBoolean(
          conditions.isEmpty || conditions.exists(c =>
            c.active(requestContext, feature)
          )
        )
      case (true, v: ValuedResultDescriptor) => {
        v.conditions
          .find(condition => condition.active(requestContext, feature))
          .map(condition => condition.jsonValue)
          .getOrElse(v.jsonValue)
      }
    }))
  }

  override val resultType: ResultType = resultDescriptor.resultType
}

case class CompleteWasmFeatureStrategy(
    enabled: Boolean,
    wasmConfig: WasmConfig,
    feature: String,
    resultType: ResultType
) extends CompleteContextualStrategy {
  def value(
      requestContext: RequestContext,
      env: Env
  ): Future[Either[IzanamiError, JsValue]] = {
    if (!enabled) {
      Future { Right(null.asInstanceOf) }(env.executionContext)
    } else {
      WasmUtils.handle(wasmConfig, requestContext, resultType)(
        env.executionContext,
        env
      )
    }
  }
}

case class LightWeightWasmFeatureStrategy(
    enabled: Boolean,
    wasmConfigName: String,
    feature: String,
    resultType: ResultType
) extends LightweightContextualStrategy

object FeatureContext {

  def readcontextualFeatureStrategyRead(
      json: JsValue,
      feature: String
  ): JsResult[CompleteContextualStrategy] = {

    val enabled = (json \ "enabled").asOpt[Boolean].getOrElse(true)
    val maybeWasmConfig =
      (json \ "wasmConfig").asOpt[WasmConfig](WasmConfig.format)
    (json \ "resultType")
      .asOpt[ResultType](ResultType.resultTypeReads)
      .map {
        case resultType: ValuedResultType => {
          val maybeConditions = json
            .asOpt[ValuedResultDescriptor](
              ValuedResultDescriptor.valuedDescriptorReads
            )
          (maybeWasmConfig, maybeConditions) match {
            case (Some(config), _) =>
              JsSuccess(
                CompleteWasmFeatureStrategy(
                  enabled,
                  config,
                  feature,
                  resultType
                )
              )
            case (_, Some(descriptor)) =>
              JsSuccess(
                ClassicalFeatureStrategy(
                  enabled,
                  feature,
                  descriptor
                )
              )
            case (None, None) =>
              throw new RuntimeException(
                "Failed to read feature strategy: it is neither wasm no classical."
              )
          }
        }
        case BooleanResult => {
          val maybeConditions = (json \ "conditions")
            .asOpt[Seq[BooleanActivationCondition]](
              Reads.seq(ActivationCondition.booleanActivationConditionRead)
            )
          (maybeWasmConfig, maybeConditions) match {
            case (Some(config), _) =>
              JsSuccess(
                CompleteWasmFeatureStrategy(
                  enabled,
                  config,
                  feature,
                  BooleanResult
                )
              )
            case (_, maybeConditions) => {
              JsSuccess(
                ClassicalFeatureStrategy(
                  enabled,
                  feature,
                  BooleanResultDescriptor(
                    maybeConditions.getOrElse(Seq())
                  )
                )
              )
            }
          }

        }
      }
      .getOrElse(JsError("Missing result type"))
  }

  val CONTEXT_REGEXP: Regex = "^[a-zA-Z0-9-]+$".r
}
