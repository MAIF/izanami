package fr.maif.izanami.datastores

import fr.maif.izanami.datastores.ImportExportDatastore.DBImportResult
import fr.maif.izanami.datastores.ImportExportDatastore.TableMetadata
import fr.maif.izanami.datastores.ImportExportDatastore.UnitDBImportResult
import fr.maif.izanami.env.Env
import fr.maif.izanami.env.pgimplicits.EnhancedRow
import fr.maif.izanami.errors.InternalServerError
import fr.maif.izanami.errors.IzanamiError
import fr.maif.izanami.events.EventOrigin.ImportOrigin
import fr.maif.izanami.events.PreviousProject
import fr.maif.izanami.events.SourceFeatureCreated
import fr.maif.izanami.events.SourceFeatureUpdated
import fr.maif.izanami.events.SourceProjectCreated
import fr.maif.izanami.events.SourceProjectUpdated
import fr.maif.izanami.models.ConflictField
import fr.maif.izanami.models.ConflictStrategy
import fr.maif.izanami.models.ExportedType
import fr.maif.izanami.models.FeatureTagType
import fr.maif.izanami.models.FeatureType
import fr.maif.izanami.models.FeatureWithOverloads
import fr.maif.izanami.models.KeyRightType
import fr.maif.izanami.models.OverloadType
import fr.maif.izanami.models.ProjectRightType
import fr.maif.izanami.models.ProjectType
import fr.maif.izanami.models.Tenant
import fr.maif.izanami.models.WebhookRightType
import fr.maif.izanami.utils.Datastore
import fr.maif.izanami.utils.Done
import fr.maif.izanami.utils.syntax.implicits.BetterJsValue
import fr.maif.izanami.web.ExportController
import fr.maif.izanami.web.ExportController.TenantExportRequest
import fr.maif.izanami.web.ImportController
import fr.maif.izanami.web.ImportController.Fail
import fr.maif.izanami.web.ImportController.ImportConflictStrategy
import fr.maif.izanami.web.ImportController.MergeOverwrite
import fr.maif.izanami.web.ImportController.Replace
import fr.maif.izanami.web.ImportController.Skip
import fr.maif.izanami.web.ProjectId
import fr.maif.izanami.web.UserInformation
import io.vertx.core.json.JsonArray
import io.vertx.sqlclient.SqlConnection
import play.api.libs.json.JsObject
import play.api.libs.json.Json
import java.util.concurrent.atomic.AtomicInteger
import scala.concurrent.Future
import fr.maif.izanami.errors.ImportFailureError
import fr.maif.izanami.errors.PostgresErrorMapper

class ImportExportDatastore(val env: Env) extends Datastore {
  private val extensionSchema: String = env.extensionsSchema

