package fr.maif.izanami

import com.typesafe.config.{Config, ConfigValueFactory, ConfigValueType}
import play.api.libs.json.Json

import java.util.Collections
import scala.jdk.CollectionConverters.*
import scala.util.Try

object ConfigUtil {
  def fixIzanamiConfigIfNeeded(config: Config): Config = {
    Seq("app.cluster.context-blocklist", "app.cluster.context-allowlist").foldLeft(config)((config, path) => {
      fixStringArrayifNeeded(config, path)
    })
  }


  def fixStringArrayifNeeded(config: Config, path: String): Config = {
    Try{
      val value = config.getValue(path)
      if (value.valueType() == ConfigValueType.STRING) {
        val newValue = Json.parse(value.unwrapped().asInstanceOf[String]).asOpt[Seq[String]].map(_.asJava).getOrElse(Collections.emptyList())
        config.withValue(path, ConfigValueFactory.fromIterable(newValue))
      } else {
        config
      }
    }.getOrElse(config)

  }
}
