package controllers.dto.meta

import play.api.libs.json.Json

case class Metadata(page: Int, pageSize: Int, count: Int, nbPages: Double)

object Metadata {
  implicit val format = Json.format[Metadata]
}
