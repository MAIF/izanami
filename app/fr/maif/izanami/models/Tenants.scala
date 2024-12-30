package fr.maif.izanami.models

import fr.maif.izanami.models.Project.dbProjectReads
import play.api.libs.json.Format.GenericFormat
import play.api.libs.json._

import scala.util.matching.Regex

case class Tenant(name: String, projects: List[Project] = List(), tags: List[Tag] = List(), description: String = "") {
  def addProject(project: Project): Tenant = Tenant(name, projects :+ project)
}
case class TenantCreationRequest(name: String, description: String = "")

object Tenant {
  val tenantReads: Reads[TenantCreationRequest] = { json =>
    (json \ "name")
      .asOpt[String]
      .filter(id => TENANT_REGEXP.pattern.matcher(id).matches())
      .map(name => TenantCreationRequest(name, description=(json \ "description").asOpt[String].getOrElse("")))
      .map(JsSuccess(_))
      .getOrElse(JsError("Invalid tenant"))
  }
  private val TENANT_REGEXP: Regex = "^[a-z0-9_-]+$".r

  implicit val tenantWrite: Writes[Tenant] = { tenant =>
    Json.obj(
      "name"     -> tenant.name,
      "projects" -> tenant.projects,
      "description" -> tenant.description,
      "tags" -> tenant.tags
    )
  }
}
