package fr.maif.izanami.models

import fr.maif.izanami.models.features.EvaluationResultWrapper

case class FeatureStrategies(
    projectId: String,
    strategies: Map[String, CompleteFeature]
)

case class EvaluatedCompleteFeature(
    featureStrategies: FeatureStrategies,
    result: EvaluationResultWrapper
) {
  def baseFeature: CompleteFeature = {
    featureStrategies.strategies("")
  }
}

case object FeatureStrategies {
  def apply(projectId: String, f: CompleteFeature): FeatureStrategies = {
    FeatureStrategies(projectId, Map("" -> f))
  }
}
