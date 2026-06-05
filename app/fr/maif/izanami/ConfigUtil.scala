package fr.maif.izanami

import com.typesafe.config.Config
import com.typesafe.config.ConfigValueFactory
import com.typesafe.config.ConfigValueType
import play.api.libs.json.Json

import java.util.Collections
import scala.jdk.CollectionConverters.*
import scala.util.Try

object ConfigUtil {
  def fixIzanamiConfigIfNeeded(config: Config): Config = {
    var fixedConfig = Seq(
      "app.cluster.context-blocklist",
      "app.cluster.context-allowlist"
    ).foldLeft(config)((config, path) => {
      fixStringArrayifNeeded(config, path)
    })

    fixedConfig = fixMapIfNeeded(config = fixedConfig, path = "app.cluster.worker-url-by-contexts")

    fixedConfig = fixContextAllowListOrBlockListBytenantIfNeeded(config = fixedConfig, path = "context-allowlist-by-tenant")
    fixContextAllowListOrBlockListBytenantIfNeeded(config = fixedConfig, path = "context-blocklist-by-tenant")
  }

  def fixContextAllowListOrBlockListBytenantIfNeeded(config: Config, path: String): Config = {
    Try {
      val value = config.getValue(path);
      
      if (value.valueType() == ConfigValueType.STRING) {
        val newValue = Json.parse(
          value.unwrapped().asInstanceOf[String]
        ).asOpt[Map[
          String,
          Seq[String]
        ]].map(scalaMap => {
          scalaMap.view.mapValues(seq => seq.asJava).toMap.asJava
        }).getOrElse(java.util.Map.of());

        config.withValue(path, ConfigValueFactory.fromMap(newValue))
      } else {
        config
      }
    }.getOrElse(config)
  }

  def fixMapIfNeeded(config: Config, path: String): Config = {
    Try {
      val value = config.getValue(path);
      
      if (value.valueType() == ConfigValueType.STRING) {
        val newValue = Json.parse(
          value.unwrapped().asInstanceOf[String]
        ).asOpt[Map[
          String,
          Map[String, String]
        ]].map(scalaMap => {
          scalaMap.asJava
        }).getOrElse(java.util.Map.of());

        config.withValue(path, ConfigValueFactory.fromMap(newValue))
      } else {
        config
      }
    }.getOrElse(config)
  }

  def fixStringArrayifNeeded(config: Config, path: String): Config = {
    Try {
      val value = config.getValue(path)
      if (value.valueType() == ConfigValueType.STRING) {
        val newValue = Json.parse(
          value.unwrapped().asInstanceOf[String]
        ).asOpt[Seq[String]].map(_.asJava).getOrElse(Collections.emptyList())
        config.withValue(path, ConfigValueFactory.fromIterable(newValue))
      } else {
        config
      }
    }.getOrElse(config)

  }
}
