package fr.maif.izanami.datastores

import fr.maif.izanami.datastores.FeatureContextDatastore.FeatureContextRow
import fr.maif.izanami.env.{Env, Postgresql}
import fr.maif.izanami.env.PostgresqlErrors.{FOREIGN_KEY_VIOLATION, RELATION_DOES_NOT_EXISTS, UNIQUE_VIOLATION}
import fr.maif.izanami.env.pgimplicits.EnhancedRow
import fr.maif.izanami.errors._
import fr.maif.izanami.events.EventOrigin.NormalOrigin
import fr.maif.izanami.events.SourceFeatureUpdated
import fr.maif.izanami.models._
import fr.maif.izanami.models.features._
import fr.maif.izanami.utils.Datastore
import fr.maif.izanami.utils.syntax.implicits.{BetterBoolean, BetterSyntax}
import fr.maif.izanami.wasm.WasmConfig
import fr.maif.izanami.web.{FeatureContextPath, UserInformation}
import io.otoroshi.wasm4s.scaladsl.WasmSourceKind
import io.vertx.core.json.JsonArray
import io.vertx.pgclient.PgException
import io.vertx.sqlclient.{Row, SqlConnection}
import play.api.libs.json.{JsArray, Json, Writes}

import java.util.Objects
import scala.concurrent.Future

class FeatureContextDatastore(val env: Env) extends Datastore {
  val postgresql: Postgresql = env.postgresql
  val extensionSchema = env.extensionsSchema

  def readContext(tenant: String, path: FeatureContextPath): Future[Option[Context]] = {
    postgresql.queryOne(
      s"""
         |SELECT global, name, project, ltree2text(parent) as parent, protected
         |FROM new_contexts
         |WHERE ctx_path=text2ltree($$1)
         |""".stripMargin,
      List(path.toDBPath),
      schemas = Seq(extensionSchema, tenant)
    ) {r => r.optContext}
  }

  def getFeatureContext(tenant: String, project: String, path: FeatureContextPath): Future[Option[Context]] = {
    postgresql.queryOne(
      s"""
         |SELECT global, name, project, ltree2text(parent) as parent, protected
         |FROM new_contexts
         |WHERE ctx_path=text2ltree($$1)
         |AND (project=$$2 OR global=true)
         |""".stripMargin,
      List(path.toDBPath, project),
      schemas = Seq(extensionSchema, tenant)
    ) {r => r.optContext}
  }

  def getLocalFeatureContext(tenant: String, project: String, path: FeatureContextPath): Future[Option[LocalContext]] = {
    getFeatureContext(tenant, project, path).map {
      case Some(l: LocalContext) => Some(l)
      case _ => None
    }
  }


  def getGlobalFeatureContext(tenant: String, parents: FeatureContextPath): Future[Option[GlobalContext]] = {
    postgresql.queryOne(
      s"""
         |SELECT name, project, ltree2text(parent) as parent, protected
         |FROM new_contexts
         |WHERE ctx_path=text2ltree($$1)
         |AND global=true
         |""".stripMargin,
      List(parents.toDBPath),
      schemas = Seq(extensionSchema, tenant)
    ) {r => r.optGlobalContext}
  }

  def updateGlobalFeatureContext(tenant: String, context: FeatureContextPath, isProtected: Boolean): Future[Either[IzanamiError, Unit]] = {
    postgresql.queryOne(
      s"""
         |UPDATE new_contexts
         |SET protected=$$1
         |WHERE ctx_path=text2ltree($$2)
         |${if(isProtected) s"OR text2ltree($$2) @> ctx_path" else ""}
         |RETURNING *
         |""".stripMargin,
      List(isProtected.toJava, context.toDBPath),
      schemas=Seq(extensionSchema, tenant)
    ){_ => Some(())}
      .map {
        case Some(_) => Right()
        case None => Left(FeatureContextDoesNotExist(context.toUserPath))
      }
  }

