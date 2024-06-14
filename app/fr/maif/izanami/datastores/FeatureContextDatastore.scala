package fr.maif.izanami.datastores

import fr.maif.izanami.datastores.FeatureContextDatastore.FeatureContextRow
import fr.maif.izanami.env.Env
import fr.maif.izanami.env.PostgresqlErrors.{FOREIGN_KEY_VIOLATION, RELATION_DOES_NOT_EXISTS, UNIQUE_VIOLATION}
import fr.maif.izanami.env.pgimplicits.EnhancedRow
import fr.maif.izanami.errors._
import fr.maif.izanami.events.{FeatureUpdated, SourceFeatureUpdated}
import fr.maif.izanami.models.Feature.{activationConditionRead, activationConditionWrite}
import fr.maif.izanami.models.FeatureContext.generateSubContextId
import fr.maif.izanami.models._
import fr.maif.izanami.utils.Datastore
import fr.maif.izanami.utils.syntax.implicits.BetterSyntax
import fr.maif.izanami.wasm.WasmConfig
import io.otoroshi.wasm4s.scaladsl.WasmSourceKind
import io.vertx.core.json.JsonArray
import io.vertx.pgclient.PgException
import io.vertx.sqlclient.Row
import play.api.libs.json.Json

import java.util.Objects
import scala.concurrent.Future

class FeatureContextDatastore(val env: Env) extends Datastore {
  def deleteGlobalFeatureContext(tenant: String, path: Seq[String]): Future[Either[IzanamiError, Unit]] = {
    val ctxName    = path.last
    val parentPart = path.init
    env.postgresql.executeInTransaction(conn => {
      env.postgresql
        .queryOne(
          s"""
             |DELETE FROM global_feature_contexts
             |WHERE id=$$1
             |RETURNING *
             |""".stripMargin,
          List(generateSubContextId(tenant, path)),
          schemas = Set(tenant),
          conn = Some(conn)
        ) { r => Some(()) }
        .map(_.toRight(FeatureContextDoesNotExist(path.mkString("/"))))
        .flatMap {
          case Left(value)  => Left(value).future
          case Right(value) =>
            env.postgresql
              .queryRaw(
                s"""DELETE FROM feature_context_name_unicity_check_table WHERE parent = $$1 AND context=$$2""",
                List(if (parentPart.isEmpty) "" else generateSubContextId(tenant, parentPart), ctxName),
                conn = Some(conn)
              ) { r => Some(()) }
              .map(_ => Right(()))
        }
    })
  }

  def createGlobalFeatureContext(
      tenant: String,
      parents: Seq[String],
      featureContext: FeatureContext
  ): Future[Either[IzanamiError, FeatureContext]] = {
    val id       = generateSubContextId(tenant, featureContext.name, parents)
    val parentId = if (parents.isEmpty) null else generateSubContextId(tenant, parents)

    env.postgresql.executeInTransaction(conn => {
      env.postgresql
        .queryOne(
          s"""
             |INSERT INTO global_feature_contexts (id, name, parent)
             |VALUES ($$1, $$2, $$3)
             |RETURNING *
             |""".stripMargin,
          List(id, featureContext.name, parentId),
          schemas = Set(tenant),
          conn = Some(conn)
        ) { row => row.optFeatureContext(global = true) }
        .map(o => o.toRight(InternalServerError()))
        .flatMap {
          case Left(value) => Left(value).future
          case Right(ctx)  =>
            env.postgresql
              .queryOne(
                s"""
                   |INSERT INTO feature_context_name_unicity_check_table (parent, context) VALUES ($$1, $$2) RETURNING context
                   |""".stripMargin,
                List(if (Objects.isNull(parentId)) "" else parentId, featureContext.name),
                conn = Some(conn)
              ) { r => Some(ctx) }
              .map(o => o.toRight(InternalServerError()))
              .recover {
                case f: PgException if f.getSqlState == UNIQUE_VIOLATION =>
                  Left(ConflictWithSameNameLocalContext(name = featureContext.name, parentCtx = parents.mkString("/")))
                case _                                                   =>
                  Left(InternalServerError())
              }
        }
    })

  }

