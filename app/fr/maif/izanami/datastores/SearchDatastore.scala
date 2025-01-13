package fr.maif.izanami.datastores

import fr.maif.izanami.env.Env
import fr.maif.izanami.env.pgimplicits.EnhancedRow
import fr.maif.izanami.utils.Datastore
import play.api.libs.json.{JsObject, Json}
import fr.maif.izanami.web.SearchController.{SearchEntityObject, SearchEntityType}

import scala.concurrent.Future
class SearchDatastore(val env: Env) extends Datastore {
  private val similarityThresholdParam  =  env.configuration.get[Int]("app.search.similarity-threshold")
  def tenantSearch(
      tenant: String,
      username: String,
      query: String,
      filter: List[Option[SearchEntityType]]
  ): Future[List[(String, JsObject, Double)]] = {
    val searchQuery  = new StringBuilder()
    val extensionsSchema = env.extensionsSchema
    searchQuery.append("WITH ")

    var scoredQueries = List[String]()
    var unionQueries  = List[String]()
    if (filter.isEmpty || filter.contains(Some(SearchEntityObject.Project))|| filter.contains(Some(SearchEntityObject.Feature))) {
      scoredQueries :+=
        s"""
      scored_projects AS (
        SELECT DISTINCT
          p.id as id,
          p.name,
          p.description,
          $extensionsSchema.SIMILARITY(p.name, $$1) AS name_score,
          $extensionsSchema.SIMILARITY(p.description, $$1) AS description_score
        FROM projects p
        LEFT JOIN izanami.users u ON u.username=$$2
        LEFT JOIN izanami.users_tenants_rights utr ON (utr.username=$$2 AND utr.tenant=$$3)
        LEFT JOIN users_projects_rights upr ON (utr.username=$$2 AND p.name=upr.project)
        WHERE utr.level='ADMIN'
        OR upr.level IS NOT NULL
        OR u.admin=true
      )
    """
    }
    if (filter.isEmpty || filter.contains(Some(SearchEntityObject.Project))) {
      unionQueries :+= s"""
      SELECT row_to_json(p.*) as json, GREATEST(p.name_score, p.description_score) AS match_score, 'project' as _type, $$3 as tenant
      FROM scored_projects p
      WHERE p.name_score > $similarityThresholdParam OR p.description_score > $similarityThresholdParam OR p.id::text = '$query'"""
    }

    if (filter.isEmpty || filter.contains(Some(SearchEntityObject.Feature))) {
      scoredQueries :+=
        s"""
      scored_features AS (
        SELECT DISTINCT
          f.id as id,
          f.project,
          f.name,
          f.description,
          $extensionsSchema.SIMILARITY(f.name, $$1) AS name_score,
          $extensionsSchema.SIMILARITY(f.description, $$1) AS description_score
        FROM scored_projects p, features f
        WHERE f.project=p.name
      )
    """
      unionQueries :+= s"""
        SELECT row_to_json(f.*) as json, GREATEST(f.name_score, f.description_score) AS match_score, 'feature' as _type, $$3 as tenant
        FROM scored_features f
        WHERE f.name_score > $similarityThresholdParam OR f.description_score > $similarityThresholdParam OR f.id::text = '$query'"""

    }

    if (filter.isEmpty || filter.contains(Some(SearchEntityObject.Key))) {
      scoredQueries :+=
        s"""
      scored_keys AS (
        SELECT DISTINCT
          k.name,
          k.description,
          $extensionsSchema.SIMILARITY(k.name, $$1) AS name_score,
          $extensionsSchema.SIMILARITY(k.description, $$1) AS description_score
        FROM apikeys k
        LEFT JOIN izanami.users u ON u.username=$$2
        LEFT JOIN izanami.users_tenants_rights utr ON (utr.username=$$2 AND utr.tenant=$$3)
        LEFT JOIN users_keys_rights ukr ON (utr.username=$$2 AND k.name=ukr.apikey)
        WHERE utr.level='ADMIN'
        OR ukr.level IS NOT NULL
        OR u.admin=true
      )
    """
      unionQueries :+= s"""
         SELECT row_to_json(k.*) as json, GREATEST(k.name_score, k.description_score) AS match_score, 'key' as _type, $$3 as tenant
         FROM scored_keys k
         WHERE k.name_score > $similarityThresholdParam OR k.description_score > $similarityThresholdParam"""
    }

    if (filter.isEmpty || filter.contains(Some(SearchEntityObject.Tag))) {
      scoredQueries :+=
        s"""
      scored_tags AS (
        SELECT DISTINCT
          t.name,
          t.description,
          $extensionsSchema.SIMILARITY(t.name, $$1) AS name_score,
          $extensionsSchema.SIMILARITY(t.description, $$1) AS description_score
        FROM tags t
        LEFT JOIN izanami.users u ON u.username=$$2
        LEFT JOIN izanami.users_tenants_rights utr ON (utr.username=$$2 AND utr.tenant=$$3)
        WHERE utr.level IS NOT NULL
        OR u.admin=true
      )
    """
      unionQueries :+= s"""
      SELECT row_to_json(t.*) as json, GREATEST(t.name_score, t.description_score) AS match_score, 'tag' as _type, $$3 as tenant
      FROM scored_tags t
      WHERE t.name_score > $similarityThresholdParam OR t.description_score > $similarityThresholdParam"""
    }

    if (filter.isEmpty || filter.contains(Some(SearchEntityObject.Script))) {
      scoredQueries :+=
        s"""
      scored_scripts AS (
        SELECT DISTINCT
         s.id as name,
         $extensionsSchema.SIMILARITY(s.id, $$1) as name_score
        FROM wasm_script_configurations s
        LEFT JOIN izanami.users u ON u.username=$$2
        LEFT JOIN izanami.users_tenants_rights utr ON (utr.username=$$2 AND utr.tenant=$$3)
        WHERE utr.level IS NOT NULL
        OR u.admin=true
      )
      """
      unionQueries :+= s"""
        SELECT row_to_json(s.*) as json, s.name_score AS match_score, 'script' as _type, $$3 as tenant
        FROM scored_scripts s
        WHERE s.name_score > $similarityThresholdParam"""
    }
    if (filter.isEmpty || filter.contains(Some(SearchEntityObject.GlobalContext))) {
      scoredQueries :+=
        s"""
      scored_global_contexts AS (
        SELECT DISTINCT
          c.parent,
          c.name as name,
          $extensionsSchema.SIMILARITY(c.name, $$1) as name_score
        FROM global_feature_contexts c
        LEFT JOIN izanami.users u ON u.username=$$2
        LEFT JOIN izanami.users_tenants_rights utr ON (utr.username=$$2 AND utr.tenant=$$3)
        WHERE utr.level IS NOT NULL
        OR u.admin=true
      )
       """
      unionQueries :+= s"""
         SELECT row_to_json(gc.*) as json, gc.name_score AS match_score, 'global_context' as _type, $$3 as tenant
         FROM scored_global_contexts gc
         WHERE gc.name_score > $similarityThresholdParam """
    }
    if (filter.isEmpty || filter.contains(Some(SearchEntityObject.LocalContext))) {
      scoredQueries :+=
        s"""
          scored_local_contexts AS (
          SELECT DISTINCT
               c.parent,
               c.project,
               c.name as name,
              $extensionsSchema.SIMILARITY(c.name, $$1) as name_score
              FROM feature_contexts c
              LEFT JOIN izanami.users u ON u.username=$$2
              LEFT JOIN izanami.users_tenants_rights utr ON (utr.username=$$2 AND utr.tenant=$$3)
              WHERE utr.level IS NOT NULL
              OR u.admin=true
          )
    """
      unionQueries :+= s"""
          SELECT row_to_json(lc.*) as json, lc.name_score AS match_score, 'local_context' as _type, $$3 as tenant
          FROM scored_local_contexts lc
          WHERE lc.name_score > $similarityThresholdParam """
    }
    if (filter.isEmpty || filter.contains(Some(SearchEntityObject.Webhook))) {
      scoredQueries :+=
        s"""
          scored_webhooks AS (
             SELECT DISTINCT
               w.name,
                w.description,
                 $extensionsSchema.SIMILARITY(w.name, $$1) as name_score,
                $extensionsSchema.SIMILARITY(w.description, $$1) as description_score
             FROM webhooks w
             LEFT JOIN izanami.users u ON u.username=$$2
             LEFT JOIN izanami.users_tenants_rights utr ON (utr.username=$$2 AND utr.tenant=$$3)
             LEFT JOIN users_webhooks_rights uwr ON (utr.username=$$2 AND w.name=uwr.webhook)
             WHERE utr.level='ADMIN'
             OR uwr.level is not null
             OR u.admin=true
          )
    """
      unionQueries :+= s"""
         SELECT row_to_json(w.*) as json, GREATEST(w.name_score, w.description_score) AS match_score, 'webhook' as _type, $$3 as tenant
         FROM scored_webhooks w
         WHERE w.name_score > $similarityThresholdParam OR w.description_score > $similarityThresholdParam"""
    }

    searchQuery.append(scoredQueries.mkString(","))
    searchQuery.append(unionQueries.mkString(" UNION ALL "))
    searchQuery.append(" ORDER BY match_score DESC LIMIT 10")

    env.postgresql.queryAll(
      searchQuery.toString(),
      List(query, username, tenant),
      schemas = Set(tenant)
    ) { r =>
      for {
        t     <- r.optString("_type")
        json  <- r.optJsObject("json")
        score <- r.optDouble("match_score")
      } yield {
        (t, json, score)
      }
    }
  }
}
