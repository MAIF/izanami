package env

import com.typesafe.config.{ConfigList, ConfigRenderOptions}
import play.api.Configuration
import pureconfig._

object AppConfig {

  import pureconfig.ConfigConvert.viaString
  import pureconfig.ConvertHelpers.catchReadError
  import pureconfig.generic.auto._

  implicit val fallbackConfigHint: ConfigReader[FallbackConfig] = ConfigReader[ConfigList].map { l =>
    FallbackConfig(l.render(ConfigRenderOptions.concise()))
  }

  implicit val dbTypeHint: ConfigConvert[IzanamiMode] =
    viaString[IzanamiMode](catchReadError(IzanamiMode.fromString), _.toString)

  def apply(configuration: Configuration): AppConfig =
    ConfigSource.fromConfig(configuration.underlying).at("tvdb").loadOrThrow[AppConfig]
}

sealed trait IzanamiMode
case object IzanamiDev  extends IzanamiMode
case object IzanamiProd extends IzanamiMode

object IzanamiMode {
  def fromString(s: String) = s match {
    case "dev"  => IzanamiDev
    case "prod" => IzanamiProd
    case _      => throw new IllegalArgumentException(s"Unexpected string $s from IzanamiMode, should be dev or prod")
  }
}

case class AppConfig(
    izanami: IzanamiConf,
    otoroshi: OtoroshiFilterConfig,
    betaSerie: BetaSerieConfig,
    tvdb: TvdbConfig,
    dbpath: String,
    front: String
)

case class IzanamiConf(
    host: String,
    mode: IzanamiMode,
    clientId: Option[String],
    clientSecret: Option[String],
    clientIdHeaderName: Option[String],
    clientSecretHeaderName: Option[String],
    fallback: IzanamiFallbackConf
)

case class IzanamiFallbackConf(
    features: FallbackConfig,
    configs: FallbackConfig,
    experiments: FallbackConfig
)

case class FallbackConfig(conf: String) extends AnyVal

case class BetaSerieConfig(url: String, apiKey: String)
case class TvdbConfig(url: String, apiKey: String, baseUrl: String)

case class OtoroshiFilterConfig(
    enabled: Boolean,
    mode: String,
    sharedKey: String,
    issuer: String,
    headerClaim: String,
    headerRequestId: String,
    headerGatewayState: String,
    headerGatewayStateResp: String
)
