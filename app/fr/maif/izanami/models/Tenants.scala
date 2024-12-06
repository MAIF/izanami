package fr.maif.izanami.models

import fr.maif.izanami.models.Project.dbProjectReads
import play.api.libs.json.Format.GenericFormat
import play.api.libs.json._

import fr.maif.izanami.models.RightLevels.RightLevel
import scala.util.matching.Regex

case class Tenant(name: String, projects: List[Project] = List(), tags: List[Tag] = List(), description: String = "",
                 defaultOIDCRightLevel: Option[RightLevel] = None) {
  def addProject(project: Project): Tenant = Tenant(name, projects :+ project)
}
case class TenantCreationRequest(name: String, description: String = "", defaultOIDCRightLevel: Option[RightLevel] = None)

object Tenant {
  val tenantReads: Reads[TenantCreationRequest] = { json =>
    (json \ "name")
      .asOpt[String]
      .filter(id => TENANT_REGEXP.pattern.matcher(id).matches())
      .map(name => TenantCreationRequest(name,
        description=(json \ "description").asOpt[String].getOrElse(""),
        defaultOIDCRightLevel=(json \ "defaultOIDCRightLevel").asOpt[String].map(level => level.toLowerCase() match {
            case "admin" => RightLevels.Admin
            case "read" => RightLevels.Read
            case "write" => RightLevels.Write
          })))
      .map(JsSuccess(_))
      .getOrElse(JsError("Invalid tenant"))
  }
  private val TENANT_REGEXP: Regex = "^[a-z0-9_-]+$".r

  implicit val tenantWrite: Writes[Tenant] = { tenant =>
    Json.obj(
      "name"     -> tenant.name,
      "projects" -> tenant.projects,
      "description" -> tenant.description,
      "tags" -> tenant.tags,
      "defaultOIDCRightLevel" -> tenant.defaultOIDCRightLevel
    )
  }
}
