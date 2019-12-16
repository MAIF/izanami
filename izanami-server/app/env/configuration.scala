package env

import java.net.{InetAddress, InetSocketAddress}
import java.nio.file.{Path, Paths}

import com.nimbusds.jose.jwk.{ECKey, JWK, KeyType, RSAKey}
import domains.AuthorizedPattern
import play.api.Configuration
import play.api.libs.ws.WSProxyServer
import pureconfig._

import scala.concurrent.duration.FiniteDuration

sealed trait DbType
case object Cassandra      extends DbType with Product with Serializable
case object Redis          extends DbType with Product with Serializable
case object LevelDB        extends DbType with Product with Serializable
case object InMemory       extends DbType with Product with Serializable
case object Elastic        extends DbType with Product with Serializable
case object Mongo          extends DbType with Product with Serializable
case object InMemoryWithDb extends DbType with Product with Serializable
case object Dynamo         extends DbType with Product with Serializable
case object Postgresql     extends DbType with Product with Serializable

object DbType {
  def fromString(s: String): DbType = s match {
    case "Cassandra"      => Cassandra
    case "Redis"          => Redis
    case "LevelDB"        => LevelDB
    case "InMemory"       => InMemory
    case "Elastic"        => Elastic
    case "Mongo"          => Mongo
    case "InMemoryWithDb" => InMemoryWithDb
    case "Dynamo"         => Dynamo
    case "Postgresql"     => Postgresql
  }
}

object EventStoreType {
  val redis       = "Redis"
  val inMemory    = "InMemory"
  val distributed = "Distributed"
  val kafka       = "Kafka"
}

object IzanamiConfig {
  import pureconfig.generic.ProductHint
  import pureconfig.generic.FieldCoproductHint
  import pureconfig.generic.auto._
  import pureconfig.ConfigConvert.viaString
  import pureconfig.ConvertHelpers.catchReadError

  private implicit def hint[T]: ProductHint[T] =
    ProductHint[T](ConfigFieldMapping(CamelCase, CamelCase))

  private implicit val filterConfHint: FieldCoproductHint[IzanamiFilter] =
    new FieldCoproductHint[IzanamiFilter]("type") {
      override def fieldValue(name: String): String = name
    }

  private implicit val storeConfHint: FieldCoproductHint[EventsConfig] = new FieldCoproductHint[EventsConfig]("store") {
    override def fieldValue(name: String): String = name.dropRight("Events".length)
  }

  implicit val keyTypeHint: ConfigConvert[KeyType] =
    viaString[KeyType](catchReadError { KeyType.parse }, _.getValue)

  implicit val dbTypeHint: ConfigConvert[DbType] =
    viaString[DbType](catchReadError { DbType.fromString }, _.toString)

  implicit val inetAddressCC: ConfigConvert[InetAddress] =
    viaString[InetAddress](catchReadError(InetAddress.getByName), _.getHostAddress)

  implicit val path: ConfigConvert[Path] =
    viaString[Path](catchReadError(str => Paths.get(str)), _.toAbsolutePath.toString)

  implicit val inetSocketAddressCC: ConfigConvert[InetSocketAddress] =
    viaString[InetSocketAddress](
      catchReadError { str =>
        val splited = str.split(":")
        InetSocketAddress.createUnresolved(splited(0), splited(1).toInt)
      },
      addr => s"${addr.getHostString}:${addr.getPort}"
    )

  def apply(configuration: Configuration): IzanamiConfig =
    ConfigSource.fromConfig(configuration.underlying).at("izanami").loadOrThrow[IzanamiConfig]
}

case class IzanamiConfig(
    mode: Option[String],
    contextPath: String,
    baseURL: String,
    patchEnabled: Boolean,
    confirmationDialog: Boolean,
    headerHost: String,
    filter: IzanamiFilter,
    oauth2: Option[Oauth2Config],
    db: DbConfig,
    logout: LogoutConfig,
    config: ConfigConfig,
    features: FeaturesConfig,
    globalScript: GlobalScriptConfig,
    experiment: ExperimentConfig,
    experimentEvent: ExperimentEventConfig,
    webhook: WebhookConfig,
    user: UserConfig,
    apikey: ApikeyConfig,
    events: EventsConfig,
    patch: PatchConfig,
    metrics: MetricsConfig
)

