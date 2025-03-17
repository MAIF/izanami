package fr.maif.izanami.datastores

import fr.maif.izanami.datastores.FeatureContextDatastore.FeatureContextRow
import fr.maif.izanami.env.Env
import fr.maif.izanami.env.PostgresqlErrors.{FOREIGN_KEY_VIOLATION, RELATION_DOES_NOT_EXISTS, UNIQUE_VIOLATION}
import fr.maif.izanami.env.pgimplicits.EnhancedRow
import fr.maif.izanami.errors._
import fr.maif.izanami.events.EventOrigin.NormalOrigin
import fr.maif.izanami.events.SourceFeatureUpdated
import fr.maif.izanami.models.FeatureContext.generateSubContextId
import fr.maif.izanami.models._
import fr.maif.izanami.models.features.{ActivationCondition, BooleanActivationCondition, BooleanResult, BooleanResultDescriptor, NumberActivationCondition, NumberResult, NumberResultDescriptor, ResultType, StringActivationCondition, StringResult, StringResultDescriptor, ValuedActivationCondition, ValuedResultDescriptor, ValuedResultType}
import fr.maif.izanami.utils.Datastore
import fr.maif.izanami.utils.syntax.implicits.BetterSyntax
import fr.maif.izanami.wasm.WasmConfig
import fr.maif.izanami.web.{FeatureContextPath, UserInformation}
import io.otoroshi.wasm4s.scaladsl.WasmSourceKind
import io.vertx.core.json.JsonArray
import io.vertx.pgclient.PgException
import io.vertx.sqlclient.{Row, SqlConnection}
import play.api.libs.json.{JsArray, JsValue, Json, Reads, Writes}

import java.util.Objects
import scala.concurrent.Future

class FeatureContextDatastore(val env: Env) extends Datastore {

  def getFeatureContext(tenant: String, project: String, parents: Seq[String]) = {
    val localParentId = generateSubContextId(project, parents)
    val globalParentId = generateSubContextId(tenant, parents)
    env.postgresql.queryOne(
      s"""
         |SELECT id, name, parent, project, false as global, protected
         |FROM feature_contexts
         |WHERE id=$$1
         |UNION ALL
         |SELECT id, name, parent, null as project, true as global, protected
         |FROM global_feature_contexts
         |WHERE id=$$2
         |""".stripMargin,
      List(localParentId, globalParentId),
      schemas = Set(tenant)
    ) {r =>
      for (
        id     <- r.optString("id");
        name   <- r.optString("name");
        global <- r.optBoolean("global");
        isProtected <- r.optBoolean("protected")
      ) yield FeatureContext(id, name, r.optString("parent").orNull, global = global, project = r.optString("project"), isProtected = isProtected)
    }
  }


  def getGlobalFeatureContext(tenant: String, parents: Seq[String]) = {
    val globalParentId = generateSubContextId(tenant, parents)
    env.postgresql.queryOne(
      s"""
         |SELECT id, name, parent, null as project, true as global, protected
         |FROM global_feature_contexts
         |WHERE id=$$1
         |""".stripMargin,
      List(globalParentId),
      schemas = Set(tenant)
    ) {r =>
      for (
        id     <- r.optString("id");
        name   <- r.optString("name");
        global <- r.optBoolean("global");
        isProtected <- r.optBoolean("protected")
      ) yield FeatureContext(id, name, r.optString("parent").orNull, global = global, project = r.optString("project"), isProtected = isProtected)
    }
  }

  private def doGetFeatureContext(tenant: String, project: String, parents: Seq[String]) = {
    val localParentId = generateSubContextId(project, parents)
    val globalParentId = generateSubContextId(tenant, parents)
    env.postgresql.queryOne(
      s"""
         |SELECT id, name, parent, project, false as global, protected
         |FROM feature_contexts
         |WHERE id=$$1
         |UNION ALL
         |SELECT id, name, parent, null as project, true as global, protected
         |FROM global_feature_contexts
         |WHERE id=$$2
         |""".stripMargin,
      List(localParentId, globalParentId),
      schemas = Set(tenant)
    ) {r =>
      for (
        id     <- r.optString("id");
        name   <- r.optString("name");
        global <- r.optBoolean("global");
        isProtected <- r.optBoolean("protected")
      ) yield FeatureContext(id, name, r.optString("parent").orNull, global = global, project = r.optString("project"), isProtected = isProtected)
    }
  }


