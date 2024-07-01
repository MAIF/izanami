package fr.maif.izanami.datastores

import fr.maif.izanami.datastores.ImportExportDatastore.TableMetadata
import fr.maif.izanami.env.Env
import fr.maif.izanami.env.pgimplicits.EnhancedRow
import fr.maif.izanami.errors.{InternalServerError, IzanamiError, PartialImportFailure}
import fr.maif.izanami.models.{ExportedType, KeyRightType, ProjectRightType, WebhookRightType}
import fr.maif.izanami.models.ExportedType.exportedTypeToString
import fr.maif.izanami.utils.Datastore
import fr.maif.izanami.utils.syntax.implicits.BetterJsValue
import fr.maif.izanami.web.ExportController.TenantExportRequest
import fr.maif.izanami.web.ImportController.ImportConflictStrategy
import fr.maif.izanami.web.{ExportController, ImportController}
import io.vertx.core.json.JsonArray
import io.vertx.sqlclient.SqlConnection
import play.api.libs.json.{JsObject, Json}

import java.util.concurrent.atomic.AtomicInteger
import scala.concurrent.Future

class ImportExportDatastore(val env: Env) extends Datastore {

  private def tableMetadata(tenant: String, table: String): Future[Option[TableMetadata]] = {
    env.postgresql.queryOne(
      s"""
         |SELECT ccu.constraint_name as pk_constraint, array_agg(distinct cs.column_name) as table_columns
         |FROM pg_constraint co, information_schema.constraint_column_usage ccu, pg_namespace n, information_schema.columns cs
         |WHERE co.contype='p'
         |AND ccu.constraint_name=co.conname
         |AND ccu.table_name=$$2
         |AND ccu.table_schema=$$1
         |AND co.connamespace=n.oid
         |AND n.nspname=$$1
         |AND cs.table_schema=$$1
         |AND cs.table_name=$$2
         |AND cs.is_generated='NEVER'
         |GROUP BY ccu.constraint_name
    """.stripMargin,
      List(tenant, table)
    ) { r =>
      for (
        constraint <- r.optString("pk_constraint");
        columns    <- r.optStringArray("table_columns")
      ) yield TableMetadata(table = table, primaryKeyConstraint = constraint, tableColumns = columns.toSet)
    }
  }

  def importTenantData(
      tenant: String,
      entries: Map[ExportedType, Seq[JsObject]],
      conflictStrategy: ImportConflictStrategy
  ): Future[Either[IzanamiError, Unit]] = {

    Future
      .sequence(
        entries.toSeq
          .sortBy { case (t, _) =>
            t.order
          }
          .map(t => tableMetadata(tenant, t._1.table).map(maybeMetdata => (maybeMetdata, t._2, t._1)))
      )
      .flatMap(s => {
        if (s.exists(_._1.isEmpty)) {
          // TODO precise table in error
          Future.successful(Left(InternalServerError(s"Failed to fetch metadata for one table")))
        } else {
          env.postgresql.executeInTransaction(conn => {
            s
              .collect { case (Some(metadata), jsons, exportedType) => (metadata, jsons, exportedType) }
              .foldLeft(Future.successful(Right(())): Future[Either[Map[ExportedType, Seq[JsObject]], Unit]])(
                (agg, t) => {
                  agg.flatMap(previousResult => {
                    val f = conflictStrategy match {
                      case ImportController.MergeOverwrite                 => importTenantDataWithMergeOnConflict(tenant, t._1, t._2)
                      case ImportController.Skip                           => importTenantDataWithSkipOnConflict(tenant, t._1, t._2)
                      case ImportController.Fail if previousResult.isRight =>
                        importTenantDataWithFailOnConflict(tenant, t._1, t._2, conn)
                      case _                                               => Future.successful(Right(()))
                    }

                    f
                      .flatMap(e => {
                        updateUserTenantRightIfNeeded(
                          tenant,
                          entries,
                          if (conflictStrategy == ImportController.Fail) Some(conn) else None
                        )
                          .map(_ => e)
                      })
                      .map(either =>
                        either.left.map(jsons =>
                          jsons.map(json => Json.obj("row" -> json, "_type" -> exportedTypeToString(t._3)))
                        )
                      )
                      .map(either =>
                        (either, previousResult) match {
                          case (Left(err), Left(errs)) => {
                            Left(errs + (t._3 -> err))
                          }
                          case (Left(err), Right(_))   => Left(Map(t._3 -> err))
                          case (Right(_), Right(_))    => Right(())
                          case (Right(_), Left(errs))  => Left(errs)
                        }
                      )
                  })
                }
              )
              .map(either => either.left.map(failedElements => PartialImportFailure(failedElements)))
          })
        }
      })
  }