  // TODO merge with createFeatureSubContext ?
  def createFeatureContext(
      tenant: String,
      project: String,
      featureContext: FeatureContext
  ): Future[Either[IzanamiError, FeatureContext]] = {
    val id = generateSubContextId(project, featureContext.name)
    env.postgresql.executeInTransaction(conn => {
      env.postgresql
        .queryOne(
          s"""
             |INSERT INTO feature_contexts (id, name, project)
             |VALUES ($$1, $$2, $$3)
             |RETURNING *
             |""".stripMargin,
          List(id, featureContext.name, project),
          schemas = Set(tenant),
          conn = Some(conn)
        ) { row => row.optFeatureContext(global = false) }
        .map(o => o.toRight(InternalServerError()))
        .flatMap {
          case Left(value) => Left(value).future
          case Right(ctx)  => {
            env.postgresql
              .queryOne(
                s"""
                   |INSERT INTO feature_context_name_unicity_check_table (parent, context) VALUES ($$1, $$2) RETURNING context
                   |""".stripMargin,
                List("", featureContext.name),
                conn = Some(conn)
              ) { _ => Some(ctx) }
              .map(o => o.toRight(InternalServerError()))
              .recover {
                case f: PgException if f.getSqlState == UNIQUE_VIOLATION =>
                  Left(ConflictWithSameNameGlobalContext(name = featureContext.name))
                case _                                                   =>
                  Left(InternalServerError())
              }
          }
        }

    })

  }

  def createFeatureSubContext(
      tenant: String,
      project: String,
      parents: Seq[String],
      name: String
  ): Future[Either[IzanamiError, FeatureContext]] = {
    if (parents.isEmpty) {
      createFeatureContext(tenant, project, FeatureContext(id = null, name, global = true))
    } else {
      val id      = generateSubContextId(project, name, parents)
      val isLocal = env.postgresql
        .queryOne(
          s"""SELECT id FROM feature_contexts WHERE id=$$1""",
          List(generateSubContextId(project, parents)),
          schemas = Set(tenant)
        ) { r =>
          Some(())
        }
        .map(o => o.fold(false)(_ => true))

      isLocal.flatMap(local => {
        val parentId =
          if (parents.isEmpty) null
          else if (local) generateSubContextId(project, parents)
          else generateSubContextId(tenant, parents)
        env.postgresql.executeInTransaction(
          conn => {
            env.postgresql
              .queryOne(
                s"""
                   |INSERT INTO feature_contexts (id, name, project, ${if (local) "parent" else "global_parent"})
                   |VALUES ($$1, $$2, $$3, $$4)
                   |RETURNING *
                   |""".stripMargin,
                List(id, name, project, parentId),
                conn = Some(conn)
              ) { row => row.optFeatureContext(global = false) }
              .map(o => o.toRight(InternalServerError()))
              .recover {
                case f: PgException if f.getSqlState == FOREIGN_KEY_VIOLATION =>
                  Left(FeatureContextDoesNotExist(parents.mkString("/")))
                case ex                                                       =>
                  logger.error("Failed to user", ex)
                  Left(InternalServerError())
              }
              .flatMap {
                case Left(error)          => Left(error).future
                case Right(ctx) if !local => {
                  env.postgresql
                    .queryOne(
                      s"""
                         |INSERT INTO feature_context_name_unicity_check_table (parent, context) VALUES ($$1, $$2) RETURNING context
                         |""".stripMargin,
                      List(parentId, name),
                      conn = Some(conn)
                    ) { _ => Some(ctx) }
                    .map(o => o.toRight(InternalServerError()))
                    .recover {
                      case f: PgException if f.getSqlState == UNIQUE_VIOLATION =>
                        Left(ConflictWithSameNameGlobalContext(name = name, parentCtx = parents.mkString("/")))
                      case _                                                   =>
                        Left(InternalServerError())
                    }
                }
                case Right(ctx)           => Right(ctx).future
              }
          },
          schemas = Set(tenant)
        )
      })
    }
  }

