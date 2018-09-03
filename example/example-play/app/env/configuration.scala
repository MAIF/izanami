package env

import play.api.Configuration
import play.api.libs.json.JsObject
import pureconfig._

object AppConfig {

  def apply(configuration: Configuration): AppConfig =
    loadConfigOrThrow[AppConfig](configuration.underlying, "tvdb")
}

case class AppConfig(izanami: IzanamiConf,
                     otoroshi: OtoroshiFilterConfig,
                     betaSerie: BetaSerieConfig,
                     tvdb: TvdbConfig,
                     dbpath: String,
                     front: String)

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
case class TvdbConfig(url: String, apiKey: String, baseUrl: String)

case class OtoroshiFilterConfig(enabled: Boolean,
                                mode: String,
                                sharedKey: String,
                                issuer: String,
                                headerClaim: String,
                                headerRequestId: String,
                                headerGatewayState: String,
                                headerGatewayStateResp: String)