  def importTenantDataWithMergeOnConflict(
      tenant: String,
      metadata: TableMetadata,
      rows: Seq[JsObject]
  ): Future[Either[Seq[JsObject], Unit]] = {

    val vertxJsonArray = new JsonArray(Json.toJson(rows).toString())
    val cols           = metadata.tableColumns.mkString(",")

    env.postgresql
      .queryOne(
        s"""
             |INSERT INTO ${metadata.table} (${cols}) select ${cols} from json_populate_recordset(null::${metadata.table}, $$1)
             |ON CONFLICT ON CONSTRAINT ${metadata.primaryKeyConstraint} DO UPDATE SET($cols)=(select $cols from json_populate_recordset(NULL::${metadata.table}, $$1))
             |""".stripMargin,
        List(vertxJsonArray),
        schemas = Set(tenant)
      ) { _ => Some(()) }
      .map(_ => Right(()))
      .recoverWith {
        case _ => {
          logger.info(s"There has been import errors, switching to unit import mode for ${metadata.table}")
          rows.foldLeft(Future.successful(Right(())): Future[Either[Seq[JsObject], Unit]])((facc, row) => {
            facc.flatMap(acc =>
              env.postgresql
                .queryOne(
                  s"""
                       |INSERT INTO ${metadata.table} (${cols}) select ${cols} from json_populate_record(null::${metadata.table}, $$1)
                       |ON CONFLICT ON CONSTRAINT ${metadata.primaryKeyConstraint} DO UPDATE SET($cols)=((select $cols from json_populate_record(NULL::${metadata.table}, $$1)))
                       |""".stripMargin,
                  List(row.vertxJsValue),
                  schemas = Set(tenant)
                ) { _ => Some(()) }
                .map(_ => Right(()))
                .recoverWith {
                  case _ => {
                    logger.info(s"Import of following row failed for table ${metadata.table} : ${row}")
                    Future.successful(Left(row))
                  }
                }
                .map(either => {
                  (either, acc) match {
                    case (Left(failedRow), Left(failedRows)) => Left(failedRows.appended(failedRow))
                    case (Right(_), Right(_))                => Right(())
                    case (Left(failedRow), Right(_))         => Left(Seq(failedRow))
                    case (Right(_), Left(failedRows))        => Left(failedRows)
                  }
                })
            )
          })
        }
      }
  }

  def importTenantDataWithFailOnConflict(
      tenant: String,
      metadata: TableMetadata,
      rows: Seq[JsObject],
      conn: SqlConnection
  ): Future[Either[Seq[JsObject], Unit]] = {
    val vertxJsonArray = new JsonArray(Json.toJson(rows).toString())
    val cols           = metadata.tableColumns.mkString(",")
    env.postgresql
      .queryOne(
        s"""
           |INSERT INTO ${metadata.table} (${cols}) select ${cols} from json_populate_recordset(null::${metadata.table}, $$1)
           |""".stripMargin,
        List(vertxJsonArray),
        schemas = Set(tenant),
        conn = Some(conn)
      ) { _ => Some(()) }
      .map(_ => Right(()))
      .recoverWith {
        case _ => {
          logger.info(
            s"There has been import errors, since strategy is to fail on conflict, all elements will be attempted"
          )
          rows.foldLeft(Future.successful(Right(())): Future[Either[Seq[JsObject], Unit]])((facc, row) => {
            facc.flatMap {
              case Left(failedRow) => Future.successful((Left(failedRow)))
              case Right(_)        => {
                env.postgresql
                  .queryOne(
                    s"""
                         |INSERT INTO ${metadata.table} (${cols}) select ${cols} from json_populate_record(null::${metadata.table}, $$1)
                         |""".stripMargin,
                    List(row.vertxJsValue),
                    schemas = Set(tenant),
                    conn = Some(conn)
                  ) { _ => Some(()) }
                  .map(_ => Right(()))
                  .recoverWith {
                    case _ => {
                      logger.info(s"Import of following row failed for table ${metadata.table} : ${row}")
                      Future.successful(Left(Seq(row)))
                    }
                  }
              }
            }
          })
        }
      }
  }

