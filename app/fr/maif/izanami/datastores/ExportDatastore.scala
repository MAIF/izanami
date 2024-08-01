package fr.maif.izanami.datastores

import fr.maif.izanami.datastores.apiKeyImplicites.ApiKeyRow
import fr.maif.izanami.datastores.featureImplicits.FeatureRow
import fr.maif.izanami.datastores.projectImplicits.ProjectRow
import fr.maif.izanami.datastores.tagImplicits.TagRow
import fr.maif.izanami.env.Env
import fr.maif.izanami.env.pgimplicits.EnhancedRow
import fr.maif.izanami.errors.{InternalServerError, IzanamiError}
import fr.maif.izanami.models.{ApiKey, LightWeightFeature, Project, Tag}
import fr.maif.izanami.utils.{Datastore, Helpers}
import fr.maif.izanami.utils.syntax.implicits.BetterJsValue
import fr.maif.izanami.wasm.WasmConfig
import fr.maif.izanami.web.ExportController
import fr.maif.izanami.web.ExportController.{ExportRequest, ExportedType, ProjectExportResult, ProjectType, TenantExportRequest, TenantExportResult}
import fr.maif.izanami.web.ImportController.{Fail, ImportConflictStrategy, MergeOverwrite, Skip}
import io.vertx.sqlclient.{SqlConnection, Tuple}
import play.api.libs.json.{JsArray, JsObject, JsValue, Json}

import java.util.concurrent.atomic.AtomicInteger
import scala.concurrent.Future

class ExportDatastore(val env: Env) extends Datastore {

  def importTenantData(
      tenant: String,
      entries: Map[ExportedType, Seq[JsObject]],
      conflictStrategy: ImportConflictStrategy
  ): Future[Either[IzanamiError, Unit]] = {
    env.postgresql.executeInTransaction(conn => {
      Future
        .sequence(
          entries.toSeq
            .sortBy { case (t, _) =>
              t.order
            }
            .map { case (t, rows) =>
              genericImport(tenant, t.table, rows, conn, conflictStrategy)
            }
        )
        .map(eitherSeq => Helpers.sequence(eitherSeq).map(_ => ()))
    })
  }

  def genericImport(
      tenant: String,
      table: String,
      rows: Seq[JsObject],
      conn: SqlConnection,
      conflictStrategy: ImportConflictStrategy
  ): Future[Either[IzanamiError, Unit]] = {
    env.postgresql
      .queryOne(
        s"""
         |INSERT INTO $table SELECT * FROM json_populate_recordset(null::$table, $$1)
         |ON CONFLICT ${conflictStrategy match {
          case Fail           => ""
          case MergeOverwrite => "" // TODO
          case Skip           => "DO NOTHING"
        }}
         |""".stripMargin,
        List(JsArray(rows).vertxJsValue),
        schemas = Set(tenant),
        conn = Some(conn)
      ) { r => Some(()) }
      .map(o => o.toRight(InternalServerError("")))
  }

