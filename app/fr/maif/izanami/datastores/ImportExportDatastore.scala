package fr.maif.izanami.datastores

import fr.maif.izanami.datastores.ImportExportDatastore.{DBImportResult, TableMetadata, UnitDBImportResult}
import fr.maif.izanami.env.Env
import fr.maif.izanami.env.pgimplicits.EnhancedRow
import fr.maif.izanami.errors.{InternalServerError, IzanamiError, PartialImportFailure}
import fr.maif.izanami.events.EventOrigin.ImportOrigin
import fr.maif.izanami.events.{
  PreviousProject,
  SourceFeatureCreated,
  SourceFeatureUpdated,
  SourceProjectCreated,
  SourceProjectUpdated
}
import fr.maif.izanami.models.{
  ExportedType,
  FeatureType,
  FeatureWithOverloads,
  KeyRightType,
  Project,
  ProjectRightType,
  ProjectType,
  Tenant,
  WebhookRightType
}
import fr.maif.izanami.models.ExportedType.exportedTypeToString
import fr.maif.izanami.utils.Datastore
import fr.maif.izanami.utils.syntax.implicits.BetterJsValue
import fr.maif.izanami.web.ExportController.{projectExportResultWrites, ExportResult, TenantExportRequest}
import fr.maif.izanami.web.ImportController.{ImportConflictStrategy, MergeOverwrite, Replace}
import fr.maif.izanami.web.{ExportController, ImportController, ImportResult, UserInformation}
import io.vertx.core.json.JsonArray
import io.vertx.sqlclient.SqlConnection
import play.api.libs.json.{JsObject, Json}

import java.util.UUID
import java.util.concurrent.atomic.AtomicInteger
import scala.concurrent.Future