  def readStrategyForContext(
      tenant: String,
      featureContext: Seq[String],
      feature: AbstractFeature
  ): Future[Option[CompleteContextualStrategy]] = {
    val possibleContextPaths = featureContext.foldLeft(Seq(): Seq[Seq[String]])((acc, next) => {
      val newElement = acc.lastOption.map(last => last.appended(next)).getOrElse(Seq(next))
      acc.appended(newElement)
    })

    val possibleContextIds = possibleContextPaths
      .map(path => {
        generateSubContextId(feature.project, path.last, path.dropRight(1))
      })
      .concat(possibleContextPaths.map(path => {
        generateSubContextId(tenant, path.last, path.dropRight(1))
      }))

    env.postgresql.queryOne(
      s"""
         |SELECT gf.conditions, gf.enabled, ws.config
         |FROM feature_contexts_strategies gf
         |LEFT OUTER JOIN wasm_script_configurations ws ON gf.script_config=ws.id
         |WHERE gf.project = $$1
         |AND gf.feature = $$2
         |AND gf.context=ANY($$3)
         |ORDER BY length(gf.context) desc
         |limit 1
         |""".stripMargin,
      List(feature.project, feature.name, possibleContextIds.toArray),
      schemas = Set(tenant)
    ) { row =>
      {
        row.optCompleteStrategy(feature.name)
      }
    }
  }

  def readGlobalFeatureContexts(tenant: String): Future[Seq[FeatureContext]] = {
    env.postgresql.queryAll(
      s"""
         |SELECT c.name, c.parent, c.id, NULL as project, COALESCE(
         |  json_agg(json_build_object('feature', s.feature, 'enabled', s.enabled, 'conditions', s.conditions, 'id', f.id, 'project', f.project, 'description', f.description , 'wasm', f.script_config)) FILTER (WHERE s.feature IS NOT NULL) , '[]'
         |) as overloads
         |FROM global_feature_contexts c
         |LEFT JOIN feature_contexts_strategies s ON s.global_context=c.id
         |LEFT JOIN features f ON f.name=s.feature
         |GROUP BY (c.name, c.parent, c.id)
         |""".stripMargin,
      List(),
      schemas = Set(tenant)
    ) { row => row.optFeatureContext(global = true) }
  }

  def readAllLocalFeatureContexts(tenant: String): Future[Seq[FeatureContext]] = {
    env.postgresql.queryAll(
      s"""
         |SELECT name, parent, id, true as global, null as project
         |FROM global_feature_contexts
         |UNION ALL
         |SELECT name, COALESCE(parent, global_parent) as parent, id, false as global, project
         |FROM feature_contexts
         |""".stripMargin,
      schemas = Set(tenant)
    ) { r =>
      for (
        id     <- r.optString("id");
        name   <- r.optString("name");
        global <- r.optBoolean("global")
      ) yield FeatureContext(id, name, r.optString("parent").orNull, global = global, project = r.optString("project"))
    }
  }

