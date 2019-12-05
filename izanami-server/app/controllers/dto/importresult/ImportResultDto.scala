package controllers.dto.importresult

import controllers.dto.error.ApiErrors
import domains.ImportResult
import play.api.libs.json.Json
import play.api.mvc.Results

case class ImportResultDto(success: Int = 0, errors: ApiErrors)

object ImportResultDto {
  implicit val format = Json.format[ImportResultDto]

  def fromImportResult(ir: ImportResult) = ImportResultDto(ir.success, ApiErrors.fromErrors(ir.errors))

  def toHttpResult(ir: ImportResult) = Results.Ok(Json.toJson(fromImportResult(ir)))
}
