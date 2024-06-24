package fr.maif.izanami.datastores

import fr.maif.izanami.datastores.searchEntityImplicits.SearchEntityRow
import fr.maif.izanami.env.Env
import fr.maif.izanami.env.pgimplicits.EnhancedRow
import fr.maif.izanami.errors.{InternalServerError, IzanamiError}
import fr.maif.izanami.models.{SearchEntity, TenantRight, User}
import fr.maif.izanami.utils.Datastore
import io.vertx.sqlclient.Row

import scala.concurrent.Future

class SearchDatastore(val env: Env) extends Datastore {
  def searchEntities(tenants: Map[String, TenantRight], query: String): Future[Either[IzanamiError, List[SearchEntity]]] = {
    val unionQueries = tenants
      .map { case (tenantName, tenantRight) =>
        val projectsList = tenantRight.projects.keys.map(p => s"'$p'").mkString(", ")
        s"""
        SELECT
          origin_table,
          id,
          name,
          '$tenantName'::TEXT AS origin_tenant,
          project,
          description,
          ts_rank_cd(searchable_name, websearch_to_tsquery('english', '${query}')) AS rank
        FROM $tenantName.search_entities
        WHERE project = ANY (ARRAY[$projectsList]::TEXT[])
          AND searchable_name @@ websearch_to_tsquery('english', '${query}')
      """
      }.mkString(" UNION ALL ") + " ORDER BY rank DESC"

    env.postgresql.queryAll(unionQueries.stripMargin) { r => r.optSearchEntity() }.map{entities => Right (entities)}
      .recover { case ex =>
      logger.error("Error while searching entities", ex)
      Right(List.empty[SearchEntity])
    }

  }
}
object searchEntityImplicits {
  implicit class SearchEntityRow(val row: Row) extends AnyVal {
    def optSearchEntity(): Option[SearchEntity] = {
      for (
        name          <- row.optString("name");
        origin_table  <- row.optString("origin_table");
        origin_tenant <- row.optString("origin_tenant");
        id            <- row.optString("id");
        project       <- Some(row.optString("project"));
        description   <- row.optString("description")
      )
        yield SearchEntity(
          id = id,
          name = name,
          origin_table = origin_table,
          origin_tenant = origin_tenant,
          project = project,
          description = description
        )
    }
  }
}