  def updateGlobalFeatureContext(tenant: String, context: FeatureContextPath, isProtected: Boolean): Future[Either[IzanamiError, Unit]] = {
    val id = context.toDBPath(tenant)
    val idForLikeClause = s"${id}_%"
    val params = if(isProtected) {
      List(java.lang.Boolean.valueOf(isProtected), id, idForLikeClause)
    } else {
      List(java.lang.Boolean.valueOf(isProtected), id)
    }

    env.postgresql.executeInTransaction(implicit conn => {
      env.postgresql.queryOne(
          s"""
             |UPDATE global_feature_contexts
             |SET protected=$$1
             |WHERE id=$$2
             |${if(isProtected) s"OR parent LIKE $$3 OR parent = $$2" else ""}
             |RETURNING *
             |""".stripMargin,
          params,
          conn = Some(conn)
        ){_ => Some(())}
        .map(o => o.toRight(FeatureContextDoesNotExist(context.toUserPath)))
        .flatMap {
          case Left(err) => Left(err).future
          case Right(_) if isProtected => {
            env.postgresql.queryAll(
              s"""
                 |UPDATE feature_contexts
                 |SET protected=$$1
                 |WHERE global_parent LIKE $$2
                 |OR global_parent = $$3
                 |OR parent LIKE CONCAT(project, '_', $$4::TEXT, '_%')
                 |""".stripMargin,
              List(java.lang.Boolean.valueOf(isProtected), idForLikeClause, id, context.toUnprefixedDBPath),
              conn = Some(conn)
            ){_ => Some(()) }
              .map(_ => Right(()))
          }
          case _ => Right(()).future
        }
    }, schemas=Set(tenant))
  }

  def updateLocalFeatureContext(tenant: String, project: String, name: String, isProtected: Boolean, parents: FeatureContextPath): Future[Either[IzanamiError, Unit]] = {
    val parentPart = if(parents.elements.nonEmpty) s"${parents.elements.mkString("_")}_" else ""
    val id = s"${project}_${parentPart}${name}"

    val params = if(isProtected) {
      List(java.lang.Boolean.valueOf(isProtected), id, s"${id}_%")
    } else {
      List(java.lang.Boolean.valueOf(isProtected), id)
    }

    env.postgresql.queryOne(
        s"""
           |UPDATE feature_contexts
           |SET protected=$$1
           |WHERE id=$$2
           |${if(isProtected) s"OR parent LIKE $$3 OR PARENT = $$2" else ""}
           |RETURNING *
           |""".stripMargin,
        params,
        schemas = Set(tenant)
      ){_ => Some(())}
      .map(o => o.toRight(FeatureContextDoesNotExist(name)))
  }

  def findChildrenForGlobalContext(tenant: String, path: FeatureContextPath): Future[Seq[FeatureContext]] = {
    val id = path.toDBPath(tenant)
    val parentStart = s"${path.toDBPath(tenant)}_%"
    env.postgresql.queryAll(
      s"""
         |SELECT id, name, parent, null as project, true as global, protected
         |FROM global_feature_contexts gfc
         |WHERE gfc.parent LIKE $$1 OR gfc.parent LIKE $$2
         |UNION
         |SELECT id, name, COALESCE(fc.parent, fc.global_parent) as parent, project, false as global, protected
         |FROM feature_contexts fc
         |WHERE parent LIKE $$1 OR parent LIKE concat(project, '_', $$3::TEXT, '_%')
         |""".stripMargin,
      List(id, parentStart, path.toUnprefixedDBPath),
      schemas = Set(tenant)){r => {
      r.optFeatureContext(r.optBoolean("global").get)
    }}
  }

