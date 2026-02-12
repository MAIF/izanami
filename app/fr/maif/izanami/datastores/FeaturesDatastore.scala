package fr.maif.izanami.datastores

import fr.maif.izanami.datastores.featureImplicits.FeatureRow
import fr.maif.izanami.env.Env
import fr.maif.izanami.env.PostgresqlErrors.{
  FOREIGN_KEY_VIOLATION,
  NOT_NULL_VIOLATION,
  RELATION_DOES_NOT_EXISTS
}
import fr.maif.izanami.env.pgimplicits.EnhancedRow
import fr.maif.izanami.errors.*
import fr.maif.izanami.events.EventAuthentication.BackOfficeAuthentication
import fr.maif.izanami.events.EventOrigin.{ImportOrigin, NormalOrigin}
import fr.maif.izanami.events.{
  SourceFeatureCreated,
  SourceFeatureDeleted,
  SourceFeatureUpdated
}
import fr.maif.izanami.models.*
import fr.maif.izanami.models.features.*
import fr.maif.izanami.utils.{Datastore, FutureEither}
import fr.maif.izanami.utils.syntax.implicits.{
  BetterFuture,
  BetterFutureEither,
  BetterJsValue,
  BetterListEither,
  BetterSyntax
}
import fr.maif.izanami.wasm.{
  WasmConfig,
  WasmConfigWithFeatures,
  WasmScriptAssociatedFeatures
}
import fr.maif.izanami.web.ImportController.{
  Fail,
  ImportConflictStrategy,
  MergeOverwrite,
  Skip
}
import fr.maif.izanami.web.{
  FeatureContextPath,
  ImportController,
  UserInformation
}
import io.otoroshi.wasm4s.scaladsl.WasmSourceKind
import io.vertx.core.json.{JsonArray, JsonObject}
import io.vertx.core.shareddata.ClusterSerializable
import io.vertx.pgclient.PgException
import io.vertx.sqlclient.{Row, SqlConnection}
import play.api.libs.json.*

import java.util.UUID
import scala.collection.mutable.ArrayBuffer
import scala.concurrent.Future
import scala.reflect.ClassTag

class FeaturesDatastore(val env: Env) extends Datastore {
  val extensionSchema = env.extensionsSchema

  def findActivationStrategiesForFeatureByName(
      tenant: String,
      name: String,
      project: String
  ): Future[Option[FeatureWithOverloads]] = {
    Tenant.isTenantValid(tenant)
    env.postgresql
      .queryOne(
        s"""SELECT f.id FROM "${tenant}".features f where project=$$1 AND name=$$2""",
        List(project, name)
      ) { r =>
        r.optString("id")
      }
      .flatMap {
        case None     => None.future
        case Some(id) => findActivationStrategiesForFeature(tenant, id)
      }
  }

  def findActivationStrategiesForFeature(
      tenant: String,
      id: String
  ): Future[Option[FeatureWithOverloads]] = {
    Tenant.isTenantValid(tenant)
    env.postgresql.queryRaw(
      s"""SELECT
         |    f.id,
         |    f.name,
         |    f.enabled,
         |    f.project,
         |    f.conditions,
         |    f.description,
         |    f.metadata,
         |    f.script_config as config,
         |    f.value,
         |    f.result_type,
         |    COALESCE(json_agg(t.id) FILTER(WHERE t.id IS NOT NULL), '[]'::json) as tags,
         |    COALESCE(
         |        json_object_agg(
         |            fcs.context, json_build_object(
         |                'enabled', fcs.enabled,
         |                'conditions', fcs.conditions,
         |                'config', fcs.script_config,
         |                'value', fcs.value,
         |                'resultType', fcs.result_type
         |            )
         |        ) FILTER(WHERE fcs.enabled IS NOT NULL), '{}'::json
         |    )
         |    AS overloads
         |FROM "${tenant}".features f
         |LEFT JOIN "${tenant}".feature_contexts_strategies fcs ON fcs.feature = f.name
         |LEFT JOIN "${tenant}".features_tags ft ON ft.feature = f.id
         |LEFT JOIN "${tenant}".tags t ON ft.tag = t.name
         |WHERE f.id=$$1
         |GROUP BY f.id""".stripMargin,
      params = List(id)
    ) { rs =>
      {
        if (rs.isEmpty) {
          None
        } else {
          rs.head
            .optFeature()
            .map(feature => {
              val overloadByContext
                  : Map[FeatureContextPath, LightWeightFeature] = rs
                .flatMap(r => {
                  r
                    .optJsObject("overloads")
                    .map(overloads => {
                      overloads.keys.map(context => {
                        (overloads \ context)
                          .asOpt[JsObject]
                          .flatMap(json => {
                            (json \ "enabled")
                              .asOpt[Boolean]
                              .map(enabled => {
                                val maybeConditionsJson =
                                  (json \ "conditions").asOpt[JsArray]
                                val maybeScriptName =
                                  (json \ "config").asOpt[String]
                                val r
                                    : (FeatureContextPath, LightWeightFeature) =
                                  (
                                    FeatureContextPath.fromDBString(context),
                                    maybeConditionsJson
                                      .flatMap(conditions => {
                                        val maybeResultDescriptor =
                                          feature.resultType match {
                                            case BooleanResult => {
                                              val conds = conditions.asOpt[Seq[
                                                BooleanActivationCondition
                                              ]](
                                                Reads.seq(
                                                  ActivationCondition.booleanActivationConditionRead
                                                )
                                              )
                                              conds.map(cs =>
                                                BooleanResultDescriptor(cs)
                                              )
                                            }
                                            case StringResult => {
                                              for (
                                                conds <- conditions.asOpt[Seq[
                                                  StringActivationCondition
                                                ]](
                                                  Reads.seq(
                                                    ActivationCondition.stringActivationConditionRead
                                                  )
                                                );
                                                value <- (json \ "value")
                                                  .asOpt[String]
                                              ) yield {
                                                StringResultDescriptor(
                                                  conditions = conds,
                                                  value = value
                                                )
                                              }
                                            }
                                            case NumberResult => {
                                              for (
                                                conds <- conditions.asOpt[Seq[
                                                  NumberActivationCondition
                                                ]](
                                                  Reads.seq(
                                                    ActivationCondition.numberActivationConditionRead
                                                  )
                                                );
                                                value <- (json \ "value")
                                                  .asOpt[String]
                                                  .map(str => BigDecimal(str))
                                              ) yield {
                                                NumberResultDescriptor(
                                                  conditions = conds,
                                                  value = value
                                                )
                                              }
                                            }
                                          }
                                        maybeResultDescriptor.map(rs => {
                                          Feature(
                                            id = feature.id,
                                            name = feature.name,
                                            project = feature.project,
                                            enabled = enabled,
                                            tags = feature.tags,
                                            metadata = feature.metadata,
                                            description = feature.description,
                                            resultDescriptor = rs
                                          )
                                        })

                                      })
                                      .orElse(
                                        maybeScriptName.map(scriptConfig =>
                                          LightWeightWasmFeature(
                                            id = feature.id,
                                            name = feature.name,
                                            project = feature.project,
                                            wasmConfigName = scriptConfig,
                                            enabled = enabled,
                                            tags = feature.tags,
                                            metadata = feature.metadata,
                                            description = feature.description,
                                            resultType = feature.resultType
                                          )
                                        )
                                      )
                                      .getOrElse(
                                        throw new RuntimeException(
                                          "Bad feature format in DB"
                                        )
                                      )
                                  )
                                r
                              })
                          })
                      })
                    })
                    .getOrElse(Set())
                })
                .flatMap(_.toSeq)
                .toMap
              FeatureWithOverloads(
                baseFeature = feature,
                overloads = overloadByContext
              )
            })
        }
      }
    }
  }

