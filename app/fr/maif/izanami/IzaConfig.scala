package fr.maif.izanami

case class IzanamiTypedConfiguration(app: AppConf, play: PlayRoot)

case class AppConf(
    secret: String,
    defaultSecret: String,
    containerized: Boolean,
    experimental: Experimental,
    reporting: Reporting,
    audit: Audit,
    webhooks: Webhooks,
    wasm: Wasm,
    admin: Admin,
    exposition: Exposition,
    openid: OpenId,
    wasmo: Wasmo,
    pg: Pg,
    authentication: Authentication,
    invitations: Invitations,
    sessions: Sessions,
    passwordResetRequests: PasswordResetRequests,
    search: Search,
    feature: Feature,
    housekeeping: Housekeeping
)

case class Experimental(staleTracking: StaleTracking)
case class StaleTracking(enabled: Boolean)
case class Reporting(url: String)
case class Audit(eventsHoursTtl: Int)
case class Webhooks(retry: Retry)
case class Retry(count: Int, intialDelay: Long, maxDelay: Long, multiplier: Int)
case class Wasm(cache: Cache, queue: WasmQueue)
case class Cache(ttl: Long)
case class WasmQueue(buffer: WasmQueueBuffer)
case class WasmQueueBuffer(size: Int)
case class Admin(username: String, password: Option[String])
case class Exposition(url: Option[String], backend: Option[String])
case class OpenId(
    method: String,
    enabled: Boolean,
    clientId: Option[String],
    clientSecret: Option[String],
    authorizeUrl: Option[String],
    tokenUrl: Option[String],
    redirectUrl: Option[String],
    callbackUrl: Option[String], // legacy, here for compat reasons
    scopes: String,
    emailField: String,
    usernameField: String,
    pkce: OpenIdPkce
)
case class OpenIdPkce(enabled: Boolean, algorithm: Option[String])
case class Wasmo(url: Option[String], clientId: Option[String], clientSecret: Option[String])
case class Pg(
    uri: Option[String],
    poolSize: Int,
    port: Int,
    host: String,
    password: String,
    database: Option[String],
    username: Option[String],
    user: Option[String],        // legacy
    connectTimeout: Option[Int],
    idleTimeout: Option[Int],
    maxLifetime: Option[Int],
    logActivity: Option[Boolean],
    pipeliningLimit: Option[Int],
    extensionsSchema: String,
    ssl: Ssl
)
case class Ssl(
    enabled: Boolean,
    mode: String,
    trustedCertsPath: List[String],
    trustedCerts: List[String],
    clientCertsPath: List[String],
    clientCerts: List[String],
    clientCertPath: Option[String],
    clientCert: Option[String],
    trustedCert: Option[String],
    trustedCertPath: Option[String],
    trustAll: Option[Boolean],
    sslHandshakeTimeout: Option[Int]
)
case class Authentication(secret: String, tokenBodySecret: String)
case class Invitations(ttl: Int)
case class Sessions(ttl: Int)
case class PasswordResetRequests(ttl: Int)
case class Search(similarityThreshold: Double)
case class Feature(callRecords: CallRecords, staleHoursDelay: Long)
case class CallRecords(callRegisterIntervalInSeconds: Long, callRetentionTimeInHours: Long)
case class Housekeeping(startDelayInSeconds: Long, intervalInSeconds: Long)

case class PlayRoot(server: PlayServer)
case class PlayServer(http: PlayHttpConf)
case class PlayHttpConf(port: Long)