  def updateLocalFeatureContext(tenant: String, project: String, isProtected: Boolean, path: FeatureContextPath): Future[Either[IzanamiError, Unit]] = {
    postgresql.queryOne(
        s"""
           |UPDATE new_contexts
           |SET protected=$$1
           |WHERE ctx_path=text2ltree($$2)
           |AND project=$$3
           |${if(isProtected) s"OR text2ltree($$2) @> ctx_path" else ""}
           |RETURNING *
           |""".stripMargin,
        List(isProtected.toJava, path.toDBPath, project),
        schemas = Seq(extensionSchema, tenant)
      ){_ => Some(())}
      .map(o => o.toRight(FeatureContextDoesNotExist(path.toUserPath)))
  }

  def findChildrenForGlobalContext(tenant: String, path: FeatureContextPath): Future[Seq[Context]] = {
    postgresql.queryAll(
      s"""
         |SELECT name, ltree2text(parent) as parent, project, global, protected
         |FROM new_contexts
         |WHERE text2ltree($$1) @> ctx_path
         |""".stripMargin,
      List(path.toDBPath),
      schemas = Seq(extensionSchema, tenant)){r => r.optContext}
  }

  def findChildrenForLocalContext(tenant: String, project: String, path: FeatureContextPath): Future[Seq[LocalContext]] = {
    postgresql.queryAll(
      s"""
         |SELECT name, ltree2text(parent) as parent, project, global, protected
         |FROM new_contexts
         |WHERE text2ltree($$1) @> ctx_path
         |AND project = $$2
         |""".stripMargin,
      List(path.toDBPath, project),
      schemas = Seq(extensionSchema, tenant))
    {r => r.optLocalContext}
  }

  def deleteGlobalFeatureContext(tenant: String, path: FeatureContextPath): Future[Either[IzanamiError, Unit]] = {
    postgresql
      .queryOne(
        s"""
           |DELETE FROM new_contexts
           |WHERE ctx_path=text2ltree($$1)
           |RETURNING *
           |""".stripMargin,
        List(path.toDBPath),
        schemas = Seq(extensionSchema, tenant)
      ) { r => Some(()) }
      .map(_.toRight(FeatureContextDoesNotExist(path.toUserPath)))
  }

  /*def updateContext(tenant: String, parents: Seq[String], featureContext: Context) = {
    postgresql.queryOne(
        s"""
           |UPDATE new_contexts
           |SET
           |  global
           |  name
           |  project
           |  protected
           |  path = $$1,
           |  project = $$2,
           |  protected = $$3,
           |  global = $$4
           |WHERE
           |  id = $$5
           |RETURNING *
           |""".stripMargin,
        List(
          parents.mkString("/").appended(featureContext.name).toArray,
          featureContext.project.orNull,
          featureContext.isProtected.toJava,
          featureContext.global.toJava,
          featureContext.id
        ),
        schemas = Seq(tenant)
      ){
        r => {
          r.optFeatureContext(global = featureContext.global)
        }
      }
      .map(o => o.toRight(InternalServerError()))
      .recover(postgresql.pgErrorPartialFunction.andThen(err => Left(err)))
  }*/

  def createContext(tenant: String, parents: FeatureContextPath, featureContext: FeatureContextCreationRequest): Future[Either[IzanamiError, Context]] = {
    val maybeProject = featureContext match {
      case ctx: LocalFeatureContextCreationRequest => Some(ctx.project)
      case _ => None
    }
    val parent = parents.toDBPath match {
      case "" => null
      case s => s
    }
    postgresql.queryOne(
        s"""
           |INSERT INTO new_contexts (parent, global, name, project, protected)
           |VALUES (
           |  text2ltree($$1),
           |  $$2,
           |  $$3,
           |  $$4,
           |  $$5
           |)
           |RETURNING ltree2text(parent) as parent, global, name, project, protected
           |""".stripMargin,
        List(
          parent,
          featureContext.global.toJava,
          featureContext.name,
          maybeProject.orNull,
          featureContext.isProtected.toJava
        ),
        schemas = Seq(extensionSchema, tenant)
      ) {
        r => r.optContext
      }
      .map(o => o.toRight(InternalServerError()))
      .recover(postgresql.pgErrorPartialFunction.andThen(err => Left(err)))
  }