  // TODO deduplicate
  def findActivationStrategiesForFeatures(
      tenant: String,
      ids: Set[String],
      conn: Option[SqlConnection] = None
  ): Future[Map[String, FeatureWithOverloads]] = {
    Tenant.isTenantValid(tenant)
    env.postgresql
      .queryAll(
        s"""SELECT
         |    f.id,
         |    f.name,
         |    f.enabled,
         |    f.project,
         |    f.conditions,
         |    f.description,
         |    f.metadata,
         |    f.script_config as config,
         |    f.value,
         |    f.result_type,
         |    COALESCE(json_agg(t.id) FILTER(WHERE t.id IS NOT NULL), '[]'::json) as tags,
         |    COALESCE(
         |        json_object_agg(
         |            fcs.context, json_build_object(
         |                'enabled', fcs.enabled,
         |                'conditions', fcs.conditions
         |            )
         |        ) FILTER(WHERE fcs.enabled IS NOT NULL), '{}'::json
         |    )
         |    AS overloads
         |FROM "${tenant}".features f
         |LEFT JOIN "${tenant}".feature_contexts_strategies fcs ON fcs.feature = f.name
         |LEFT JOIN "${tenant}".features_tags ft ON ft.feature = f.id
         |LEFT JOIN "${tenant}".tags t ON ft.tag = t.name
         |WHERE f.id=ANY($$1)
         |GROUP BY f.id""".stripMargin,
        params = List(ids.toArray),
        conn = conn
      ) { r =>
        {
          val maybeTuple = r
            .optFeature()
            .map(feature => {
              val overloadByContext
                  : Map[FeatureContextPath, LightWeightFeature] = r
                .optJsObject("overloads")
                .map(overloads => {
                  overloads.keys.map(context => {
                    (overloads \ context)
                      .asOpt[JsObject]
                      .flatMap(json => {
                        for (enabled <- (json \ "enabled").asOpt[Boolean])
                          yield {
                            val maybeConditionsJson =
                              (json \ "conditions").asOpt[JsArray]
                            val maybeScriptName =
                              (json \ "config").asOpt[String]
                            val r: (FeatureContextPath, LightWeightFeature) = (
                              FeatureContextPath.fromDBString(context),
                              maybeConditionsJson
                                .flatMap(conditions => {
                                  val maybeResultDescriptor =
                                    feature.resultType match {
                                      case BooleanResult => {
                                        val conds = conditions.asOpt[Seq[
                                          BooleanActivationCondition
                                        ]](
                                          Reads.seq(
                                            ActivationCondition.booleanActivationConditionRead
                                          )
                                        )
                                        conds.map(cs =>
                                          BooleanResultDescriptor(cs)
                                        )
                                      }
                                      case StringResult => {
                                        for (
                                          conds <- conditions.asOpt[Seq[
                                            StringActivationCondition
                                          ]](
                                            Reads.seq(
                                              ActivationCondition.stringActivationConditionRead
                                            )
                                          );
                                          value <- (json \ "value")
                                            .asOpt[String]
                                        ) yield {
                                          StringResultDescriptor(
                                            conditions = conds,
                                            value = value
                                          )
                                        }
                                      }
                                      case NumberResult => {
                                        for (
                                          conds <- conditions.asOpt[Seq[
                                            NumberActivationCondition
                                          ]](
                                            Reads.seq(
                                              ActivationCondition.numberActivationConditionRead
                                            )
                                          );
                                          value <- (json \ "value")
                                            .asOpt[String]
                                            .map(str => BigDecimal(str))
                                        ) yield {
                                          NumberResultDescriptor(
                                            conditions = conds,
                                            value = value
                                          )
                                        }
                                      }
                                    }
                                  maybeResultDescriptor.map(rs => {
                                    Feature(
                                      id = feature.id,
                                      name = feature.name,
                                      project = feature.project,
                                      enabled = enabled,
                                      tags = feature.tags,
                                      metadata = feature.metadata,
                                      description = feature.description,
                                      resultDescriptor = rs
                                    )
                                  })

                                })
                                .orElse(
                                  maybeScriptName.map(scriptName =>
                                    LightWeightWasmFeature(
                                      id = feature.id,
                                      name = feature.name,
                                      project = feature.project,
                                      wasmConfigName = scriptName,
                                      enabled = enabled,
                                      tags = feature.tags,
                                      metadata = feature.metadata,
                                      description = feature.description,
                                      resultType = feature.resultType
                                    )
                                  )
                                )
                                .getOrElse(
                                  throw new RuntimeException(
                                    "Bad feature format in DB"
                                  )
                                )
                            )
                            r
                          }
                      })
                  })
                })
                .getOrElse(Set())
                .flatMap(_.toSeq)
                .toMap
              (
                feature.id,
                FeatureWithOverloads(
                  baseFeature = feature,
                  overloads = overloadByContext
                )
              )
            })
          maybeTuple
        }
      }
      .map(l => l.toMap)
  }

  def findFeatureMatching(
      tenant: String,
      pattern: String,
      clientId: String,
      count: Int,
      page: Int
  ): Future[(Int, Seq[CompleteFeature])] = {
    Tenant.isTenantValid(tenant)
    val countQuery = env.postgresql.queryOne(
      s"""
         |select count(f.id) as count
         |from "${tenant}".features f
         |left join "${tenant}".apikeys a on a.clientid=$$1
         |left join "${tenant}".apikeys_projects ap on (ap.project=f.project and ap.apikey=a.name)
         |where f.id LIKE $$2
         |and (f.conditions is null or jsonb_typeof(f.conditions) = 'object')
         |and (ap.project is not null or a.admin=true)
         |""".stripMargin,
      List(clientId, pattern.replaceAll("\\*", "%"))
    ) { r => r.optInt("count") }

    val dataQuery = env.postgresql.queryAll(
      s"""select f.*, s.config AS wasm, COALESCE(json_agg(ft.tag) FILTER (WHERE ft.tag IS NOT NULL), '[]') AS tags
         |from "${tenant}".features f
         |left join "${tenant}".features_tags ft
         |on ft.feature = f.id
         |left join "${tenant}".wasm_script_configurations s
         |on s.id = f.script_config
         |left join "${tenant}".apikeys a on a.clientid=$$1
         |left join "${tenant}".apikeys_projects ap on (ap.project=f.project and ap.apikey=a.name)
         |where f.id LIKE $$2
         |and (f.conditions is null or jsonb_typeof(f.conditions) = 'object')
         |and (ap.project is not null or a.admin=true)
         |group by f.id, wasm
         |order by f.id
         |limit $$3
         |offset $$4""".stripMargin,
      List(
        clientId,
        pattern.replaceAll("\\*", "%"),
        Integer.valueOf(count),
        Integer.valueOf((page - 1) * count)
      )
    ) { r => r.optCompleteFeature() }

    for (
      count <- countQuery;
      features <- dataQuery
    ) yield {
      (count.getOrElse(features.size), features)
    }
  }

