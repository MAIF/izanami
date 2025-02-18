package fr.maif.izanami.models

import fr.maif.izanami.models.LightWeightFeatureWithUsageInformation.writeLightWeightFeatureWithUsageInformation
import play.api.libs.json.Format.GenericFormat
import play.api.libs.json._

import java.util.UUID
import scala.util.matching.Regex


case class Project(id: UUID, name: String, features: List[LightWeightFeature] = List(), description: String)
case class ProjectWithUsageInformation(id: UUID, name: String, features: List[LightWeightFeatureWithUsageInformation] = List(), description: String)
object ProjectWithUsageInformation {
  def fromProject(project: Project, features: List[LightWeightFeatureWithUsageInformation]): ProjectWithUsageInformation = {
    ProjectWithUsageInformation(id = project.id, name = project.name, features = features, description = project.description)
  }

  def projectWithUsageInformationWrites: Writes[ProjectWithUsageInformation] = project => {
    Json.obj(
      "id" -> project.id,
      "description" -> project.description,
      "name" -> project.name,
      "features" -> Json.toJson(project.features)(Writes.list(writeLightWeightFeatureWithUsageInformation))
    )
  }
}

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