  /*def createGlobalFeatureContext(
                                  tenant: String,
                                  parents: Seq[String],
                                  featureContext: FeatureContext
                                ): Future[Either[IzanamiError, FeatureContext]] = {
    val id       = generateSubContextId(tenant, featureContext.name, parents)
    val parentId = if (parents.isEmpty) null else generateSubContextId(tenant, parents)

    postgresql.executeInTransaction(conn => {
      postgresql
        .queryOne(
          s"""
             |INSERT INTO global_feature_contexts (id, name, parent, protected)
             |VALUES ($$1, $$2, $$3, $$4)
             |RETURNING *
             |""".stripMargin,
          List(id, featureContext.name, parentId, java.lang.Boolean.valueOf(featureContext.isProtected)),
          schemas = Seq(tenant),
          conn = Some(conn)
        ) { row => row.optFeatureContext(global = true) }
        .map(o => o.toRight(InternalServerError()))
        .recover(postgresql.pgErrorPartialFunction.andThen(err => Left(err)))
        .flatMap {
          case Left(value) => Left(value).future
          case Right(ctx)  =>
            postgresql
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

    createContext(tenant, parents, featureContext)
  }*/

  // TODO merge with createFeatureSubContext ?
  /* def createFeatureContext(
                             tenant: String,
                             project: String,
                             featureContext: FeatureContext
                           ): Future[Either[IzanamiError, FeatureContext]] = {
     val id = generateSubContextId(project, featureContext.name)
     postgresql.executeInTransaction(conn => {
       postgresql
         .queryOne(
           s"""
              |INSERT INTO feature_contexts (id, name, project, protected)
              |VALUES ($$1, $$2, $$3, $$4)
              |RETURNING *
              |""".stripMargin,
           List(id, featureContext.name, project, java.lang.Boolean.valueOf(featureContext.isProtected)),
           schemas = Seq(tenant),
           conn = Some(conn)
         ) { row => row.optFeatureContext(global = false) }
         .map(o => o.toRight(InternalServerError()))
         .recover(postgresql.pgErrorPartialFunction.andThen(err => Left(err)))
         .flatMap {
           case Left(value) => Left(value).future
           case Right(ctx)  => {
             postgresql
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

  }*/

  /*def createFeatureSubContext(
                               tenant: String,
                               project: String,
                               parents: Seq[String],
                               name: String,
                               isProtected: Boolean
                             ): Future[Either[IzanamiError, FeatureContext]] = {
    createContext(tenant, parents = parents, featureContext = FeatureContext(id = null, name, global = true, isProtected = isProtected, project = Some(project)))
    if (parents.isEmpty) {
      createFeatureContext(tenant, project, FeatureContext(id = null, name, global = true, isProtected = isProtected))
    } else {
      val id      = generateSubContextId(project, name, parents)
      val isLocal = postgresql
        .queryOne(
          s"""SELECT id FROM feature_contexts WHERE id=$$1""",
          List(generateSubContextId(project, parents)),
          schemas = Seq(tenant)
        ) { r =>
          Some(())
        }
        .map(o => o.fold(false)(_ => true))

      isLocal.flatMap(local => {
        val parentId =
          if (parents.isEmpty) null
          else if (local) generateSubContextId(project, parents)
          else generateSubContextId(tenant, parents)
        postgresql.executeInTransaction(
          conn => {
            postgresql
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
                  postgresql
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
          schemas = Seq(tenant)
        )
      })
    }
  }*/

  def readStrategyForContext(
                              tenant: String,
                              featureContext: FeatureContextPath,
                              feature: AbstractFeature
                            ): Future[Option[CompleteContextualStrategy]] = {
    postgresql.queryOne(
      s"""
         |SELECT gf.conditions, gf.enabled, gf.value, gf.result_type, ws.config
         |FROM feature_contexts_strategies gf
         |LEFT OUTER JOIN wasm_script_configurations ws ON gf.script_config=ws.id
         |WHERE gf.project = $$1
         |AND gf.feature = $$2
         |AND gf.context @> text2ltree($$3)
         |ORDER BY nlevel(gf.context) desc
         |limit 1
         |""".stripMargin,
      List(feature.project, feature.name, featureContext.toDBPath),
      schemas = Seq(tenant)
    ) { row =>
      row.optCompleteStrategy(feature.name).map(cs => cs.asInstanceOf[CompleteContextualStrategy])
    }
  }