  def findByIdForKey(
      tenant: String,
      id: String,
      context: FeatureContextPath,
      clientId: String,
      clientSecret: String
  ): Future[Option[CompleteFeature]] = {
    require(Tenant.isTenantValid(tenant))
    val needContexts = context.elements.nonEmpty
    val params =
      if (needContexts) List(clientId, id, context.toDBPath)
      else List(clientId, id)

    env.postgresql
      .queryAll(
        s"""
         |SELECT
         |  k.clientsecret,
         |  ${if (needContexts) s"fcs.context," else "null as context,"}
         |  json_build_object(
         |    'name', f.name,
         |    'project', f.project,
         |    'description', f.description,
         |    'id', f.id)::jsonb ||
         |    ${if (needContexts) s"""(CASE
         |    WHEN fcs.enabled IS NOT NULL THEN
         |    json_build_object(
         |      'enabled', fcs.enabled,
         |      'resultType', fcs.result_type,
         |      'value', fcs.value,
         |      'config', ow.config,
         |      'conditions', fcs.conditions
         |    )::jsonb
         |    ELSE""" else ""}
         |    json_build_object(
         |      'enabled', f.enabled,
         |      'resultType', f.result_type,
         |      'value', f.value,
         |      'config', w.config,
         |      'conditions', f.conditions
         |    )::jsonb
         |    ${if (needContexts) s"END)" else ""} as feature
         |FROM "${tenant}".features f
         |${
            if (needContexts)
              s"""LEFT JOIN "${tenant}".feature_contexts_strategies fcs ON fcs.feature=f.name AND fcs.context OPERATOR("${extensionSchema}".@>)  text2ltree($$3) LEFT JOIN wasm_script_configurations ow ON fcs.script_config=ow.id"""
            else ""
          }
         |INNER JOIN "${tenant}".apikeys k ON (k.clientid=$$1 AND k.enabled=true)
         |LEFT JOIN "${tenant}".wasm_script_configurations w ON w.id=f.script_config
         |LEFT JOIN "${tenant}".apikeys_projects kp ON (kp.apikey=k.name AND kp.project=f.project)
         |WHERE f.id=$$2
         |AND (kp.apikey IS NOT NULL OR k.admin=TRUE)
         |""".stripMargin,
        params
      ) { r =>
        {
          for (
            _ <- r
              .optString("clientsecret")
              .filter(hashed =>
                clientSecret == hashed
              ); // TODO put this check in the above query
            jsonFeature <- r.optJsObject("feature");
            js <- (jsonFeature \ "config")
              .asOpt[JsValue]
              .map(js => jsonFeature.as[JsObject] + ("wasmConfig" -> js))
              .orElse(Some(jsonFeature));
            feature <- Feature.readCompleteFeature(js).asOpt
          ) yield (r.optString("context"), feature)
        }
      }
      .map(ls =>
        ls.sortWith((f1, f2) => {
          (f1, f2) match {
            case ((None, _), _) => false
            case (_, (None, _)) => true
            case ((Some(ctx1), _), (Some(ctx2), _))
                if ctx1.length > ctx2.length =>
              true
            case _ => false
          }
        }).headOption
          .map(t => t._2)
      )
  }

  def searchFeature(
      tenant: String,
      tags: Set[String]
  ): Future[Seq[LightWeightFeature]] = {
    require(Tenant.isTenantValid(tenant))
    val hasTags = tags.nonEmpty
    env.postgresql
      .queryOne(
        s"""
         |select COALESCE(
         |  json_agg(row_to_json(f.*)::jsonb
         |    || (json_build_object('tags', (
         |      array(
         |        SELECT ft.tag
         |        FROM "${tenant}".features_tags ft
         |        WHERE ft.feature = f.id
         |        GROUP BY ft.tag
         |      )
         |    ), 'wasmConfig', f.script_config))::jsonb)
         |    FILTER (WHERE f.id IS NOT NULL), '[]'
         |) as "features"
         |from "${tenant}".features f${
            if (hasTags) {
              s""", "${tenant}".features_tags ft
         |WHERE ft.feature = f.id
         |AND ft.tag = ANY($$1)"""
            } else ""
          }
         |""".stripMargin,
        if (hasTags) List(tags.toArray)
        else List()
      ) { r =>
        r.optJsArray("features")
          .map(arr =>
            arr.value.toSeq
              .map(js => Feature.readLightWeightFeature(js).asOpt)
              .flatMap(_.toSeq)
          )
      }
      .map(o => o.getOrElse(Seq()))
  }

  def readScriptConfig(
      tenant: String,
      path: String
  ): Future[Option[WasmConfig]] = {
    require(Tenant.isTenantValid(tenant))
    env.postgresql
      .queryOne(
        s"""
         |SELECT config
         |FROM "${tenant}".wasm_script_configurations
         |WHERE config COMPARE("${extensionSchema}."#>>) '{source,path}' = $$1
         |""".stripMargin,
        List(path)
      ) { row => { row.optJsObject("config") } }
      .map(o => o.map(jsObj => jsObj.as[WasmConfig](WasmConfig.format)))
  }

  def findFeaturesProjects(
      tenant: String,
      featureIds: Set[String]
  ): FutureEither[Map[String, String]] = {
    Tenant.isTenantValid(tenant)
    env.postgresql
      .queryAll(
        s"""
         |SELECT DISTINCT id, project FROM "${tenant}".features WHERE id=ANY($$1)
         |""".stripMargin,
        List(featureIds.toArray)
      ) { r =>
        {
          for (
            project <- r.optString("project");
            id <- r.optString("id")
          ) yield (id, project)
        }
      }
      .map(fs => Right(fs.toMap))
      .recover {
        case f: PgException if f.getSqlState == RELATION_DOES_NOT_EXISTS =>
          Left(TenantDoesNotExists(tenant))
        case _ => Left(InternalServerError())
      }
      .toFEither
  }

  def findByIdLightweight(
      tenant: String,
      id: String,
      conn: Option[SqlConnection] = None
  ): FutureEither[Option[LightWeightFeature]] = {
    require(Tenant.isTenantValid(tenant))
    env.postgresql
      .queryOne(
        s"""select f.*, f.script_config as config, COALESCE(json_agg(ft.tag) FILTER (WHERE ft.tag IS NOT NULL), '[]') AS tags
           |from "${tenant}".features f
           |left join "${tenant}".features_tags ft
           |on ft.feature = f.id
           |where f.id = $$1
           |group by f.id""".stripMargin,
        List(id),
        conn = conn
      ) { row => row.optFeature() }
      .mapToFEither
  }

  def findByIds(
      tenant: String,
      ids: Set[String],
      conn: Option[SqlConnection] = None
  ): FutureEither[Map[String, CompleteFeature]] = {
    require(Tenant.isTenantValid(tenant))
    env.postgresql
      .queryAll(
        s"""select f.*, s.config AS wasm, COALESCE(json_agg(ft.tag) FILTER (WHERE ft.tag IS NOT NULL), '[]') AS tags
           |from "${tenant}".features f
           |left join "${tenant}".features_tags ft
           |on ft.feature = f.id
           |left join "${tenant}".wasm_script_configurations s
           |on s.id = f.script_config
           |where f.id = ANY($$1)
           |group by f.id, wasm""".stripMargin,
        List(ids.toArray),
        conn = conn
      ) { row => row.optCompleteFeature() }
      .map(fs => Right(fs.map(f => (f.id -> f)).toMap))
      .recover {
        case f: PgException if f.getSqlState == RELATION_DOES_NOT_EXISTS =>
          Left(TenantDoesNotExists(tenant))
        case _ => Left(InternalServerError())
      }
      .toFEither
  }

  def findById(
      tenant: String,
      id: String,
      conn: Option[SqlConnection] = None
  ): Future[Either[IzanamiError, Option[CompleteFeature]]] = {
    require(Tenant.isTenantValid(tenant))
    env.postgresql
      .queryOne(
        s"""select f.*, s.config AS wasm, COALESCE(json_agg(ft.tag) FILTER (WHERE ft.tag IS NOT NULL), '[]') AS tags
         |from "${tenant}".features f
         |left join "${tenant}".features_tags ft
         |on ft.feature = f.id
         |left join "${tenant}".wasm_script_configurations s
         |on s.id = f.script_config
         |where f.id = $$1
         |group by f.id, wasm""".stripMargin,
        List(id),
        conn = conn
      ) { row => row.optCompleteFeature() }
      .map(o => Right(o))
      .recover {
        case f: PgException if f.getSqlState == RELATION_DOES_NOT_EXISTS =>
          Left(TenantDoesNotExists(tenant))
        case _ => Left(InternalServerError())
      }
  }

  def findByIdForKeyWithoutCheck(
      tenant: String,
      id: String,
      clientId: String
  ): Future[Either[IzanamiError, Option[CompleteFeature]]] = {
    Tenant.isTenantValid(tenant)
    env.postgresql
      .queryOne(
        s"""select (ap.project IS NOT NULL OR k.admin=TRUE) AS authorized, f.*, s.config AS wasm, COALESCE(json_agg(ft.tag) FILTER (WHERE ft.tag IS NOT NULL), '[]') AS tags
           |from "${tenant}".features f
           |left join "${tenant}".features_tags ft
           |on ft.feature = f.id
           |left join "${tenant}".wasm_script_configurations s
           |on s.id = f.script_config
           |inner join "${tenant}".apikeys k
           |on k.clientid=$$2
           |left join "${tenant}".apikeys_projects ap
           |on (ap.apikey=k.name AND ap.project=f.project)
           |where f.id = $$1
           |group by f.id, k.admin, wasm, ap.project""".stripMargin,
        List(id, clientId)
      ) { row =>
        {
          row
            .optBoolean("authorized")
            .map(authorized => {
              if (authorized) {
                row.optCompleteFeature().toRight(InternalServerError())
              } else {
                Left(NotEnoughRights)
              }
            })
        }
      }
      .map {
        case Some(Right(feature)) => Right(Some(feature))
        case Some(Left(error))    => Left(error)
        case None                 => Right(None)
      }
      .recover {
        case f: PgException if f.getSqlState == RELATION_DOES_NOT_EXISTS =>
          Left(TenantDoesNotExists(tenant))
        case _ => Left(InternalServerError())
      }
  }

  def doFindByRequestForKey(
      tenant: String,
      request: FeatureRequest,
      clientId: String,
      clientSecret: String,
      conditions: Boolean
  ): Future[Either[IzanamiError, Map[UUID, Map[String, Iterable[
    (Option[String], CompleteFeature)
  ]]]]] = {
    if(!Tenant.isTenantValid(tenant)) {
      Future.successful(Left(InvalidApiKey))
    } else {
      val needTags =
        request.allTagsIn.nonEmpty || request.noTagIn.nonEmpty || request.oneTagIn.nonEmpty;
      val needContexts = request.context.nonEmpty || conditions

      val params = if (needContexts && !conditions) {
        List(
          clientId,
          clientSecret,
          request.projects.toArray,
          request.features.toArray,
          FeatureContextPath(request.context).toDBPath
        )
      } else {
        List(
          clientId,
          clientSecret,
          request.projects.toArray,
          request.features.toArray
        )
      }

      env.postgresql
        .queryAll(
          s"""
             |SELECT
             |    f.id,
             |    p.id as pid,
             |    f.enabled,
             |    f.name,
             |    f.project,
             |    f.conditions,
             |    f.description,
             |    f.script_config,
             |    f.metadata,
             |    f.result_type,
             |    f.value,
             |    w.config as wasm
             |    ${
            if (needContexts)
              """,
             |    COALESCE(json_object_agg(fcs.context, json_build_object(
             |      'id', f.id,
             |      'name', f.name,
             |      'project', f.project,
             |      'description', f.description,
             |      'value', fcs.value,
             |      'resultType', fcs.result_type,
             |      'enabled', fcs.enabled,
             |      'conditions', fcs.conditions,
             |      'context', fcs.context,
             |      'wasmConfig', ow.config,
             |      'context', fcs.context)) FILTER(WHERE fcs.enabled IS NOT NULL), '{}'::json) AS overloads
             |    """
            else ""
          }
             |    ${
            if (needTags)
              ",COALESCE(json_agg(t.id) FILTER(WHERE t.id IS NOT NULL), '[]') as tags"
            else ""
          }
             |  FROM "${tenant}".projects p
             |  LEFT JOIN "${tenant}".features f on f.project = p.name
             |  ${
            if (needTags)
              s"""
                 |    LEFT JOIN "${tenant}".features_tags ft ON f.id=ft.feature
                 |    LEFT JOIN "${tenant}".tags t ON t.name=ft.tag""" else ""
          }
             |   ${
            if (needContexts)
              s"""
                 |    LEFT JOIN "${tenant}".feature_contexts_strategies fcs ON fcs.feature=f.name ${
                if (!conditions)
                  s"""AND fcs.context OPERATOR("${extensionSchema}".@>) "${extensionSchema}".text2ltree($$5)"""
                else ""
              }
                 |    LEFT JOIN "${tenant}".wasm_script_configurations ow ON fcs.script_config=ow.id""".stripMargin
            else ""
          }
             |  LEFT JOIN "${tenant}".wasm_script_configurations w ON w.id=f.script_config
             |  INNER JOIN "${tenant}".apikeys k ON (k.clientid=$$1 AND k.clientsecret=$$2 AND k.enabled=true)
             |  LEFT JOIN "${tenant}".apikeys_projects kp ON (kp.apikey=k.name AND kp.project=p.name)
             |  WHERE (f.project = p.name OR f.name IS NULL)
             |  AND (kp.apikey IS NOT NULL OR k.admin=TRUE)
             |  AND (p.id=ANY($$3) OR f.id=ANY($$4))
             |  GROUP BY f.id, pid, w.config
             |""".stripMargin,
          params
        ) { r => {
          r.optCompleteFeature()
            .filter(f => {
              if (needTags) {
                val tags = f.tags.map(t => UUID.fromString(t))
                val specificFeatureRequest = request.features.contains(f.id)
                val allTagsInOk = request.allTagsIn.subsetOf(tags)
                val oneTagInOk = request.oneTagIn.isEmpty || request.oneTagIn
                  .exists(u => tags.contains(u))
                val noTagsInOk = !request.noTagIn.exists(u => tags.contains(u))

                specificFeatureRequest || (allTagsInOk && oneTagInOk && noTagsInOk)
              } else {
                true
              }
            })
            .flatMap(f => {
              if (needContexts) {
                r.optJsObject("overloads")
                  .map(jsObject => {
                    val objByContext = jsObject
                      .as[Map[String, JsObject]]
                      .map(entry => (entry._1.replaceAll("\\.", "/"), entry._2))
                    val overloadByPath: Map[Option[String], CompleteFeature] =
                      objByContext
                        .map { case (ctx, jsObject) =>
                          (ctx, Feature.readCompleteFeature(jsObject).asOpt)
                        }
                        .filter {
                          case (_, None) => false
                          case _ => true
                        }
                        .map { case (ctx, optionF) => (Some(ctx), optionF.get) }

                    (r.optUUID("pid").get, (f.id, overloadByPath + (None -> f)))
                  })
              } else {
                Some((r.optUUID("pid").get, (f.id, Map(None -> f))))
              }
            })
        }
        }
        .map(l => {
          val featureByProjects = l.groupBy(t => t._1).map { case (k, v) =>
            (k, v.map(t => t._2).toMap)
          }
          Right(featureByProjects)
        })
        .recover {
          case f: PgException if f.getSqlState == RELATION_DOES_NOT_EXISTS =>
            Left(InvalidApiKey)
          case _ => Left(InternalServerError())
        }
    }
  }

  def findByRequestForKey(
      tenant: String,
      request: FeatureRequest,
      clientId: String,
      clientSecret: String
  ): Future[Either[IzanamiError, Map[UUID, Seq[CompleteFeature]]]] = {
    doFindByRequestForKey(
      tenant,
      request,
      clientId,
      clientSecret,
      conditions = false
    ).map {
      case Left(err) => Left(err)
      case Right(l)  => {
        Right(l.map {
          case (projectId, featuresById) => {
            (
              projectId,
              featuresById.map {
                case (id, featuresWithContext) => {
                  featuresWithContext.toSeq
                    .sortWith {
                      case (
                            (firstContext, feature),
                            (secondContext, feature2)
                          ) => {
                        (firstContext, secondContext) match {
                          case (None, _) => false
                          case (_, None) => true
                          case (Some(ctx1), Some(ctx2))
                              if ctx1.length > ctx2.length =>
                            true
                          case _ => false
                        }
                      }
                    }
                    .head
                    ._2
                }
              }.toSeq
            )
          }
        })
      }
    }
  }

  def findByRequestV2(
      tenant: String,
      request: FeatureRequest,
      contexts: FeatureContextPath,
      user: String
  ): Future[Map[UUID, Seq[CompleteFeature]]] = {
    Tenant.isTenantValid(tenant)
    env.postgresql
      .queryAll(
        s"""
         |WITH filtered_features AS (
         |  SELECT
         |    f.id,
         |    p.id as pid,
         |    f.enabled,
         |    f.name,
         |    f.project,
         |    f.conditions,
         |    f.script_config,
         |    f.description,
         |    f.result_type,
         |    f.value,
         |    w.config,
         |    fcs.enabled as overload_enabled,
         |    fcs.conditions as overload_conditions,
         |    fcs.context as overload_context,
         |    fcs.value as overload_value,
         |    fcs.result_type as overload_result_type,
         |    ow.config as overload_config,
         |    fcs.context,
         |    COALESCE(json_agg(t.id) FILTER(WHERE t.id IS NOT NULL), '[]'::json) as tags
         |  FROM
         |    izanami.sessions s
         |        JOIN izanami.users u ON s.username=u.username
         |        LEFT JOIN izanami.users_tenants_rights utr ON utr.username=u.username
         |        LEFT JOIN "${tenant}".users_projects_rights upr ON upr.username=s.username,
         |    "${tenant}".projects p
         |  LEFT JOIN "${tenant}".features f on f.project = p.name
         |  LEFT JOIN "${tenant}".features_tags ft ON f.id=ft.feature
         |  LEFT JOIN "${tenant}".tags t ON t.name=ft.tag
         |  LEFT JOIN "${tenant}".feature_contexts_strategies fcs ON fcs.feature=f.name AND fcs.context OPERATOR("${extensionSchema}".@>) "${extensionSchema}".text2ltree($$4)
         |  LEFT JOIN "${tenant}".wasm_script_configurations ow ON fcs.script_config=ow.id
         |  LEFT JOIN "${tenant}".wasm_script_configurations w ON w.id=f.script_config
         |  WHERE s.username=$$1
         |  AND (f.project = p.name OR f.name IS NULL)
         |  AND (
         |    (upr.project=p.name AND upr.username=s.username)
         |    OR (u.admin OR utr.level = 'ADMIN')
         |  )
         |  AND (p.id=ANY($$2) OR f.id=ANY($$3))
         |  GROUP BY f.id, pid, w.config, ow.config, fcs.enabled, fcs.conditions, fcs.context, fcs.context, fcs.value, fcs.result_type
         |) SELECT filtered_features.pid AS project_id,
         |         COALESCE(json_agg(json_build_object(
         |           'name', filtered_features.name,
         |           'project', filtered_features.project,
         |           'tags', filtered_features.tags,
         |           'context', filtered_features.context,
         |           'description', filtered_features.description,
         |           'id', filtered_features.id)::jsonb ||
         |           (CASE
         |            WHEN filtered_features.overload_enabled IS NOT NULL THEN
         |            json_build_object(
         |              'enabled', filtered_features.overload_enabled,
         |              'config', filtered_features.overload_config,
         |              'conditions', filtered_features.overload_conditions,
         |              'resultType', filtered_features.overload_result_type,
         |              'value', filtered_features.overload_value
         |            )::jsonb
         |            ELSE
         |            json_build_object(
         |              'enabled', filtered_features.enabled,
         |              'config', filtered_features.config,
         |              'conditions', filtered_features.conditions,
         |              'resultType', filtered_features.result_type,
         |              'value', filtered_features.value
         |            )::jsonb
         |           END)
         |         ) FILTER(WHERE filtered_features.name IS NOT NULL), '[]'::json) as features
         |FROM filtered_features
         |GROUP BY filtered_features.pid;
         |""".stripMargin,
        List(
          user,
          request.projects.toArray,
          request.features.toArray,
          contexts.toDBPath
        )
      ) { r =>
        {
          r.optUUID("project_id")
            .map(p => {
              val tuple: (UUID, Seq[CompleteFeature]) = (
                p,
                r.optJsArray("features")
                  .toSeq
                  .flatMap(maybeArray => maybeArray.value)
                  .groupBy(jsObj => (jsObj \ "id").as[String])
                  .values
                  .map(featureDuplicates =>
                    featureDuplicates
                      .sortWith((first, second) => {
                        def contextSize(jsValue: JsValue): Int =
                          (jsValue \ "context")
                            .asOpt[String]
                            .map(_.split("."))
                            .map(_.length)
                            .getOrElse(0)
                        val firstContextSize = contextSize(first)
                        val secondContextSize = contextSize(second)

                        if (firstContextSize == 0) true
                        else if (secondContextSize == 0) false
                        else if (firstContextSize < secondContextSize) false
                        else true
                      })
                      .head
                  )
                  .flatMap(f => {
                    Feature
                      .readCompleteFeature(
                        (f \ "config")
                          .asOpt[JsValue]
                          .map(js => f.as[JsObject] + ("wasmConfig" -> js))
                          .getOrElse(f)
                      )
                      .asOpt
                      .toSeq
                  })
                  .filter(f =>
                    request.features.contains(f.id) || request.allTagsIn
                      .subsetOf(f.tags.map(UUID.fromString))
                  )
                  .filter(f =>
                    request.features.contains(
                      f.id
                    ) || request.oneTagIn.isEmpty || request.oneTagIn
                      .exists(u => f.tags.contains(u.toString))
                  )
                  .filter(f =>
                    request.features.contains(f.id) || !request.noTagIn
                      .exists(u => f.tags.contains(u.toString))
                  )
                  .toSeq
              )
              tuple
            })
        }
      }
      .map(_.toMap)
  }

  def createFeaturesAndProjects(
      tenant: String,
      features: Iterable[CompleteFeature],
      conflictStrategy: ImportConflictStrategy,
      user: UserInformation,
      conn: Option[SqlConnection]
  ): Future[Either[List[IzanamiError], Unit]] = {
    // TODO return seq[Error] instead of a single one
    if (features.isEmpty) {
      Future.successful(Right(()))
    } else {
      def callback(
          conn: SqlConnection
      ): Future[Either[List[IzanamiError], Unit]] = {
        env.datastores.projects
          .createProjects(
            tenant,
            features.map(_.project).toSet,
            conflictStrategy,
            user,
            conn = conn
          )
          .flatMap {
            case Left(error) => Future.successful(Left(List(error)))
            case _ => createBulk(tenant, features, conflictStrategy, conn, user)
          }
      }

      conn
        .map(callback)
        .getOrElse(env.postgresql.executeInTransaction(conn => callback(conn)))
    }
  }

  def createBulk(
      tenant: String,
      features: Iterable[CompleteFeature],
      conflictStrategy: ImportConflictStrategy,
      conn: SqlConnection,
      user: UserInformation
  ): Future[Either[List[IzanamiError], Unit]] = {
    Tenant.isTenantValid(tenant)
    def insertFeatures[T <: ClusterSerializable](
        params: (
            Array[String],
            Array[String],
            Array[String],
            Array[java.lang.Boolean],
            Array[T],
            Array[Object],
            Array[String],
            Array[String],
            Array[String]
        )
    ): Future[Either[InternalServerError, List[(String, String)]]] = {
      env.postgresql
        .queryAll(
          s"""INSERT INTO "${tenant}".features (id, name, project, enabled, conditions, metadata, description, result_type, value)
               |VALUES (unnest($$1::text[]), unnest($$2::text[]), unnest($$3::text[]), unnest($$4::boolean[]), unnest($$5::jsonb[]), unnest($$6::jsonb[]), unnest($$7::text[]), unnest($$8::"${tenant}".RESULT_TYPE[]), unnest($$9::text[]))
                ${conflictStrategy match {
              case Fail => ""
              case Skip => " ON CONFLICT DO NOTHING"
              case MergeOverwrite | ImportController.Replace =>
                """ ON CONFLICT (name, project) DO UPDATE SET id=excluded.id, name=excluded.name, project=excluded.project, enabled=excluded.enabled, conditions=excluded.conditions, metadata=excluded.metadata, description=excluded.description, script_config=null, result_type=excluded.result_type, value=excluded.value
               |""".stripMargin
            }}
                returning id, project""".stripMargin,
          params.productIterator.toList.map(a => a.asInstanceOf[AnyRef]),
          conn = Some(conn)
        ) { row =>
          for (
            id <- row.optString("id");
            project <- row.optString("project")
          ) yield (id, project)
        }
        .map(ls => Right(ls))
        .recover { case ex =>
          logger.error("Failed to insert feature", ex)
          Left(InternalServerError())
        }

    }

    def insertLegacyFeatures[T <: ClusterSerializable](
        params: (
            Array[String],
            Array[String],
            Array[String],
            Array[java.lang.Boolean],
            Array[T],
            Array[Object],
            Array[String]
        )
    ): Future[Either[InternalServerError, List[(String, String)]]] = {
      insertFeatures(
        Tuple9(
          params._1,
          params._2,
          params._3,
          params._4,
          params._5,
          params._6,
          params._7,
          params._1.map(_ => BooleanResult.toDatabaseName),
          params._1.map(_ => null)
        )
      )
    }

    val wasmConfigs = features
      .map {
        case wf: CompleteWasmFeature => Some(wf.wasmConfig)
        case _                       => None
      }
      .flatMap(o => o.toList)

    def unzip7[
        A: ClassTag,
        B: ClassTag,
        C: ClassTag,
        D: ClassTag,
        E: ClassTag,
        F: ClassTag,
        G: ClassTag
    ](
        l: Iterable[(A, B, C, D, E, F, G)]
    ): (
        Array[A],
        Array[B],
        Array[C],
        Array[D],
        Array[E],
        Array[F],
        Array[G]
    ) = {
      l.foldLeft(
        Tuple7(
          Array[A](),
          Array[B](),
          Array[C](),
          Array[D](),
          Array[E](),
          Array[F](),
          Array[G]()
        )
      ) { case (res, (e1, e2, e3, e4, e5, e6, e7)) =>
        (
          res._1.appended(e1),
          res._2.appended(e2),
          res._3.appended(e3),
          res._4.appended(e4),
          res._5.appended(e5),
          res._6.appended(e6),
          res._7.appended(e7)
        )
      }
    }

    def unzip8[
        A: ClassTag,
        B: ClassTag,
        C: ClassTag,
        D: ClassTag,
        E: ClassTag,
        F: ClassTag,
        G: ClassTag,
        H: ClassTag
    ](
        l: Iterable[(A, B, C, D, E, F, G, H)]
    ): (
        Array[A],
        Array[B],
        Array[C],
        Array[D],
        Array[E],
        Array[F],
        Array[G],
        Array[H]
    ) = {
      l.foldLeft(
        Tuple8(
          Array[A](),
          Array[B](),
          Array[C](),
          Array[D](),
          Array[E](),
          Array[F](),
          Array[G](),
          Array[H]()
        )
      ) { case (res, (e1, e2, e3, e4, e5, e6, e7, e8)) =>
        (
          res._1.appended(e1),
          res._2.appended(e2),
          res._3.appended(e3),
          res._4.appended(e4),
          res._5.appended(e5),
          res._6.appended(e6),
          res._7.appended(e7),
          res._8.appended(e8)
        )
      }
    }

    def unzip9[
        A: ClassTag,
        B: ClassTag,
        C: ClassTag,
        D: ClassTag,
        E: ClassTag,
        F: ClassTag,
        G: ClassTag,
        H: ClassTag,
        I: ClassTag
    ](
        l: Iterable[(A, B, C, D, E, F, G, H, I)]
    ): (
        Array[A],
        Array[B],
        Array[C],
        Array[D],
        Array[E],
        Array[F],
        Array[G],
        Array[H],
        Array[I]
    ) = {
      l.foldLeft(
        Tuple9(
          Array[A](),
          Array[B](),
          Array[C](),
          Array[D](),
          Array[E](),
          Array[F](),
          Array[G](),
          Array[H](),
          Array[I]()
        )
      ) { case (res, (e1, e2, e3, e4, e5, e6, e7, e8, e9)) =>
        (
          res._1.appended(e1),
          res._2.appended(e2),
          res._3.appended(e3),
          res._4.appended(e4),
          res._5.appended(e5),
          res._6.appended(e6),
          res._7.appended(e7),
          res._8.appended(e8),
          res._9.appended(e9)
        )
      }
    }

    val (modernFeatures, wasmFeatures, legacyFeatures): (
        ArrayBuffer[Feature],
        ArrayBuffer[CompleteWasmFeature],
        ArrayBuffer[SingleConditionFeature]
    ) = (
      ArrayBuffer(): ArrayBuffer[Feature],
      ArrayBuffer(): ArrayBuffer[CompleteWasmFeature],
      ArrayBuffer(): ArrayBuffer[SingleConditionFeature]
    )
    features.foreach {
      case f: Feature                => modernFeatures.addOne(f)
      case wf: CompleteWasmFeature   => wasmFeatures.addOne(wf)
      case s: SingleConditionFeature => legacyFeatures.addOne(s)
    }

    val legacyFeatureParams = unzip7(legacyFeatures.map {
      case SingleConditionFeature(
            id,
            name,
            project,
            conditions,
            enabled,
            tags,
            metadata,
            description
          ) =>
        (
          Option(id).getOrElse(UUID.randomUUID().toString),
          name,
          project,
          java.lang.Boolean.valueOf(enabled),
          new JsonObject(Json.toJson(conditions).toString()),
          metadata.vertxJsValue,
          description
        )
    })

    val modernFeatureParams = unzip9(
      modernFeatures.map {
        case Feature(
              id,
              name,
              project,
              enabled,
              tags,
              metadata,
              description,
              resultDescriptor
            ) =>
          (
            Option(id).getOrElse(UUID.randomUUID().toString),
            name,
            project,
            java.lang.Boolean.valueOf(enabled),
            new JsonArray(Json.toJson(resultDescriptor.conditions).toString()),
            metadata.vertxJsValue,
            description,
            resultDescriptor.resultType.toDatabaseName,
            resultDescriptor match {
              case descriptor: ValuedResultDescriptor  => descriptor.stringValue
              case BooleanResultDescriptor(conditions) => null
            }
          )
      }
    )

    val wasmFeatureParams = unzip8(
      wasmFeatures.map {
        case CompleteWasmFeature(
              id,
              name,
              project,
              enabled,
              wasmConfig,
              tags,
              metadata,
              description,
              resultType
            ) =>
          (
            Option(id).getOrElse(UUID.randomUUID().toString),
            name,
            project,
            java.lang.Boolean.valueOf(enabled),
            wasmConfig.name,
            metadata.vertxJsValue,
            description,
            resultType.toDatabaseName
          )
      }
    )

    createWasmScripts(tenant, wasmConfigs.toList, conflictStrategy, conn.some)
      .flatMap {
        case Left(err) => Left(List(err)).future
        case Right(_)  => {
          Future
            .sequence(
              List(
                insertFeatures(modernFeatureParams),
                insertLegacyFeatures(legacyFeatureParams),
                env.postgresql
                  .queryAll(
                    s"""INSERT INTO "${tenant}".features (id, name, project, enabled, script_config, metadata, description, result_type)
                   |VALUES (unnest($$1::TEXT[]), unnest($$2::TEXT[]), unnest($$3::TEXT[]), unnest($$4::BOOLEAN[]), unnest($$5::TEXT[]), unnest($$6::JSONB[]), unnest($$7::TEXT[]), unnest($$8::"${tenant}".RESULT_TYPE[]))
                   |returning id, project""".stripMargin,
                    wasmFeatureParams.productIterator.toList
                      .map(a => a.asInstanceOf[AnyRef]),
                    conn = conn.some
                  ) { row =>
                    for (
                      id <- row.optString("id");
                      project <- row.optString("project")
                    ) yield (id, project)
                  }
                  .map(ls => Right(ls))
                  .recover {
                    case f: PgException
                        if f.getSqlState == RELATION_DOES_NOT_EXISTS =>
                      Left(TenantDoesNotExists(tenant))
                    case ex =>
                      logger.error("Failed to insert feature", ex)
                      Left(InternalServerError())
                  }
              )
            )
            .map(eithers => eithers.toEitherList.map(l => l.flatten))
        }
      }
      .flatMap {
        case Left(errors) => Future.successful(Left(errors))
        case Right(ids)   =>
          Future
            .sequence(
              features.map(f =>
                insertIntoFeatureTags(tenant, f.id, f.tags, conn.some)
              )
            )
            .map(eithers => eithers.toEitherList.map(_ => features))
      }
      .flatMap {
        case Left(errors)    => Future.successful(Left(errors))
        case Right(features) => {
          Future
            .sequence(
              features.map(f =>
                env.eventService.emitEvent(
                  channel = tenant,
                  event = SourceFeatureCreated(
                    id = f.id,
                    project = f.project,
                    tenant = tenant,
                    user = user.username,
                    feature = FeatureWithOverloads(f.toLightWeightFeature),
                    origin = ImportOrigin,
                    authentication = user.authentication
                  )
                )(conn)
              )
            )
            .map(_ => Right(()))
        }
      }
  }

  def create(
      tenant: String,
      project: String,
      feature: CompleteFeature,
      user: UserInformation
  ): Future[Either[IzanamiError, String]] = {
    env.postgresql.executeInTransaction(implicit conn =>
      doCreate(tenant, project, feature, conn, user)
    )
  }

  private def doCreate(
      tenant: String,
      project: String,
      feature: CompleteFeature,
      conn: SqlConnection,
      user: UserInformation
  ): Future[Either[IzanamiError, String]] = {
    (feature match {
      case _: Feature               => Future(Right(()))
      case cwf: CompleteWasmFeature =>
        createWasmScriptIfNeeded(tenant, cwf.wasmConfig, conn = Some(conn))
      case s: SingleConditionFeature => Future(Right(()))
    }).flatMap {
      case Left(err) => Left(err).future
      case Right(_)  => {
        insertFeature(tenant, project, feature, user)(conn)
          .flatMap(eitherId => {
            eitherId.fold(
              err => Future.successful(Left(err)),
              id =>
                insertIntoFeatureTags(tenant, id, feature.tags, Some(conn)).map(
                  either => either.map(_ => id)
                )
            )
          })
      }
    }
  }

  def readLocalScripts(tenant: String): Future[Seq[WasmConfig]] = {
    Tenant.isTenantValid(tenant)
    env.postgresql.queryAll(
      s"""
         |SELECT config FROM "${tenant}".wasm_script_configurations
         |""".stripMargin,
      List()
    ) { r => r.optJsObject("config").map(js => js.as(WasmConfig.format)) }
  }

  def deleteLocalScript(
      tenant: String,
      name: String
  ): Future[Either[IzanamiError, Unit]] = {
    Tenant.isTenantValid(tenant)
    env.postgresql
      .queryOne(
        s"""
         |DELETE FROM "${tenant}".wasm_script_configurations WHERE id=$$1
         |""".stripMargin,
        List(name)
      ) { r => Some(()) }
      .map(_ => Right(()))
      .recover {
        case f: PgException if f.getSqlState == FOREIGN_KEY_VIOLATION =>
          Left(FeatureDependsOnThisScript())
      }
  }

  def readLocalScriptsWithAssociatedFeatures(
      tenant: String
  ): Future[Seq[WasmConfigWithFeatures]] = {
    Tenant.isTenantValid(tenant)
    env.postgresql.queryAll(
      s"""
         |SELECT c.config, json_agg(json_build_object('id', features.id, 'name', features.name, 'project', features.project)) as features
         |FROM "${tenant}".wasm_script_configurations c
         |LEFT JOIN "${tenant}".features ON features.script_config=c.id
         |GROUP BY c.config
         |""".stripMargin,
      List()
    ) { r =>
      {
        r.optJsObject("config")
          .map(js => js.as(WasmConfig.format))
          .flatMap(config => {
            r.optJsArray("features")
              .map(arr => {
                val features = arr.value
                  .map(jsValue => {
                    for {
                      name <- (jsValue \ "name").asOpt[String]
                      id <- (jsValue \ "id").asOpt[String]
                      project <- (jsValue \ "project").asOpt[String]
                    } yield WasmScriptAssociatedFeatures(
                      name = name,
                      project = project,
                      id = id
                    )
                  })
                  .filter(o => o.isDefined)
                  .map(o => o.get)
                  .toSeq

                WasmConfigWithFeatures(wasmConfig = config, features = features)
              })
          })
      }
    }
  }

  def readAllLocalScripts(): Future[Seq[WasmConfig]] = {
    env.datastores.tenants
      .readTenants()
      .flatMap(tenants => {
        Future.sequence(tenants.map(tenant => {
          env.postgresql.queryAll(
            s"""
               |SELECT config FROM "${tenant.name}".wasm_script_configurations
               |""".stripMargin,
            List()
          ) { r => r.optJsObject("config").map(js => js.as(WasmConfig.format)) }
        }))
      })
      .map(os => os.flatten)
  }

  def createWasmScriptIfNeeded(
      tenant: String,
      wasmConfig: WasmConfig,
      conn: Option[SqlConnection]
  ): Future[Either[IzanamiError, String]] = {
    Tenant.isTenantValid(tenant)
    wasmConfig.source.kind match {
      case WasmSourceKind.Unknown =>
        throw new RuntimeException("Unknown wasm script")
      case WasmSourceKind.Local => Right(wasmConfig.source.path).future
      case _                    =>
        env.postgresql
          .queryOne(
            s"""INSERT INTO "${tenant}".wasm_script_configurations (id, config) VALUES ($$1,$$2) RETURNING id""",
            List(
              wasmConfig.name,
              Json.toJson(wasmConfig)(WasmConfig.format).vertxJsValue
            ),
            conn = conn
          ) { row => row.optString("id") }
          .map(o => o.toRight(InternalServerError()))
          .recover {
            case f: PgException if f.getSqlState == FOREIGN_KEY_VIOLATION =>
              Left(WasmScriptAlreadyExists(wasmConfig.source.path))
          }
          .recover(
            env.postgresql.pgErrorPartialFunction.andThen(err => Left(err))
          )
          .flatMap(either => {
            // TODO this should be elsewhere
            wasmConfig.source
              .getWasm()(env.wasmIntegration.context, env.executionContext)
              .map(_ => either)
          })
    }
  }

  def readWasmScript(
      tenant: String,
      name: String
  ): Future[Option[WasmConfig]] = {
    Tenant.isTenantValid(tenant)
    env.postgresql.queryOne(
      s"""
         |SELECT config
         |FROM "${tenant}".wasm_script_configurations
         |WHERE id=$$1
         |""".stripMargin,
      List(name)
    ) { r => r.optJsObject("config").map(js => js.as(WasmConfig.format)) }
  }

  def createWasmScripts(
      tenant: String,
      wasmConfigs: List[WasmConfig],
      conflictStrategy: ImportConflictStrategy,
      conn: Option[SqlConnection]
  ): Future[Either[IzanamiError, Set[String]]] = {
    Tenant.isTenantValid(tenant)

    if (wasmConfigs.isEmpty) {
      Future.successful(Right(Set()))
    } else {

      val (ids, scripts) = wasmConfigs
        .filter(w =>
          w.source.kind != WasmSourceKind.Local && w.source.kind != WasmSourceKind.Unknown
        )
        .map(w => (w.name, Json.toJson(w)(WasmConfig.format).vertxJsValue))
        .unzip

      val localScriptIds = wasmConfigs
        .filter(w => w.source.kind == WasmSourceKind.Local)
        .map(w => w.name)

      env.postgresql
        .queryRaw(
          s"""
         |INSERT INTO "${tenant}".wasm_script_configurations(id, config)
         |VALUES (unnest($$1::TEXT[]), unnest($$2::JSONB[]))
         |${conflictStrategy match {
              case Fail                                      => ""
              case MergeOverwrite | ImportController.Replace =>
                """
            |ON CONFLICT(id) DO UPDATE SET config = excluded.config
            |""".stripMargin
              case Skip => " ON CONFLICT(id) DO NOTHING "
            }}
         |returning id
         |""".stripMargin,
          List(ids.toArray, scripts.toArray),
          conn = conn
        ) { rs => rs.flatMap(_.optString("id")).toSet }
        .map(ids => {
          ids.foreach(id =>
            wasmConfigs
              .find(w => w.name == id)
              .get
              .source
              .getWasm()(env.wasmIntegration.context, env.executionContext)
          )
          Right(ids.concat(localScriptIds))
        })
        .recover {
          case f: PgException if f.getSqlState == FOREIGN_KEY_VIOLATION =>
            Left(WasmScriptAlreadyExists("")) // TODO specify script name
        }
    }
  }

  def updateWasmScript(
      tenant: String,
      script: String,
      wasmConfig: WasmConfig
  ): Future[Unit] = {
    Tenant.isTenantValid(tenant)
    env.postgresql
      .queryOne(
        s"""UPDATE "${tenant}".wasm_script_configurations SET id=$$1, config=$$2 WHERE id=$$3 RETURNING id""",
        List(
          wasmConfig.name,
          wasmConfig.json.vertxJsValue,
          script
        )
      ) { row => row.optString("id") }
      .map(o => ())
  }

  private def insertFeature(
      tenant: String,
      project: String,
      feature: CompleteFeature,
      user: UserInformation
  )(implicit
      conn: SqlConnection
  ): Future[Either[IzanamiError, String]] = {
    Tenant.isTenantValid(tenant)
    val (request, params) = feature match {
      case SingleConditionFeature(
            id,
            name,
            project,
            conditions,
            enabled,
            _,
            metadata,
            description
          ) =>
        (
          s"""INSERT INTO "${tenant}".features (id, name, project, enabled, conditions, metadata, description, result_type)
             |VALUES ($$1, $$2, $$3, $$4, $$5, $$6, $$7, $$8)
             |returning id""".stripMargin,
          List(
            Option(id).getOrElse(UUID.randomUUID().toString),
            name,
            project,
            java.lang.Boolean.valueOf(enabled),
            new JsonObject(Json.toJson(conditions).toString()),
            metadata.vertxJsValue,
            description,
            BooleanResult.toDatabaseName
          )
        )
      case Feature(
            id,
            name,
            project,
            enabled,
            _,
            metadata,
            description,
            resultDescriptor
          ) =>
        (
          s"""INSERT INTO "${tenant}".features (id, name, project, enabled, conditions, metadata, description, result_type, value)
           |VALUES ($$1, $$2, $$3, $$4, $$5, $$6, $$7, $$8, $$9)
           |returning id""".stripMargin,
          List(
            Option(id).getOrElse(UUID.randomUUID().toString),
            name,
            project,
            java.lang.Boolean.valueOf(enabled),
            new JsonArray(Json.toJson(resultDescriptor.conditions).toString()),
            metadata.vertxJsValue,
            description,
            resultDescriptor.resultType.toDatabaseName,
            resultDescriptor match {
              case descriptor: ValuedResultDescriptor  => descriptor.stringValue
              case BooleanResultDescriptor(conditions) => null
            }
          )
        )
      case CompleteWasmFeature(
            id,
            name,
            project,
            enabled,
            config,
            _,
            metadata,
            description,
            resultType
          ) =>
        (
          s"""INSERT INTO "${tenant}".features (id, name, project, enabled, script_config, metadata, description, result_type)
            |VALUES ($$1, $$2, $$3, $$4, $$5, $$6, $$7, $$8)
            |returning id""".stripMargin,
          List(
            Option(id).getOrElse(UUID.randomUUID().toString),
            name,
            project,
            java.lang.Boolean.valueOf(enabled),
            config.name,
            metadata.vertxJsValue,
            description,
            resultType.toDatabaseName
          )
        )
    }

    env.postgresql
      .queryOne(
        request,
        params,
        conn = Some(conn)
      ) { row => row.optString("id") }
      .map(_.toRight(InternalServerError()))
      .recover {
        case f: PgException if f.getSqlState == FOREIGN_KEY_VIOLATION =>
          Left(ProjectDoesNotExists(project))
        case f: PgException if f.getSqlState == RELATION_DOES_NOT_EXISTS =>
          Left(TenantDoesNotExists(tenant))
      }
      .recover(env.postgresql.pgErrorPartialFunction.andThen(err => Left(err)))
      .flatMap {
        case Left(error) => Future.successful(Left(error))
        case Right(id)   =>
          env.eventService
            .emitEvent(
              channel = tenant,
              event = SourceFeatureCreated(
                id = id,
                project = project,
                tenant = tenant,
                user = user.username,
                feature = FeatureWithOverloads(feature.toLightWeightFeature),
                authentication = user.authentication,
                origin = NormalOrigin
              )
            )(conn)
            .map(_ => Right(id))
      }
  }

  def update(
      tenant: String,
      id: String,
      feature: CompleteFeature,
      user: UserInformation,
      conn: Option[SqlConnection] = None
  ): Future[Either[IzanamiError, String]] = {
    Tenant.isTenantValid(tenant)
    def act(
        conn: SqlConnection,
        oldFeature: FeatureWithOverloads
    ): Future[Either[IzanamiError, String]] = {

      // Updating result type of a feature automatically trigger overload delete, to avoid
      // returning a string for some contexts and a boolean in others
      val maybeDeleteFuture =
        if (oldFeature.baseFeature.resultType != feature.resultType) {
          env.datastores.featureContext.deleteFeatureStrategies(
            tenant,
            feature.project,
            feature.name,
            conn = conn
          )
        } else {
          Future.successful(())
        }
      maybeDeleteFuture.flatMap(_ => {
        val (request, params) = feature match {
          case SingleConditionFeature(
                id,
                name,
                project,
                conditions,
                enabled,
                tags,
                metadata,
                description
              ) =>
            (
              s"""update "${tenant}".features
                 |SET name=$$1, enabled=$$2, conditions=$$3, script_config=NULL, description=$$5, project=$$6, result_type='boolean' WHERE id=$$4 returning id""".stripMargin,
              List(
                name,
                java.lang.Boolean.valueOf(enabled),
                new JsonObject(Json.toJson(conditions).toString()),
                id,
                description,
                project
              )
            )
          case Feature(
                _,
                name,
                project,
                enabled,
                _,
                _,
                description,
                resultDescriptor
              ) =>
            (
              s"""update "${tenant}".features
                 |SET name=$$1, enabled=$$2, conditions=$$3, script_config=NULL, description=$$5, project=$$6, result_type=$$7, value=$$8  WHERE id=$$4 returning id""".stripMargin,
              List(
                name,
                java.lang.Boolean.valueOf(enabled),
                new JsonArray(
                  Json.toJson(resultDescriptor.conditions).toString()
                ),
                id,
                description,
                project,
                resultDescriptor.resultType.toDatabaseName,
                resultDescriptor match {
                  case descriptor: ValuedResultDescriptor =>
                    descriptor.stringValue
                  case BooleanResultDescriptor(conditions) => null
                }
              )
            )
          case CompleteWasmFeature(
                _,
                name,
                project,
                enabled,
                wasmConfig,
                _,
                _,
                description,
                resultType
              ) =>
            (
              s"""update "${tenant}".features
                 |SET name=$$1, enabled=$$2, script_config=$$4, conditions=NULL, description=$$5, project=$$6, result_type=$$7, value=null  WHERE id=$$3 returning id""".stripMargin,
              List(
                name,
                java.lang.Boolean.valueOf(enabled),
                id,
                wasmConfig.name,
                description,
                project,
                resultType.toDatabaseName
              )
            )
        }

        (feature match {
          case feat: CompleteWasmFeature
              if feat.wasmConfig.source.kind != WasmSourceKind.Local =>
            createWasmScriptIfNeeded(tenant, feat.wasmConfig, Some(conn)).map(
              e => e.map(_ => ())
            )
          case _ => Future(Right(()))
        }).flatMap {
          case Left(err) => Future.successful(Left(err))
          case Right(_)  =>
            // delete overload in potential old project, this cover the case where a feature
            // is transfered from one project to another.
            env.postgresql
              .queryRaw(
                s"""
                 |DELETE FROM "${tenant}".feature_contexts_strategies fc USING "${tenant}".features f, "${tenant}".new_contexts c
                 |WHERE fc.feature=f.name AND "${extensionSchema}".ltree_eq(c.ctx_path, fc.context)
                 |AND fc.project=f.project
                 |AND f.id=$$1
                 |AND f.project != $$2
                 |AND c.global=false
                 |AND c.project != $$2
                 |""".stripMargin,
                List(id, feature.project),
                conn = Some(conn)
              ) { _ => Some(()) }
              .flatMap(_ =>
                env.postgresql
                  .queryOne(
                    request,
                    params,
                    conn = Some(conn)
                  ) { row => row.optString("id") }
                  .map(maybeId => maybeId.toRight(InternalServerError()))
                  .recover {
                    case f: PgException
                        if f.getSqlState == NOT_NULL_VIOLATION =>
                      Left(MissingFeatureFields())
                  }
                  .recover(
                    env.postgresql.pgErrorPartialFunction
                      .andThen(err => Left(err))
                  )
                  .flatMap(either => {
                    either.fold(
                      err => Future.successful(Left(err)),
                      id => {
                        env.postgresql
                          .queryOne(
                            s"""delete from "${tenant}".features_tags where feature=$$1""",
                            List(id),
                            conn = Some(conn)
                          ) { _ => Some(id) }
                          .flatMap(_ =>
                            insertIntoFeatureTags(
                              tenant,
                              id,
                              feature.tags,
                              Some(conn)
                            )
                              .map(either => either.map(_ => id))
                          )
                      }
                    )
                  })
              )
              .flatMap {
                case Right(_)
                    if !oldFeature.baseFeature
                      .hasSameActivationStrategy(
                        feature
                      ) || oldFeature.project != feature.project =>
                  env.eventService
                    .emitEvent(
                      channel = tenant,
                      event = SourceFeatureUpdated(
                        id = id,
                        project = feature.project,
                        tenant = tenant,
                        user = user.username,
                        previous = oldFeature,
                        feature =
                          oldFeature.setFeature(feature.toLightWeightFeature),
                        authentication = user.authentication,
                        origin = NormalOrigin
                      )
                    )(conn)
                    .map(_ => Right(id))
                case Right(_)  => Future.successful(Right(id))
                case Left(err) => Future.successful(Left(err))
              }
        }
      })
    }
    // TODO allow updating metadata
    findActivationStrategiesForFeature(tenant = tenant, id = id)
      .flatMap {
        case None => Future.successful(Left(FeatureDoesNotExist(id)))
        case Some(oldFeature) => {
          conn match {
            case Some(c) => act(c, oldFeature)
            case None    =>
              env.postgresql.executeInTransaction(c => act(c, oldFeature))
          }
        }
      }
  }

  def insertIntoFeatureTags(
      tenant: String,
      id: String,
      tags: Set[String],
      conn: Option[SqlConnection]
  ): Future[Either[IzanamiError, Unit]] = {
    Tenant.isTenantValid(tenant)
    if (tags.isEmpty) {
      Future.successful(Right(()))
    } else {
      env.postgresql
        .queryOne(
          s"""
             |INSERT INTO "${tenant}".features_tags (feature, tag)
             |VALUES ($$1, unnest($$2::TEXT[])) returning *""".stripMargin,
          List(id, tags.toArray),
          conn = conn
        ) { _ => Some(()) }
        .map(_ => Right(()))
        .recover {
          case f: PgException if f.getSqlState == RELATION_DOES_NOT_EXISTS =>
            Left(TenantDoesNotExists(tenant))
          case f: PgException if f.getSqlState == FOREIGN_KEY_VIOLATION =>
            Left(TagDoesNotExists(tags.map(t => t).mkString(",")))
          case ex =>
            logger.error("Failed to update feature/tag mapping table", ex)
            Left(InternalServerError())
        }
    }
  }

  def delete(
      tenant: String,
      id: String,
      user: UserInformation
  ): Future[Either[IzanamiError, String]] = {
    Tenant.isTenantValid(tenant)
    env.postgresql.executeInTransaction(conn =>
      env.postgresql
        .queryOne(
          s"""DELETE FROM "${tenant}".features WHERE id=$$1 returning id, project, name""",
          List(id),
          conn = Some(conn)
        ) { row =>
          for (
            id <- row.optString("id");
            project <- row.optString("project");
            name <- row.optString("name")
          ) yield (id, project, name)
        }
        .map { _.toRight(InternalServerError()) }
        .recover {
          case ex: PgException if ex.getSqlState == RELATION_DOES_NOT_EXISTS =>
            Left(TenantDoesNotExists(tenant))
          case _ => Left(InternalServerError())
        }
        .flatMap {
          case l @ Left(err)              => Future.successful(Left(err))
          case Right((id, project, name)) =>
            env.eventService
              .emitEvent(
                channel = tenant,
                event = SourceFeatureDeleted(
                  id = id,
                  project = project,
                  tenant = tenant,
                  user = user.username,
                  name = name,
                  authentication = user.authentication,
                  origin = NormalOrigin
                )
              )(conn)
              .map(_ => Right(id))
        }
    )
  }
}

object featureImplicits {
  implicit class FeatureRow(val row: Row) extends AnyVal {
    // TODO deduplicate with below
    def optCompleteFeature(): Option[CompleteFeature] = {
      val tags =
        row
          .optJsArray("tags")
          .map(array => array.value.map(v => v.as[String]).toSet)
          .getOrElse(Set())

      for (
        name <- row.optString("name");
        id <- row.optString("id");
        description <- row.optString("description");
        project <- row.optString("project");
        enabled <- row
          .optBoolean("contextual_enabled")
          .orElse(row.optBoolean("enabled"));
        metadata <- row.optJsObject("metadata");
        resultType <- row
          .optString("result_type")
          .flatMap(str => ResultType.parseResultType(str))
      )
        yield {
          val maybeClassicalConditionsJson = row
            .optJsArray("contextual_conditions")
            .orElse(row.optJsArray("conditions"))

          lazy val maybeLegacyConditions = row
            .optJsObject("contextual_conditions")
            .orElse(row.optJsObject("conditions"))
            .map(v => v.as[LegacyCompatibleCondition])

          lazy val maybeWasmConfig = row
            .optJsObject("contextual_wasm")
            .orElse(row.optJsObject("wasm"))
            .map(jsObject => jsObject.as[WasmConfig](WasmConfig.format))

          (
            maybeClassicalConditionsJson,
            maybeLegacyConditions,
            maybeWasmConfig
          ) match {
            case (Some(classicalConditions), _, _) => {
              resultType match {
                case NumberResult => {
                  val conds = classicalConditions
                    .asOpt[Seq[NumberActivationCondition]](
                      Reads
                        .seq(ActivationCondition.numberActivationConditionRead)
                    )
                    .getOrElse(Seq())
                  Feature(
                    id = id,
                    name = name,
                    project = project,
                    enabled = enabled,
                    metadata = metadata,
                    tags = tags,
                    description = description,
                    resultDescriptor = NumberResultDescriptor(
                      value =
                        row.optString("value").map(v => BigDecimal(v)).get,
                      conditions = conds
                    )
                  )
                }
                case StringResult => {
                  val conds = classicalConditions
                    .asOpt[Seq[StringActivationCondition]](
                      Reads
                        .seq(ActivationCondition.stringActivationConditionRead)
                    )
                    .getOrElse(Seq())
                  Feature(
                    id = id,
                    name = name,
                    project = project,
                    enabled = enabled,
                    metadata = metadata,
                    tags = tags,
                    description = description,
                    resultDescriptor = StringResultDescriptor(
                      value = row.optString("value").get,
                      conditions = conds
                    )
                  )
                }
                case BooleanResult => {
                  val conds = classicalConditions
                    .asOpt[Seq[BooleanActivationCondition]](
                      Reads
                        .seq(ActivationCondition.booleanActivationConditionRead)
                    )
                    .getOrElse(Seq())
                  Feature(
                    id = id,
                    name = name,
                    project = project,
                    enabled = enabled,
                    metadata = metadata,
                    tags = tags,
                    description = description,
                    resultDescriptor = BooleanResultDescriptor(
                      conditions = conds
                    )
                  )
                }
              }

            }
            case (_, Some(legacyCompatibleCondition), _) => {
              SingleConditionFeature(
                id = id,
                name = name,
                project = project,
                enabled = enabled,
                condition = legacyCompatibleCondition,
                metadata = metadata,
                tags = tags,
                description = description
              )
            }
            case (_, _, Some(wasmConfig)) => {
              CompleteWasmFeature(
                id = id,
                name = name,
                project = project,
                enabled = enabled,
                wasmConfig = wasmConfig,
                metadata = metadata,
                tags = tags,
                description = description,
                resultType = resultType
              )
            }
            case _ => throw new RuntimeException("Failed to read feature " + id)
          }
        }
    }

    def optFeature(): Option[LightWeightFeature] = {
      val tags =
        row
          .optJsArray("tags")
          .map(array => array.value.map(v => v.as[String]).toSet)
          .getOrElse(Set())
      lazy val maybeWasmName = row.optString("config")

      for (
        name <- row.optString("name");
        id <- row.optString("id");
        description <- row.optString("description");
        project <- row.optString("project");
        enabled <- row
          .optBoolean("contextual_enabled")
          .orElse(row.optBoolean("enabled"));
        metadata <- row.optJsObject("metadata");
        resultType <- row
          .optString("result_type")
          .flatMap(str => ResultType.parseResultType(str))
      )
        yield {
          val maybeClassicalConditionsJson = row
            .optJsArray("contextual_conditions")
            .orElse(row.optJsArray("conditions"))

          lazy val maybeLegacyConditions = row
            .optJsObject("contextual_conditions")
            .orElse(row.optJsObject("conditions"))
            .map(v => v.as[LegacyCompatibleCondition])
          (
            maybeClassicalConditionsJson,
            maybeLegacyConditions,
            maybeWasmName
          ) match {
            case (Some(classicalConditions), _, _) => {
              resultType match {
                case NumberResult => {
                  val conds = classicalConditions
                    .asOpt[Seq[NumberActivationCondition]](
                      Reads
                        .seq(ActivationCondition.numberActivationConditionRead)
                    )
                    .getOrElse(Seq())
                  Feature(
                    id = id,
                    name = name,
                    project = project,
                    enabled = enabled,
                    metadata = metadata,
                    tags = tags,
                    description = description,
                    resultDescriptor = NumberResultDescriptor(
                      value =
                        row.optString("value").map(v => BigDecimal(v)).get,
                      conditions = conds
                    )
                  )
                }
                case StringResult => {
                  val conds = classicalConditions
                    .asOpt[Seq[StringActivationCondition]](
                      Reads
                        .seq(ActivationCondition.stringActivationConditionRead)
                    )
                    .getOrElse(Seq())
                  Feature(
                    id = id,
                    name = name,
                    project = project,
                    enabled = enabled,
                    metadata = metadata,
                    tags = tags,
                    description = description,
                    resultDescriptor = StringResultDescriptor(
                      value = row.optString("value").get,
                      conditions = conds
                    )
                  )
                }
                case BooleanResult => {
                  val conds = classicalConditions
                    .asOpt[Seq[BooleanActivationCondition]](
                      Reads
                        .seq(ActivationCondition.booleanActivationConditionRead)
                    )
                    .getOrElse(Seq())
                  Feature(
                    id = id,
                    name = name,
                    project = project,
                    enabled = enabled,
                    metadata = metadata,
                    tags = tags,
                    description = description,
                    resultDescriptor = BooleanResultDescriptor(
                      conditions = conds
                    )
                  )
                }
              }
            }
            case (_, Some(legacyCompatibleCondition), _) => {
              SingleConditionFeature(
                id = id,
                name = name,
                project = project,
                enabled = enabled,
                condition = legacyCompatibleCondition,
                metadata = metadata,
                tags = tags,
                description = description
              )
            }
            case (_, _, Some(wasmConfig)) => {
              LightWeightWasmFeature(
                id = id,
                name = name,
                project = project,
                enabled = enabled,
                wasmConfigName = wasmConfig,
                metadata = metadata,
                tags = tags,
                description = description,
                resultType = resultType
              )
            }
            case _ => throw new RuntimeException("Failed to read feature " + id)
          }
        }
    }
  }
}
