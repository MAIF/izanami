package fr.maif.izanami.datastores

import fr.maif.izanami.datastores.FeatureContextDatastore.FeatureContextRow
import fr.maif.izanami.env.{Env, Postgresql}
import fr.maif.izanami.env.PostgresqlErrors.{
  FOREIGN_KEY_VIOLATION,
  RELATION_DOES_NOT_EXISTS,
  UNIQUE_VIOLATION
}
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
  private val postgresql: Postgresql = env.postgresql
  private val extensionSchema = env.extensionsSchema

  def readProtectedContexts(
      tenant: String,
      project: String,
      parent: Option[FeatureContextPath] = None
  ): Future[Seq[Context]] = {
    require(Tenant.isTenantValid(tenant))
    postgresql.queryAll(
      s"""
         |SELECT global, name, project, "${extensionSchema}".ltree2text(parent) as parent, protected
         |FROM "${tenant}".new_contexts
         |WHERE (global=true
         |OR project=$$1)
         |AND protected=true
         |${parent
          .map(path =>
            s"""AND "${extensionSchema}".text2ltree($$2) OPERATOR("${extensionSchema}".@>) ctx_path"""
          )
          .getOrElse("")}
         |""".stripMargin,
      params = parent.fold(List(project))(p => List(project, p.toDBPath))
    ) { r => r.optContext }
  }

  def readContext(
      tenant: String,
      path: FeatureContextPath
  ): Future[Option[Context]] = {
    require(Tenant.isTenantValid(tenant))
    postgresql.queryOne(
      s"""
         |SELECT global, name, project, "${extensionSchema}".ltree2text(parent) as parent, protected
         |FROM "${tenant}".new_contexts
         |WHERE "${extensionSchema}".ltree_eq(ctx_path, "${extensionSchema}".text2ltree($$1))
         |""".stripMargin,
      List(path.toDBPath)
    ) { r => r.optContext }
  }

  def getFeatureContext(
      tenant: String,
      project: String,
      path: FeatureContextPath
  ): Future[Option[Context]] = {
    require(Tenant.isTenantValid(tenant))
    postgresql.queryOne(
      s"""
         |SELECT global, name, project, "${extensionSchema}".ltree2text(parent) as parent, protected
         |FROM "${tenant}".new_contexts
         |WHERE "${extensionSchema}".ltree_eq(ctx_path, "${extensionSchema}".text2ltree($$1))
         |AND (project=$$2 OR global=true)
         |""".stripMargin,
      List(path.toDBPath, project)
    ) { r => r.optContext }
  }

  def getLocalFeatureContext(
      tenant: String,
      project: String,
      path: FeatureContextPath
  ): Future[Option[LocalContext]] = {
    getFeatureContext(tenant, project, path).map {
      case Some(l: LocalContext) => Some(l)
      case _                     => None
    }
  }

  def getGlobalFeatureContext(
      tenant: String,
      parents: FeatureContextPath
  ): Future[Option[GlobalContext]] = {
    require(Tenant.isTenantValid(tenant))
    postgresql.queryOne(
      s"""
         |SELECT name, project, "${extensionSchema}".ltree2text(parent) as parent, protected
         |FROM ${tenant}.new_contexts
         |WHERE "${extensionSchema}".ltree_eq(ctx_path, "${extensionSchema}".text2ltree($$1))
         |AND global=true
         |""".stripMargin,
      List(parents.toDBPath)
    ) { r => r.optGlobalContext }
  }

  def updateGlobalFeatureContext(
      tenant: String,
      context: FeatureContextPath,
      isProtected: Boolean
  ): Future[Either[IzanamiError, Unit]] = {
    require(Tenant.isTenantValid(tenant))
    postgresql
      .queryOne(
        s"""
         |UPDATE "${tenant}".new_contexts
         |SET protected=$$1
         |WHERE "${extensionSchema}".ltree_eq(ctx_path, "${extensionSchema}".text2ltree($$2))
         |${
            if (isProtected)
              s"""OR "${extensionSchema}".text2ltree($$2) OPERATOR(${extensionSchema}.@>) ctx_path"""
            else ""
          }
         |RETURNING *
         |""".stripMargin,
        List(isProtected.toJava, context.toDBPath)
      ) { _ => Some(()) }
      .map {
        case Some(_) => Right(())
        case None    => Left(FeatureContextDoesNotExist(context.toUserPath))
      }
  }

  def updateLocalFeatureContext(
      tenant: String,
      project: String,
      isProtected: Boolean,
      path: FeatureContextPath
  ): Future[Either[IzanamiError, Unit]] = {
    require(Tenant.isTenantValid(tenant))
    postgresql
      .queryOne(
        s"""
           |UPDATE "${tenant}".new_contexts
           |SET protected=$$1
           |WHERE "${extensionSchema}".ltree_eq(ctx_path, "${extensionSchema}".text2ltree($$2))
           |AND project=$$3
           |${
            if (isProtected)
              s"""OR ${extensionSchema}.text2ltree($$2) OPERATOR("${extensionSchema}".@>) ctx_path"""
            else ""
          }
           |RETURNING *
           |""".stripMargin,
        List(isProtected.toJava, path.toDBPath, project)
      ) { _ => Some(()) }
      .map(o => o.toRight(FeatureContextDoesNotExist(path.toUserPath)))
  }

  def findChildrenForGlobalContext(
      tenant: String,
      path: FeatureContextPath
  ): Future[Seq[Context]] = {
    require(Tenant.isTenantValid(tenant))
    postgresql.queryAll(
      s"""
         |SELECT name, "${extensionSchema}".ltree2text(parent) as parent, project, global, protected
         |FROM "${tenant}".new_contexts
         |WHERE "${extensionSchema}".text2ltree($$1) OPERATOR("${extensionSchema}".@>) ctx_path
         |""".stripMargin,
      List(path.toDBPath)
    ) { r => r.optContext }
  }

  def findChildrenForLocalContext(
      tenant: String,
      project: String,
      path: FeatureContextPath
  ): Future[Seq[LocalContext]] = {
    require(Tenant.isTenantValid(tenant))
    postgresql.queryAll(
      s"""
         |SELECT name, "${extensionSchema}".ltree2text(parent) as parent, project, global, protected
         |FROM "${tenant}".new_contexts
         |WHERE "${extensionSchema}".text2ltree($$1) OPERATOR("${extensionSchema}".@>) ctx_path
         |AND project = $$2
         |""".stripMargin,
      List(path.toDBPath, project)
    ) { r => r.optLocalContext }
  }

  def deleteGlobalFeatureContext(
      tenant: String,
      path: FeatureContextPath
  ): Future[Either[IzanamiError, Unit]] = {
    require(Tenant.isTenantValid(tenant))
    postgresql
      .queryOne(
        s"""
           |DELETE FROM "${tenant}".new_contexts
           |WHERE "${extensionSchema}".ltree_eq(ctx_path, "${extensionSchema}".text2ltree($$1))
           |RETURNING *
           |""".stripMargin,
        List(path.toDBPath)
      ) { r => Some(()) }
      .map(_.toRight(FeatureContextDoesNotExist(path.toUserPath)))
  }

  def createContext(
      tenant: String,
      parents: FeatureContextPath,
      featureContext: FeatureContextCreationRequest
  ): Future[Either[IzanamiError, Context]] = {
    require(Tenant.isTenantValid(tenant))
    val maybeProject = featureContext match {
      case ctx: LocalFeatureContextCreationRequest => Some(ctx.project)
      case _                                       => None
    }
    val parent = parents.toDBPath match {
      case "" => null
      case s  => s
    }
    postgresql
      .queryOne(
        s"""
           |INSERT INTO "${tenant}".new_contexts (parent, global, name, project, protected)
           |VALUES (
           |  "${extensionSchema}".text2ltree($$1),
           |  $$2,
           |  $$3,
           |  $$4,
           |  $$5
           |)
           |RETURNING "${extensionSchema}".ltree2text(parent) as parent, global, name, project, protected
           |""".stripMargin,
        List(
          parent,
          featureContext.global.toJava,
          featureContext.name,
          maybeProject.orNull,
          featureContext.isProtected.toJava
        )
      ) { r =>
        r.optContext
      }
      .map(o => o.toRight(InternalServerError()))
      .recover(postgresql.pgErrorPartialFunction.andThen(err => Left(err)))
  }

  def readStrategyForContext(
      tenant: String,
      featureContext: FeatureContextPath,
      feature: AbstractFeature
  ): Future[Option[CompleteContextualStrategy]] = {
    require(Tenant.isTenantValid(tenant))
    postgresql.queryOne(
      s"""
         |SELECT gf.conditions, gf.enabled, gf.value, gf.result_type, ws.config
         |FROM "${tenant}".feature_contexts_strategies gf
         |LEFT OUTER JOIN "${tenant}".wasm_script_configurations ws ON gf.script_config=ws.id
         |WHERE gf.project = $$1
         |AND gf.feature = $$2
         |AND gf.context OPERATOR("${extensionSchema}".@>) "${extensionSchema}".text2ltree($$3)
         |ORDER BY "${extensionSchema}".nlevel(gf.context) desc
         |limit 1
         |""".stripMargin,
      List(feature.project, feature.name, featureContext.toDBPath)
    ) { row =>
      row
        .optCompleteStrategy(feature.name)
        .map(cs => cs.asInstanceOf[CompleteContextualStrategy])
    }
  }

  def readGlobalFeatureContexts(
      tenant: String
  ): Future[Seq[ContextWithOverloads]] = {
    require(Tenant.isTenantValid(tenant))
    postgresql.queryAll(
      // TODO add last call
      s"""
         |SELECT c.name, "${extensionSchema}".ltree2text(c.parent) as parent, c.protected, c.global, COALESCE(
         |  json_agg(json_build_object('feature', s.feature, 'enabled', s.enabled, 'conditions', s.conditions, 'id', f.id, 'project', f.project, 'description', f.description , 'wasm', s.script_config)) FILTER (WHERE s.feature IS NOT NULL) , '[]'
         |) as overloads
         |FROM "${tenant}".new_contexts c
         |LEFT JOIN "${tenant}".feature_contexts_strategies s ON "${extensionSchema}".ltree_eq(s.context, c.ctx_path)
         |LEFT JOIN "${tenant}".features f ON f.name=s.feature
         |WHERE c.global=true
         |GROUP BY (c.name, c.parent, c.protected, c.global)
         |""".stripMargin,
      List()
    ) { row => row.optContextWithOverloadHierarchy }
  }

  def readAllLocalFeatureContexts(tenant: String): Future[Seq[Context]] = {
    require(Tenant.isTenantValid(tenant))
    postgresql.queryAll(
      s"""
         |SELECT name, "${extensionSchema}".ltree2text(parent) as parent, protected, global, project
         |FROM "${tenant}".new_contexts fc
         |""".stripMargin
    ) { r => r.optContext }
  }

  def readFeatureContexts(
      tenant: String,
      project: String
  ): Future[Seq[ContextWithOverloads]] = {
    Tenant.isTenantValid(tenant)
    postgresql.queryAll(
      s"""
         |SELECT c.name, "${extensionSchema}".ltree2text(parent) as parent, c.project, c.protected, c.global, COALESCE(
         |  json_agg(json_build_object('feature', s.feature, 'enabled', s.enabled, 'conditions', s.conditions, 'id', f.id, 'description', f.description, 'project', f.project, 'wasm', s.script_config, 'value', s.value, 'resultType', s.result_type)) FILTER (WHERE s.feature IS NOT NULL) , '[]'
         |) as overloads
         |FROM "${tenant}".new_contexts c
         |LEFT JOIN "${tenant}".feature_contexts_strategies s ON "${extensionSchema}".ltree_eq(s.context, c.ctx_path)
         |LEFT JOIN "${tenant}".features f ON f.name=s.feature
         |WHERE c.project=$$1 or c.global=true
         |GROUP BY (c.name, parent, c.protected, c.project, c.global)
         |""".stripMargin,
      List(project)
    ) { row => row.optContextWithOverloadHierarchy }
  }

  def deleteContext(
      tenant: String,
      project: String,
      path: FeatureContextPath
  ): Future[Either[IzanamiError, Unit]] = {
    Tenant.isTenantValid(tenant)
    postgresql.executeInTransaction(conn => {
      postgresql
        .queryOne(
          s"""
             |DELETE FROM "${tenant}".new_contexts
             |WHERE "${extensionSchema}".ltree_eq(ctx_path, "${extensionSchema}".text2ltree($$1))
             |RETURNING *
             |""".stripMargin,
          List(path.toDBPath),
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
    Tenant.isTenantValid(tenant)
    postgresql.queryRaw(
      s"""DELETE FROM "${tenant}".feature_contexts_strategies WHERE project=$$1 AND feature=$$2""",
      List(project, feature),
      conn = Some(conn)
    ) { _ => () }
  }

  def deleteFeatureStrategy(
      tenant: String,
      project: String,
      path: FeatureContextPath,
      feature: String,
      user: UserInformation,
      conn: Option[SqlConnection] = None
  ): Future[Either[IzanamiError, Unit]] = {
    Tenant.isTenantValid(tenant)
    env.postgresql.executeInOptionalTransaction(
      conn,
      conn => {
        env.datastores.features
          .findActivationStrategiesForFeatureByName(tenant, feature, project)
          .map(o => o.toRight(InternalServerError()))
          .flatMap {
            case Left(error)                 => Left(error).future
            case Right(featureWithOverloads) => {

              postgresql
                .queryOne(
                  s"""
                   |DELETE FROM "${tenant}".feature_contexts_strategies
                   |WHERE project=$$1
                   |AND feature=$$2
                   |AND "${extensionSchema}".ltree_eq(context, "${extensionSchema}".text2ltree($$3))
                   |RETURNING (SELECT f.id FROM "${tenant}".features f WHERE f.project=$$1 AND name=$$2)
                   |""".stripMargin,
                  List(
                    project,
                    feature,
                    path.toDBPath
                  ),
                  conn = Some(conn)
                ) { r => r.optString("id") }
                .map(
                  _.toRight(
                    FeatureOverloadDoesNotExist(
                      project,
                      path.toUserPath,
                      feature
                    )
                  )
                )
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
                          feature = featureWithOverloads.removeOverload(path),
                          origin = NormalOrigin,
                          authentication = user.authentication
                        )
                      )(conn)
                      .map(_ => Right(()))
                  }
                }
            }
          }
      }
    )
  }

  def updateFeatureStrategy(
      tenant: String,
      project: String,
      path: FeatureContextPath,
      feature: String,
      strategy: CompleteContextualStrategy,
      user: UserInformation,
      conn: Option[SqlConnection] = None
  ): Future[Either[IzanamiError, Unit]] = {
    Tenant.isTenantValid(tenant)
    env.datastores.features
      .findActivationStrategiesForFeatureByName(tenant, feature, project)
      .map(o => o.toRight(FeatureDoesNotExist(feature)))
      .flatMap {
        case Left(value)       => Left(value).future
        case Right(oldFeature) => {
          postgresql.executeInOptionalTransaction(
            conn,
            implicit conn => {
              (strategy match {
                case ClassicalFeatureStrategy(enabled, _, resultDescriptor) =>
                  postgresql
                    .queryRaw(
                      s"""
                         |INSERT INTO "${tenant}".feature_contexts_strategies (project, context, conditions, enabled, feature, result_type ${
                          if (resultDescriptor.resultType != BooleanResult)
                            ",value"
                          else ""
                        })
                         |VALUES($$1,"${extensionSchema}".text2ltree($$2),$$3,$$4,$$5, $$6 ${
                          if (resultDescriptor.resultType != BooleanResult)
                            s",$$7"
                          else ""
                        })
                         |ON CONFLICT(project, context, feature) DO UPDATE
                         |SET conditions=EXCLUDED.conditions, enabled=EXCLUDED.enabled, script_config=NULL, value=EXCLUDED.value, result_type=EXCLUDED.result_type
                         |""".stripMargin,
                      resultDescriptor match {
                        case descriptor: ValuedResultDescriptor =>
                          List(
                            project,
                            path.toDBPath,
                            new JsonArray(
                              Json
                                .toJson(resultDescriptor.conditions)
                                .toString()
                            ),
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
                                .toJson(conditions)(
                                  Writes.seq(
                                    ActivationCondition.booleanConditionWrites
                                  )
                                )
                                .toString()
                            ),
                            java.lang.Boolean.valueOf(enabled),
                            feature,
                            resultDescriptor.resultType.toDatabaseName
                          )
                      },
                      conn = Some(conn)
                    ) { _ => Right(oldFeature.id) }
                    .recover {
                      case f: PgException
                          if f.getSqlState == RELATION_DOES_NOT_EXISTS =>
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
                      case f: PgException
                          if f.getSqlState == FOREIGN_KEY_VIOLATION =>
                        Left(FeatureContextDoesNotExist(path.toUserPath))
                      case _ => Left(InternalServerError())
                    }
                case f @ (_: CompleteWasmFeatureStrategy) => {
                  (f match {
                    case f: CompleteWasmFeatureStrategy
                        if f.wasmConfig.source.kind != WasmSourceKind.Local =>
                      env.datastores.features.createWasmScriptIfNeeded(
                        tenant,
                        f.wasmConfig,
                        Some(conn)
                      )
                    case f: CompleteWasmFeatureStrategy =>
                      Right(f.wasmConfig.name).future
                  }).flatMap {
                    case Left(err) => Left(err).future
                    case Right(id) => {
                      postgresql
                        .queryRaw(
                          s"""
                             |INSERT INTO "${tenant}".feature_contexts_strategies (project, context, script_config, enabled, feature, result_type)
                             |VALUES($$1,"${extensionSchema}".text2ltree($$2),$$3,$$4,$$5, $$6)
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
                          .updateConditionsForContext(
                            path,
                            strategy.toLightWeightContextualStrategy
                          ),
                        origin = NormalOrigin,
                        authentication = user.authentication
                      )
                    )
                    .map(_ => Right(()))
                case Left(err) => Left(err).future
              }
            }
          )
        }
      }
  }

  /** Find parents contexts of given path (including it), sorted from "higher"
    * ancestor to "lower"
    * @param tenant
    *   schema to use
    * @param path
    *   path to search parent for
    * @return
    *   list of ordered parents, from higher to lower
    */
  def findParents(tenant: String, path: String): Future[List[Context]] = {
    Tenant.isTenantValid(tenant)
    postgresql.queryAll(
      s"""
         |SELECT
         |  ${extensionSchema}.ltree2text(parent) as parent,
         |  name,
         |  global,
         |  protected,
         |  project
         |FROM "${tenant}".new_contexts
         |WHERE ctx_path OPERATOR("${extensionSchema}".@>) "${extensionSchema}".text2ltree($$1)
         |ORDER BY "${extensionSchema}".nlevel(ctx_path) ASC
         |""".stripMargin,
      List(path)
    ) { r => r.optContext }
  }

  def findLocalContexts(
      tenant: String,
      idCandidates: Set[String]
  ): Future[List[String]] = {
    Tenant.isTenantValid(tenant)
    postgresql.queryAll( // TODO review this use
      s"""
         |SELECT id
         |FROM "${tenant}".new_contexts
         |WHERE id=ANY($$1)
         |""".stripMargin,
      List(idCandidates.toArray)
    ) { r => r.optString("id") }
  }
}

object FeatureContextDatastore {
  implicit class FeatureContextRow(val row: Row) extends AnyVal {
    def optContextWithOverloadHierarchy: Option[ContextWithOverloads] = {
      row.optContext.map(ctx =>
        ContextWithOverloads(ctx, overloads = row.optOverloads("overloads"))
      )
    }

    def optContext: Option[Context] = {
      row
        .optBoolean("global")
        .flatMap(global => {
          if (global) {
            optGlobalContext
          } else {
            optLocalContext
          }
        })
    }

    def optLocalContext: Option[LocalContext] = {
      for (
        ctx <- optGlobalContext;
        project <- row.optString("project")
      )
        yield LocalContext(
          name = ctx.name,
          path = ctx.path,
          isProtected = ctx.isProtected,
          project = project
        )
    }

    def optGlobalContext: Option[GlobalContext] = {
      for (
        name <- row.optString("name");
        parent = row
          .optString("parent")
          .map(ltree => FeatureContextPath.fromDBString(ltree))
          .getOrElse(FeatureContextPath());
        isProtected <- row.optBoolean("protected")
      )
        yield GlobalContext(
          name = name,
          path = parent,
          isProtected = isProtected
        )
    }

    def optOverloads(field: String): Seq[AbstractFeature] = {
      row
        .optJsArray(field)
        .getOrElse(JsArray.empty)
        .value
        .flatMap(jsObject => {
          val maybeSeq: Option[Seq[LightWeightFeature]] =
            for (
              name <- (jsObject \ "feature").asOpt[String];
              enabled <- (jsObject \ "enabled").asOpt[Boolean];
              enabled <- (jsObject \ "enabled").asOpt[Boolean];
              id <- (jsObject \ "id").asOpt[String];
              description <- (jsObject \ "description").asOpt[String];
              project <- (jsObject \ "project").asOpt[String];
              resultType <- (jsObject \ "resultType")
                .asOpt[ResultType](ResultType.resultTypeReads)
            )
              yield {
                val maybeConditions = (jsObject \ "conditions")
                  .asOpt[JsArray]
                val maybeScriptConfig = (jsObject \ "wasm").asOpt[String]
                (maybeScriptConfig, maybeConditions) match {
                  case (Some(wasmConfig), _) =>
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
                          .asOpt[ValuedResultDescriptor](
                            ValuedResultDescriptor.valuedDescriptorReads
                          )
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
                      case BooleanResult => {
                        val conds = conditions.value.flatMap(c =>
                          c.asOpt[BooleanActivationCondition](
                            ActivationCondition.booleanActivationConditionRead
                          ).toSeq
                        )
                        Some(
                          Feature(
                            name = name,
                            enabled = enabled,
                            id = id,
                            project = project,
                            description = description,
                            resultDescriptor =
                              BooleanResultDescriptor(conds.toSeq)
                          )
                        )
                      }
                    }
                  }
                  case _ => None
                }
              }.toSeq
          maybeSeq
        })
        .flatten
        .toSeq
    }

    def optCompleteStrategy(
        feature: String
    ): Option[CompleteContextualStrategy] = {
      (for (
        enabled <- row.optBoolean("enabled");
        resultTypeStr <- row.optString("result_type");
        resultType <- ResultType.parseResultType(resultTypeStr)
      ) yield {
        val maybeConditions = row
          .optJsArray("conditions")
          .getOrElse(JsArray.empty)
        val maybeConfig =
          row.optJsObject("config").flatMap(obj => obj.asOpt(WasmConfig.format))
        (maybeConfig, maybeConditions) match {
          case (Some(config), _) =>
            Some(
              CompleteWasmFeatureStrategy(
                enabled,
                config,
                feature,
                resultType = resultType
              )
            )
          case (_, conditions) => {
            resultType match {
              case resultType: ValuedResultType => {
                resultType match {
                  case StringResult => {
                    val conds = conditions.value.flatMap(json =>
                      json
                        .asOpt[StringActivationCondition](
                          ActivationCondition.stringActivationConditionRead
                        )
                        .toSeq
                    )
                    row
                      .optString("value")
                      .map(value => {
                        ClassicalFeatureStrategy(
                          enabled,
                          feature,
                          StringResultDescriptor(
                            value = value,
                            conditions = conds.toSeq
                          )
                        )
                      })
                  }
                  case NumberResult => {
                    val conds = conditions.value.flatMap(json =>
                      json
                        .asOpt[NumberActivationCondition](
                          ActivationCondition.numberActivationConditionRead
                        )
                        .toSeq
                    )
                    row
                      .optString("value")
                      .map(str => BigDecimal(str))
                      .map(value => {
                        ClassicalFeatureStrategy(
                          enabled,
                          feature = feature,
                          resultDescriptor = NumberResultDescriptor(
                            value = value,
                            conditions = conds.toSeq
                          )
                        )
                      })
                  }
                }
              }
              case BooleanResult => {
                val conds = conditions.value.flatMap(json =>
                  json
                    .asOpt[BooleanActivationCondition](
                      ActivationCondition.booleanActivationConditionRead
                    )
                    .toSeq
                )
                Some(
                  ClassicalFeatureStrategy(
                    enabled,
                    feature,
                    BooleanResultDescriptor(conds.toSeq)
                  )
                )
              }
            }
          }
        }
      }).flatten
    }
  }

}
