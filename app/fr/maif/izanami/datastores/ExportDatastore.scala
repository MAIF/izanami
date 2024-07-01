package fr.maif.izanami.datastores

import fr.maif.izanami.datastores.apiKeyImplicites.ApiKeyRow
import fr.maif.izanami.datastores.featureImplicits.FeatureRow
import fr.maif.izanami.datastores.projectImplicits.ProjectRow
import fr.maif.izanami.datastores.tagImplicits.TagRow
import fr.maif.izanami.env.Env
import fr.maif.izanami.env.pgimplicits.EnhancedRow
import fr.maif.izanami.errors.IzanamiError
import fr.maif.izanami.models.{ApiKey, LightWeightFeature, Project, Tag}
import fr.maif.izanami.utils.Datastore
import fr.maif.izanami.wasm.WasmConfig
import fr.maif.izanami.web.ExportController
import fr.maif.izanami.web.ExportController.{
  ExportRequest,
  ProjectExportResult,
  TenantExportRequest,
  TenantExportResult
}
import io.vertx.sqlclient.Tuple
import play.api.libs.json.{JsArray, JsObject, JsValue, Json}

import java.util.concurrent.atomic.AtomicInteger
import scala.concurrent.Future

class ExportDatastore(val env: Env) extends Datastore {

  def tenantData(tenant: String, request: TenantExportRequest): Future[List[JsObject]] = {
    val paramIndex    = new AtomicInteger(1)
    val projectFilter = request.projects match {
      case ExportController.ExportItemList(projects) => Some(projects)
      case _                                         => None
    }

    val keyFilter = request.keys match {
      case ExportController.ExportItemList(keys) => Some(keys)
      case _                                     => None
    }

    val webhookFilter = request.webhooks match {
      case ExportController.ExportItemList(webhooks) => Some(webhooks)
      case _                                         => None
    }

    env.postgresql.queryAll(
      s"""
         |WITH project_results AS (
         |    SELECT p.name as pname, (jsonb_build_object('_type', 'project', 'row', to_jsonb(p.*)::jsonb)) as result
         |    FROM projects p
         |    ${projectFilter.map(_ => s"WHERE p.name=ANY($$${paramIndex.getAndIncrement()})").getOrElse("")}
         |), feature_results AS (
         |    SELECT f.script_config, f.id as fid, f.name as fname, jsonb_build_object('_type', 'feature' , 'row', to_jsonb(f.*)) as result
         |    FROM project_results, features f
         |    WHERE f.project=project_results.pname
         |), tag_results AS (
         |    SELECT jsonb_build_object('_type', 'tag', 'row', row_to_json(t.*)::jsonb) as result
         |    FROM tags t, features_tags ft, feature_results
         |    WHERE ft.feature=feature_results.fid
         |    AND t.name=ft.tag
         |), overload_results AS (
         |    SELECT fcs.project, fcs.local_context, fcs.global_context, jsonb_build_object('_type', 'overload', 'row', to_jsonb(fcs.*)) as result
         |    FROM feature_contexts_strategies fcs, feature_results f, project_results p
         |    WHERE fcs.feature=f.fname
         |    AND fcs.project=p.pname
         |), local_context_results AS (
         |    SELECT jsonb_build_object('_type', 'local_context', 'row', to_jsonb(fc.*)) as result
         |    FROM feature_contexts fc, overload_results ors
         |    WHERE fc.id = ors.local_context
         |    AND fc.project = ors.project
         |), global_context_results AS (
         |    SELECT jsonb_build_object('_type', 'global_context', 'row', to_jsonb(fc.*)) as result
         |    FROM global_feature_contexts fc, overload_results ors
         |    WHERE fc.id = ors.global_context
         |), key_results AS (
         |    SELECT k.name, jsonb_build_object('_type', 'key', 'row', to_jsonb(k.*)) as result
         |    FROM apikeys k
         |    ${keyFilter.map(_ => s"WHERE k.name=ANY($$${paramIndex.getAndIncrement()})").getOrElse("")}
         |), webhook_results AS (
         |    SELECT jsonb_build_object('_type', 'webhook', 'row', to_jsonb(w.*)) as result
         |    FROM webhooks w
         |     ${webhookFilter.map(_ => s"WHERE w.name=ANY($$${paramIndex.getAndIncrement()})").getOrElse("")}
         |), project_rights AS (
         |    SELECT jsonb_build_object('type', 'project_right', 'row', to_jsonb(upr.*)) as result
         |    FROM users_projects_rights upr, project_results pr
         |    WHERE upr.project = pr.pname
         |), key_rights AS (
         |    SELECT jsonb_build_object('type', 'key_right', 'row', to_jsonb(ukr.*)) as result
         |    FROM users_keys_rights ukr, key_results kr
         |    WHERE ukr.apikey=kr.name
         |), script_resuls AS (
         |    SELECT jsonb_build_object('type', 'script', 'row', to_jsonb(wsc.*)) as result
         |    FROM wasm_script_configurations wsc, feature_results fr
         |    WHERE wsc.id = fr.script_config
         |)
         |SELECT result FROM tag_results
         |UNION ALL
         |SELECT result FROM project_results
         |UNION ALL
         |SELECT result FROM feature_results
         |UNION ALL
         |SELECT result FROM global_context_results
         |UNION ALL
         |SELECT result FROM local_context_results
         |UNION ALL
         |SELECT result FROM overload_results
         |UNION ALL
         |SELECT result FROM key_results
         |UNION ALL
         |SELECT result FROM webhook_results
         |UNION ALL
         |SELECT result FROM project_rights
         |UNION ALL
         |SELECT result FROM key_rights
         |""".stripMargin,
      List(projectFilter, keyFilter, webhookFilter).flatMap(_.toList).map(s => s.toArray),
      schemas = Set(tenant)
    ) { r =>
      {
        r.optJsObject("result")
      }
    }
  }

