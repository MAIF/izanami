package fr.maif.izanami.models

import fr.maif.izanami.errors.IzanamiError
import play.api.libs.json.JsValue
import io.otoroshi.wasm4s.scaladsl.WasmIntegration

import scala.concurrent.Future
import scala.concurrent.ExecutionContext

case class FeatureStrategies(strategies: Map[String, CompleteFeature]) {

  def evaluate(
      requestContext: RequestContext,
      wasmIntegration: WasmIntegration,
      wasmAllowed: Boolean
  )(implicit ec: ExecutionContext): Future[Either[IzanamiError, EvaluatedCompleteFeature]] = {
    val context = requestContext.contextAsString
    val strategyToUse = if (context.isBlank) {
      strategies("")
    } else {
      strategies
        .filter { case (ctx, f) => context.startsWith(ctx) }
        .toSeq
        .sortWith {
          case ((c1, _), (c2, _)) if c1.length < c2.length => false
          case _                                           => true
        }
        .headOption
        .map(_._2)
        .getOrElse(strategies(""))
    }
    strategyToUse.value(requestContext, wasmIntegration, wasmAllowed).map(either =>
      either.map(v => EvaluatedCompleteFeature(this, v))
    )(ec)
  }

}

case class EvaluatedCompleteFeature(
    featureStrategies: FeatureStrategies,
    result: JsValue
) {
  def baseFeature: CompleteFeature = {
    featureStrategies.strategies("")
  }
}

case object FeatureStrategies {
  def apply(f: CompleteFeature): FeatureStrategies = {
    FeatureStrategies(Map("" -> f))
  }
}
