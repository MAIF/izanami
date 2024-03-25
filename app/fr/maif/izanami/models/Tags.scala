package fr.maif.izanami.models

import play.api.libs.json._

import java.util.UUID
import scala.util.matching.Regex

case class Tag(id: UUID, name: String, description: String)
case class TagCreationRequest(name: String, description: String = "")

object Tag {
  val tagRequestReads: Reads[TagCreationRequest] = { json =>
    val maybeRequest =
      for (name <- (json \ "name").asOpt[String].filter(id => TAG_REGEXP.pattern.matcher(id).matches()))
        yield TagCreationRequest(name, description = (json \ "description").asOpt[String].getOrElse(""))

    maybeRequest.map(JsSuccess(_)).getOrElse(JsError("Error reading tag creation request"))
  }
  val tagReads: Reads[Tag] = { json =>
    {
      for (
        name <- (json \ "name").asOpt[String].filter(id => TAG_REGEXP.pattern.matcher(id).matches())
      ) yield JsSuccess(Tag(name = name, description = (json \ "description").asOpt[String].orNull, id = (json \ "id").asOpt[UUID].orNull))
    }.getOrElse(JsError("Error reading tag"))
  }
  private val TAG_REGEXP: Regex = "^[a-zA-Z0-9_-]+$".r

  implicit val tagWrites: Writes[Tag] = { tag =>
    Json.obj(
      "id" -> tag.id,
      "name"        -> tag.name,
      "description" -> tag.description
    )
  }
}