  def readGlobalFeatureContexts(tenant: String): Future[Seq[ContextWithOverloads]] = {
    postgresql.queryAll(
      // TODO add last call
      s"""
         |SELECT c.name, ltree2text(c.parent) as parent, c.protected, c.global, COALESCE(
         |  json_agg(json_build_object('feature', s.feature, 'enabled', s.enabled, 'conditions', s.conditions, 'id', f.id, 'project', f.project, 'description', f.description , 'wasm', s.script_config)) FILTER (WHERE s.feature IS NOT NULL) , '[]'
         |) as overloads
         |FROM new_contexts c
         |LEFT JOIN feature_contexts_strategies s ON s.context=c.ctx_path
         |LEFT JOIN features f ON f.name=s.feature
         |WHERE c.global=true
         |GROUP BY (c.name, c.parent, c.protected, c.global)
         |""".stripMargin,
      List(),
      schemas = Seq(extensionSchema, tenant)
    ) { row => row.optContextWithOverloadHierarchy}
  }

  def readAllLocalFeatureContexts(tenant: String): Future[Seq[Context]] = {
    postgresql.queryAll(
      s"""
         |SELECT name, ltree2text(parent) as parent, protected, global, project
         |FROM new_contexts fc
         |""".stripMargin,
      schemas = Seq(extensionSchema, tenant)
    ) { r => r.optContext}
  }

  def readFeatureContexts(tenant: String, project: String): Future[Seq[ContextWithOverloads]] = {
    postgresql.queryAll(
      s"""
         |SELECT c.name, ltree2text(parent) as parent, c.project, c.protected, c.global, COALESCE(
         |  json_agg(json_build_object('feature', s.feature, 'enabled', s.enabled, 'conditions', s.conditions, 'id', f.id, 'description', f.description, 'project', f.project, 'wasm', s.script_config, 'value', s.value, 'resultType', s.result_type)) FILTER (WHERE s.feature IS NOT NULL) , '[]'
         |) as overloads
         |FROM new_contexts c
         |LEFT JOIN feature_contexts_strategies s ON s.context=c.ctx_path
         |LEFT JOIN features f ON f.name=s.feature
         |WHERE c.project=$$1 or c.global=true
         |GROUP BY (c.name, parent, c.protected, c.project, c.global)
         |""".stripMargin,
      List(project),
      schemas = Seq(extensionSchema, tenant)
    ) { row => row.optContextWithOverloadHierarchy}
  }

  def deleteContext(tenant: String, project: String, path: FeatureContextPath): Future[Either[IzanamiError, Unit]] = {
    postgresql.executeInTransaction(conn => {
      postgresql
        .queryOne(
          s"""
             |DELETE FROM new_contexts
             |WHERE ctx_path=text2ltree($$1)
             |RETURNING *
             |""".stripMargin,
          List(path.toDBPath),
          schemas = Seq(extensionSchema, tenant),
          conn = Some(conn)
        ) { r => Some(()) }
        .map(_.toRight(FeatureContextDoesNotExist(path.toUserPath)))
    })
  }

  def deleteFeatureStrategies(
                               tenant: String,
                               project: String,
                               feature: String,
                               conn: SqlConnection
                             ): Future[Unit] = {
    postgresql.queryRaw(
      s"""DELETE FROM feature_contexts_strategies WHERE project=$$1 AND feature=$$2""",
      List(project, feature),
      schemas = Seq(tenant),
      conn = Some(conn)
    ) { _ => () }
  }