  def exportTenantData(
      tenant: String,
      request: TenantExportRequest
  ): Future[List[JsObject]] = {
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
         |    SELECT DISTINCT p.name as pname, p.id, (jsonb_build_object('_type', 'project', 'row', to_jsonb(p.*)::jsonb)) as result
         |    FROM projects p
         |    ${projectFilter.map(_ => s"WHERE p.name=ANY($$${paramIndex.getAndIncrement()})").getOrElse("")}
         |), feature_results AS (
         |    SELECT DISTINCT f.script_config, f.id as fid, f.name as fname, jsonb_build_object('_type', 'feature' , 'row', to_jsonb(f.*)) as result
         |    FROM project_results, features f
         |    WHERE f.project=project_results.pname
         |), tag_results AS (
         |    SELECT DISTINCT jsonb_build_object('_type', 'tag', 'row', row_to_json(t.*)::jsonb) as result
         |    FROM tags t ${projectFilter
        .map(_ => """
                              |, features_tags ft, feature_results
                              |    WHERE ft.feature=feature_results.fid
                              |    AND t.name=ft.tag""".stripMargin)
        .getOrElse("")}
         |), features_tags_results AS (
         |    SELECT DISTINCT jsonb_build_object('_type', 'feature_tag', 'row', to_jsonb(ft.*)) as result
         |    FROM features_tags ft, feature_results
         |    WHERE ft.feature=feature_results.fid
         |), overload_results AS (
         |    SELECT DISTINCT fcs.project, fcs.local_context, fcs.global_context, jsonb_build_object('_type', 'overload', 'row', to_jsonb(fcs.*)) as result
         |    FROM feature_contexts_strategies fcs, feature_results f, project_results p
         |    WHERE fcs.feature=f.fname
         |    AND fcs.project=p.pname
         |), local_context_results AS (
         |    SELECT DISTINCT jsonb_build_object('_type', 'local_context', 'row', to_jsonb(fc.*)) as result
         |    FROM feature_contexts fc${projectFilter
        .map(_ => """
                    |    , overload_results ors
                    |    WHERE fc.id = ors.local_context
                    |    AND fc.project = ors.project""".stripMargin)
        .getOrElse("")}
         |), global_context_results AS (
         |    SELECT DISTINCT jsonb_build_object('_type', 'global_context', 'row', to_jsonb(fc.*)) as result
         |    FROM global_feature_contexts fc${projectFilter
        .map(_ => """
           |    , overload_results ors
           |    WHERE fc.id = ors.global_context
           |""".stripMargin)
        .getOrElse("")}
         |), key_results AS (
         |    SELECT DISTINCT k.name, jsonb_build_object('_type', 'key', 'row', to_jsonb(k.*)) as result
         |    FROM apikeys k
         |    ${keyFilter.map(_ => s"WHERE k.name=ANY($$${paramIndex.getAndIncrement()})").getOrElse("")}
         |), apikeys_projects_result AS (
         |    SELECT DISTINCT jsonb_build_object('_type', 'apikey_project', 'row', to_jsonb(ap.*)) as result
         |    FROM apikeys_projects ap, key_results kr
         |    WHERE ap.apikey=kr.name
         |), webhook_results AS (
         |    SELECT DISTINCT w.id, w.name, jsonb_build_object('_type', 'webhook', 'row', to_jsonb(w.*)) as result
         |    FROM webhooks w
         |     ${webhookFilter.map(_ => s"WHERE w.name=ANY($$${paramIndex.getAndIncrement()})").getOrElse("")}
         |), webhooks_features_result AS (
         |    SELECT DISTINCT jsonb_build_object('_type', 'webhook_feature', 'row', to_jsonb(wf.*)) as result
         |    FROM webhooks_features wf, webhook_results w, feature_results
         |    WHERE wf.webhook=w.id
         |), webhooks_projects_result AS (
         |    SELECT DISTINCT jsonb_build_object('_type', 'webhook_project', 'row', to_jsonb(wp.*)) as result
         |    FROM webhooks_projects wp, webhook_results w, project_results
         |    WHERE wp.webhook=w.id
         |), users_webhooks_rights_result AS (
         |    SELECT DISTINCT jsonb_build_object('_type', 'user_webhook_right', 'row', to_jsonb(uwr.*)) as result
         |    FROM users_webhooks_rights uwr, webhook_results
         |    WHERE uwr.webhook=webhook_results.name
         |), project_rights AS (
         |    SELECT DISTINCT jsonb_build_object('_type', 'project_right', 'row', to_jsonb(upr.*)) as result
         |    FROM users_projects_rights upr, project_results pr
         |    WHERE upr.project = pr.pname
         |), key_rights AS (
         |    SELECT DISTINCT jsonb_build_object('_type', 'key_right', 'row', to_jsonb(ukr.*)) as result
         |    FROM users_keys_rights ukr, key_results kr
         |    WHERE ukr.apikey=kr.name
         |), script_resuls AS (
         |    SELECT DISTINCT jsonb_build_object('_type', 'script', 'row', to_jsonb(wsc.*)) as result
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
         |SELECT result FROM project_rights
         |UNION ALL
         |SELECT result FROM key_rights
         |UNION ALL
         |SELECT result FROM features_tags_results
         |UNION ALL
         |SELECT result FROM apikeys_projects_result
         |UNION ALL
         |SELECT result FROM webhook_results
         |UNION ALL
         |SELECT result FROM webhooks_projects_result
         |UNION ALL
         |SELECT result FROM webhooks_features_result
         |UNION ALL
         |SELECT result FROM users_webhooks_rights_result
         |""".stripMargin,
      List(projectFilter, keyFilter, webhookFilter).flatMap(_.toList).map(s => s.toArray),
      schemas = Set(tenant)
    ) { r =>
      {
        r.optJsObject("result")
      }
    }
  }

}