  def findChildrenForLocalContext(tenant: String, project: String, path: FeatureContextPath): Future[Seq[FeatureContext]] = {
    val id = path.toDBPath(project)
    val parentStart = s"${path.toDBPath(project)}_%"
    env.postgresql.queryAll(
      s"""
         |SELECT id, name, parent, project, false as global, protected
         |FROM feature_contexts
         |WHERE parent LIKE $$1 OR parent LIKE $$2
         |""".stripMargin,
      List(id, parentStart),
      schemas = Set(tenant)){r => {
      r.optFeatureContext(r.optBoolean("global").get)
    }}
  }

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
                s"""DELETE FROM feature_context_name_unicity_check_table
                   |WHERE (parent = $$1 AND context=$$2) OR (parent = $$3) OR (parent LIKE $$4)
                   |""".stripMargin,
                List(
                  if (parentPart.isEmpty) "" else generateSubContextId(tenant, parentPart),
                  ctxName,
                  s"${tenant}_${ctxName}",
                  s"${tenant}_${ctxName}_%"
                ),
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
             |INSERT INTO global_feature_contexts (id, name, parent, protected)
             |VALUES ($$1, $$2, $$3, $$4)
             |RETURNING *
             |""".stripMargin,
          List(id, featureContext.name, parentId, java.lang.Boolean.valueOf(featureContext.isProtected)),
          schemas = Set(tenant),
          conn = Some(conn)
        ) { row => row.optFeatureContext(global = true) }
        .map(o => o.toRight(InternalServerError()))
        .recover(env.postgresql.pgErrorPartialFunction.andThen(err => Left(err)))
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
             |INSERT INTO feature_contexts (id, name, project, protected)
             |VALUES ($$1, $$2, $$3, $$4)
             |RETURNING *
             |""".stripMargin,
          List(id, featureContext.name, project, java.lang.Boolean.valueOf(featureContext.isProtected)),
          schemas = Set(tenant),
          conn = Some(conn)
        ) { row => row.optFeatureContext(global = false) }
        .map(o => o.toRight(InternalServerError()))
        .recover(env.postgresql.pgErrorPartialFunction.andThen(err => Left(err)))
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
                               name: String,
                               isProtected: Boolean
                             ): Future[Either[IzanamiError, FeatureContext]] = {
    if (parents.isEmpty) {
      createFeatureContext(tenant, project, FeatureContext(id = null, name, global = true, isProtected = isProtected))
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
                   |INSERT INTO feature_contexts (id, name, project, ${if (local) "parent" else "global_parent"}, protected)
                   |VALUES ($$1, $$2, $$3, $$4, $$5)
                   |RETURNING *
                   |""".stripMargin,
                List(id, name, project, parentId, java.lang.Boolean.valueOf(isProtected)),
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
         |SELECT gf.conditions, gf.enabled, gf.value, gf.result_type, ws.config
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
      row.optCompleteStrategy(feature.name).map(cs => cs.asInstanceOf[CompleteContextualStrategy])
    }
  }

  def readGlobalFeatureContexts(tenant: String): Future[Seq[FeatureContext]] = {
    env.postgresql.queryAll(
      s"""
         |SELECT c.name, c.parent, c.id, NULL as project, c.protected, COALESCE(
         |  json_agg(json_build_object('feature', s.feature, 'enabled', s.enabled, 'conditions', s.conditions, 'id', f.id, 'project', f.project, 'description', f.description , 'wasm', s.script_config)) FILTER (WHERE s.feature IS NOT NULL) , '[]'
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
         |SELECT name, gfc.parent, id, protected, true as global, null as project
         |FROM global_feature_contexts gfc
         |UNION ALL
         |SELECT name, COALESCE(fc.parent, fc.global_parent) as parent, id, protected, false as global, project
         |FROM feature_contexts fc
         |""".stripMargin,
      schemas = Set(tenant)
    ) { r =>
      for (
        id     <- r.optString("id");
        name   <- r.optString("name");
        global <- r.optBoolean("global");
        isProtected <- r.optBoolean("protected")
      ) yield FeatureContext(id, name, r.optString("parent").orNull, global = global, project = r.optString("project"), isProtected = isProtected)
    }
  }

  def readFeatureContexts(tenant: String, project: String): Future[Seq[FeatureContext]] = {
    env.postgresql.queryAll(
      s"""
         |SELECT c.name, COALESCE(c.parent, c.global_parent) as parent, c.id, c.project, c.protected, COALESCE(
         |  json_agg(json_build_object('feature', s.feature, 'enabled', s.enabled, 'conditions', s.conditions, 'id', f.id, 'description', f.description, 'project', f.project, 'wasm', s.script_config, 'value', s.value, 'resultType', s.result_type)) FILTER (WHERE s.feature IS NOT NULL) , '[]'
         |) as overloads
         |FROM feature_contexts c
         |LEFT JOIN feature_contexts_strategies s ON s.context=c.id
         |LEFT JOIN features f ON f.name=s.feature
         |WHERE c.project=$$1
         |GROUP BY (c.name, parent, c.id, c.project)
         |UNION ALL
         |SELECT c.name, c.parent, c.id, NULL as project, c.protected, COALESCE(
         |  json_agg(json_build_object('feature', s.feature, 'enabled', s.enabled, 'conditions', s.conditions, 'id', f.id, 'description', f.description, 'project', f.project, 'wasm', s.script_config, 'value', s.value, 'resultType', s.result_type)) FILTER (WHERE s.feature IS NOT NULL) , '[]'
         |) as overloads
         |FROM global_feature_contexts c
         |LEFT JOIN feature_contexts_strategies s ON s.global_context=c.id
         |LEFT JOIN features f ON f.name=s.feature
         |GROUP BY (c.name, c.parent, c.id)
         |""".stripMargin,
      List(project),
      schemas = Set(tenant)
    ) { row =>
    {
      row.optFeatureContext(global = row.optString("project").isEmpty)
    }
    }
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
                s"""DELETE FROM feature_context_name_unicity_check_table
                   |WHERE (parent = $$1 AND context=$$2)
                   |""".stripMargin,
                List(
                  if (parentPart.isEmpty) "" else generateSubContextId(project, parentPart),
                  ctxName
                ),
                conn = Some(conn)
              ) { _ => Some(()) }
              .map(_ => Right(()))
          }
        }
    })
  }

  def deleteFeatureStrategies(
                               tenant: String,
                               project: String,
                               feature: String,
                               conn: SqlConnection
                             ): Future[Unit] = {
    env.postgresql.queryRaw(
      s"""DELETE FROM feature_contexts_strategies WHERE project=$$1 AND feature=$$2""",
      List(project, feature),
      schemas = Set(tenant),
      conn = Some(conn)
    ) { _ => () }
  }

  def deleteFeatureStrategy(
                             tenant: String,
                             project: String,
                             path: Seq[String],
                             feature: String,
                             user: UserInformation
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
                        user = user.username,
                        previous = featureWithOverloads,
                        feature = featureWithOverloads.removeOverload(path.mkString("_")),
                        origin = NormalOrigin,
                        authentication = user.authentication
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
                             user: UserInformation
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
                case ClassicalFeatureStrategy(enabled, _, resultDescriptor) =>
                  env.postgresql
                    .queryRaw(
                      s"""
                         |INSERT INTO feature_contexts_strategies (project, ${if (local) "local_context"
                      else "global_context"}, conditions, enabled, feature, result_type ${if (
                        resultDescriptor.resultType != BooleanResult
                      ) ",value"
                      else ""})
                         |VALUES($$1,$$2,$$3,$$4,$$5, $$6 ${if (resultDescriptor.resultType != BooleanResult) s",$$7"
                      else ""})
                         |ON CONFLICT(project, context, feature) DO UPDATE
                         |SET conditions=EXCLUDED.conditions, enabled=EXCLUDED.enabled, script_config=NULL, value=EXCLUDED.value, result_type=EXCLUDED.result_type
                         |""".stripMargin,
                      resultDescriptor match {
                        case descriptor: ValuedResultDescriptor  =>
                          List(
                            project,
                            if (local) generateSubContextId(project, path.last, path.dropRight(1))
                            else generateSubContextId(tenant, path.last, path.dropRight(1)),
                            new JsonArray(Json.toJson(resultDescriptor.conditions).toString()),
                            java.lang.Boolean.valueOf(enabled),
                            feature,
                            descriptor.resultType.toDatabaseName,
                            descriptor.stringValue
                          )
                        case BooleanResultDescriptor(conditions) =>
                          List(
                            project,
                            if (local) generateSubContextId(project, path.last, path.dropRight(1))
                            else generateSubContextId(tenant, path.last, path.dropRight(1)),
                            new JsonArray(
                              Json
                                .toJson(conditions)(Writes.seq(ActivationCondition.booleanConditionWrites))
                                .toString()
                            ),
                            java.lang.Boolean.valueOf(enabled),
                            feature,
                            resultDescriptor.resultType.toDatabaseName
                          )
                      },
                      schemas = Set(tenant),
                      conn = Some(conn)
                    ) { _ => Right(oldFeature.id) }
                    .recover {
                      case f: PgException if f.getSqlState == RELATION_DOES_NOT_EXISTS =>
                        Left(TenantDoesNotExists(tenant))
                      case f: PgException if (f.getSqlState == FOREIGN_KEY_VIOLATION && f.getConstraint == "feature_contexts_strategies_feature_project_fkey") =>
                        Left(NoFeatureMatchingOverloadDefinition(tenant = tenant, project = project, feature = feature, resultType = strategy.resultType.toDatabaseName))
                      case f: PgException if f.getSqlState == FOREIGN_KEY_VIOLATION    =>
                        Left(FeatureContextDoesNotExist(path.mkString("/")))
                      case _                                                           => Left(InternalServerError())
                    }
                case f @ (_: CompleteWasmFeatureStrategy)                            => {
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
                          else "global_context"}, script_config, enabled, feature, result_type) VALUES($$1,$$2,$$3,$$4,$$5, $$6)
                             |ON CONFLICT(project, context, feature) DO UPDATE
                             |SET script_config=EXCLUDED.script_config, enabled=EXCLUDED.enabled, conditions=NULL, result_type=EXCLUDED.result_type
                             |""".stripMargin,
                          List(
                            project,
                            if (local) generateSubContextId(project, path.last, path.dropRight(1))
                            else generateSubContextId(tenant, path.last, path.dropRight(1)),
                            id,
                            java.lang.Boolean.valueOf(f.enabled),
                            feature,
                            f.resultType.toDatabaseName
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
                        user = user.username,
                        previous = oldFeature,
                        feature = oldFeature
                          .updateConditionsForContext(path.mkString("_"), strategy.toLightWeightContextualStrategy),
                        origin = NormalOrigin,
                        authentication = user.authentication
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

  def findLocalContexts(tenant: String, idCandidates: Set[String]): Future[List[String]] = {
    env.postgresql.queryAll(
      s"""
         |SELECT id
         |FROM feature_contexts
         |WHERE id=ANY($$1)
         |""".stripMargin,
      List(idCandidates.toArray),
      schemas = Set(tenant)
    ) { r => r.optString("id") }
  }
}

object FeatureContextDatastore {
  implicit class FeatureContextRow(val row: Row) extends AnyVal {
    def optFeatureContext(global: Boolean): Option[FeatureContext] = {
      val features = row
        .optJsArray("overloads")
        .getOrElse(JsArray.empty)
        .value
        .flatMap(jsObject => {
          val maybeSeq: Option[Seq[LightWeightFeature]] =
            for (
              name        <- (jsObject \ "feature").asOpt[String];
              enabled     <- (jsObject \ "enabled").asOpt[Boolean];
              id          <- (jsObject \ "id").asOpt[String];
              description <- (jsObject \ "description").asOpt[String];
              project     <- (jsObject \ "project").asOpt[String];
              resultType  <- (jsObject \ "resultType").asOpt[ResultType](ResultType.resultTypeReads)
            )
            yield {
              val maybeConditions   = (jsObject \ "conditions")
                .asOpt[JsArray]
              val maybeScriptConfig = (jsObject \ "wasm").asOpt[String]
              (maybeScriptConfig, maybeConditions) match {
                case (Some(wasmConfig), _)    =>
                  Some(
                    LightWeightWasmFeature(
                      name = name,
                      enabled = enabled,
                      id = id,
                      project = project,
                      wasmConfigName = wasmConfig,
                      description = description,
                      resultType = resultType
                    )
                  )
                case (None, Some(conditions)) => {
                  resultType match {
                    case resultType: ValuedResultType => {
                      jsObject
                        .asOpt[ValuedResultDescriptor](ValuedResultDescriptor.valuedDescriptorReads)
                        .map(rd => {
                          Feature(
                            name = name,
                            enabled = enabled,
                            id = id,
                            project = project,
                            description = description,
                            resultDescriptor = rd
                          )
                        })
                    }
                    case BooleanResult                => {
                      val conds = conditions.value.flatMap(c =>
                        c.asOpt[BooleanActivationCondition](ActivationCondition.booleanActivationConditionRead).toSeq
                      )
                      Some(
                        Feature(
                          name = name,
                          enabled = enabled,
                          id = id,
                          project = project,
                          description = description,
                          resultDescriptor = BooleanResultDescriptor(conds.toSeq)
                        )
                      )
                    }
                  }
                }
                case _                        => None
              }
            }.toSeq
          maybeSeq
        })
        .flatten

      Some(
        FeatureContext(
          id = row.getString("id"),
          name = row.getString("name"),
          parent = row.getString("parent"),
          project = if (global) None else row.optString("project"),
          overloads = features.toSeq,
          global = global,
          isProtected = row.getBoolean("protected")
        )
      )
    }

    def optCompleteStrategy(feature: String): Option[CompleteContextualStrategy] = {
      (for (
        enabled       <- row.optBoolean("enabled");
        resultTypeStr <- row.optString("result_type");
        resultType    <- ResultType.parseResultType(resultTypeStr)
      ) yield {
        val maybeConditions                     = row
          .optJsArray("conditions")
          .getOrElse(JsArray.empty)
        val maybeConfig                         = row.optJsObject("config").flatMap(obj => obj.asOpt(WasmConfig.format))
        (maybeConfig, maybeConditions) match {
          case (Some(config), _) => Some(CompleteWasmFeatureStrategy(enabled, config, feature, resultType = resultType))
          case (_, conditions)   => {
            resultType match {
              case resultType: ValuedResultType => {
                resultType match {
                  case StringResult => {
                    val conds = conditions.value.flatMap(json =>
                      json.asOpt[StringActivationCondition](ActivationCondition.stringActivationConditionRead).toSeq
                    )
                    row
                      .optString("value")
                      .map(value => {
                        ClassicalFeatureStrategy(
                          enabled,
                          feature,
                          StringResultDescriptor(value = value, conditions = conds.toSeq)
                        )
                      })
                  }
                  case NumberResult => {
                    val conds = conditions.value.flatMap(json =>
                      json.asOpt[NumberActivationCondition](ActivationCondition.numberActivationConditionRead).toSeq
                    )
                    row
                      .optString("value")
                      .map(str => BigDecimal(str))
                      .map(value => {
                        ClassicalFeatureStrategy(
                          enabled,
                          feature = feature,
                          resultDescriptor = NumberResultDescriptor(value = value, conditions = conds.toSeq)
                        )
                      })
                  }
                }
              }
              case BooleanResult                => {
                val conds = conditions.value.flatMap(json =>
                  json.asOpt[BooleanActivationCondition](ActivationCondition.booleanActivationConditionRead).toSeq
                )
                Some(ClassicalFeatureStrategy(enabled, feature, BooleanResultDescriptor(conds.toSeq)))
              }
            }
          }
        }
      }).flatten
    }

    /*def optStrategy(feature: String): Option[ContextualFeatureStrategy] = {
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
    }*/
  }

}
