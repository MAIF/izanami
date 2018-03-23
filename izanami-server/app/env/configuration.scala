package env

import java.net.{InetAddress, InetSocketAddress}

import domains.AuthorizedPattern
import play.api.Configuration
import pureconfig._

import scala.concurrent.duration.FiniteDuration

object DbType {
  val cassandra = "Cassandra"
  val redis     = "Redis"
  val levelDB   = "LevelDB"
  val inMemory  = "InMemory"
  val elastic   = "Elastic"
}

object EventStoreType {
  val redis       = "Redis"
  val inMemory    = "InMemory"
  val distributed = "Distributed"
  val kafka       = "Kafka"
}

object IzanamiConfig {
  import pureconfig.ConfigConvert.viaString
  import pureconfig.ConvertHelpers.catchReadError

  implicit def hint[T] =
    ProductHint[T](ConfigFieldMapping(CamelCase, CamelCase))
  implicit val filterConfHint = new FieldCoproductHint[IzanamiFilter]("type") {
    override def fieldValue(name: String) = name
  }
  implicit val storeConfHint = new FieldCoproductHint[EventsConfig]("store") {
    override def fieldValue(name: String) = name.dropRight("Events".length)
  }

  implicit val inetAddressCC: ConfigConvert[InetAddress] =
    viaString[InetAddress](catchReadError(InetAddress.getByName), _.getHostAddress)
  implicit val inetSocketAddressCC: ConfigConvert[InetSocketAddress] =
    viaString[InetSocketAddress](
      catchReadError { str =>
        val splited = str.split(":")
        InetSocketAddress.createUnresolved(splited(0), splited(1).toInt)
      },
      addr => s"${addr.getHostString}:${addr.getPort}"
    )

  def apply(configuration: Configuration): IzanamiConfig =
    loadConfigOrThrow[IzanamiConfig](configuration.underlying, "izanami")
}

case class IzanamiConfig(
    mode: Option[String],
    contextPath: String,
    filter: IzanamiFilter,
    db: DbConfig,
    logout: LogoutConfig,
    config: ConfigConfig,
    features: FeaturesConfig,
    globalScript: GlobalScriptConfig,
    experiment: ExperimentConfig,
    variantBinding: VariantBindingConfig,
    experimentEvent: ExperimentEventConfig,
    webhook: WebhookConfig,
    user: UserConfig,
    apikey: ApikeyConfig,
    events: EventsConfig,
    patch: PatchConfig
)

//case class Claim(sharedKey: String, header: String, headerClientId: String, headerClientSecret: String)
case class LogoutConfig(url: String)
case class ApiKeyHeaders(headerClientId: String, headerClientSecret: String)
case class OtoroshiFilterConfig(sharedKey: String,
                                issuer: String,
                                headerClaim: String,
                                headerRequestId: String,
                                headerGatewayState: String,
                                headerGatewayStateResp: String)
case class DefaultFilter(allowedPaths: Seq[String],
                         issuer: String,
                         sharedKey: String,
                         cookieClaim: String,
                         apiKeys: ApiKeyHeaders)

sealed trait IzanamiFilter
case class Otoroshi(otoroshi: OtoroshiFilterConfig) extends IzanamiFilter
case class Default(default: DefaultFilter)          extends IzanamiFilter

case class ConfigConfig(db: DbDomainConfig)
case class FeaturesConfig(db: DbDomainConfig)
case class GlobalScriptConfig(db: DbDomainConfig)
case class ExperimentConfig(db: DbDomainConfig)
case class VariantBindingConfig(db: DbDomainConfig)
case class ExperimentEventConfig(db: DbDomainConfig)
case class WebhookConfig(db: DbDomainConfig, events: WebhookEventsConfig)
case class WebhookEventsConfig(group: Int, within: FiniteDuration, nbMaxErrors: Int, errorReset: FiniteDuration)
case class UserConfig(db: DbDomainConfig, initialize: InitialUserConfig)
case class InitializeApiKey(clientId: Option[String], clientSecret: Option[String], authorizedPatterns: String)
case class ApikeyConfig(db: DbDomainConfig, initialize: InitializeApiKey) {

  def keys: Option[domains.apikey.Apikey] =
    for {
      id     <- initialize.clientId
      secret <- initialize.clientSecret
    } yield domains.apikey.Apikey(id, "", secret, AuthorizedPattern(initialize.authorizedPatterns))
}
case class PatchConfig(db: DbDomainConfig)

sealed trait EventsConfig
case class InMemoryEvents(inmemory: InMemoryEventsConfig)          extends EventsConfig
case class DistributedEvents(distributed: DistributedEventsConfig) extends EventsConfig
case class RedisEvents(redis: RedisEventsConfig)                   extends EventsConfig
case class KafkaEvents(kafka: KafkaEventsConfig)                   extends EventsConfig

case class InMemoryEventsConfig()
case class DistributedEventsConfig(topic: String)
case class RedisEventsConfig(topic: String)
case class KafkaEventsConfig(topic: String)

case class DbConfig(
    default: String,
    redis: Option[RedisConfig],
    leveldb: Option[LevelDbConfig],
    cassandra: Option[CassandraConfig],
    kafka: Option[KafkaConfig],
    elastic: Option[ElasticConfig]
)

case class RedisConfig(
    host: String,
    port: Int,
    password: Option[String],
    slaves: Option[Seq[RedisConfig]] = None,
    databaseId: Option[Int] = None
)
case class LevelDbConfig(parentPath: String)

case class CassandraConfig(addresses: Seq[String],
                           clusterName: Option[String],
                           replicationFactor: Int,
                           keyspace: String,
                           username: Option[String] = None,
                           password: Option[String] = None)

case class KafkaConfig(servers: String, keyPass: Option[String], keystore: Location, truststore: Location)

case class Location(location: Option[String])

case class ElasticConfig(host: String,
                         port: Int,
                         scheme: String,
                         user: Option[String],
                         password: Option[String],
                         automaticRefresh: Boolean = false)

case class DbDomainConfig(`type`: String, conf: DbDomainConfigDetails)
case class InitialUserConfig(userId: String, password: String)
case class DbDomainConfigDetails(namespace: String)