  def readFeatureContexts(tenant: String, project: String): Future[Seq[FeatureContext]] = {
    env.postgresql.queryAll(
      s"""
         |SELECT c.name, COALESCE(c.parent, c.global_parent) as parent, c.id, c.project, COALESCE(
         |  json_agg(json_build_object('feature', s.feature, 'enabled', s.enabled, 'conditions', s.conditions, 'id', f.id, 'description', f.description, 'project', f.project, 'wasm', f.script_config)) FILTER (WHERE s.feature IS NOT NULL) , '[]'
         |) as overloads
         |FROM feature_contexts c
         |LEFT JOIN feature_contexts_strategies s ON s.context=c.id
         |LEFT JOIN features f ON f.name=s.feature
         |WHERE c.project=$$1
         |GROUP BY (c.name, parent, c.id, c.project)
         |UNION ALL
         |SELECT c.name, c.parent, c.id, NULL as project, COALESCE(
         |  json_agg(json_build_object('feature', s.feature, 'enabled', s.enabled, 'conditions', s.conditions, 'id', f.id, 'description', f.description, 'project', f.project, 'wasm', f.script_config)) FILTER (WHERE s.feature IS NOT NULL) , '[]'
         |) as overloads
         |FROM global_feature_contexts c
         |LEFT JOIN feature_contexts_strategies s ON s.global_context=c.id
         |LEFT JOIN features f ON f.name=s.feature
         |GROUP BY (c.name, c.parent, c.id)
         |""".stripMargin,
      List(project),
      schemas = Set(tenant)
    ) { row => row.optFeatureContext(global = row.optString("project").isEmpty) }
  }

  def deleteContext(tenant: String, project: String, path: Seq[String]): Future[Either[IzanamiError, Unit]] = {
    val ctxName    = path.last
    val parentPart = path.init
    env.postgresql.executeInTransaction(conn => {
      env.postgresql
        .queryOne(
          s"""
             |DELETE FROM feature_contexts
             |WHERE id=$$1
             |RETURNING *
             |""".stripMargin,
          List(generateSubContextId(project, path)),
          schemas = Set(tenant),
          conn = Some(conn)
        ) { r => Some(()) }
        .map(_.toRight(FeatureContextDoesNotExist(path.mkString("/"))))
        .flatMap {
          case Left(value)  => Left(value).future
          case Right(value) => {
            env.postgresql
              .queryRaw(
                s"""DELETE FROM feature_context_name_unicity_check_table WHERE parent = $$1 AND context=$$2""",
                List(if (parentPart.isEmpty) "" else generateSubContextId(project, parentPart), ctxName),
                conn = Some(conn)
              ) { _ => Some(()) }
              .map(_ => Right(()))
          }
        }
    })
  }

  def deleteFeatureStrategy(
      tenant: String,
      project: String,
      path: Seq[String],
      feature: String,
      user: String
  ): Future[Either[IzanamiError, Unit]] = {
    val isLocal = env.postgresql
      .queryOne(
        s"""SELECT id FROM feature_contexts WHERE id=$$1""",
        List(generateSubContextId(project, path)),
        schemas = Set(tenant)
      ) { r =>
        Some(())
      }
      .map(o => o.fold(false)(_ => true))
    isLocal
      .flatMap(local =>
        env.datastores.features
          .findActivationStrategiesForFeatureByName(tenant, feature, project)
          .map(o => o.toRight(InternalServerError()))
          .map(e => e.map(map => (local, FeatureWithOverloads(map))))
      )
      .flatMap {
        case Left(error)                          => Left(error).future
        case Right((local, featureWithOverloads)) => {

          env.postgresql.executeInTransaction(implicit conn => {
            env.postgresql
              .queryOne(
                s"""
                   |DELETE FROM feature_contexts_strategies
                   |WHERE project=$$1 AND context=$$2 AND feature=$$3
                   |RETURNING (SELECT f.id FROM features f WHERE f.project=$$1 AND name=$$3)
                   |""".stripMargin,
                List(
                  project,
                  if (local) generateSubContextId(project, path) else generateSubContextId(tenant, path),
                  feature
                ),
                schemas = Set(tenant),
                conn = Some(conn)
              ) { r => r.optString("id") }
              .map(_.toRight(FeatureOverloadDoesNotExist(project, path.mkString("/"), feature)))
              .flatMap {
                case Left(err)  => Left(err).future
                case Right(fid) => {
                  env.eventService
                    .emitEvent(
                      channel = tenant,
                      event = SourceFeatureUpdated(
                        id = fid,
                        project = project,
                        tenant = tenant,
                        user = user,
                        previous = featureWithOverloads,
                        feature = featureWithOverloads.removeOverload(path.mkString("_"))
                      )
                    )
                    .map(_ => Right(()))
                }
              }
          })
        }
      }
  }

