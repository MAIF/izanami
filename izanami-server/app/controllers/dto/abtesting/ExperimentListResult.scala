package controllers.dto.abtesting

import controllers.dto.meta.Metadata
import domains.abtesting.{Experiment, ExperimentInstances}
import play.api.libs.json.Json

case class ExperimentListResult(results: List[Experiment], metadata: Metadata)

object ExperimentListResult {
  implicit val cF     = ExperimentInstances.format
  implicit val format = Json.format[ExperimentListResult]
}
