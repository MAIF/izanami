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
import fr.maif.izanami.web.ExportController.ExportRequest
import play.api.libs.json.{JsArray, JsValue}

import scala.concurrent.Future

class ExportDatastore(val env: Env) extends Datastore {
  def readFeatures(tenant: String): Future[(Set[LightWeightFeature], Set[String], Set[String])] = {
    env.postgresql
      .queryAll(
        s"""
         |SELECT f.*, wsc.id as wasm_id,  json_arrayagg(ft.tag) as tags
         |FROM features f
         |LEFT OUTER JOIN wasm_script_configurations wsc ON f.script_config = wsc.id
         |LEFT OUTER JOIN features_tags ft ON ft.feature = f.id
         |GROUP by f.id, wsc.id
         |""".stripMargin,
        schemas = Set(tenant)
      ) { r =>
        {
          (r.optFeature().map(f => (f, r.optString("wasm_id"), r.optJsArray("tags").getOrElse(JsArray.empty))))
        }
      }
      .map(l => {
        val (features, maybeScriptIds, tags) = l.unzip3
        (
          features.toSet,
          maybeScriptIds.collect { case Some(id) =>
            id
          }.toSet,
          tags.flatMap(jsArray => jsArray.value.map(js => js.as[String])).toSet
        )
      })
  }

  def readTags(tenant: String, names: Set[String]): Future[List[Tag]] = {
    env.postgresql.queryAll(
      s"""
         |SELECT *
         |FROM tags
         |WHERE name=ANY($$1)
         |""".stripMargin,
      List(names.toArray),
      schemas = Set(tenant)
    ) { row => row.optTag() }
  }

  def readWasmConfigs(tenant: String, ids: Set[String]): Future[List[WasmConfig]] = {
    env.postgresql.queryAll(s"""
         |SELECT config
         |FROM wasm_script_configurations
         |WHERE id=ANY(
         |""".stripMargin) { r => r.optJsObject("config").map(js => js.as(WasmConfig.format)) }
  }

  def readProjects(tenant: String, names: Set[String]): Future[List[Project]] = {
    env.postgresql.queryAll(
      s"""
         |SELECT id, name, description
         |FROM projects
         |WHERE name=ANY($$1)
         |""".stripMargin,
      List(names.toArray),
      schemas = Set(tenant)
    ) { r => r.optProject() }
  }

  def readKeys(tenant: String, names: Set[String]): Future[List[ApiKey]] = {
    env.postgresql.queryAll(
      s"""
         |SELECT *
         |FROM apikeys
         |WHERE name=ANY($$1)
         |""".stripMargin,
      List(names.toArray),
      schemas = Set(tenant)
    ) { r => r.optApiKey(tenant) }
  }

}
