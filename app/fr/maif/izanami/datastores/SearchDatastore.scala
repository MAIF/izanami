package fr.maif.izanami.datastores

import fr.maif.izanami.datastores.searchEntityImplicits.SearchEntityRow
import fr.maif.izanami.env.Env
import fr.maif.izanami.env.pgimplicits.EnhancedRow
import fr.maif.izanami.errors.IzanamiError
import fr.maif.izanami.models.{RightLevels, SearchEntity, TenantRight, User}
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
           |  parent,
           |  izanami.SIMILARITY(name, $$1) as similarity_name,
           |  izanami.SIMILARITY(description, $$1) as similarity_description,
           |  GREATEST(
           |    izanami.SIMILARITY(name, $$1),
           |    izanami.SIMILARITY(description, $$1)
           |  ) AS match_score
           |FROM $tenant.search_entities
           |WHERE  izanami.SIMILARITY(name, $$1) > 0.1
           |   OR izanami.SIMILARITY(description, $$1) > 0.1
           |   OR izanami.SOUNDEX(name) = izanami.SOUNDEX($$1)
           |   OR izanami.SOUNDEX(description) = izanami.SOUNDEX($$1)
     """.stripMargin
      }
      .mkString(" UNION ALL ") + "ORDER BY match_score DESC LIMIT 10"

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


    val adminQuery = (index: Int, tenant: String) =>
      s"""
    SELECT
      origin_table,
      id,
      name,
      $$${index + 1}::TEXT AS origin_tenant,
      project,
      description,
      parent,
      izanami.SIMILARITY(name, $$${index}) as similarity_name,
      izanami.SIMILARITY(description, $$${index}) as similarity_description,
      GREATEST(
        izanami.SIMILARITY(name, $$${index}),
        izanami.SIMILARITY(description, $$${index})
      ) AS match_score
    FROM $tenant.search_entities
    WHERE izanami.SIMILARITY(name, $$${index}) > 0.4
      OR izanami.SIMILARITY(description, $$${index}) > 0.4
      OR izanami.SOUNDEX(name) = izanami.SOUNDEX($$${index})
      OR izanami.SOUNDEX(description) = izanami.SOUNDEX($$${index})
  """

    val nonAdminQuery = (index: Int, tenant: String) =>
      s"""
    SELECT
      origin_table,
      id,
      name,
      $$${index + 1}::TEXT AS origin_tenant,
      project,
      description,
      parent,
      izanami.SIMILARITY(name, $$${index}) as similarity_name,
      izanami.SIMILARITY(description, $$${index}) as similarity_description,
      GREATEST(
        izanami.SIMILARITY(name, $$${index}),
        izanami.SIMILARITY(description, $$${index})
      ) AS match_score
    FROM $tenant.search_entities
    WHERE
      ((project = ANY ($$${index + 2}::TEXT[]) AND origin_table IN ($$${index + 3}, $$${index + 4}))
      OR (origin_table=$$${index + 5} AND name = ANY ($$${index + 6}::TEXT[]))
      OR (origin_table=$$${index + 7} AND name = ANY ($$${index + 8}::TEXT[]))
      OR (origin_table=$$${index + 9} AND project IS NULL))
      AND izanami.SIMILARITY(name, $$${index}) > 0.4
      OR izanami.SIMILARITY(description, $$${index}) > 0.4
      OR izanami.SOUNDEX(name) = izanami.SOUNDEX($$${index})
      OR izanami.SOUNDEX(description) = izanami.SOUNDEX($$${index})
  """

    var currentIndex = 1

    val searchQuery = tenantRights.zipWithIndex.map { case ((tenant, right), _) =>
      val queryPart = if (right.level == RightLevels.Admin) {
        val q = adminQuery(currentIndex, tenant)
        currentIndex += 2
        q
      } else {
        val q = nonAdminQuery(currentIndex, tenant)
        currentIndex += 10
        q
      }
      queryPart
    }.mkString(" UNION ALL ") + " ORDER BY match_score DESC LIMIT 10"

    val params = List.newBuilder[AnyRef]
    tenantRights.foreach { case (tenant, tenantRight) =>
      params += query
      params += tenant
      if (tenantRight.level != RightLevels.Admin) {
        params += tenantRight.projects.keys.toArray
        params += "Projects"
        params += "Features"
        params += "Webhooks"
        params += tenantRight.webhooks.keys.toArray
        params += "Apikeys"
        params += tenantRight.keys.keys.toArray
        params += "Tags"
      }
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
        description   <- Some(row.optString("description"));
        parent        <- Some(row.optString("parent"));
        similarity_name <-  Some(row.optDouble("similarity_name"));
        similarity_description <-  Some(row.optDouble("similarity_description"))
      )
        yield SearchEntity(
          id = id,
          name = name,
          origin_table = origin_table,
          origin_tenant = origin_tenant,
          project = project,
          description = description,
          parent = parent,
          similarity_name = similarity_name,
          similarity_description = similarity_description
        )
    }
  }
}
