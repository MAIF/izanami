package controllers.dto

import controllers.dto.meta.Metadata
import domains.lock.{IzanamiLock, LockInstances}
import play.api.libs.json.Json

case class LockListResult(results: List[IzanamiLock], metadata: Metadata)
object FeatureListResult {
  implicit val cF     = LockInstances.format
  implicit val format = Json.format[LockListResult]
}
