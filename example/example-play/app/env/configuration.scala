package env

import play.api.Configuration
import play.api.libs.json.JsObject
import pureconfig._

object AppConfig {

  def apply(configuration: Configuration): AppConfig =
    loadConfigOrThrow[AppConfig](configuration.underlying, "tvdb")
}

case class AppConfig(izanami: IzanamiConf, betaSerie: BetaSerieConfig)

case class IzanamiConf(
    host: String,
    clientId: Option[String],
    clientSecret: Option[String],
    fallback: IzanamiFallbackConf
)

case class IzanamiFallbackConf(
    features: String,
    configs: String,
    experiments: String
)

case class BetaSerieConfig(url: String, apiKey: String)
