package fr.maif.izanami.models

import fr.maif.izanami.env.Env
import fr.maif.izanami.errors.IzanamiError
import fr.maif.izanami.models.Feature.activationConditionRead
import fr.maif.izanami.utils.syntax.implicits.BetterSyntax
import fr.maif.izanami.wasm.{WasmConfig, WasmUtils}
import play.api.libs.json._

import scala.concurrent.{ExecutionContext, Future}
import scala.util.matching.Regex

case class FeatureContext(id: String, name: String, parent: String = null, children: Seq[FeatureContext]= Seq(), overloads: Seq[AbstractFeature] = Seq(), global: Boolean, project: Option[String] = None)

sealed trait ContextualFeatureStrategy{
  val enabled: Boolean
  val feature: String
  def active(requestContext: RequestContext, env: Env): Future[Either[IzanamiError, Boolean]]
}


case class ClassicalFeatureStrategy(
   enabled: Boolean,
   conditions: Set[ActivationCondition],
   feature: String
 ) extends ContextualFeatureStrategy {
  def active(requestContext: RequestContext, env: Env): Future[Either[IzanamiError, Boolean]] = {
    // TODO handle default as for features
    Future(Right{
      enabled && (conditions.isEmpty || conditions.exists(cond => cond.active(requestContext, feature)))
    })(env.executionContext)
  }
}

case class WasmFeatureStrategy(
  enabled: Boolean,
  wasmConfig: WasmConfig,
  feature: String
) extends ContextualFeatureStrategy {
  def active(requestContext: RequestContext, env: Env): Future[Either[IzanamiError, Boolean]] = {
    if(!enabled) {
      Future {Right(false)}(env.executionContext)
    } else {
      WasmUtils.handle(wasmConfig, requestContext)(env.executionContext, env)
    }
  }
}

object FeatureContext {
  def generateSubContextId(project: String, name: String, path: Seq[String] = Seq()): String =
    generateSubContextId(project, path.appended(name))

  def generateSubContextId(project: String, path: Seq[String]): String =
    s"${(project +: path).mkString("_")}"

  def genratePossibleIds(project: String, path: Seq[String] = Seq()): Seq[String] = {
    path
      .foldLeft(Seq():Seq[String])((acc, next) => acc.prepended(acc.headOption.map(last => s"${last}_${next}").getOrElse(next)))
      .map(keyEnd => s"${project}_${keyEnd}")
  }

  def readcontextualFeatureStrategyRead(json: JsValue, feature: String): JsResult[ContextualFeatureStrategy] = {
    val maybeConditions = (json \ "conditions").asOpt[Set[ActivationCondition]]
    val maybeWasmConfig = (json \ "wasmConfig").asOpt[WasmConfig](WasmConfig.format)
    val enabled = (json \ "enabled").asOpt[Boolean].getOrElse(true)

    (maybeWasmConfig, maybeConditions) match {
      case (Some(config), _) => JsSuccess(WasmFeatureStrategy(enabled, config, feature))
      case (_, maybeConditions) => JsSuccess(ClassicalFeatureStrategy(enabled, maybeConditions.getOrElse(Set(ActivationCondition())), feature))
    }
  }

  implicit val featureContextWrites: Writes[FeatureContext] = { context =>
    Json.obj(
      "name" -> context.name,
      "id"   -> context.id,
      "overloads" -> {context.overloads.map(f => Feature.featureWrite.writes(f))},
      "children" -> {context.children.map(f => FeatureContext.featureContextWrites.writes(f))},
      "global" -> context.global,
      "project" -> context.project
    )
  }

  val CONTEXT_REGEXP: Regex = "^[a-zA-Z0-9-]+$".r
  def readFeatureContext(json: JsValue, global: Boolean): JsResult[FeatureContext] = {
    val name = (json \ "name").asOpt[String].filter(id => CONTEXT_REGEXP.pattern.matcher(id).matches())
    val id = (json \ "id").asOpt[String]

    name
      .map(n => FeatureContext(id.orNull, n, global = global)) // TODO CHANGEME
      .map(JsSuccess(_))
      .getOrElse(JsError("Error reading context"))

  }
}