class ImportExportDatastore(val env: Env) extends Datastore {
  val extensionSchema = env.extensionsSchema

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
      conflictStrategy: ImportConflictStrategy,
      user: UserInformation
  ): Future[Either[IzanamiError, Unit]] = {
    Tenant.isTenantValid(tenant)
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
              .foldLeft(
                Future.successful(Right(Map())): Future[Either[IzanamiError, Map[ExportedType, UnitDBImportResult]]]
              )((agg, t) => {
                val maybeId = if (t._3 == FeatureType || t._3 == ProjectType) Some("id") else None
                agg.flatMap {
                  case Right(previousResult) => {
                    val f: Future[Either[IzanamiError, UnitDBImportResult]] = conflictStrategy match {
                      case ImportController.MergeOverwrite =>
                        importTenantDataWithMergeOnConflict(tenant, t._1, t._2, maybeId).map(r => Right(r))
                      case ImportController.Skip           =>
                        importTenantDataWithSkipOnConflict(tenant, t._1, t._2, maybeId).map(r => Right(r))
                      case ImportController.Fail           =>
                        importTenantDataWithFailOnConflict(tenant, t._1, t._2, conn).map {
                          case Right(_)    =>
                            maybeId
                              .map(idCol =>
                                t._2.map(json => (json \ idCol).asOpt[String]).collect { case Some(id) =>
                                  id
                                }
                              )
                              .map(ids => Right(UnitDBImportResult(createdElements = ids.toSet)))
                              .getOrElse(Right(UnitDBImportResult()))
                          case Left(jsons) => Left(PartialImportFailure(Map(t._3 -> jsons)))
                        }
                      case Replace                         =>
                        throw new IllegalArgumentException("Replace strategy can't be used for tenant import")
                    }

                    f.flatMap {
                      case Left(err) => Future.successful(Left(err): Either[IzanamiError, UnitDBImportResult])
                      case Right(r)  =>
                        updateUserTenantRightIfNeeded(
                          tenant,
                          entries,
                          if (conflictStrategy == ImportController.Fail) Some(conn) else None
                        ).map(_ => Right(r): Either[IzanamiError, UnitDBImportResult])
                    }.map(e => e.map(v => previousResult + (t._3 -> v)))
                  }
                  case Left(err)             => Future.successful(Left(err))
                }
              })
              .map(e => e.map(m => DBImportResult.from(m)))
              .flatMap {
                case Left(err)     => (Future.successful(Left(err)): Future[Either[IzanamiError, Unit]])
                case Right(result) => {
                  val projectNamesBydId = entries
                    .get(ProjectType)
                    .toSeq
                    .flatten
                    .map(json => {
                      for (
                        id   <- (json \ "id").asOpt[UUID];
                        name <- (json \ "name").asOpt[String]
                      ) yield (id, name)
                    })
                    .collect { case Some(t) =>
                      t
                    }
                    .toMap

                  Future
                    .sequence(result.createdProjects.map(projectId => {
                      env.eventService.emitEvent(
                        tenant,
                        SourceProjectCreated(
                          tenant = tenant,
                          id = projectId,
                          name = projectNamesBydId(UUID.fromString(projectId)),
                          user = user.username,
                          origin = ImportOrigin,
                          authentication = user.authentication
                        )
                      )(conn)
                    }))
                    .flatMap(_ => {
                      if (result.updatedProjects.nonEmpty) {
                        val previousProjectNames: Future[Map[UUID, String]] =
                          if (conflictStrategy == MergeOverwrite && entries.get(FeatureType).exists(s => s.nonEmpty)) {
                            env.postgresql
                              .queryAll(
                                s"""
                                   |SELECT id, name FROM "${tenant}".projects WHERE id=ANY($$1)
                                   |""".stripMargin,
                                List(result.updatedProjects.map(s => UUID.fromString(s)).toArray)
                              ) { r =>
                                {
                                  for (
                                    id   <- r.optUUID("id");
                                    name <- r.optString("name")
                                  ) yield (id, name)
                                }
                              }
                              .map(l => l.toMap)
                              .recover(_ => Map())
                          } else {
                            Future.successful(Map())
                          }
                        previousProjectNames.flatMap(ids => {
                          Future.sequence(result.updatedProjects.map(projectId => {
                            env.eventService.emitEvent(
                              tenant,
                              SourceProjectUpdated(
                                tenant = tenant,
                                id = projectId,
                                name = projectNamesBydId(UUID.fromString(projectId)),
                                previous = PreviousProject(ids.get(UUID.fromString(projectId)).orNull),
                                user = user.username,
                                origin = ImportOrigin,
                                authentication = user.authentication
                              )
                            )(conn)
                          }))
                        })
                      } else {
                        Future.successful(())
                      }
                    })
                    .flatMap(_ => {
                      env.datastores.features
                        .findActivationStrategiesForFeatures(
                          tenant,
                          result.createdFeatures ++ result.updatedFeatures,
                          conn = Some(conn)
                        )
                        .map(m => m.map { case (feature, strat) => (feature, FeatureWithOverloads(strat)) })
                        .flatMap(strategiesByFeature => {
                          Future
                            .sequence(result.createdFeatures.map(created => {
                              strategiesByFeature
                                .get(created)
                                .map(strategies => {
                                  env.eventService.emitEvent(
                                    tenant,
                                    SourceFeatureCreated(
                                      id = created,
                                      project = strategies.baseFeature().project,
                                      tenant = tenant,
                                      user = user.username,
                                      feature = strategies,
                                      authentication = user.authentication,
                                      origin = ImportOrigin
                                    )
                                  )(conn = conn)
                                })
                                .getOrElse(Future.successful(()))
                            }))
                            .flatMap(_ => {
                              if (result.updatedFeatures.nonEmpty) {
                                val previousFeatureStates: Future[Map[String, FeatureWithOverloads]] = if (
                                  conflictStrategy == MergeOverwrite && entries.get(FeatureType).exists(s => s.nonEmpty)
                                ) {
                                  env.datastores.features
                                    .findActivationStrategiesForFeatures(
                                      tenant,
                                      entries(FeatureType).map(f => (f \ "id").as[String]).toSet
                                    )
                                    .map(m => m.map { case (f, s) => (f, FeatureWithOverloads(s)) })
                                    .recover(_ => Map())
                                } else {
                                  Future.successful(Map())
                                }
                                previousFeatureStates.flatMap(previousStates => {
                                  Future.sequence(
                                    result.updatedFeatures
                                      .filter(updated => {
                                        !strategiesByFeature
                                          .get(updated)
                                          .exists(newStrat => previousStates.get(updated).contains(newStrat))
                                      })
                                      .map(updated => {
                                        strategiesByFeature
                                          .get(updated)
                                          .map(strategies => {
                                            env.eventService.emitEvent(
                                              tenant,
                                              SourceFeatureUpdated(
                                                id = updated,
                                                project = strategies.baseFeature().project,
                                                tenant = tenant,
                                                user = user.username,
                                                feature = strategies,
                                                previous = previousStates.get(updated).orNull,
                                                authentication = user.authentication,
                                                origin = ImportOrigin
                                              )
                                            )(conn = conn)
                                          })
                                          .getOrElse(Future.successful(()))
                                      })
                                  )
                                })
                              } else {
                                Future.successful(())
                              }
                            })
                          // Stop feature part

                        })
                    })
                    .map(_ => Right(()))
                }
              }
          })
        }
      })
  }

  def importTenantDataWithMergeOnConflict(
      tenant: String,
      metadata: TableMetadata,
      rows: Seq[JsObject],
      maybeId: Option[String]
  ): Future[UnitDBImportResult] = {
    Tenant.isTenantValid(tenant)
    val vertxJsonArray = new JsonArray(Json.toJson(rows).toString())
    val cols           = metadata.tableColumns.mkString(",")

    env.postgresql.executeInTransaction(conn => {
      env.postgresql
        .queryRaw(s"SET CONSTRAINTS ALL DEFERRED", List(), conn = Some(conn)) { _ => () }
        .flatMap(_ => {
          env.postgresql
            .queryRaw(
              s"""
                 |INSERT INTO "${tenant}".${metadata.table} (${cols}) select ${cols} from json_populate_recordset(null::"${tenant}".${metadata.table}, $$1)
                 |ON CONFLICT ON CONSTRAINT ${metadata.primaryKeyConstraint} DO UPDATE SET($cols)=(select $cols from json_populate_recordset(NULL::"${tenant}".${metadata.table}, $$1))
                 |RETURNING (xmax = 0) AS inserted ${maybeId.map(idCol => s", ${idCol}::TEXT as id").getOrElse("")}
                 |""".stripMargin,
              List(vertxJsonArray),
              conn = Some(conn)
            ) { rows =>
              {
                maybeId
                  .map(_ => {
                    val insertedMap = rows
                      .map(r => {
                        (for (
                          id       <- r.optString("id");
                          inserted <- r.optBoolean("inserted")
                        ) yield (id, inserted))
                      })
                      .collect { case Some(r) =>
                        r
                      }
                      .groupMap(_._2)(_._1)

                    UnitDBImportResult(
                      createdElements = insertedMap.getOrElse(true, List[String]()).toSet,
                      updatedElements = insertedMap.getOrElse(false, List[String]()).toSet
                    )
                  })
                  .getOrElse(UnitDBImportResult())
              }
            }
            .recoverWith {
              case _ => {
                logger.info(s"There has been import errors, switching to unit import mode for ${metadata.table}")
                rows.foldLeft(Future.successful(UnitDBImportResult()))((facc, row) => {
                  facc.flatMap(acc =>
                    env.postgresql
                      .queryOne(
                        s"""
                           |INSERT INTO "${tenant}".${metadata.table} (${cols}) select ${cols} from json_populate_record(null::${metadata.table}, $$1)
                           |ON CONFLICT ON CONSTRAINT ${metadata.primaryKeyConstraint} DO UPDATE SET($cols)=((select $cols from json_populate_record(NULL::${metadata.table}, $$1)))
                           |RETURNING (xmax = 0) AS inserted ${maybeId
                          .map(idCol => s", ${idCol}::TEXT as id")
                          .getOrElse("")}
                           |""".stripMargin,
                        List(row.vertxJsValue)
                      ) { r =>
                        {
                          (for (
                            id       <- r.optString("id");
                            inserted <- r.optBoolean("inserted")
                          ) yield (id, inserted))
                            .map {
                              case (id, false) => acc.addUpdatedElements(id)
                              case (id, true)  => acc.addCreatedElements(id)
                            }
                        }
                      }
                      .map(r => r.getOrElse(acc))
                      .recoverWith {
                        case _ => {
                          logger.info(s"Import of following row failed for table ${metadata.table} : ${row}")
                          Future.successful(acc.addFailedElement(row))
                        }
                      }
                  )
                })
              }
            }
        })
    })

  }

  def importTenantDataWithFailOnConflict(
      tenant: String,
      metadata: TableMetadata,
      rows: Seq[JsObject],
      conn: SqlConnection
  ): Future[Either[Seq[JsObject], Unit]] = {
    Tenant.isTenantValid(tenant)
    val vertxJsonArray = new JsonArray(Json.toJson(rows).toString())
    val cols           = metadata.tableColumns.mkString(",")
    env.postgresql
      .queryRaw(s"SET CONSTRAINTS ALL DEFERRED", List(), conn = Some(conn)) { _ => () }
      .flatMap(_ =>
        env.postgresql
          .queryOne(
            s"""
           |INSERT INTO "${tenant}".${metadata.table} (${cols}) select ${cols} from json_populate_recordset(null::"${tenant}".${metadata.table}, $$1)
           |""".stripMargin,
            List(vertxJsonArray),
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
                       |INSERT INTO "${tenant}".${metadata.table} (${cols}) select ${cols} from json_populate_record(null::${metadata.table}, $$1)
                       |""".stripMargin,
                        List(row.vertxJsValue),
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
      )
  }

  def importTenantDataWithSkipOnConflict(
      tenant: String,
      metadata: TableMetadata,
      rows: Seq[JsObject],
      maybeId: Option[String]
  ): Future[UnitDBImportResult] = {
    Tenant.isTenantValid(tenant)
    val vertxJsonArray = new JsonArray(Json.toJson(rows).toString())
    val cols           = metadata.tableColumns.mkString(",")
    env.postgresql.executeInTransaction(conn => {
      env.postgresql
        .queryRaw(s"SET CONSTRAINTS ALL DEFERRED", List(), conn = Some(conn)) { _ => () }
        .flatMap(_ => {
          env.postgresql
            .queryRaw(
              s"""
                 |INSERT INTO "${tenant}".${metadata.table} (${cols}) select ${cols} from json_populate_recordset(null::"${tenant}".${metadata.table}, $$1)
                 |ON CONFLICT ON CONSTRAINT ${metadata.primaryKeyConstraint} DO NOTHING
                 |RETURNING (xmax = 0) AS inserted ${maybeId.map(idCol => s", ${idCol}::TEXT as id").getOrElse("")}
                 |""".stripMargin,
              List(vertxJsonArray),
              conn = Some(conn)
            ) { rows =>
              {
                maybeId
                  .map(_ => {
                    val insertedIds = rows
                      .map(r => {
                        (for (
                          id       <- r.optString("id");
                          inserted <- r.optBoolean("inserted")
                        ) yield (id, inserted))
                      })
                      .collect { case Some(r @ (featureId, true)) =>
                        featureId
                      }
                      .toSet

                    UnitDBImportResult(createdElements = insertedIds)
                  })
                  .getOrElse(UnitDBImportResult())
              }
            }
            .recoverWith {
              case _ => {
                logger.info(s"There has been import errors, switching to unit import mode for ${metadata.table}")
                rows.foldLeft(Future.successful(UnitDBImportResult()): Future[UnitDBImportResult])((facc, row) => {
                  facc.flatMap(acc =>
                    env.postgresql
                      .queryOne(
                        s"""
                           |INSERT INTO "${tenant}".${metadata.table} (${cols}) select ${cols} from json_populate_record(null::${metadata.table}, $$1)
                           |ON CONFLICT ON CONSTRAINT ${metadata.primaryKeyConstraint} DO NOTHING
                           |RETURNING (xmax = 0) AS inserted ${maybeId
                          .map(idCol => s", ${idCol}::TEXT as id")
                          .getOrElse("")}
                           |""".stripMargin,
                        List(row.vertxJsValue)
                      ) { r =>
                        {
                          maybeId.flatMap(_ => {
                            r.optBoolean("inserted")
                              .filter(r => r)
                              .flatMap(_ => r.optString("id"))
                              .map(id => acc.addCreatedElements(id))
                          })
                        }
                      }
                      .map(r => r.getOrElse(acc))
                      .recoverWith {
                        case _ => {
                          logger.info(s"Import of following row failed for table ${metadata.table} : ${row}")
                          Future.successful(acc.addFailedElement(row))
                        }
                      }
                  )
                })
              }
            }
        })
    })
  }

  def exportTenantData(
      tenant: String,
      request: TenantExportRequest
  ): Future[List[JsObject]] = {
    Tenant.isTenantValid(tenant)
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
         |    FROM "${tenant}".projects p
         |    ${projectFilter.map(_ => s"WHERE p.name=ANY($$${paramIndex.getAndIncrement()})").getOrElse("")}
         |), feature_results AS (
         |    SELECT DISTINCT f.script_config, f.id as fid, f.name as fname, jsonb_build_object('_type', 'feature' , 'row', to_jsonb(f.*)) as result
         |    FROM project_results, "${tenant}".features f
         |    WHERE f.project=project_results.pname
         |), tag_results AS (
         |    SELECT DISTINCT jsonb_build_object('_type', 'tag', 'row', row_to_json(t.*)::jsonb) as result
         |    FROM "${tenant}".tags t ${projectFilter
        .map(_ => """
         |, features_tags ft, feature_results
         |    WHERE ft.feature=feature_results.fid
         |    AND t.name=ft.tag""".stripMargin)
        .getOrElse("")}
         |), features_tags_results AS (
         |    SELECT DISTINCT jsonb_build_object('_type', 'feature_tag', 'row', to_jsonb(ft.*)) as result
         |    FROM "${tenant}".features_tags ft, feature_results
         |    WHERE ft.feature=feature_results.fid
         |), overload_results AS (
         |    SELECT DISTINCT fcs.project, fcs.context, jsonb_build_object('_type', 'overload', 'row', to_jsonb(fcs.*)) as result
         |    FROM "${tenant}".feature_contexts_strategies fcs, feature_results f, project_results p
         |    WHERE fcs.feature=f.fname
         |    AND fcs.project=p.pname
         |), context_results AS (
         |    SELECT DISTINCT jsonb_build_object('_type', 'context', 'row', to_jsonb(c.*) - 'ctx_path') as result
         |    FROM "${tenant}".new_contexts c${projectFilter
        .map(_ => """
         |    , overload_results ors
         |    WHERE c.ctx_path = ors.context
         |    AND c.project = ors.project""".stripMargin)
        .getOrElse("")}
         |), key_results AS (
         |    SELECT DISTINCT k.name, jsonb_build_object('_type', 'key', 'row', to_jsonb(k.*)) as result
         |    FROM "${tenant}".apikeys k
         |    ${keyFilter.map(_ => s"WHERE k.name=ANY($$${paramIndex.getAndIncrement()})").getOrElse("")}
         |), apikeys_projects_result AS (
         |    SELECT DISTINCT jsonb_build_object('_type', 'apikey_project', 'row', to_jsonb(ap.*)) as result
         |    FROM "${tenant}".apikeys_projects ap, key_results kr
         |    WHERE ap.apikey=kr.name
         |), webhook_results AS (
         |    SELECT DISTINCT w.id, w.name, jsonb_build_object('_type', 'webhook', 'row', to_jsonb(w.*)) as result
         |    FROM "${tenant}".webhooks w
         |     ${webhookFilter.map(_ => s"WHERE w.name=ANY($$${paramIndex.getAndIncrement()})").getOrElse("")}
         |), webhooks_features_result AS (
         |    SELECT DISTINCT jsonb_build_object('_type', 'webhook_feature', 'row', to_jsonb(wf.*)) as result
         |    FROM "${tenant}".webhooks_features wf, webhook_results w, feature_results
         |    WHERE wf.webhook=w.id
         |), webhooks_projects_result AS (
         |    SELECT DISTINCT jsonb_build_object('_type', 'webhook_project', 'row', to_jsonb(wp.*)) as result
         |    FROM "${tenant}".webhooks_projects wp, webhook_results w, project_results
         |    WHERE wp.webhook=w.id
         |), script_results AS (
         |    SELECT DISTINCT jsonb_build_object('_type', 'script', 'row', to_jsonb(wsc.*)) as result
         |    FROM "${tenant}".wasm_script_configurations wsc, feature_results fr
         |    WHERE wsc.id = fr.script_config
         |)${if (request.userRights)
        s""", users_webhooks_rights_result AS (
         |    SELECT DISTINCT jsonb_build_object('_type', 'user_webhook_right', 'row', to_jsonb(uwr.*)) as result
         |    FROM "${tenant}".users_webhooks_rights uwr, webhook_results
         |    WHERE uwr.webhook=webhook_results.name
         |), project_rights AS (
         |    SELECT DISTINCT jsonb_build_object('_type', 'project_right', 'row', to_jsonb(upr.*)) as result
         |    FROM "${tenant}".users_projects_rights upr, project_results pr
         |    WHERE upr.project = pr.pname
         |), key_rights AS (
         |    SELECT DISTINCT jsonb_build_object('_type', 'key_right', 'row', to_jsonb(ukr.*)) as result
         |    FROM "${tenant}".users_keys_rights ukr, key_results kr
         |    WHERE ukr.apikey=kr.name
         |)"""
      else ""}
         |SELECT result FROM tag_results
         |UNION ALL
         |SELECT result FROM project_results
         |UNION ALL
         |SELECT result FROM feature_results
         |UNION ALL
         |SELECT result FROM context_results
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
         |${if (request.userRights)
        s"""UNION ALL
             |SELECT result FROM users_webhooks_rights_result
             |UNION ALL
             |SELECT result FROM project_rights
             |UNION ALL
             |SELECT result FROM key_rights"""
      else ""}
         |""".stripMargin,
      List(projectFilter, keyFilter, webhookFilter).flatMap(_.toList).map(s => s.toArray)
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

  case class DBImportResult(
      failedElements: Set[JsObject] = Set(),
      updatedFeatures: Set[String] = Set(),
      createdFeatures: Set[String] = Set(),
      createdProjects: Set[String] = Set(),
      updatedProjects: Set[String] = Set(),
      previousStrategies: Map[String, FeatureWithOverloads] = Map()
  ) {
    def addFailedElement(json: JsObject): DBImportResult = copy(failedElements = failedElements + json)

    def addUpdatedFeatures(id: String): DBImportResult        = copy(updatedFeatures = updatedFeatures + id)
    def withUpdatedFeatures(ids: Set[String]): DBImportResult = copy(updatedFeatures = updatedFeatures ++ ids)

    def addCreatedFeatures(id: String): DBImportResult        = copy(createdFeatures = createdFeatures + id)
    def withCreatedFeatures(ids: Set[String]): DBImportResult = copy(createdFeatures = createdFeatures ++ ids)

    def withCreatedProjects(ids: Set[String]): DBImportResult = copy(createdProjects = createdProjects ++ ids)
    def withUpdatedProjects(ids: Set[String]): DBImportResult = copy(updatedProjects = updatedProjects ++ ids)

    def mergeWith(other: DBImportResult): DBImportResult = {
      copy(
        failedElements = failedElements ++ other.failedElements,
        updatedFeatures = updatedFeatures ++ other.updatedFeatures,
        createdFeatures = createdFeatures ++ other.createdFeatures
      )
    }
  }

  object DBImportResult {
    def from(m: Map[ExportedType, UnitDBImportResult]): DBImportResult = {
      m.foldLeft(DBImportResult())((res, t) => {
        if (t._1 == FeatureType) {
          res
            .withCreatedFeatures(t._2.createdElements)
            .withUpdatedFeatures(t._2.updatedElements)
        } else if (t._1 == ProjectType) {
          res
            .withCreatedProjects(t._2.createdElements)
            .withUpdatedProjects(t._2.updatedElements)
        } else {
          res
        }

      })
    }
  }

  case class UnitDBImportResult(
      failedElements: Set[JsObject] = Set(),
      updatedElements: Set[String] = Set(),
      createdElements: Set[String] = Set(),
      previousStrategies: Map[String, FeatureWithOverloads] = Map()
  ) {
    def addFailedElement(json: JsObject): UnitDBImportResult = copy(failedElements = failedElements + json)

    def addUpdatedElements(id: String): UnitDBImportResult = copy(updatedElements = updatedElements + id)

    def addCreatedElements(id: String): UnitDBImportResult = copy(createdElements = createdElements + id)

    def mergeWith(other: UnitDBImportResult): UnitDBImportResult = {
      copy(
        failedElements = failedElements ++ other.failedElements,
        updatedElements = updatedElements ++ other.updatedElements,
        createdElements = createdElements ++ other.createdElements
      )
    }
  }

}
