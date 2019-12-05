package controllers.dto.apikeys

import controllers.dto.meta.Metadata
import domains.apikey.{Apikey, ApikeyInstances}
import play.api.libs.json.Json

case class ApikeyListResult(results: List[Apikey], metadata: Metadata)

object ApikeyListResult {
  implicit val cF     = ApikeyInstances.format
  implicit val format = Json.format[ApikeyListResult]
}
