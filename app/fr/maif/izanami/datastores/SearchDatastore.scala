package fr.maif.izanami.datastores

import fr.maif.izanami.datastores.searchEntityImplicits.SearchEntityRow
import fr.maif.izanami.env.Env
import fr.maif.izanami.env.pgimplicits.EnhancedRow
import fr.maif.izanami.errors.IzanamiError
import fr.maif.izanami.models.{RightLevels, SearchEntity, TenantRight, User}
import fr.maif.izanami.utils.Datastore
import io.vertx.sqlclient.Row
import play.api.libs.json.{JsObject, Json}

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

  def search(username: String, query: String, tenants: Set[String]): Future[List[(String, JsObject)]] = {
    Future.sequence(tenants.map(tenant => tenantSearch(tenant, username, query))).map(_.flatten.toList)
  }

  def tenantSearch(tenant: String, username: String, query: String): Future[List[(String, JsObject)]] = {
    env.postgresql.queryAll(
      s"""
         |WITH scored_projects AS (
         |    SELECT
         |        p.name,
         |        p.description,
         |        izanami.SIMILARITY(p.name, $$1) as name_score,
         |        izanami.SIMILARITY(p.description, $$1) as description_score
         |    FROM projects p
         |    LEFT JOIN izanami.users u ON u.username=$$2
         |    LEFT JOIN izanami.users_tenants_rights utr ON (utr.username=$$2 AND utr.tenant=$$3)
         |    LEFT JOIN users_projects_rights upr ON (utr.username=$$2 AND p.name=upr.project)
         |    WHERE utr.level='ADMIN'
         |    OR upr.level is not null
         |    OR u.admin=true
         |), scored_features AS (
         |    SELECT
         |        f.project,
         |        f.name,
         |        f.description,
         |        izanami.SIMILARITY(f.name, $$1) as name_score,
         |        izanami.SIMILARITY(f.description, $$1) as description_score
         |    FROM scored_projects p, features f
         |    WHERE f.project=p.name
         |), scored_keys AS (
         |    SELECT
         |        k.name,
         |        k.description,
         |        izanami.SIMILARITY(k.name, $$1) as name_score,
         |        izanami.SIMILARITY(k.description, $$1) as description_score
         |    FROM apikeys k
         |    LEFT JOIN izanami.users u ON u.username=$$2
         |    LEFT JOIN izanami.users_tenants_rights utr ON (utr.username=$$2 AND utr.tenant=$$3)
         |    LEFT JOIN users_keys_rights ukr ON (utr.username=$$2 AND k.name=ukr.apikey)
         |    WHERE utr.level='ADMIN'
         |    OR ukr.level is not null
         |    OR u.admin=true
         |), scored_tags AS (
         |    SELECT
         |        t.name,
         |        t.description,
         |        izanami.SIMILARITY(t.name, $$1) as name_score,
         |        izanami.SIMILARITY(t.description, $$1) as description_score
         |    FROM tags t
         |    LEFT JOIN izanami.users u ON u.username=$$2
         |    LEFT JOIN izanami.users_tenants_rights utr ON (utr.username=$$2 AND utr.tenant=$$3)
         |    WHERE utr.level IS NOT NULL
         |    OR u.admin=true
         |), scored_scripts AS (
         |    SELECT
         |        s.id as name,
         |        izanami.SIMILARITY(s.id, $$1) as name_score
         |    FROM wasm_script_configurations s
         |    LEFT JOIN izanami.users u ON u.username=$$2
         |    LEFT JOIN izanami.users_tenants_rights utr ON (utr.username=$$2 AND utr.tenant=$$3)
         |    WHERE utr.level IS NOT NULL
         |    OR u.admin=true
         | ), scored_global_contexts AS (
         |        SELECT
         |        c.parent,
         |        c.name as name,
         |        izanami.SIMILARITY(c.name, $$1) as name_score
         |    FROM global_feature_contexts c
         |    LEFT JOIN izanami.users u ON u.username=$$2
         |    LEFT JOIN izanami.users_tenants_rights utr ON (utr.username=$$2 AND utr.tenant=$$3)
         |    WHERE utr.level IS NOT NULL
         |    OR u.admin=true
         | ), scored_local_contexts AS (
         |    SELECT
         |        c.parent,
         |        c.global_parent,
         |        c.project,
         |        c.name as name,
         |        izanami.SIMILARITY(c.name, $$1) as name_score
         |    FROM feature_contexts c
         |    LEFT JOIN izanami.users u ON u.username=$$2
         |    LEFT JOIN izanami.users_tenants_rights utr ON (utr.username=$$2 AND utr.tenant=$$3)
         |    WHERE utr.level IS NOT NULL
         |    OR u.admin=true
         | ), scored_webhooks AS (
         |    SELECT
         |        w.name,
         |        w.description,
         |        izanami.SIMILARITY(w.name, $$1) as name_score,
         |        izanami.SIMILARITY(w.description, $$1) as description_score
         |    FROM webhooks w
         |    LEFT JOIN izanami.users u ON u.username=$$2
         |    LEFT JOIN izanami.users_tenants_rights utr ON (utr.username=$$2 AND utr.tenant=$$3)
         |    LEFT JOIN users_webhooks_rights uwr ON (utr.username=$$2 AND w.name=uwr.webhook)
         |    WHERE utr.level='ADMIN'
         |    OR uwr.level is not null
         |    OR u.admin=true
         | )
         |SELECT row_to_json(f.*) as json, GREATEST(f.name_score, f.description_score) AS match_score, 'feature' as _type, $$3 as tenant
         |FROM scored_features f
         |WHERE f.name_score > 0.2 OR f.description_score > 0.2
         |UNION ALL
         |SELECT row_to_json(p.*) as json, GREATEST(p.name_score, p.description_score) AS match_score, 'project' as _type, $$3 as tenant
         |FROM scored_projects p
         |WHERE p.name_score > 0.2 OR p.description_score > 0.2
         |UNION ALL
         |SELECT row_to_json(k.*) as json, GREATEST(k.name_score, k.description_score) AS match_score, 'key' as _type, $$3 as tenant
         |FROM scored_keys k
         |WHERE k.name_score > 0.2 OR k.description_score > 0.2
         |UNION ALL
         |SELECT row_to_json(t.*) as json, GREATEST(t.name_score, t.description_score) AS match_score, 'tag' as _type, $$3 as tenant
         |FROM scored_tags t
         |WHERE t.name_score > 0.2 OR t.description_score > 0.2
         |UNION ALL
         |SELECT row_to_json(s.*) as json, s.name_score AS match_score, 'script' as _type, $$3 as tenant
         |FROM scored_scripts s
         |WHERE s.name_score > 0.2
         |UNION ALL
         |SELECT row_to_json(gc.*) as json, gc.name_score AS match_score, 'global_context' as _type, $$3 as tenant
         |FROM scored_global_contexts gc
         |WHERE gc.name_score > 0.2
         |UNION ALL
         |SELECT row_to_json(lc.*) as json, lc.name_score AS match_score, 'local_context' as _type, $$3 as tenant
         |FROM scored_local_contexts lc
         |WHERE lc.name_score > 0.2
         |UNION ALL
         |SELECT row_to_json(w.*) as json, GREATEST(w.name_score, w.description_score) AS match_score, 'webhook' as _type, $$3 as tenant
         |FROM scored_webhooks w
         |WHERE w.name_score > 0.2 OR w.description_score > 0.2
         |ORDER BY match_score DESC LIMIT 10
         |""".stripMargin,
      List(query, username, tenant),
      schemas = Set(tenant)
    ) { r =>
      {
        for (
          t    <- r.optString("_type");
          json <- r.optJsObject("json")
        ) yield {
          (t, json)
        }
      }
    }
  }

  def searchEntities(
      tenantRights: Map[String, TenantRight],
      query: String
  ): Future[Either[IzanamiError, List[SearchEntity]]] = {

    val adminQuery = (index: Int, tenant: String) => s"""
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

    val nonAdminQuery = (index: Int, tenant: String) => s"""
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

    val searchQuery = tenantRights.zipWithIndex
      .map { case ((tenant, right), _) =>
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
      }
      .mkString(" UNION ALL ") + " ORDER BY match_score DESC LIMIT 10"

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
        name                   <- row.optString("name");
        origin_table           <- row.optString("origin_table");
        origin_tenant          <- row.optString("origin_tenant");
        id                     <- row.optString("id");
        project                <- Some(row.optString("project"));
        description            <- Some(row.optString("description"));
        parent                 <- Some(row.optString("parent"));
        similarity_name        <- Some(row.optDouble("similarity_name"));
        similarity_description <- Some(row.optDouble("similarity_description"))
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
