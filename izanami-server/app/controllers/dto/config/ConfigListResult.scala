package controllers.dto.config

import controllers.dto.meta.Metadata
import domains.config.{Config, ConfigInstances}
import play.api.libs.json.Json

case class ConfigListResult(results: List[Config], metadata: Metadata)

object ConfigListResult {
  implicit val cF     = ConfigInstances.format
  implicit val format = Json.format[ConfigListResult]
}
