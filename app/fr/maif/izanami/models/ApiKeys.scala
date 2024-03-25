package fr.maif.izanami.models

import fr.maif.izanami.security.IdGenerator
import fr.maif.izanami.security.IdGenerator.token
import play.api.libs.functional.syntax.toFunctionalBuilderOps
import play.api.libs.json._

import java.util.UUID

case class ApiKeyProject(name: String, id: UUID)

case class ApiKeyWithCompleteRights(tenant: String, clientId: String=null, clientSecret: String = null, name: String, projects: Set[ApiKeyProject] = Set(), enabled: Boolean, legacy: Boolean, admin: Boolean)

case class ApiKey(tenant: String, clientId: String=null, clientSecret: String=null, name: String, projects: Set[String] = Set(), description: String = "", enabled: Boolean = true, legacy: Boolean = false, admin: Boolean = false) {
  def withNewSecret(): ApiKey = {
    copy(clientSecret = token(64))
  }
  def withNewClientId(): ApiKey = {
    copy(clientId = IdGenerator.namedToken(tenant, 16))
  }
}

object ApiKey {
  def read(json: JsValue, tenant: String): JsResult[ApiKey] = (
    (__ \ "clientId").readNullable[String] and
    (__ \ "name").read[String](Reads.pattern(NAME_REGEXP, s"Name does not match regexp ${NAME_REGEXP.pattern}")) and
    (__ \ "description").readNullable[String] and
    (__ \ "enabled").read[Boolean] and
    (__ \ "projects").readWithDefault[Set[String]](Set())(Reads.set(Reads.pattern(NAME_REGEXP, s"Name does not match regexp ${NAME_REGEXP.pattern}"))) and
    (__ \ "admin").readWithDefault(false)
  )((clientId, name, description, enabled, projects, admin) => ApiKey(clientId = clientId.orNull, name=name, tenant=tenant, projects = projects, description=description.getOrElse(""), enabled=enabled, admin=admin)).reads(json)

  implicit val keyWrites: Writes[ApiKey] = { key =>
    Json.obj(
      "clientId" -> key.clientId,
      "clientSecret" -> key.clientSecret,
      "name" -> key.name,
      "description" -> key.description,
      "projects" -> key.projects,
      "enabled" -> key.enabled,
      "admin" -> key.admin
    )
  }

  def extractTenant(clientId: String): Option[String] = {
    if(!clientId.contains("_")) {
      None
    } else {
      clientId.split("_", 2).headOption.filter(_.nonEmpty)
    }
  }
}