  def deleteFeatureStrategy(
                             tenant: String,
                             project: String,
                             path: FeatureContextPath,
                             feature: String,
                             user: UserInformation
                           ): Future[Either[IzanamiError, Unit]] = {
        env.datastores.features
          .findActivationStrategiesForFeatureByName(tenant, feature, project)
          .map(o => o.toRight(InternalServerError()))
          .map(e => e.map(map => FeatureWithOverloads(map)))
          .flatMap {
        case Left(error)                          => Left(error).future
        case Right(featureWithOverloads) => {

          postgresql.executeInTransaction(implicit conn => {
            postgresql
              .queryOne(
                s"""
                   |DELETE FROM feature_contexts_strategies
                   |WHERE project=$$1
                   |AND feature=$$2
                   |AND context=text2ltree($$3)
                   |RETURNING (SELECT f.id FROM features f WHERE f.project=$$1 AND name=$$2)
                   |""".stripMargin,
                List(
                  project,
                  feature,
                  path.toDBPath
                ),
                schemas = Seq(extensionSchema, tenant),
                conn = Some(conn)
              ) { r => r.optString("id") }
              .map(_.toRight(FeatureOverloadDoesNotExist(project, path.toUserPath, feature)))
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
                        feature = featureWithOverloads.removeOverload(path.toUserPath),
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

  def updateFeatureStrategy(
                             tenant: String,
                             project: String,
                             path: FeatureContextPath,
                             feature: String,
                             strategy: CompleteContextualStrategy,
                             user: UserInformation
                           ): Future[Either[IzanamiError, Unit]] = {
      env.datastores.features
        .findActivationStrategiesForFeatureByName(tenant, feature, project)
        .map(o => o.toRight(FeatureDoesNotExist(feature)).map(m => FeatureWithOverloads(m)))
      .flatMap {
        case Left(value)                => Left(value).future
        case Right(oldFeature) => {
          postgresql.executeInTransaction(
            implicit conn => {
              (strategy match {
                case ClassicalFeatureStrategy(enabled, _, resultDescriptor) =>
                  postgresql
                    .queryRaw(
                      s"""
                         |INSERT INTO feature_contexts_strategies (project, context, conditions, enabled, feature, result_type ${if (
                        resultDescriptor.resultType != BooleanResult
                      ) ",value"
                      else ""})
                         |VALUES($$1,text2ltree($$2),$$3,$$4,$$5, $$6 ${if (resultDescriptor.resultType != BooleanResult) s",$$7"
                      else ""})
                         |ON CONFLICT(project, context, feature) DO UPDATE
                         |SET conditions=EXCLUDED.conditions, enabled=EXCLUDED.enabled, script_config=NULL, value=EXCLUDED.value, result_type=EXCLUDED.result_type
                         |""".stripMargin,
                      resultDescriptor match {
                        case descriptor: ValuedResultDescriptor  =>
                          List(
                            project,
                            path.toDBPath,
                            new JsonArray(Json.toJson(resultDescriptor.conditions).toString()),
                            java.lang.Boolean.valueOf(enabled),
                            feature,
                            descriptor.resultType.toDatabaseName,
                            descriptor.stringValue
                          )
                        case BooleanResultDescriptor(conditions) =>
                          List(
                            project,
                            path.toDBPath,
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
                      schemas = Seq(extensionSchema, tenant),
                      conn = Some(conn)
                    ) { _ => Right(oldFeature.id) }
                    .recover {
                      case f: PgException if f.getSqlState == RELATION_DOES_NOT_EXISTS =>
                        Left(TenantDoesNotExists(tenant))
                      case f: PgException
                          if f.getSqlState == FOREIGN_KEY_VIOLATION && f.getConstraint == "feature_contexts_strategies_feature_project_fkey" =>
                        Left(
                          NoFeatureMatchingOverloadDefinition(
                            tenant = tenant,
                            project = project,
                            feature = feature,
                            resultType = strategy.resultType.toDatabaseName
                          )
                        )
                      case f: PgException if f.getSqlState == FOREIGN_KEY_VIOLATION    =>
                        Left(FeatureContextDoesNotExist(path.toUserPath))
                      case _                                                           => Left(InternalServerError())
                    }
                case f @ (_: CompleteWasmFeatureStrategy)                   => {
                  (f match {
                    case f: CompleteWasmFeatureStrategy if f.wasmConfig.source.kind != WasmSourceKind.Local =>
                      env.datastores.features.createWasmScriptIfNeeded(tenant, f.wasmConfig, Some(conn))
                    case f: CompleteWasmFeatureStrategy                                                     => Right(f.wasmConfig.name).future
                  }).flatMap {
                    case Left(err) => Left(err).future
                    case Right(id) => {
                      postgresql
                        .queryRaw(
                          s"""
                             |INSERT INTO feature_contexts_strategies (project, context, script_config, enabled, feature, result_type)
                             |VALUES($$1,text2ltree($$2),$$3,$$4,$$5, $$6)
                             |ON CONFLICT(project, context, feature) DO UPDATE
                             |SET script_config=EXCLUDED.script_config, enabled=EXCLUDED.enabled, conditions=NULL, result_type=EXCLUDED.result_type
                             |""".stripMargin,
                          List(
                            project,
                            path.toDBPath,
                            id,
                            java.lang.Boolean.valueOf(f.enabled),
                            feature,
                            f.resultType.toDatabaseName
                          ),
                          schemas = Seq(extensionSchema, tenant),
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
                          .updateConditionsForContext(path.toUserPath, strategy.toLightWeightContextualStrategy),
                        origin = NormalOrigin,
                        authentication = user.authentication
                      )
                    )
                    .map(_ => Right(()))
                case Left(err)  => Left(err).future
              }
            },
            schemas = Seq(tenant)
          )
        }
      }
  }


  /**
   * Find parents contexts of given path (including it), sorted from "higher" ancestor to "lower"
   * @param tenant schema to use
   * @param path path to search parent for
   * @return list of ordered parents, from higher to lower
   */
  def findParents(tenant: String, path: String): Future[List[Context]] = {
    postgresql.queryAll(
      s"""
         |SELECT
         |  ltree2text(parent) as parent,
         |  name,
         |  global,
         |  protected,
         |  project
         |FROM new_contexts
         |WHERE ctx_path @> text2ltree($$1)
         |ORDER BY nlevel(ctx_path) ASC
         |""".stripMargin,
      List(path),
      schemas = Seq(tenant)
    ){r =>r.optContext }
  }

  def findLocalContexts(tenant: String, idCandidates: Set[String]): Future[List[String]] = {
    postgresql.queryAll( // TODO review this use
      s"""
         |SELECT id
         |FROM new_contexts
         |WHERE id=ANY($$1)
         |""".stripMargin,
      List(idCandidates.toArray),
      schemas = Seq(tenant)
    ) { r => r.optString("id") }
  }
}

object FeatureContextDatastore {
  implicit class FeatureContextRow(val row: Row) extends AnyVal {
    def optContextWithOverloadHierarchy: Option[ContextWithOverloads] = {
      row.optContext.map(ctx => ContextWithOverloads(ctx, overloads = row.optOverloads("overloads")))
    }

    def optContext: Option[Context] = {
      row.optBoolean("global").flatMap(global => {
        if(global) {
          optGlobalContext
        } else {
          optLocalContext
        }
      })
    }

    def optLocalContext: Option[LocalContext] = {
      for(
        ctx <- optGlobalContext;
        project     <- row.optString("project")
      ) yield LocalContext(name = ctx.name, path = ctx.path, isProtected = ctx.isProtected, project = project)
    }

    def optGlobalContext: Option[GlobalContext] = {
      for (
        name        <- row.optString("name");
        parent <- row.optString("parent").map(ltree => ltree.split("\\.")).map(_.toSeq).orElse(Some(Seq()));
        isProtected <- row.optBoolean("protected")
      ) yield GlobalContext(name = name, path = parent, isProtected = isProtected)
    }

    def optOverloads(field: String): Seq[AbstractFeature] = {
      row
        .optJsArray(field)
        .getOrElse(JsArray.empty)
        .value
        .flatMap(jsObject => {
          val maybeSeq: Option[Seq[LightWeightFeature]] =
            for (
              name        <- (jsObject \ "feature").asOpt[String];
              enabled     <- (jsObject \ "enabled").asOpt[Boolean];
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
        .toSeq
    }

    def optCompleteStrategy(feature: String): Option[CompleteContextualStrategy] = {
      (for (
        enabled       <- row.optBoolean("enabled");
        resultTypeStr <- row.optString("result_type");
        resultType    <- ResultType.parseResultType(resultTypeStr)
      ) yield {
        val maybeConditions = row
          .optJsArray("conditions")
          .getOrElse(JsArray.empty)
        val maybeConfig     = row.optJsObject("config").flatMap(obj => obj.asOpt(WasmConfig.format))
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
  }

}