  def isGlobal(tenant: String, context: Seq[String]): Future[Boolean] = {
    env.postgresql
      .queryOne(
        s"""SELECT id FROM global_feature_contexts WHERE id=$$1""",
        List(generateSubContextId(tenant, context.mkString("_"))),
        schemas = Set(tenant)
      ) { r =>
        Some(())
      }
      .map(o => o.fold(false)(_ => true))
  }

  def updateFeatureStrategy(
      tenant: String,
      project: String,
      path: Seq[String],
      feature: String,
      strategy: CompleteContextualStrategy,
      user: String
  ): Future[Either[IzanamiError, Unit]] = {
    // TODO factorize this
    val isLocal = env.postgresql
      .queryOne(
        s"""SELECT id FROM feature_contexts WHERE id=$$1""",
        List(generateSubContextId(project, path)),
        schemas = Set(tenant)
      ) { r =>
        Some(())
      }
      .map(o => o.fold(false)(_ => true))
    isLocal
      .flatMap(local => {
        env.datastores.features
          .findActivationStrategiesForFeatureByName(tenant, feature, project)
          .map(o => o.toRight(FeatureDoesNotExist(feature)).map(m => (local, FeatureWithOverloads(m))))
      })
      .flatMap {
        case Left(value)                => Left(value).future
        case Right((local, oldFeature)) => {
          env.postgresql.executeInTransaction(
            implicit conn => {
              (strategy match {
                case ClassicalFeatureStrategy(enabled, conditions, _) =>
                  env.postgresql
                    .queryRaw(
                      s"""
                         |INSERT INTO feature_contexts_strategies (project, ${if (local) "local_context"
                      else "global_context"}, conditions, enabled, feature) VALUES($$1,$$2,$$3,$$4,$$5)
                         |ON CONFLICT(project, context, feature) DO UPDATE
                         |SET conditions=EXCLUDED.conditions, enabled=EXCLUDED.enabled, script_config=NULL
                         |""".stripMargin,
                      List(
                        project,
                        if (local) generateSubContextId(project, path.last, path.dropRight(1))
                        else generateSubContextId(tenant, path.last, path.dropRight(1)),
                        new JsonArray(Json.toJson(conditions).toString()),
                        java.lang.Boolean.valueOf(enabled),
                        feature
                      ),
                      schemas = Set(tenant),
                      conn = Some(conn)
                    ) { _ => Right(oldFeature.id) }
                    .recover {
                      case f: PgException if f.getSqlState == RELATION_DOES_NOT_EXISTS =>
                        Left(TenantDoesNotExists(tenant))
                      case f: PgException if f.getSqlState == FOREIGN_KEY_VIOLATION    =>
                        Left(FeatureContextDoesNotExist(path.mkString("/")))
                      case _                                                           => Left(InternalServerError())
                    }
                case f @ (_: CompleteWasmFeatureStrategy)             => {
                  (f match {
                    case f: CompleteWasmFeatureStrategy if f.wasmConfig.source.kind != WasmSourceKind.Local =>
                      env.datastores.features.createWasmScriptIfNeeded(tenant, f.wasmConfig, Some(conn))
                    case f: CompleteWasmFeatureStrategy                                                     => Right(f.wasmConfig.name).future
                  }).flatMap {
                    case Left(err) => Left(err).future
                    case Right(id) => {
                      env.postgresql
                        .queryRaw(
                          s"""
                               |INSERT INTO feature_contexts_strategies (project, ${if (local) "local_context"
                          else "global_context"}, script_config, enabled, feature) VALUES($$1,$$2,$$3,$$4,$$5)
                               |ON CONFLICT(project, context, feature) DO UPDATE
                               |SET script_config=EXCLUDED.script_config, enabled=EXCLUDED.enabled, conditions=NULL
                               |""".stripMargin,
                          List(
                            project,
                            if (local) generateSubContextId(project, path.last, path.dropRight(1))
                            else generateSubContextId(tenant, path.last, path.dropRight(1)),
                            id,
                            java.lang.Boolean.valueOf(f.enabled),
                            feature
                          ),
                          schemas = Set(tenant),
                          conn = Some(conn)
                        ) { _ => Right(id) }
                    }
                  }
                }
              }).flatMap {
                case Right(fid) =>
                  env.eventService
                    .emitEvent(
                      channel = tenant,
                      event = SourceFeatureUpdated(
                        id = fid,
                        project = project,
                        tenant = tenant,
                        user = user,
                        previous = oldFeature,
                        feature = oldFeature
                          .updateConditionsForContext(path.mkString("_"), strategy.toLightWeightContextualStrategy)
                      )
                    )
                    .map(_ => Right(()))
                case Left(err)  => Left(err).future
              }
            },
            schemas = Set(tenant)
          )
        }
      }
  }
}

