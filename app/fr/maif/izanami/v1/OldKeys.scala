package fr.maif.izanami.v1

import fr.maif.izanami.models.ApiKey
import fr.maif.izanami.v1.OldCommons.authorizedPatternReads
import fr.maif.izanami.v1.OldCommons.filterProjects
import play.api.libs.functional.syntax.toFunctionalBuilderOps
import play.api.libs.json.Reads
import play.api.libs.json.__

case class OldKey(
    clientId: String,
    name: String,
    clientSecret: String,
    authorizedPatterns: Seq[AuthorizedPattern],
    admin: Boolean = false
)

object OldKey {
  def toNewKey(tenant: String, key: OldKey, projects: Set[String], shoudlFilterProject: Boolean): ApiKey = {
    val filteredProjects = if(shoudlFilterProject){
      key.authorizedPatterns.flatMap(ap => {
        filterProjects(ap.pattern, projects)
      }).toSet
    } else {
      projects
    }

    ApiKey(
      tenant = tenant,
      clientId = key.clientId,
      clientSecret = key.clientSecret,
      name = key.name,
      projects = filteredProjects,
      enabled = true,
      legacy = true,
      admin=key.admin
    )
  }

  val oldKeyReads: Reads[OldKey] = {
    (
      (__ \ "clientId").read[String].filter(name => "^[@0-9\\p{L} .'-]+$".r.pattern.matcher(name).matches()) and
      (__ \ "name").read[String].filter(name => "^[@0-9\\p{L} .'-]+$".r.pattern.matcher(name).matches()) and
      (__ \ "clientSecret").read[String].filter(name => "^[@0-9\\p{L} .'-]+$".r.pattern.matcher(name).matches()) and
      (__ \ "authorizedPatterns")
        .read[Seq[AuthorizedPattern]]
        .orElse((__ \ "authorizedPatterns").read[Seq[AuthorizedPattern]]) and
      (__ \ "admin").read[Boolean].orElse(Reads.pure(false))
    )(OldKey.apply _)
  }
}