  def readFeatures(tenant: String, projects: Set[String]): Future[JsObject] = {
    env.postgresql
      .queryAll(
        s"""
         |SELECT f.project, json_build_object('feature', f.*,  'overloads', json_arrayagg(row_to_json(fcs.*))) as json_result
         |FROM features f
         |LEFT JOIN feature_contexts_strategies fcs ON (f.name=fcs.feature AND f.project=fcs.project)
         |WHERE f.project=ANY($$1)
         |GROUP BY f.id
         |""".stripMargin,
        List(projects.toArray),
        schemas = Set(tenant)
      ) { r =>
        {
          val json                   = r.optJsObject("json_result").get
          val overloadJsArray        = (json \ "overloads").as[JsArray].value
          val localContextsByProject = overloadJsArray
            .map(overload => {
              for (
                localCtx <- (overload \ "local_context").asOpt[String];
                project  <- (overload \ "project").asOpt[String]
              ) yield (localCtx, project)
            })
            .flatMap(_.toList)

          val globalContexts = overloadJsArray.flatMap(overload => (overload \ "global_context").asOpt[String].toList)

          val maybeFeature =
            for (
              project <- r.optString("project");
              json    <- r.optJsObject("json_result")
            ) yield (project, json)

          maybeFeature.map(f => (globalContexts, localContextsByProject, f))
        }
      }
      .flatMap(featuresAndOverloads => {

        val featureProjects: List[(String, JsObject)] = featuresAndOverloads.map(l => l._3)
        val featureMap: Map[String, List[JsObject]]   = featureProjects.groupMap(_._1)(_._2)

        val projects                  = featuresAndOverloads.map(_._3).map(_._1).toSet
        val globalContexts            = featuresAndOverloads.flatMap(_._1).toSet
        val localContextsWithProjects = featuresAndOverloads.flatMap(_._2).toSet
        for (
          globalContextsJson           <- readGlobalContexts(tenant, globalContexts);
          localContextJsonWithProjects <- readLocalContexts(tenant, localContextsWithProjects);
          localContextsByProject        = localContextJsonWithProjects.groupMap(_._1)(_._2)
        ) yield {
          val projectMap = featureMap.map {
            case (project, features) => {
              val contexts = localContextsByProject.getOrElse(project, List())
              Json.obj("features" -> features, "contexts" -> contexts)
            }
          }
          Json.obj(
            "projects" -> projectMap,
            "globalContexts" -> globalContextsJson
          )
        }
      })
  }

  private def readTags(tenant: String, features: Set[String]): Future[List[JsObject]] = {
    env.postgresql.queryAll(
      s"""
         |SELECT (row_to_json(t.*)::jsonb || json_build_object('features', json_arrayagg(ft.feature))::jsonb) as result
         |FROM tags t, features_tags ft
         |WHERE ft.feature=ANY($$1)
         |AND t.name=ft.tag
         |GROUP BY t.id
         |""".stripMargin,
      List(features.toArray),
      schemas = Set(tenant)
    ) { r =>
      {
        r.optJsObject("result")
      }
    }
  }

  private def readGlobalContexts(tenant: String, contexts: Set[String]): Future[List[JsObject]] = {
    env.postgresql.queryAll(
      s"""
         |SELECT row_to_json(c.*) as result
         |FROM global_feature_contexts c
         |WHERE c.id=ANY($$1)
         |""".stripMargin,
      List(contexts.toArray),
      schemas = Set(tenant)
    ) { r => r.optJsObject("result") }
  }

  private def readLocalContexts(tenant: String, contexts: Set[(String, String)]): Future[List[(String, JsObject)]] = {
    if (contexts.isEmpty) {
      Future.successful(List())
    } else {
      env.postgresql.queryAll(
        s"""
           |SELECT c.project, row_to_json(c.*) as result
           |FROM feature_contexts c
           |WHERE (c.id, c.project) IN (${contexts
          .map { case (context, project) => s"('$context', '$project')" }
          .mkString(", ")})
           |""".stripMargin,
        List(),
        schemas = Set(tenant)
      ) { r =>
        {
          for (
            project <- r.optString("project");
            json    <- r.optJsObject("result")
          ) yield (project, json)
        }
      }
    }
  }
}