  private def tableMetadata(
      tenant: String,
      table: String
  ): Future[Option[TableMetadata]] = {
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
        columns <- r.optStringArray("table_columns")
      )
        yield TableMetadata(
          table = table,
          primaryKeyConstraint = constraint,
          tableColumns = columns.toSet
        )
    }
  }

  def importTenantData(
      tenant: String,
      entries: Map[ExportedType, Seq[JsObject]],
      conflictStrategy: ConflictStrategy,
      user: UserInformation
  ): Future[Either[IzanamiError, Unit]] = {
    Tenant.isTenantValid(tenant)
    entries.toSeq
      .sortBy { case (t, _) =>
        t.order
      }.foldLeft(Future.successful(Seq()): Future[Seq[(
          Option[TableMetadata],
          Seq[JsObject],
          ExportedType
      )]])((acc, next) => {
        val futureMetadata = tableMetadata(tenant, next._1.table)
          .map(maybeMetdata => (maybeMetdata, next._2, next._1))
        for (
          curr <- acc;
          metadata <- futureMetadata
        ) yield curr.appended(metadata)
      }).flatMap(s => {
        if (s.exists(_._1.isEmpty)) {
          // TODO precise table in error
          Future.successful(
            Left(InternalServerError(s"Failed to fetch metadata for one table"))
          )
        } else {
          env.postgresql.executeInTransactionAllowingSavepoint(
            (conn, rollback) => {
              s
                .collect { case (Some(metadata), jsons, exportedType) =>
                  (metadata, jsons, exportedType)
                }
                .foldLeft(
                  Future.successful(Right(Map())): Future[
                    Either[IzanamiError, Map[ExportedType, UnitDBImportResult]]
                  ]
                )((agg, t) => {
                  val maybeId =
                    if (t._3 == FeatureType || t._3 == ProjectType) Some("id")
                    else None
                  agg.flatMap(data => {
                    env.postgresql
                      .queryRaw(
                        s"SET CONSTRAINTS ALL DEFERRED",
                        List(),
                        conn = Some(conn)
                      ) {
                        _ => data
                      }
                  }).flatMap(data =>
                    env.postgresql.queryRaw(
                      s"SELECT set_config('search_path', $$1, true)",
                      List(s"$extensionSchema, $tenant, public"),
                      conn = Some(conn)
                    ) {
                      _ => data
                    }
                  ).flatMap {
                    case Right(previousResult) if t._2.nonEmpty => {
                      logger.info(
                        s"Starting import for table ${t._1.table} (${t._2.length} row(s) to import)"
                      )
                      val f: Future[Either[IzanamiError, UnitDBImportResult]] =
                        if (t._3 == FeatureType) {
                          importFeatures(
                            tenant = tenant,
                            rows = t._2,
                            conflictStrategy = conflictStrategy,
                            conn = conn
                          ).map(r => Right(r))
                        } else if (t._3 == FeatureTagType) {
                          importFeatureTags(
                            tenant = tenant,
                            rows = t._2,
                            conflictStrategy = conflictStrategy,
                            conn = conn
                          ).map(r => Right(r))
                        } else if (t._3 == OverloadType) {
                          importFeatureOverloads(
                            tenant = tenant,
                            rows = t._2,
                            conflictStrategy = conflictStrategy,
                            conn = conn
                          ).map(r => Right(r))
                        } else {
                          conflictStrategy.defaultStrategy match {
                            case ImportController.MergeOverwrite | ImportController.Skip =>
                              doImportTenantData(
                                tenant,
                                t._1,
                                t._2,
                                maybeId,
                                conflictStrategy,
                                conn = conn
                              ).map(r => {
                                Right(r)
                              })
                            case ImportController.Fail =>
                              doImportTenantData(
                                tenant,
                                t._1,
                                t._2,
                                maybeId,
                                conflictStrategy,
                                conn
                              ).map(importResult => {
                                if (importResult.failedElements.isEmpty) {
                                  Right(importResult)
                                } else {
                                  Left(ImportFailureError(
                                    Map(
                                      t._3 -> importResult.failedElements.map {
                                        case (json, error) =>
                                          (error, Json.stringify(json))
                                      }
                                    )
                                  ))
                                }
                              })
                            case Replace =>
                              throw new IllegalArgumentException(
                                "Replace strategy can't be used for tenant import"
                              )
                          }
                        }
                      f.flatMap {
                        case Left(err) =>
                          Future.successful(
                            Left(err): Either[IzanamiError, UnitDBImportResult]
                          )
                        case Right(r) =>
                          updateUserTenantRightIfNeeded(
                            tenant,
                            entries,
                            if (
                              conflictStrategy.defaultStrategy == ImportController.Fail
                            )
                              Some(conn)
                            else None
                          ).map(_ =>
                            Right(r): Either[IzanamiError, UnitDBImportResult]
                          )
                      }.map(e => e.map(v => previousResult + (t._3 -> v)))
                    }
                    case Right(previousResult) =>
                      Future.successful(Right(previousResult))
                    case Left(err) => Future.successful(Left(err))
                  }
                })
                .map(e => e.map(m => DBImportResult.from(m)))
                .flatMap {
                  case Left(err) => {
                    Future
                      .successful(Left(err)): Future[Either[IzanamiError, Unit]]
                  }
                  case Right(result) if (result.failedElements.nonEmpty) => {
                    logger.info(
                      s"Import has failed elements for ${result.failedElements.keySet.mkString(", ")}, rolling back transaction"
                    )
                    rollback().map(_ =>
                      Left(ImportFailureError(failedElements =
                        result.failedElements.view.mapValues(jsons =>
                          jsons.map((err, json) => (err, Json.stringify(json)))
                        ).toMap
                      ))
                    )
                  }
                  case Right(result) => {
                    val projectNamesBydId = entries
                      .get(ProjectType)
                      .toSeq
                      .flatten
                      .map(json => {
                        for (
                          id <- (json \ "id").asOpt[String];
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
                            name = projectNamesBydId(projectId),
                            user = user.username,
                            origin = ImportOrigin,
                            authentication = user.authentication
                          )
                        )(conn)
                      }))
                      .flatMap(_ => {
                        if (result.updatedProjects.nonEmpty) {
                          val previousProjectNames
                              : Future[Map[ProjectId, String]] =
                            if (
                              conflictStrategy.defaultStrategy == MergeOverwrite && entries
                                .get(FeatureType)
                                .exists(s => s.nonEmpty)
                            ) {
                              env.postgresql
                                .queryAll(
                                  s"""
                                   |SELECT id, name FROM "${tenant}".projects WHERE id=ANY($$1)
                                   |""".stripMargin,
                                  List(
                                    result.updatedProjects
                                      .toArray
                                  )
                                ) { r =>
                                  {
                                    for (
                                      id <- r.optString("id");
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
                            Future.sequence(
                              result.updatedProjects.map(projectId => {
                                env.eventService.emitEvent(
                                  tenant,
                                  SourceProjectUpdated(
                                    tenant = tenant,
                                    id = projectId,
                                    name = projectNamesBydId(
                                      projectId
                                    ),
                                    previous = PreviousProject(
                                      ids.get(projectId).orNull
                                    ),
                                    user = user.username,
                                    origin = ImportOrigin,
                                    authentication = user.authentication
                                  )
                                )(conn)
                              })
                            )
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
                          .flatMap(strategiesByFeature => {
                            result.createdFeatures.foldLeft(
                              Future.successful(Done.done()): Future[Done]
                            )((previousFuture, created) => {
                              previousFuture.flatMap(_ => {
                                strategiesByFeature
                                  .get(created)
                                  .map(strategies => {
                                    env.eventService.emitEvent(
                                      tenant,
                                      SourceFeatureCreated(
                                        id = created,
                                        project =
                                          strategies.baseFeature.project,
                                        tenant = tenant,
                                        user = user.username,
                                        feature = strategies,
                                        authentication = user.authentication,
                                        origin = ImportOrigin
                                      )
                                    )(conn = conn)
                                      .map(_ => Done.done())
                                  })
                                  .getOrElse(Future.successful(Done.done()))
                              })
                            }).map(_ => strategiesByFeature)
                          })
                          .flatMap(strategiesByFeature => {
                            if (result.updatedFeatures.nonEmpty) {
                              env.datastores.features
                                .findActivationStrategiesForFeatures(
                                  tenant,
                                  result.updatedFeatures
                                ).flatMap(previousFeatureStates => {
                                  result.updatedFeatures.filter(updated => {
                                    val hasChanged = !strategiesByFeature
                                      .get(updated)
                                      .exists(newStrat =>
                                        previousFeatureStates
                                          .get(updated)
                                          .contains(newStrat)
                                      )
                                    hasChanged
                                  }).foldLeft(Future.successful(Done.done()))(
                                    (previousFuture, updated) => {
                                      previousFuture.flatMap(_ => {
                                        strategiesByFeature
                                          .get(updated)
                                          .map(strategies => {
                                            env.eventService.emitEvent(
                                              tenant,
                                              SourceFeatureUpdated(
                                                id = updated,
                                                project =
                                                  strategies.baseFeature.project,
                                                tenant = tenant,
                                                user = user.username,
                                                feature = strategies,
                                                previous = previousFeatureStates
                                                  .get(updated)
                                                  .orNull,
                                                authentication =
                                                  user.authentication,
                                                origin = ImportOrigin
                                              )
                                            )(conn = conn).map(_ => Done.done())
                                          })
                                          .getOrElse(Future.successful(
                                            Done.done()
                                          ))
                                      })
                                    }
                                  )
                                })

                            } else {
                              Future.successful(())
                            }
                          })
                        // Stop feature part

                      })
                      .map(_ => Right(()))
                  }
                }
            }
          )
        }
      })
  }

  private def importFeatureUpdateStatement(table: String)(
      name: String,
      conflictStrategy: ImportConflictStrategy
  ): String = {
    val base = s"${name}="
    s"${extensionSchema}.ltree"
    s"""OPERATOR("${extensionSchema}".=)"""

    val value = conflictStrategy match
      case Fail =>
        s"""
        |CASE WHEN EXCLUDED.${name}=${table}.${name} THEN ${table}.${name}
        |ELSE CASE WHEN 1/(txid_current() - txid_current()) = 1 THEN ${table}.${name} ELSE ${table}.${name} END -- ugly hack to make request fail
        |END
        """ // This is ugly, but it's impossible to RAISE after a DO UPDATE
      case MergeOverwrite | Replace => s"EXCLUDED.${name}"
      case Skip                     => s"${table}.${name}"

    base + value
  }

  private def importFeatureOverloads(
      tenant: String,
      rows: Seq[JsObject],
      conflictStrategy: ConflictStrategy,
      conn: SqlConnection
  ): Future[UnitDBImportResult] = {
    val conflictFunction =
      importFeatureUpdateStatement("feature_contexts_strategies")
    val conflictPart =
      Seq(
        conflictFunction(
          "project",
          conflictStrategy.strategyFor(ConflictField.FeatureProject)
        ),
        conflictFunction(
          "feature",
          conflictStrategy.strategyFor(ConflictField.FeatureName)
        ),
        conflictFunction(
          "conditions",
          conflictStrategy.strategyFor(ConflictField.FeatureConditions)
        ),
        conflictFunction(
          "script_config",
          conflictStrategy.strategyFor(ConflictField.FeatureConditions)
        ),
        conflictFunction(
          "value",
          conflictStrategy.strategyFor(ConflictField.FeatureConditions)
        ),
        conflictFunction(
          "result_type",
          conflictStrategy.strategyFor(ConflictField.FeatureConditions)
        ),
        conflictFunction(
          "enabled",
          conflictStrategy.strategyFor(ConflictField.FeatureEnabling)
        ),
        conflictFunction(
          "context",
          conflictStrategy.defaultStrategy
        )
      ).mkString(", ");
    new JsonArray(Json.toJson(rows).toString())
    val columns =
      "project, feature, conditions, script_config, enabled, value, result_type, context"
    val query = s"""
                 |INSERT INTO "${tenant}".feature_contexts_strategies ($columns) select $columns from json_populate_recordset(null::"${tenant}".feature_contexts_strategies, $$1)
                 |ON CONFLICT (project, context, feature) DO UPDATE
                 |SET $conflictPart
                 |RETURNING false AS inserted, json_build_object('feature',feature,'project',project)::TEXT as id
                 |""".stripMargin;

    val unitQuery = s"""
                         |INSERT INTO "${tenant}".feature_contexts_strategies ($columns) select $columns from json_populate_record(null::"${tenant}".feature_contexts_strategies, $$1)
                         |ON CONFLICT (project, context, feature) DO UPDATE
                         |SET $conflictPart
                         |RETURNING false AS inserted, json_build_object('feature',feature,'project',project)::TEXT as id
                         |""".stripMargin;

    genericTableImport(
      globalQuery = query,
      unitQuery = unitQuery,
      rows = rows,
      conn = conn
    ).map(res => {
      res.updatedElements.map(json => Json.parse(json)).map(json => {
        val name = (json \ "feature").as[String]
        val project = (json \ "project").as[String]
        (project, name)
      })
    }).flatMap(projectAndNames => {
      env.datastores.features.findFeatureIds(
        tenant = tenant,
        featureNameAndProjects = projectAndNames
      )
    })
      .map(map => UnitDBImportResult(updatedElements = map.values.toSet))
  }

  private def importFeatureTags(
      tenant: String,
      rows: Seq[JsObject],
      conflictStrategy: ConflictStrategy,
      conn: SqlConnection
  ): Future[UnitDBImportResult] = {

    val featureIds =
      rows.map(json => (json \ "feature").asOpt[String]).collect {
        case Some(featureId) => featureId
      }.toSet
    val conflictStrategyToUse =
      conflictStrategy.strategyFor(ConflictField.FeatureTags)
    if (
      conflictStrategyToUse == MergeOverwrite || conflictStrategyToUse == Replace
    ) {

      env.postgresql.queryRaw(
        s"""
|DELETE FROM "${tenant}".features_tags WHERE feature=ANY($$1)
""".stripMargin,
        params = List(featureIds.toArray),
        conn = Some(conn)
      )(_ => Done.done()).flatMap(_ => {
        val vertxJsonArray = new JsonArray(Json.toJson(rows).toString())
        val columns =
          "tag, feature"

        val query =
          s"""
            |INSERT INTO "${tenant}".features_tags ($columns) select $columns from json_populate_recordset(null::"${tenant}".features_tags, $$1)
            |RETURNING feature
            |""".stripMargin;

        env.postgresql
          .queryRaw(
            query,
            List(vertxJsonArray),
            conn = Some(conn)
          ) { rows =>
            {
              rows.map(r => r.getString("feature"))
            }
          }.map(updatedFeatureIds => {
            UnitDBImportResult(updatedElements = featureIds)
          })
      })
    } else {
      Future.successful(UnitDBImportResult())
    }
  }

  private def importFeatures(
      tenant: String,
      rows: Seq[JsObject],
      conflictStrategy: ConflictStrategy,
      conn: SqlConnection
  ): Future[UnitDBImportResult] = {

    val conflictFunction = importFeatureUpdateStatement("features")
    val conflictPart = Seq(
      conflictFunction(
        "name",
        conflictStrategy.strategyFor(ConflictField.FeatureName)
      ),
      conflictFunction(
        "description",
        conflictStrategy.strategyFor(ConflictField.FeatureDescription)
      ),
      conflictFunction(
        "project",
        conflictStrategy.strategyFor(ConflictField.FeatureProject)
      ),
      conflictFunction(
        "enabled",
        conflictStrategy.strategyFor(ConflictField.FeatureEnabling)
      ),
      conflictFunction(
        "conditions",
        conflictStrategy.strategyFor(ConflictField.FeatureConditions)
      ),
      conflictFunction(
        "value",
        conflictStrategy.strategyFor(ConflictField.FeatureConditions)
      ),
      conflictFunction(
        "result_type",
        conflictStrategy.strategyFor(ConflictField.FeatureConditions)
      ),
      conflictFunction(
        "script_config",
        conflictStrategy.strategyFor(ConflictField.FeatureConditions)
      ),
      conflictFunction(
        "created_at",
        conflictStrategy.defaultStrategy
      ),
      conflictFunction(
        "metadata",
        conflictStrategy.defaultStrategy
      )
    ).mkString(", ")
    new JsonArray(Json.toJson(rows).toString())
    val columns =
      "id, name, project, enabled, conditions, script_config, metadata, description, value, result_type, created_at"
    val query =
      s"""
          |INSERT INTO "${tenant}".features ($columns) select $columns from json_populate_recordset(null::"${tenant}".features, $$1)
          |ON CONFLICT (id) DO UPDATE
          |SET $conflictPart
          |RETURNING (xmax = 0) AS inserted, id
          |""".stripMargin;

    val unitQuery =
      s"""
          |INSERT INTO "${tenant}".features ($columns) select $columns from json_populate_record(null::"${tenant}".features, $$1)
          |ON CONFLICT (id) DO UPDATE
          |SET $conflictPart
          |RETURNING (xmax = 0) AS inserted, id
          |""".stripMargin;

    genericTableImport(
      globalQuery = query,
      unitQuery = unitQuery,
      rows = rows,
      conn
    )
  }

  private def genericTableImport(
      globalQuery: String,
      unitQuery: String,
      rows: Seq[JsObject],
      conn: SqlConnection
  ): Future[UnitDBImportResult] = {
    val vertxJsonArray = new JsonArray(Json.toJson(rows).toString())

    env.postgresql.queryRaw("SAVEPOINT savepoint", conn = Some(conn)) { _ =>
      Done.done()
    }.flatMap(_ =>
      env.postgresql
        .queryRaw(
          globalQuery,
          List(vertxJsonArray),
          conn = Some(conn)
        ) { rows =>
          {

            val insertedMap = rows
              .map(r => {
                (for (
                  id <- r.optString("id");
                  inserted <- r.optBoolean("inserted")
                ) yield (id, inserted))
              })
              .collect { case Some(r) =>
                r
              }
              .groupMap(_._2)(_._1)

            UnitDBImportResult(
              createdElements =
                insertedMap.getOrElse(true, List[String]()).toSet,
              updatedElements =
                insertedMap.getOrElse(false, List[String]()).toSet
            )
          }
        }
        .recoverWith {
          case _ => {
            logger.info("There has been import error, switching to unit mode")
            rows.foldLeft(env.postgresql.queryRaw(
              s"ROLLBACK TO SAVEPOINT savepoint",
              conn = Some(conn)
            ) { _ => UnitDBImportResult() })((facc, row) => {
              facc.flatMap(acc =>
                env.postgresql.queryRaw(
                  "SAVEPOINT savepoint",
                  conn = Some(conn)
                ) { _ =>
                  Done.done()
                }
                  .flatMap(_ => {
                    env.postgresql
                      .queryOne(
                        unitQuery,
                        params = List(row.vertxJsValue),
                        conn = Some(conn)
                      ) { r =>
                        {
                          (for (
                            id <- r.optString("id");
                            inserted <- r.optBoolean("inserted")
                          ) yield (id, inserted))
                            .map {
                              case (id, false) => acc.addUpdatedElements(id)
                              case (id, true)  => acc.addCreatedElements(id)
                            }
                        }
                      }.flatMap(r => {
                        env.postgresql.queryRaw(
                          "RELEASE SAVEPOINT savepoint",
                          conn = Some(conn)
                        ) {
                          _ => r
                        }
                      })
                      .map(r => r.getOrElse(acc))
                      .recoverWith {
                        case ex => {
                          val error =
                            PostgresErrorMapper.postgresErrorToIzanamiError(ex)
                          logger.info(
                            s"Insertion of this row failed in unit mode : ${row}"
                          )
                          env.postgresql.queryRaw(
                            "ROLLBACK TO SAVEPOINT savepoint",
                            conn = Some(conn)
                          ) { _ =>
                            Done.done()
                          }.map(_ => acc.addFailedElement(row, error))
                        }
                      }
                  })
              )
            })
          }
        }
    )
  }

  private def doImportTenantData(
      tenant: String,
      metadata: TableMetadata,
      rows: Seq[JsObject],
      maybeId: Option[String],
      conflictStrategy: ConflictStrategy,
      conn: SqlConnection
  ): Future[UnitDBImportResult] = {
    val cols = metadata.tableColumns.mkString(",")
    val idReturnPart = maybeId
      .map(idCol => s", ${idCol}::TEXT as id")
      .getOrElse("")

    val conflictPart = conflictStrategy.defaultStrategy match {
      case MergeOverwrite | Replace => {
        val setPart = metadata.tableColumns
          .map(col =>
            s"""
              |${col}=EXCLUDED.${col}
              |""".stripMargin
          ).mkString(", ")

        s"ON CONFLICT ON CONSTRAINT ${metadata.primaryKeyConstraint} DO UPDATE SET ${setPart}"
      }
      case Skip => {
        s"ON CONFLICT ON CONSTRAINT ${metadata.primaryKeyConstraint} DO NOTHING"
      }
      case Fail => {
        ""
      }
    }

    val query = s"""
                   |INSERT INTO "${tenant}".${metadata.table} (${cols}) select ${cols} from json_populate_recordset(null::"${tenant}".${metadata.table}, $$1)
                   |${conflictPart}
                   |RETURNING (xmax = 0) AS inserted ${idReturnPart}
                   |""".stripMargin

    val unitQuery = s"""
                        |INSERT INTO "${tenant}".${metadata.table} (${cols}) select ${cols} from json_populate_record(null::"${tenant}".${metadata.table}, $$1)
                        |$conflictPart
                        |RETURNING (xmax = 0) AS inserted ${idReturnPart}
                        |""".stripMargin
    genericTableImport(
      globalQuery = query,
      unitQuery = unitQuery,
      rows = rows,
      conn = conn
    )
  }

  def exportTenantData(
      tenant: String,
      request: TenantExportRequest
  ): Future[List[JsObject]] = {
    Tenant.isTenantValid(tenant)
    val paramIndex = new AtomicInteger(1)
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
         |    ${projectFilter
          .map(_ => s"WHERE p.name=ANY($$${paramIndex.getAndIncrement()})")
          .getOrElse("")}
         |), feature_results AS (
         |    SELECT DISTINCT f.script_config, f.id as fid, f.name as fname, jsonb_build_object('_type', 'feature' , 'row', to_jsonb(f.*)) as result
         |    FROM project_results, "${tenant}".features f
         |    WHERE f.project=project_results.pname
         |), tag_results AS (
         |    SELECT DISTINCT jsonb_build_object('_type', 'tag', 'row', row_to_json(t.*)::jsonb) as result
         |    FROM "${tenant}".tags t ${projectFilter
          .map(_ => s"""
         |, "${tenant}".features_tags ft, feature_results
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
         |    ${keyFilter
          .map(_ => s"WHERE k.name=ANY($$${paramIndex.getAndIncrement()})")
          .getOrElse("")}
         |), apikeys_projects_result AS (
         |    SELECT DISTINCT jsonb_build_object('_type', 'apikey_project', 'row', to_jsonb(ap.*)) as result
         |    FROM "${tenant}".apikeys_projects ap, key_results kr
         |    WHERE ap.apikey=kr.name
         |), webhook_results AS (
         |    SELECT DISTINCT w.id, w.name, jsonb_build_object('_type', 'webhook', 'row', to_jsonb(w.*)) as result
         |    FROM "${tenant}".webhooks w
         |     ${webhookFilter
          .map(_ => s"WHERE w.name=ANY($$${paramIndex.getAndIncrement()})")
          .getOrElse("")}
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
         |)${
          if (request.userRights)
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
          else ""
        }
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
         |${
          if (request.userRights)
            s"""UNION ALL
             |SELECT result FROM users_webhooks_rights_result
             |UNION ALL
             |SELECT result FROM project_rights
             |UNION ALL
             |SELECT result FROM key_rights"""
          else ""
        }
         |""".stripMargin,
      List(projectFilter, keyFilter, webhookFilter)
        .flatMap(_.toList)
        .map(s => s.toArray)
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
  case class TableMetadata(
      table: String,
      primaryKeyConstraint: String,
      tableColumns: Set[String]
  )

  case class DBImportResult(
      failedElements: Map[ExportedType, Seq[(IzanamiError, JsObject)]] = Map(),
      updatedFeatures: Set[String] = Set(),
      createdFeatures: Set[String] = Set(),
      createdProjects: Set[String] = Set(),
      updatedProjects: Set[String] = Set(),
      previousStrategies: Map[String, FeatureWithOverloads] = Map()
  ) {
    def withFailedElements(
        exportedType: ExportedType,
        jsons: Seq[(IzanamiError, JsObject)]
    ): DBImportResult =
      if (jsons.isEmpty) {
        this
      } else {
        copy(failedElements = failedElements + (exportedType -> jsons))
      }

    def addUpdatedFeatures(id: String): DBImportResult =
      copy(updatedFeatures = updatedFeatures + id)
    def withUpdatedFeatures(ids: Set[String]): DBImportResult =
      copy(updatedFeatures = updatedFeatures ++ ids)

    def addCreatedFeatures(id: String): DBImportResult =
      copy(createdFeatures = createdFeatures + id)
    def withCreatedFeatures(ids: Set[String]): DBImportResult =
      copy(createdFeatures = createdFeatures ++ ids)

    def withCreatedProjects(ids: Set[String]): DBImportResult =
      copy(createdProjects = createdProjects ++ ids)
    def withUpdatedProjects(ids: Set[String]): DBImportResult =
      copy(updatedProjects = updatedProjects ++ ids)

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
      val res = m.foldLeft(DBImportResult())((res, t) => {
        val resWithFailures = res.withFailedElements(
          t._1,
          t._2.failedElements.map((json, error) => (error, json))
        )
        if (t._1 == FeatureType || t._1 == FeatureTagType) {
          resWithFailures
            .withCreatedFeatures(t._2.createdElements)
            .withUpdatedFeatures(t._2.updatedElements)
        } else if (t._1 == ProjectType) {
          resWithFailures
            .withCreatedProjects(t._2.createdElements)
            .withUpdatedProjects(t._2.updatedElements)
        } else if (t._1 == OverloadType) {
          resWithFailures
            .withUpdatedFeatures(t._2.updatedElements)
        } else {
          resWithFailures
        }
      })

      val finalResult =
        res.copy(updatedFeatures = res.updatedFeatures -- res.createdFeatures)
      finalResult
    }
  }

  case class UnitDBImportResult(
      failedElements: Seq[(JsObject, IzanamiError)] = Seq(),
      updatedElements: Set[String] = Set(),
      createdElements: Set[String] = Set(),
      previousStrategies: Map[String, FeatureWithOverloads] = Map()
  ) {
    def addFailedElement(
        json: JsObject,
        error: IzanamiError
    ): UnitDBImportResult =
      copy(failedElements = failedElements.appended((json, error)))

    def addUpdatedElements(id: String): UnitDBImportResult =
      copy(updatedElements = updatedElements + id)

    def addCreatedElements(id: String): UnitDBImportResult =
      copy(createdElements = createdElements + id)

    def mergeWith(other: UnitDBImportResult): UnitDBImportResult = {
      copy(
        failedElements = failedElements ++ other.failedElements,
        updatedElements = updatedElements ++ other.updatedElements,
        createdElements = createdElements ++ other.createdElements
      )
    }
  }

}
