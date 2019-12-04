package controllers.dto.feature

import controllers.dto.meta.Metadata
import domains.feature.{Feature, FeatureInstances}
import play.api.libs.json.Json

case class FeatureListResult(results: List[Feature], metadata: Metadata)
object FeatureListResult {
  implicit val cF     = FeatureInstances.format
  implicit val format = Json.format[FeatureListResult]
}
