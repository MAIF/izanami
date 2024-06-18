package fr.maif.izanami.datastores

import fr.maif.izanami.datastores.searchEntityImplicits.SearchEntityRow
import fr.maif.izanami.env.Env
import fr.maif.izanami.env.pgimplicits.EnhancedRow
import fr.maif.izanami.errors.{InternalServerError, IzanamiError, TenantDoesNotExists}
import fr.maif.izanami.models.{AbstractFeature, SearchEntity, Tag}
import fr.maif.izanami.utils.Datastore
import io.vertx.sqlclient.Row
import play.api.libs.json.Json
import play.api.mvc.Results

import java.sql.ResultSet
import java.util.{SimpleTimeZone, UUID}
import scala.concurrent.Future

class SearchDatastore(val env: Env) extends Datastore {

  def searchEntities(user: String, query: String): Future[List[SearchEntity]] = {
    env.postgresql.queryAll(
      s"""
         |SELECT origin_table, id, name_search as name, origin_tenant, project FROM izanami.search_all_byusers($$1, $$2);""".stripMargin,
      List(query, user),
    ) { r => r.optSearchEntity() }
  }

  def searchEntitiesByTenant(tenant: String, query: String): Future[List[SearchEntity]] = {
    env.postgresql.queryAll(
      s"""
         |SELECT * FROM search_entities WHERE to_tsquery('english', $$1) @@ searchable_name;""".stripMargin,
      List(query.concat(":*")),
      schemas = Set(tenant)
    ) { r => r.optSearchEntityByTenant(tenant) }

  }
}
object searchEntityImplicits {
  implicit class SearchEntityRow(val row: Row) extends AnyVal {
    def optSearchEntity(): Option[SearchEntity] = {
      for (
        name <- row.optString("name");
        origin_table <- row.optString("origin_table");
        origin_tenant <- row.optString("origin_tenant");
        id <- row.optString("id");
        project<- Some(row.optString("project"))
      ) yield SearchEntity(id = id, name = name, origin_table = origin_table, origin_tenant = origin_tenant, project=project)
    }
    def optSearchEntityByTenant(tenant: String): Option[SearchEntity] = {
      for (
        name <- row.optString("name");
        origin_table <- row.optString("origin_table");
        id <- row.optString("id");
        project<- Some(row.optString("project"))
      ) yield SearchEntity(id = id, name = name, origin_table = origin_table, origin_tenant = tenant, project=project)
    }
  }
}

