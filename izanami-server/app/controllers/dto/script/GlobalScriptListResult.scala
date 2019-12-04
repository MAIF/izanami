package controllers.dto.script

import controllers.dto.meta.Metadata
import domains.script.{GlobalScript, GlobalScriptInstances, ScriptInstances}
import play.api.libs.json.{Format, Json, Writes}

case class GlobalScriptListResult(results: List[GlobalScript], metadata: Metadata)

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
