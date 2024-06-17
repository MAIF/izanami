package fr.maif.izanami.models

import play.api.libs.json.Format.GenericFormat
import play.api.libs.json.OFormat.oFormatFromReadsAndOWrites
import play.api.libs.json._

import java.util.UUID
import scala.util.matching.Regex

trait ProjectQueryResult

case class Project(id: UUID, name: String, features: List[LightWeightFeature] = List(), description: String) extends ProjectQueryResult

case class EmptyProjectRow() extends ProjectQueryResult

case class ProjectCreationRequest(name: String, description: String)

object Project {
  val projectReads: Reads[ProjectCreationRequest] = { json =>
    (json \ "name")
      .asOpt[String]
      .filter(name => PROJECT_REGEXP.pattern.matcher(name).matches())
      .map(name => JsSuccess(ProjectCreationRequest(name = name, description=(json \ "description").asOpt[String].getOrElse(""))))
      .getOrElse(JsError("Invalid project"))
  }
  private val PROJECT_REGEXP: Regex = "^[a-zA-Z0-9_-]+$".r

  implicit val dbProjectReads: Reads[Project] = { json =>
    {
      for (
        name <- (json \ "name").asOpt[String];
        id <- (json \ "id").asOpt[UUID];
        description   <- (json \ "description").asOpt[String]
      ) yield JsSuccess(Project(name = name, description=description, id=id))
    }.getOrElse(JsError("Error reading project"))
  }

  implicit val projectWrites: Writes[Project] = { project =>
    Json.obj(
      "name"     -> project.name,
      "id"     -> project.id,
      "features" -> {project.features.map(f => Feature.featureWrite.writes(f))},
      "description" -> project.description
    )
  }
}
