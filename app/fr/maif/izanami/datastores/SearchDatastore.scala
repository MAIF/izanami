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
  def searchEntitiesForAdmin(
      tenants: List[String],
      query: String
  ): Future[Either[IzanamiError, List[SearchEntity]]] = {
    val searchQuery = tenants.zipWithIndex
      .map { case (tenant, index) =>
        val tenantPlaceholder = s"$$${index * 1 + 2}"
        s"""
           |SELECT
           |  origin_table,
           |  id,
           |  name,
           |  $tenantPlaceholder::TEXT AS origin_tenant,
           |  project,
           |  description,
           |  SIMILARITY(name, $$1) AS rank
           |FROM $tenant.search_entities
           |WHERE SIMILARITY(name, $$1)> 0.2
     """.stripMargin

      }
      .mkString(" UNION ALL ") + "ORDER BY rank DESC"

    val params = List.newBuilder[AnyRef]
    params += query
    tenants.foreach { tenant =>
      params += tenant
    }
    env.postgresql
      .queryAll(searchQuery, params.result()) { r => r.optSearchEntity() }
      .map { entities => Right(entities) }
      .recover { case ex =>
        logger.error("Error while searching entities", ex)
        Right(List.empty[SearchEntity])
      }

  }
  def searchEntities(
      tenantRights: Map[String, TenantRight],
      query: String
  ): Future[Either[IzanamiError, List[SearchEntity]]] = {

    val searchQuery = tenantRights.zipWithIndex
      .map { case ((tenant, _), index) =>
        val tenantPlaceholder  = s"$$${index * 4 + 7}"
        val projectPlaceholder = s"$$${index * 4 + 8}"
        val webhookPlaceholder = s"$$${index * 4 + 9}"
        val keyPlaceholder     = s"$$${index * 4 + 10}"
        s"""
           |SELECT
           |  origin_table,
           |  id,
           |  name,
           |  $tenantPlaceholder::TEXT AS origin_tenant,
           |  project,
           |  description,
           |  SIMILARITY(name, $$1) AS rank AS rank
           |FROM $tenant.search_entities
           |WHERE (
           |  (project = ANY ($projectPlaceholder::TEXT[]) AND origin_table IN ($$2, $$3))
           |  OR (origin_table=$$4 AND name = ANY ($webhookPlaceholder::TEXT[]))
           |  OR (origin_table=$$5 AND name = ANY ($keyPlaceholder::TEXT[]))
           |  OR (origin_table=$$6 AND project IS NULL)
           |)
           |AND SIMILARITY(name, $$1) AS rank
     """.stripMargin

      }
      .mkString(" UNION ALL ") + "ORDER BY rank DESC"

    val params = List.newBuilder[AnyRef]
    params += query
    params += "projects"
    params += "features"
    params += "webhooks"
    params += "apikeys"
    params += "tags"
    tenantRights.foreach { case (tenant, tenantRight) =>
      params += tenant
      params += tenantRight.projects.keys.toArray
      params += tenantRight.webhooks.keys.toArray
      params += tenantRight.keys.keys.toArray

    }

    env.postgresql
      .queryAll(searchQuery, params.result()) { r => r.optSearchEntity() }
      .map { entities => Right(entities) }
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
        description   <- Some(row.optString("description"))
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