  def importTenantDataWithSkipOnConflict(
      tenant: String,
      metadata: TableMetadata,
      rows: Seq[JsObject]
  ): Future[Either[Seq[JsObject], Unit]] = {
    val vertxJsonArray = new JsonArray(Json.toJson(rows).toString())
    val cols           = metadata.tableColumns.mkString(",")
    env.postgresql
      .queryOne(
        s"""
         |INSERT INTO ${metadata.table} (${cols}) select ${cols} from json_populate_recordset(null::${metadata.table}, $$1)
         |ON CONFLICT ON CONSTRAINT ${metadata.primaryKeyConstraint} DO NOTHING
         |""".stripMargin,
        List(vertxJsonArray),
        schemas = Set(tenant)
      ) { _ => Some(()) }
      .map(_ => Right(()))
      .recoverWith {
        case _ => {
          logger.info(s"There has been import errors, switching to unit import mode for ${metadata.table}")
          rows.foldLeft(Future.successful(Right(())): Future[Either[Seq[JsObject], Unit]])((facc, row) => {
            facc.flatMap(acc =>
              env.postgresql
                .queryOne(
                  s"""
               |INSERT INTO ${metadata.table} (${cols}) select ${cols} from json_populate_record(null::${metadata.table}, $$1)
               |ON CONFLICT ON CONSTRAINT ${metadata.primaryKeyConstraint} DO NOTHING
               |""".stripMargin,
                  List(row.vertxJsValue),
                  schemas = Set(tenant)
                ) { _ => Some(()) }
                .map(_ => Right(()))
                .recoverWith {
                  case _ => {
                    logger.info(s"Import of following row failed for table ${metadata.table} : ${row}")
                    Future.successful(Left(row))
                  }
                }
                .map(either => {
                  (either, acc) match {
                    case (Left(failedRow), Left(failedRows)) => Left(failedRows.appended(failedRow))
                    case (Right(_), Right(_))                => Right(())
                    case (Left(failedRow), Right(_))         => Left(Seq(failedRow))
                    case (Right(_), Left(failedRows))        => Left(failedRows)
                  }
                })
            )
          })
        }
      }
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
         |    SELECT DISTINCT fcs.project, fcs.local_context, fcs.global_context, jsonb_build_object('_type', 'overload', 'row', to_jsonb(fcs.*) - 'context' - 'context_path') as result
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
         |), script_results AS (
         |    SELECT DISTINCT jsonb_build_object('_type', 'script', 'row', to_jsonb(wsc.*)) as result
         |    FROM wasm_script_configurations wsc, feature_results fr
         |    WHERE wsc.id = fr.script_config
         |)${if (request.userRights) """, users_webhooks_rights_result AS (
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
         |)""" else ""}
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
         |SELECT result FROM script_results
         |${if (request.userRights) s"""UNION ALL
         |SELECT result FROM users_webhooks_rights_result
         |UNION ALL
         |SELECT result FROM project_rights
         |UNION ALL
         |SELECT result FROM key_rights""" else ""}
         |""".stripMargin,
      List(projectFilter, keyFilter, webhookFilter).flatMap(_.toList).map(s => s.toArray),
      schemas = Set(tenant)
    ) { r =>
      {
        r.optJsObject("result")
      }
    }
  }

  def updateUserTenantRightIfNeeded(
      tenant: String,
      importedData: Map[ExportedType, Seq[JsObject]],
      maybeConn: Option[SqlConnection]
  ): Future[Unit] = {
    val usernames = importedData
      .collect {
        case (WebhookRightType | ProjectRightType | KeyRightType, jsons) => {
          jsons.map(json => (json \ "username").asOpt[String])
        }
      }
      .flatten
      .collect { case Some(username) =>
        username
      }
    if (usernames.isEmpty) {
      Future.successful(())
    } else {
      env.postgresql
        .queryRaw(
          s"""
             |INSERT INTO izanami.users_tenants_rights (username, tenant, level) VALUES(unnest($$1::text[]), $$2, 'READ')
             |ON CONFLICT (username, tenant) DO NOTHING
             |""".stripMargin,
          List(usernames.toArray, tenant),
          conn = maybeConn
        ) { _ => Some(()) }
        .map(_ => ())
        .recoverWith(ex => {
          // User right on tenant may fail if user does not exist
          Future.successful(())
        })
    }
  }

}

object ImportExportDatastore {
  case class TableMetadata(table: String, primaryKeyConstraint: String, tableColumns: Set[String])
}
