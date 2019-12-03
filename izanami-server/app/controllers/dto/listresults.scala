package controllers.dto

import domains.abtesting.{Experiment, ExperimentInstances}
import domains.config.{Config, ConfigInstances}
import domains.feature.{Feature, FeatureInstances}
import domains.script.{GlobalScript, GlobalScriptInstances, ScriptInstances}
import domains.user.{User, UserNoPasswordInstances}
import play.api.libs.json.{Format, Json, Writes}

object Metadata {
  implicit val format = Json.format[Metadata]
}

case class Metadata(page: Int, pageSize: Int, count: Int, nbPages: Double)

object ConfigListResult {
  implicit val cF     = ConfigInstances.format
  implicit val format = Json.format[ConfigListResult]
}
case class ConfigListResult(results: Seq[Config], metadata: Metadata)

object FeatureListResult {
  implicit val cF     = FeatureInstances.format
  implicit val format = Json.format[FeatureListResult]
}
case class FeatureListResult(results: Seq[Feature], metadata: Metadata)

object ExperimentListResult {
  implicit val cF     = ExperimentInstances.format
  implicit val format = Json.format[ExperimentListResult]
}
case class ExperimentListResult(results: Seq[Experiment], metadata: Metadata)

object GlobalScriptListResult {

  implicit val format = {
    implicit val cF: Format[GlobalScript] = GlobalScriptInstances.format
    Json.format[GlobalScriptListResult]
  }

  implicit val partialFormat = {
    val write = Writes[GlobalScript] { gs =>
      Json.obj("label" -> gs.name, "value" -> gs.id)
    }
    implicit val sF                       = ScriptInstances.reads
    val reads                             = Json.reads[GlobalScript]
    implicit val cF: Format[GlobalScript] = Format(reads, write)
    Json.format[GlobalScriptListResult]
  }
}
case class GlobalScriptListResult(results: Seq[GlobalScript], metadata: Metadata)

object UserListResult {
  implicit val cF     = UserNoPasswordInstances.format
  implicit val format = Json.format[UserListResult]
}
case class UserListResult(results: Seq[User], metadata: Metadata)
