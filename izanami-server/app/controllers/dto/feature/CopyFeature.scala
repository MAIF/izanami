package controllers.dto.feature

import domains.Key
import domains.feature.{Feature, FeatureInstances}
import play.api.libs.json.Json

case class CopyRequest(from: Key, to: Key, default: Boolean)

object CopyRequest {
  implicit val format = Json.format[CopyRequest]
}

case class CopyNodeResponse(features: List[Feature])
object CopyNodeResponse {
  import play.api.libs.json._
  implicit val fRead  = FeatureInstances.format
  implicit val format = Json.writes[CopyNodeResponse]
}
