package fr.maif.izanami.models

import fr.maif.izanami.models.features.EvaluationResultWrapper

case class FeatureStrategies(strategies: Map[String, CompleteFeature])

case class EvaluatedCompleteFeature(
    featureStrategies: FeatureStrategies,
    result: EvaluationResultWrapper
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