object FeatureContextDatastore {
  implicit class FeatureContextRow(val row: Row) extends AnyVal {
    def optFeatureContext(global: Boolean): Option[FeatureContext] = {

      val features = row
        .optJsArray("overloads")
        .toSeq
        .flatMap(array =>
          array.value.flatMap(jsObject => {
            val maybeConditions   = (jsObject \ "conditions").asOpt[Set[ActivationCondition]]
            val maybeScriptConfig = (jsObject \ "wasm").asOpt[String]

            for (
              name        <- (jsObject \ "feature").asOpt[String];
              enabled     <- (jsObject \ "enabled").asOpt[Boolean];
              id          <- (jsObject \ "id").asOpt[String];
              description <- (jsObject \ "description").asOpt[String];
              project     <- (jsObject \ "project").asOpt[String]
            )
              yield {
                (maybeScriptConfig, maybeConditions) match {
                  case (Some(wasmConfig), _)    =>
                    Some(
                      LightWeightWasmFeature(
                        name = name,
                        enabled = enabled,
                        id = id,
                        project = project,
                        wasmConfigName = wasmConfig,
                        description = description
                      )
                    )
                  case (None, Some(conditions)) =>
                    Some(
                      Feature(
                        name = name,
                        enabled = enabled,
                        id = id,
                        project = project,
                        conditions = conditions,
                        description = description
                      )
                    )
                  case _                        => None
                }
              }.toSeq
          })
        )
        .flatMap(o => o.toSeq)
      Some(
        FeatureContext(
          id = row.getString("id"),
          name = row.getString("name"),
          parent = row.getString("parent"),
          project = if (global) None else row.optString("project"),
          overloads = features,
          global = global
        )
      )
    }

    def optCompleteStrategy(feature: String): Option[CompleteContextualStrategy] = {
      val maybeConditions = row
        .optJsArray("conditions")
        .map(arr => arr.value.map(v => v.as[ActivationCondition]).toSet)
        .getOrElse(Set.empty)
      val maybeConfig     = row.optJsObject("config").flatMap(obj => obj.asOpt(WasmConfig.format))

      row
        .optBoolean("enabled")
        .map(enabled => {
          (maybeConfig, maybeConditions) match {
            case (Some(config), _) => CompleteWasmFeatureStrategy(enabled, config, feature)
            case (_, conditions)   => ClassicalFeatureStrategy(enabled, conditions, feature)
          }
        })
    }

    def optStrategy(feature: String): Option[ContextualFeatureStrategy] = {
      val maybeConditions = row
        .optJsArray("conditions")
        .map(arr => arr.value.map(v => v.as[ActivationCondition]).toSet)
        .getOrElse(Set.empty)
      val maybeConfig     = row.optString("config")

      row
        .optBoolean("enabled")
        .map(enabled => {
          (maybeConfig, maybeConditions) match {
            case (Some(config), _) => LightWeightWasmFeatureStrategy(enabled, config, feature)
            case (_, conditions)   => ClassicalFeatureStrategy(enabled, conditions, feature)
          }
        })
    }
  }

}
