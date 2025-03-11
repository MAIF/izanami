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
import play.api.libs.json._

import scala.concurrent.Future
import scala.util.matching.Regex

case class FeatureContext(
    id: String,
    name: String,
    parent: String = null,
    children: Seq[FeatureContext] = Seq(),
    overloads: Seq[AbstractFeature] = Seq(),
    global: Boolean,
    project: Option[String] = None,
    isProtected: Boolean
)

sealed trait LightweightContextualStrategy extends ContextualFeatureStrategy

sealed trait CompleteContextualStrategy extends ContextualFeatureStrategy {
  def value(requestContext: RequestContext, env: Env): Future[Either[IzanamiError, JsValue]]
  def toLightWeightContextualStrategy: LightweightContextualStrategy = {
    this match {
      case f: ClassicalFeatureStrategy                                           => f
      case CompleteWasmFeatureStrategy(enabled, wasmConfig, feature, resultType) =>
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
  def value(requestContext: RequestContext, env: Env): Future[Either[IzanamiError, JsValue]] = {
    Future.successful(Right((enabled, resultDescriptor) match {
      case (false, r: BooleanResultDescriptor)                  => JsFalse
      case (false, _)                                           => JsNull
      case (true, BooleanResultDescriptor(conditions))          =>
        JsBoolean(conditions.isEmpty || conditions.exists(c => c.active(requestContext, feature)))
      case (true, v:ValuedResultDescriptor) => {
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
  def value(requestContext: RequestContext, env: Env): Future[Either[IzanamiError, JsValue]] = {
    if (!enabled) {
      Future { Right(null.asInstanceOf) }(env.executionContext)
    } else {
      WasmUtils.handle(wasmConfig, requestContext, resultType)(env.executionContext, env)
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
  def generateSubContextId(project: String, name: String, path: Seq[String] = Seq()): String =
    generateSubContextId(project, path.appended(name))

  def generateSubContextId(project: String, path: Seq[String]): String =
    s"${(project +: path).mkString("_")}"

  def genratePossibleIds(project: String, path: Seq[String] = Seq()): Seq[String] = {
    path
      .foldLeft(Seq(): Seq[String])((acc, next) =>
        acc.prepended(acc.headOption.map(last => s"${last}_${next}").getOrElse(next))
      )
      .map(keyEnd => s"${project}_${keyEnd}")
  }

  def readcontextualFeatureStrategyRead(
      json: JsValue,
      feature: String
  ): JsResult[CompleteContextualStrategy] = {

    val enabled         = (json \ "enabled").asOpt[Boolean].getOrElse(true)
    val maybeWasmConfig = (json \ "wasmConfig").asOpt[WasmConfig](WasmConfig.format)
    (json \ "resultType")
      .asOpt[ResultType](ResultType.resultTypeReads)
      .map {
        case resultType: ValuedResultType => {
          val maybeConditions = json
            .asOpt[ValuedResultDescriptor](ValuedResultDescriptor.valuedDescriptorReads)
          (maybeWasmConfig, maybeConditions) match {
            case (Some(config), _)    => JsSuccess(CompleteWasmFeatureStrategy(enabled, config, feature, resultType))
            case (_, Some(descriptor)) =>
              JsSuccess(
                ClassicalFeatureStrategy(
                  enabled,
                  feature,
                  descriptor
                )
              )
          }
        }
        case BooleanResult                => {
          val maybeConditions = (json \ "conditions")
            .asOpt[Seq[BooleanActivationCondition]](Reads.seq(ActivationCondition.booleanActivationConditionRead))
          (maybeWasmConfig, maybeConditions) match {
            case (Some(config), _)    => JsSuccess(CompleteWasmFeatureStrategy(enabled, config, feature, BooleanResult))
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

  implicit def featureContextWrites: Writes[FeatureContext] = { context =>
    Json.obj(
      "name"      -> context.name,
      "id"        -> context.id,
      "overloads" -> { context.overloads.map(f => Feature.featureWrite.writes(f)) },
      "children"  -> { context.children.map(f => FeatureContext.featureContextWrites.writes(f)) },
      "global"    -> context.global,
      "protected" -> context.isProtected,
      "project"   -> context.project
    )
  }

  val CONTEXT_REGEXP: Regex = "^[a-zA-Z0-9-]+$".r
  def readFeatureContext(json: JsValue, global: Boolean): JsResult[FeatureContext] = {
    val name = (json \ "name").asOpt[String].filter(id => CONTEXT_REGEXP.pattern.matcher(id).matches())
    val id   = (json \ "id").asOpt[String]
    val isProtected   = (json \ "protected").asOpt[Boolean].getOrElse(false)

    name
      .map(n => FeatureContext(id.orNull, n, global = global, isProtected = isProtected))
      .map(JsSuccess(_))
      .getOrElse(JsError("Error reading context"))

  }
}