case class MetricsConfig(
    verbose: Boolean,
    includeCount: Boolean,
    refresh: FiniteDuration,
    console: MetricsConsoleConfig,
    log: MetricsLogConfig,
    http: MetricsHttpConfig,
    kafka: MetricsKafkaConfig,
    elastic: MetricsElasticConfig
)
case class MetricsConsoleConfig(enabled: Boolean, interval: FiniteDuration)
case class MetricsLogConfig(enabled: Boolean, interval: FiniteDuration)
case class MetricsHttpConfig(defaultFormat: String)
case class MetricsKafkaConfig(enabled: Boolean, topic: String, format: String, pushInterval: FiniteDuration)
case class MetricsElasticConfig(enabled: Boolean, index: String, pushInterval: FiniteDuration)

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

sealed trait AlgoSettingsConfig
case class HS(size: Int, secret: String)                                 extends AlgoSettingsConfig
case class ES(size: Int, publicKey: String, privateKey: Option[String])  extends AlgoSettingsConfig
case class RSA(size: Int, publicKey: String, privateKey: Option[String]) extends AlgoSettingsConfig
case class JWKS(url: String, headers: Option[Map[String, String]], timeout: Option[FiniteDuration])
    extends AlgoSettingsConfig

case class Oauth2Config(authorizeUrl: String,
                        tokenUrl: String,
                        userInfoUrl: String,
                        introspectionUrl: String,
                        loginUrl: String,
                        logoutUrl: String,
                        clientId: String,
                        clientSecret: String,
                        scope: Option[String] = None,
                        claims: String = "email name",
                        accessTokenField: String = "access_token",
                        jwtVerifier: Option[AlgoSettingsConfig],
                        readProfileFromToken: Boolean = false,
                        useCookie: Boolean = true,
                        useJson: Boolean = true,
                        idField: String,
                        nameField: String,
                        emailField: String,
                        adminField: String,
                        authorizedPatternField: String,
                        defaultPatterns: String)

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
    redis: Option[RedisConfig] = None,
    leveldb: Option[LevelDbConfig] = None,
    cassandra: Option[CassandraConfig] = None,
    kafka: Option[KafkaConfig] = None,
    elastic: Option[ElasticConfig] = None,
    mongo: Option[MongoConfig] = None,
    dynamo: Option[DynamoConfig] = None,
    inMemoryWithDb: Option[InMemoryWithDbConfig] = None,
    postgresql: Option[PostgresqlConfig] = None
)

case class InMemoryWithDbConfig(db: DbType, pollingInterval: Option[FiniteDuration] = None)

sealed trait RedisConfig
case class Master(
    host: String,
    port: Int,
    poolSize: Int,
    password: Option[String] = None,
    databaseId: Option[Int] = None
) extends RedisConfig

case class Sentinel(
    host: String,
    port: Int,
    poolSize: Int,
    masterId: String,
    password: Option[String],
    sentinels: Option[Seq[RedisOneSentinelConfig]] = None,
    databaseId: Option[Int] = None
) extends RedisConfig

case class RedisOneSentinelConfig(host: String, port: Int)

case class LevelDbConfig(parentPath: String)

case class CassandraConfig(addresses: Seq[String],
                           clusterName: Option[String],
                           replicationFactor: Int,
                           keyspace: String,
                           username: Option[String] = None,
                           password: Option[String] = None)

case class DynamoConfig(tableName: String,
                        eventsTableName: String,
                        region: String,
                        host: String,
                        port: Int,
                        tls: Boolean = true,
                        parallelism: Int = 32,
                        accessKey: Option[String] = None,
                        secretKey: Option[String] = None)

case class KafkaConfig(servers: String, keyPass: Option[String], keystore: Location, truststore: Location)

case class Location(location: Option[String])

case class ElasticConfig(host: String,
                         port: Int,
                         scheme: String,
                         user: Option[String],
                         password: Option[String],
                         automaticRefresh: Boolean = false)

case class MongoConfig(url: String, database: Option[String], name: Option[String])

case class PostgresqlConfig(driver: String,
                            url: String,
                            username: String,
                            password: String,
                            connectionPoolSize: Int,
                            tmpfolder: Option[String])

case class DbDomainConfig(`type`: DbType, conf: DbDomainConfigDetails, `import`: Option[Path])
case class InitialUserConfig(userId: String, password: String)
case class DbDomainConfigDetails(namespace: String, db: Option[DbDomainConfig])
