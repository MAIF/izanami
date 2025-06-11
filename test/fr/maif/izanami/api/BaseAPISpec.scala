package fr.maif.izanami.api

import akka.NotUsed
import akka.actor.ActorSystem
import akka.http.scaladsl.Http
import akka.http.scaladsl.model.{HttpRequest, HttpResponse, Uri}
import akka.http.scaladsl.model.headers.RawHeader
import akka.http.scaladsl.model.sse.ServerSentEvent
import akka.stream.{KillSwitches, Materializer, ThrottleMode, UniqueKillSwitch}
import akka.stream.alpakka.sse.scaladsl.EventSource
import akka.stream.scaladsl.{FileIO, Keep, Sink, Source}
import akka.util.Timeout
import com.github.tomakehurst.wiremock.WireMockServer
import com.github.tomakehurst.wiremock.client.WireMock
import com.github.tomakehurst.wiremock.client.WireMock.{aResponse, configure}
import com.github.tomakehurst.wiremock.core.{Container, WireMockConfiguration}
import com.github.tomakehurst.wiremock.http.{HttpHeaders, Request}
import com.typesafe.config.{ConfigFactory, ConfigValueFactory}
import fr.maif.izanami.IzanamiLoader
import fr.maif.izanami.api.BaseAPISpec.{
  cleanUpDB,
  eventKillSwitch,
  isAvailable,
  izanamiInstance,
  login,
  maybeContainers,
  shouldCleanUpEvents,
  shouldCleanUpMails,
  shouldCleanUpWasmServer,
  shouldRestartInstance,
  startContainers,
  webhookServers,
  ws
}
import fr.maif.izanami.utils.syntax.implicits.BetterSyntax
import fr.maif.izanami.utils.{WasmManagerClient, WiremockResponseDefinitionTransformer}
import org.awaitility.scala.AwaitilitySupport
import org.postgresql.util.PSQLException
import org.scalatest.{BeforeAndAfterAll, BeforeAndAfterEach}
import org.scalatestplus.play.PlaySpec
import org.testcontainers.containers.DockerComposeContainer
import play.api.ApplicationLoader.Context
import play.api.inject.DefaultApplicationLifecycle
import play.api.{Application, Configuration, Environment, Mode}
import play.api.libs.json._
import play.api.libs.ws.ahc.{AhcWSClient, StandaloneAhcWSClient}
import play.api.libs.ws.{WSAuthScheme, WSClient, WSCookie, WSResponse}
import play.api.test.Helpers.{await, OK}
import play.api.mvc.MultipartFormData.FilePart
import play.api.test.{DefaultAwaitTimeout, DefaultTestServerFactory, RunningServer}
import play.core.server.ServerConfig

import java.io.{BufferedWriter, File, FileWriter, IOException}
import java.net.Socket
import java.nio.charset.StandardCharsets
import java.nio.file.{Files, Paths}
import java.sql.{Connection, DriverManager}
import java.time._
import java.time.format.DateTimeFormatter
import java.util.{Base64, Objects, TimeZone}
import scala.collection.concurrent.TrieMap
import scala.collection.mutable
import scala.collection.mutable.ArrayBuffer
import scala.concurrent.ExecutionContext.Implicits.global
import scala.concurrent.{ExecutionContext, Future, Promise}
import scala.concurrent.duration.{DurationInt, SECONDS}
import scala.util.Try

case class StubServer(server: WireMockServer, extension: WiremockResponseDefinitionTransformer)

class BaseAPISpec
    extends PlaySpec
    with BeforeAndAfterEach
    with BeforeAndAfterAll
    with DefaultAwaitTimeout
    //with IzanamiServerTest
    with AwaitilitySupport {
  override implicit def defaultAwaitTimeout: Timeout = 30.seconds

  private var mailjetMockServer: WireMockServer                       = null
  private var mailjetExtension: WiremockResponseDefinitionTransformer = null

  private var mailgunMockServer: WireMockServer                       = null
  private var mailgunExtension: WiremockResponseDefinitionTransformer = null

  def mailjetRequests(): Seq[(Request, HttpHeaders)] = mailjetExtension.requests.toSeq

  def mailgunRequests(): Seq[(Request, HttpHeaders)] = mailgunExtension.requests.toSeq

  override def beforeAll(): Unit = {
    mailjetExtension = new WiremockResponseDefinitionTransformer()
    mailjetMockServer = new WireMockServer(WireMockConfiguration.options().extensions(mailjetExtension).port(9998))
    mailjetMockServer.stubFor(
      WireMock.post("/v3.1/send")
    )
    mailjetMockServer.start()

    mailgunExtension = new WiremockResponseDefinitionTransformer()
    mailgunMockServer = new WireMockServer(WireMockConfiguration.options().extensions(mailgunExtension).port(9997))
    mailgunMockServer.stubFor(
      WireMock.post("/baz.com/messages")
    )
    mailgunMockServer.start()
  }

  override def beforeEach(): Unit = {
    if (isAvailable(5432) && maybeContainers.isEmpty) {
      startContainers()
    }

    var futures: Seq[Future[Any]] = Seq()

    if (shouldCleanUpWasmServer) {
      futures = futures.appended(clearWasmServer())
      shouldCleanUpWasmServer = false
    }

    if (shouldCleanUpMails) {
      futures = futures.appended(ws.url("http://localhost:1080/api/emails").delete())
      mailjetMockServer.stubFor(
        WireMock.post("/v3.1/send")
      )
      mailgunMockServer.stubFor(
        WireMock.post("/baz.com/messages")
      )
      shouldCleanUpMails = false
    }

    /*if (shouldCleanUpEvents) {
      futures = futures.appended(cleanEvents)
      shouldCleanUpEvents = false
    }*/

    if (izanamiInstance != null) {
      val baseFuture = if (shouldCleanUpEvents) cleanEvents else Future.successful()

      val res = baseFuture.map(_ => {
        if (shouldRestartInstance) {
          shouldRestartInstance = false
          println("Izanami instance is running from previous test, stopping it...")
          BaseAPISpec.stopServer()
          println("Izanami is stopped")
          cleanUpDB(hard = true)
        } else {
          println(
            "An izanami instance is already running, and no custom configuration was provided, no need to shut it down"
          )
          cleanUpDB(hard = false)
        }
      })

      futures = futures.appended(res)
    } else {
      futures = futures.appended(Future { cleanUpDB(hard = true) })
    }

    if (Objects.nonNull(eventKillSwitch)) {
      eventKillSwitch.shutdown()
    }

    await(Future.sequence(futures))
  }

  def cleanEvents: Future[Any] = {
    val res = login("RESERVED_ADMIN_USER", "ADMIN_DEFAULT_PASSWORD")
    ws.url(s"${BaseAPISpec.ADMIN_BASE_URL}/")
      .withCookies(res.cookies: _*)
      .delete()
  }

  def clearWasmServer(): Future[Any] = {
    ws.url("http://localhost:5001/api/plugins")
      .withHttpHeaders("Authorization" -> "Basic YWRtaW4tYXBpLWFwaWtleS1pZDphZG1pbi1hcGktYXBpa2V5LXNlY3JldA==")
      .get()
      .map(response => response.json \\ "pluginId")
      .flatMap(values =>
        Future.sequence(
          values
            .map(js => js.as[String])
            .map(pluginId => ws.url(s"http://localhost:5001/api/plugins/${pluginId}").delete())
        )
      )
  }

  override def afterEach(): Unit = {
    if (shouldCleanUpMails) {
      Option(mailjetMockServer).filter(_.isRunning).foreach(_.resetAll())
      Option(mailjetExtension).foreach(_.reset())

      Option(mailgunMockServer).filter(_.isRunning).foreach(_.resetAll())
      Option(mailgunExtension).foreach(_.reset())
    }

    if (webhookServers.nonEmpty) {
      webhookServers.foreach {
        case (port, StubServer(server, extension)) => {
          println(s"Stopping wiremock on port $port")
          server.stop()
        }
      }
      webhookServers.clear()
    }
  }

  override def afterAll(): Unit = {
    Option(mailjetMockServer).filter(_.isRunning).foreach(_.stop())
    Option(mailgunMockServer).filter(_.isRunning).foreach(_.stop())
  }
}

class IzanamiServerFactory extends DefaultTestServerFactory {
  override def serverConfig(app: Application): ServerConfig = {
    val sc = ServerConfig(port = Some(9000), sslPort = None, mode = Mode.Test, rootDir = app.path)
    sc.copy(configuration = sc.configuration ++ overrideServerConfiguration(app))
  }
}

object BaseAPISpec extends DefaultAwaitTimeout {
  override implicit def defaultAwaitTimeout: Timeout = 30.seconds
  val SCHEMA_TO_KEEP                                 = Set("INFORMATION_SCHEMA", "IZANAMI", "PUBLIC", "PG_CATALOG", "PG_TOAST")
  val BASE_URL                                       = "http://localhost:9000/api"
  val ADMIN_BASE_URL                                 = BASE_URL + "/admin"
  implicit val system                                = ActorSystem()
  implicit val materializer                          = Materializer.apply(system)
  implicit val executor                              = system.dispatcher
  val ws: WSClient                                   = new AhcWSClient(StandaloneAhcWSClient())
  val DATE_TIME_FORMATTER                            = DateTimeFormatter.ISO_INSTANT
  val TIME_FORMATTER                                 = DateTimeFormatter.ISO_TIME
  val TEST_SECRET                                    = "supersafesecret"
  val DEFAULT_OIDC_CONFIG                            = Json.obj(
    "pkce"                  -> JsNull,
    "method"                -> JsString("BASIC"),
    "scopes"                -> JsString("openid email profile"),
    "enabled"               -> JsBoolean(true),
    "clientId"              -> JsString("foo"),
    "tokenUrl"              -> JsString("http://localhost:9001/connect/token"),
    "nameField"             -> JsString("name"),
    "emailField"            -> JsString("email"),
    "callbackUrl"           -> JsString("http://localhost:3000/login"),
    "authorizeUrl"          -> JsString("http://localhost:9001/connect/authorize"),
    "clientSecret"          -> JsString("bar"),
    "defaultOIDCUserRights" -> Json.obj("tenants" -> Json.obj())
  )
  val dbConnectionChain                              = "jdbc:postgresql://localhost:5432/postgres?user=postgres&password=postgres"

  var shouldCleanUpWasmServer                                  = true
  var shouldCleanUpMails                                       = true
  var shouldCleanUpEvents                                      = true
  var shouldRestartInstance                                    = false
  var eventKillSwitch: UniqueKillSwitch                        = null
  var izanamiInstance: RunningServer                           = null
  var maybeContainers: Option[DockerComposeContainer[Nothing]] = None

  val webhookServers: scala.collection.mutable.Map[Int, StubServer] = scala.collection.mutable.Map()

  def isAvailable(port: Int): Boolean = {
    var socket: Option[Socket] = None
    try {
      socket = Some(new Socket("localhost", port))
      false
    } catch {
      case _: IOException => true
    } finally {
      socket.foreach(_.close())
    }
  }

  def stopServer(): Unit = {
    izanamiInstance.stopServer.close()
    izanamiInstance = null

    org.awaitility.Awaitility.await atMost (30, SECONDS) until (() => {
      val res = Try {
        val b = await(ws.url("http://localhost:9000/api/_health").get().map(r => r.status != 200))
        b
      }.getOrElse(true)
      res
    })
  }

  def startContainers(): Unit = {
    val ciEnvVariable = System.getenv("CI");
    val isCi          = Option(ciEnvVariable).map(_.toUpperCase).contains("TRUE")
    if (isAvailable(5432)) {
      println("Port 5432 is available, starting docker-compose for the current suite")
      var containers = new DockerComposeContainer(new File("docker-compose.yml"))
      containers = containers.withLocalCompose(!isCi).asInstanceOf[DockerComposeContainer[Nothing]]

      containers.start()
      maybeContainers = Some(containers)
      val maybeWasmManager = containers.getContainerByServiceName("wasm-manager")

      org.awaitility.Awaitility.await atMost (30, SECONDS) until (() =>
        java.lang.Boolean.valueOf(maybeWasmManager.get.isHealthy)
      )
      BaseAPISpec.shouldCleanUpWasmServer = false
    } else {
      println("Port 5432 is taken, assuming that docker containers are already running")
    }
  }

  def stopContainers(): Unit = {
    try {
      maybeContainers.foreach(_.close())
    } catch { // In case the suite aborts, ensure containers are stopped
      case ex: Throwable => {
        println("Exception was thrown ", ex)
        maybeContainers.foreach(_.close())
        throw ex
      }
    }
  }

  def startServer(customConfig: Map[String, AnyRef]): RunningServer = {
    val configuration: Configuration = Configuration.load(Environment.simple(), Map("config.file" -> "conf/dev.conf"))
    var updatedConfig                = customConfig.foldLeft(configuration.underlying)((conf, entry) => {
      conf.withValue(entry._1, ConfigValueFactory.fromAnyRef(entry._2))
    })

    lazy val application = new IzanamiLoader().load(
      Context(
        environment = Environment.simple(),
        devContext = None,
        lifecycle = new DefaultApplicationLifecycle(),
        initialConfiguration = Configuration(updatedConfig)
      )
    )

    lazy val server = new IzanamiServerFactory()

    println("Starting server")
    val runningServer = server.start(application)
    org.awaitility.Awaitility.await atMost (30, SECONDS) until (() => {
      val b = await(
        ws.url("http://localhost:9000/api/_health")
          .get()
          .map(r => r.status == 200)
      )
      b
    })
    println("Server started")

    runningServer
  }

  def setupWebhookServer(port: Int, responseCode: Int = OK, path: String = "/"): Unit = {
    webhookServers.get(port) match {
      case Some(StubServer(server, _)) => {
        server.resetMappings()
        server.stubFor(
          WireMock.post(path).willReturn(aResponse().withStatus(responseCode))
        )
      }
      case None                        => {
        val extension = new WiremockResponseDefinitionTransformer()
        val server    = new WireMockServer(WireMockConfiguration.options().extensions(extension).port(port))
        server.stubFor(
          WireMock.post(path).willReturn(aResponse().withStatus(responseCode))
        )
        webhookServers.put(port, StubServer(server = server, extension = extension))
        server.start()
      }
    }
  }

  def getWebhookServerRequests(port: Int): mutable.Seq[(Request, HttpHeaders)] = {
    webhookServers(port).extension.requests
  }

  def executeInDatabase(callback: (Connection => Unit)) {
    classOf[org.postgresql.Driver]
    val con_str = dbConnectionChain
    val conn    = DriverManager.getConnection(con_str)

    try {
      callback(conn)
    } finally {
      conn.close()
    }
  }

  def cleanUpDB(hard: Boolean): Unit = {
    classOf[org.postgresql.Driver]
    val con_str = "jdbc:postgresql://localhost:5432/postgres?user=postgres&password=postgres"
    val conn    = DriverManager.getConnection(con_str)
    try {
      val stm    = conn.createStatement()
      val result = stm.executeQuery("SELECT schema_name FROM information_schema.schemata")
      println("Killing connections...")
      conn
        .createStatement()
        .execute(s"""
           |SELECT
           |    pg_terminate_backend(pid)
           |FROM
           |    pg_stat_activity
           |WHERE
           |    -- don't kill my own connection!
           |    pid <> pg_backend_pid()
           |    AND backend_type = 'client backend'
           |    AND ((query LIKE 'LISTEN%'
           |    AND query <> 'LISTEN "izanami"')
           |    ${if (hard) "OR application_name = 'vertx-pg-client'" else ""})
           |""".stripMargin)

      while (result.next()) {
        val schema = result.getString(1)
        if (schema.equals("izanami")) {
          conn.createStatement().execute("DELETE FROM izanami.tenants CASCADE")
          // TODO factorize user name
          conn.createStatement().execute("DELETE FROM izanami.users WHERE username != 'RESERVED_ADMIN_USER'")
          conn.createStatement().execute("TRUNCATE TABLE izanami.users_tenants_rights CASCADE")
          conn.createStatement().execute("TRUNCATE TABLE izanami.invitations CASCADE")
          conn.createStatement().execute("TRUNCATE TABLE izanami.sessions CASCADE")
          conn.createStatement().execute("TRUNCATE TABLE izanami.pending_imports CASCADE")
          conn.createStatement().execute("TRUNCATE TABLE izanami.key_tenant CASCADE")
          conn.createStatement().execute("TRUNCATE TABLE izanami.global_events CASCADE")
          conn.createStatement().execute("TRUNCATE TABLE izanami.personnal_access_tokens CASCADE")
          conn.createStatement().execute("UPDATE izanami.mailers SET configuration='{}'::JSONB")
          conn
            .createStatement()
            .execute("UPDATE izanami.configuration SET mailer='CONSOLE', invitation_mode='RESPONSE'")
        }
        if (!SCHEMA_TO_KEEP.contains(schema.toUpperCase())) {
          try {
            val result = conn.createStatement().executeQuery(s"""DROP SCHEMA "${schema}" CASCADE""")
          } catch {
            case e: PSQLException => // NOT THE RIGHT TO DELETE THIS SCHEMA
            case e: Throwable     => throw e
          }
        }
      }

    } finally {
      conn.close()
    }
  }

  def enabledFeatureBase64 = {
    val arch = System.getProperty("os.arch")
    if (arch == "aarch64") {
      scala.io.Source.fromResource("enabled_script_feature_base64").getLines().mkString("")
    } else {
      scala.io.Source.fromResource("enabled_script_feature_base64_intel").getLines().mkString("")
    }

  }
  def disabledFeatureBase64 = {
    val arch = System.getProperty("os.arch")
    if (arch == "aarch64") {
      scala.io.Source.fromResource("disabled_script_feature_base64").getLines().mkString("")
    } else {
      scala.io.Source.fromResource("disabled_script_feature_base64_intel").getLines().mkString("")
    }
  }

  def createWebhook(tenant: String, webhook: TestWebhook, cookies: Seq[WSCookie] = Seq()) = {
    BaseAPISpec.this.shouldRestartInstance = true
    ws.url(s"${ADMIN_BASE_URL}/tenants/${tenant}/webhooks")
      .withCookies(cookies: _*)
      .post(webhook.toJson)
  }

  def createPersonnalAccessToken(token: TestPersonnalAccessToken, user: String, cookies: Seq[WSCookie] = Seq()) = {
    ws.url(s"${ADMIN_BASE_URL}/users/${user}/tokens")
      .withCookies(cookies: _*)
      .post(token.toJson)
  }

  def updateFeatureCreationDateInDB(tenant: String, feature: String, newDate: LocalDateTime) = {
    val dtf = DateTimeFormatter.ISO_OFFSET_DATE_TIME;
    executeInDatabase(conn => {
      conn
        .createStatement()
        .execute(s"""UPDATE ${tenant}.features SET created_at='${newDate
          .atOffset(ZoneOffset.UTC)
          .format(dtf)}' WHERE id='${feature}'""")
    })
  }

  def createFeature(
      name: String,
      project: String,
      tenant: String,
      enabled: Boolean = true,
      tags: Seq[String] = Seq(),
      metadata: JsObject = JsObject.empty,
      conditions: Set[TestCondition] = Set(),
      wasmConfig: TestWasmConfig = null,
      id: String = null,
      cookies: Seq[WSCookie] = Seq(),
      resultType: String = "boolean",
      value: String = null,
      description: String = null
  ): RequestResult = {
    createFeatureWithRawConditions(
      name,
      project,
      tenant,
      enabled,
      tags,
      metadata,
      Json.toJson(conditions.map(c => c.json)).toString(),
      wasmConfig,
      id,
      cookies,
      resultType,
      value,
      description
    )
  }

  def createFeatureAsync(
      name: String,
      project: String,
      tenant: String,
      enabled: Boolean = true,
      tags: Seq[String] = Seq(),
      metadata: JsObject = JsObject.empty,
      conditions: Set[TestCondition] = Set(),
      wasmConfig: TestWasmConfig = null,
      id: String = null,
      cookies: Seq[WSCookie] = Seq(),
      resultType: String = "boolean",
      value: String = null
  ): Future[WSResponse] = {
    createFeatureWithRawConditionsAsync(
      name,
      project,
      tenant,
      enabled,
      tags,
      metadata,
      Json.toJson(conditions.map(c => c.json)).toString(),
      wasmConfig,
      id,
      cookies,
      resultType,
      value
    )
  }

  def createFeatureWithRawConditions(
      name: String,
      project: String,
      tenant: String,
      enabled: Boolean = true,
      tags: Seq[String] = Seq(),
      metadata: JsObject = JsObject.empty,
      conditions: String,
      wasmConfig: TestWasmConfig = null,
      id: String = null,
      cookies: Seq[WSCookie] = Seq(),
      resultType: String = "boolean",
      value: String = null,
      description: String = null
  ): RequestResult = {
    val response = await(
      createFeatureWithRawConditionsAsync(
        name,
        project,
        tenant,
        enabled,
        tags,
        metadata,
        conditions,
        wasmConfig,
        id,
        cookies,
        resultType,
        value,
        description
      )
    )

    val jsonTry = Try {
      response.json
    }
    RequestResult(json = jsonTry, status = response.status)
  }

  def createFeatureWithRawConditionsAsync(
      name: String,
      project: String,
      tenant: String,
      enabled: Boolean = true,
      tags: Seq[String] = Seq(),
      metadata: JsObject = JsObject.empty,
      conditions: String,
      wasmConfig: TestWasmConfig = null,
      id: String,
      cookies: Seq[WSCookie] = Seq(),
      resultType: String = "boolean",
      value: String = null,
      description: String = null
  ): Future[WSResponse] = {
    ws.url(s"${ADMIN_BASE_URL}/tenants/${tenant}/projects/${project}/features")
      .withCookies(cookies: _*)
      .post(Json.parse(s"""{
           |"name": "${name}",
           |"tags": [${tags.map(name => s""""${name}"""").mkString(",")}],
           |"metadata": ${Json.stringify(metadata)},
           |"enabled": ${enabled},
           |"resultType": "${resultType}",
           |${if (value != null) {
        if (resultType == "number") s""" "value": ${value},""" else s""" "value": "${value}", """
      } else ""}
           |"conditions": ${Json.parse(conditions).as[JsArray]}
           |${if (description != null) s""" , "description": ${JsString(description)} """ else ""}
           |${if (Objects.nonNull(wasmConfig))
        s""" ,"wasmConfig": ${Json.stringify(wasmConfig.json)} """
      else ""}
           |${if (Objects.nonNull(id)) s""" ,"id": "$id" """ else s""}
           |
           |}""".stripMargin))
  }

  def createTag(
      name: String,
      tenant: String,
      description: String = "",
      cookies: Seq[WSCookie] = Seq()
  ): RequestResult = {
    val response = await(createTagAsync(name, tenant, description, cookies))
    val jsonTry  = Try {
      response.json
    }
    RequestResult(json = jsonTry, status = response.status, idField = "name")
  }

  def createTagAsync(
      name: String,
      tenant: String,
      description: String = "",
      cookies: Seq[WSCookie] = Seq()
  ): Future[WSResponse] = {
    ws.url(s"${ADMIN_BASE_URL}/tenants/${tenant}/tags")
      .withCookies(cookies: _*)
      .post(Json.parse(s"""{ "name": "${name}", "tenant": "${tenant}", "description": "${description}" }"""))
  }

  def createTenantAndProject(tenant: String, project: String): (String, String) = {
    val tenantResult  = createTenant(tenant)
    val projectResult = createProject(project, tenant = tenant)
    (tenantResult.id.get, projectResult.id.get)
  }

  def createTenant(name: String, description: String = null, cookies: Seq[WSCookie] = Seq()): RequestResult = {
    val response = await(createTenantAsync(name, description, cookies))

    val jsonTry = Try {
      response.json
    }
    RequestResult(json = jsonTry, status = response.status)
  }

  def createTenantAsync(
      name: String,
      description: String = null,
      cookies: Seq[WSCookie] = Seq()
  ): Future[WSResponse] = {
    ws.url(s"${ADMIN_BASE_URL}/tenants")
      .withCookies(cookies: _*)
      .post(Json.parse(s"""{ "name": "${name}" ${if (description != null) s""", "description": "${description}" """
      else ""} }"""))
  }

  def createProject(
      name: String,
      tenant: String,
      description: String = null,
      cookies: Seq[WSCookie] = Seq()
  ): RequestResult = {
    val response = await(createProjectAsync(name, tenant, description, cookies))
    val jsonTry  = Try {
      response.json
    }
    RequestResult(json = jsonTry, status = response.status, idField = "name")
  }

  def createProjectAsync(
      name: String,
      tenant: String,
      description: String = null,
      cookies: Seq[WSCookie] = Seq()
  ): Future[WSResponse] = {
    ws.url(s"${ADMIN_BASE_URL}/tenants/${tenant}/projects")
      .withCookies(cookies: _*)
      .post(Json.parse(s"""{ "name": "${name}", "tenant": "${tenant}" ${if (description != null)
        s""" ,"description": "${description}" """
      else ""} }"""))
  }

  def fetchTenants(right: String = null, cookies: Seq[WSCookie] = Seq()): RequestResult = {
    val response = await(
      ws.url(s"${ADMIN_BASE_URL}/tenants${if (Objects.nonNull(right)) s"?right=${right}" else ""}")
        .withCookies(cookies: _*)
        .get()
    )
    RequestResult(json = Try { response.json }, status = response.status)
  }

  def fetchTenant(id: String, cookies: Seq[WSCookie] = Seq()): RequestResult = {
    val response = await(ws.url(s"${ADMIN_BASE_URL}/tenants/${id}").withCookies(cookies: _*).get())
    RequestResult(json = Try { response.json }, status = response.status)
  }

  def fetchTag(tenant: String, name: String, cookie: Seq[WSCookie] = Seq()): RequestResult = {
    val response = await(ws.url(s"${ADMIN_BASE_URL}/tenants/${tenant}/tags/${name}").withCookies(cookie: _*).get())
    RequestResult(json = Try { response.json }, status = response.status)
  }

  def fetchTags(tenant: String, cookie: Seq[WSCookie] = Seq()): RequestResult = {
    val response = await(ws.url(s"${ADMIN_BASE_URL}/tenants/${tenant}/tags").withCookies(cookie: _*).get())
    RequestResult(json = Try { response.json }, status = response.status)
  }

  def fetchProject(tenant: String, projectId: String, cookies: Seq[WSCookie]): RequestResult = {
    val response = await(
      ws.url(s"${ADMIN_BASE_URL}/tenants/${tenant}/projects/${projectId}")
        .withCookies(cookies: _*)
        .get()
    )
    RequestResult(json = Try { response.json }, status = response.status)
  }

  def fetchProjects(tenant: String, cookies: Seq[WSCookie] = Seq()): RequestResult = {
    val response = await(ws.url(s"${ADMIN_BASE_URL}/tenants/${tenant}/projects").withCookies(cookies: _*).get())
    RequestResult(json = Try { response.json }, status = response.status)
  }
  def fetchSearchEntities(searchQuery: String, cookies: Seq[WSCookie] = Seq()): RequestResult = {
    val response = await(ws.url(s"${ADMIN_BASE_URL}/search?query=${searchQuery}").withCookies(cookies: _*).get())
    RequestResult(json = Try { response.json }, status = response.status)
  }

  def updateFeature(tenant: String, id: String, json: JsValue, cookies: Seq[WSCookie] = Seq()): RequestResult = {
    val response = await(
      ws.url(s"${ADMIN_BASE_URL}/tenants/${tenant}/features/${id}").withCookies(cookies: _*).put(json)
    )
    RequestResult(json = Try { response.json }, status = response.status)
  }

  def checkFeature(
      id: String,
      user: String = null,
      headers: Map[String, String] = Map(),
      context: String = null,
      conditions: Boolean = false
  ): RequestResult = {
    val maybeContext = Option(context).map(ctx => s"context=${context}")
    val maybeUser    = Option(user).map(u => s"user=${u}")
    val params       = Seq(maybeContext, maybeUser).filter(_.isDefined).map(_.get).mkString("&")
    val response     = await(
      ws.url(
        s"""${BASE_URL}/v2/features/${id}?conditions=${conditions}${if (!params.isBlank) s"&${params}" else ""}"""
      ).withHttpHeaders(headers.toList: _*)
        .get()
    )
    RequestResult(json = Try { response.json }, status = response.status)
  }

  def deleteFeature(tenant: String, id: String, cookies: Seq[WSCookie] = Seq()): RequestResult = {
    val response = await(
      ws.url(s"${ADMIN_BASE_URL}/tenants/${tenant}/features/${id}")
        .withCookies(cookies: _*)
        .delete()
    )
    RequestResult(json = Try { response.json }, status = response.status)
  }

  def checkFeatures(
      projects: Seq[String],
      headers: Map[String, String] = Map(),
      oneTagIn: Seq[String] = Seq(),
      allTagsIn: Seq[String] = Seq(),
      noTagIn: Seq[String] = Seq(),
      user: String = null,
      contextPath: String = null,
      features: Seq[String] = Seq(),
      conditions: Boolean = false
  ): RequestResult = {
    val response = await(
      ws.url(s"""${BASE_URL}/v2/features?conditions=${conditions}&oneTagIn=${oneTagIn
        .mkString(
          ","
        )}&allTagsIn=${allTagsIn.mkString(",")}&noTagIn=${noTagIn.mkString(",")}&projects=${projects.mkString(
        ","
      )}&features=${features.mkString(",")}${Option(user).map(u => s"&user=${u}").getOrElse("")}${if (
        contextPath != null
      ) s"&context=${contextPath}"
      else ""}""")
        .withHttpHeaders(headers.toList: _*)
        .get()
    )
    RequestResult(json = Try { response.json }, status = response.status)
  }

  def createAPIKey(
      tenant: String,
      name: String,
      projects: Seq[String] = Seq(),
      description: String = "",
      enabled: Boolean = true,
      admin: Boolean = false,
      cookies: Seq[WSCookie] = Seq()
  ): RequestResult = {
    val response = await(createAPIKeyAsync(tenant, name, projects, description, enabled, admin, cookies))
    val jsonTry  = Try {
      response.json
    }
    RequestResult(json = jsonTry, status = response.status)
  }

  def createAPIKeyAsync(
      tenant: String,
      name: String,
      projects: Seq[String] = Seq(),
      description: String = "",
      enabled: Boolean = true,
      admin: Boolean = false,
      cookies: Seq[WSCookie] = Seq()
  ): Future[WSResponse] = {

    ws.url(s"${ADMIN_BASE_URL}/tenants/${tenant}/keys")
      .withCookies(cookies: _*)
      .post(Json.parse(s"""{ "name": "${name}", "enabled": ${enabled},"admin": ${admin}, "projects": ${toJsArray(
        projects
      )}, "description": "${description}" }"""))
  }

  def toJsArray(elems: Seq[String]): String = {
    s"""[${elems.map(p => s""""${p}"""").mkString(",")}]"""
  }

  def fetchAPIKeys(tenant: String, cookies: Seq[WSCookie] = Seq()): RequestResult = {
    val response = await(
      ws.url(s"${ADMIN_BASE_URL}/tenants/${tenant}/keys").withCookies(cookies: _*).get()
    )
    val jsonTry  = Try {
      response.json
    }
    RequestResult(json = jsonTry, status = response.status)
  }

  def deleteAPIKey(tenant: String, name: String, cookies: Seq[WSCookie] = Seq()): RequestResult = {
    val response = await(
      ws.url(s"${ADMIN_BASE_URL}/tenants/${tenant}/keys/${name}")
        .withCookies(cookies: _*)
        .delete()
    )

    val jsonTry = Try {
      response.json
    }
    RequestResult(json = jsonTry, status = response.status)
  }

  def updateAPIKey(
      tenant: String,
      currentName: String,
      newName: String = null,
      description: String,
      projects: Seq[String],
      enabled: Boolean = true,
      admin: Boolean,
      cookies: Seq[WSCookie] = Seq()
  ): RequestResult = {
    val response = await(
      ws.url(s"${ADMIN_BASE_URL}/tenants/${tenant}/keys/${currentName}")
        .withCookies(cookies: _*)
        .put(Json.parse(s"""
          |{
          | "name": "${if (newName != null) newName else currentName}",
          | "description": "${description}",
          | "enabled": ${enabled},
          | "admin": ${admin},
          | "projects": [${projects.map(p => s""" "${p}" """).mkString(",")}]
          |}
          |""".stripMargin))
    )

    val jsonTry = Try {
      response.json
    }
    RequestResult(json = jsonTry, status = response.status)
  }

  def updateConfiguration(
      mailerConfiguration: JsObject = Json.obj("mailer" -> "Console"),
      invitationMode: String = "Response",
      originEmail: String = null,
      oidcConfiguration: JsObject = DEFAULT_OIDC_CONFIG,
      cookies: Seq[WSCookie] = Seq()
  ): RequestResult = {
    val response = await(
      updateConfigurationAsync(mailerConfiguration, invitationMode, originEmail, oidcConfiguration, cookies)
    )
    RequestResult(json = Try { response.json }, status = response.status)
  }

  def updateConfigurationAsync(
      mailerConfiguration: JsObject = Json.obj("mailer" -> "Console"),
      invitationMode: String = "Response",
      originEmail: String = null,
      oidcConfiguration: JsObject,
      cookies: Seq[WSCookie] = Seq(),
      anonymousReporting: Boolean = false,
      anonymousReportingDate: LocalDateTime = LocalDateTime.now()
  ): Future[WSResponse] = {
    val dateStr = anonymousReportingDate.atOffset(ZoneOffset.UTC).format(DateTimeFormatter.ISO_OFFSET_DATE_TIME)
    ws.url(s"""${ADMIN_BASE_URL}/configuration""")
      .withCookies(cookies: _*)
      .put(
        if (Objects.isNull(originEmail)) {
          Json.obj(
            "mailerConfiguration"    -> mailerConfiguration,
            "invitationMode"         -> invitationMode,
            "anonymousReporting"     -> anonymousReporting,
            "anonymousReportingDate" -> dateStr,
            "oidcConfiguration"      -> oidcConfiguration
          )
        } else {
          Json.obj(
            "mailerConfiguration"    -> mailerConfiguration,
            "invitationMode"         -> invitationMode,
            "originEmail"            -> originEmail,
            "anonymousReporting"     -> anonymousReporting,
            "anonymousReportingDate" -> dateStr,
            "oidcConfiguration"      -> oidcConfiguration
          )
        }
      )
  }

  def createContextHierarchy(
      tenant: String,
      project: String,
      context: TestFeatureContext,
      parents: Seq[String] = Seq(),
      cookies: Seq[WSCookie] = Seq()
  ): Unit = {
    await(createContextHierarchyAsync(tenant, project, context, parents, cookies))
  }

  def createContextHierarchyAsync(
      tenant: String,
      project: String,
      context: TestFeatureContext,
      parents: Seq[String] = Seq(),
      cookies: Seq[WSCookie] = Seq()
  ): Future[Unit] = {
    createContextAsync(
      tenant,
      project,
      name = context.name,
      parents = parents.mkString("/"),
      isProtected = context.isProtected,
      cookies = cookies
    )
      .map(res => {
        if (res.status >= 400) {
          throw new RuntimeException("Failed to create context")
        } else ()
      })
      .flatMap(_ => {
        Future.sequence(
          context.subContext.map(child =>
            createContextHierarchyAsync(tenant, project, child, parents.appended(context.name), cookies)
          )
        )
      })
      .flatMap(_ => {
        Future.sequence(context.overloads.map {
          case TestFeature(name, enabled, _, _, conditions, wasmConfig, _, resultType, value) => {
            changeFeatureStrategyForContextAsync(
              tenant,
              project,
              parents.appended(context.name).mkString("/"),
              name,
              enabled,
              conditions,
              wasmConfig,
              cookies,
              resultType,
              value
            ).map(result => {
              if (result.status >= 400) {
                throw new RuntimeException("Failed to create feature overload")
              }
            })
          }
        })
      })
      .map(_ => ())
  }

  def createGlobalContextHierarchyAsync(
      tenant: String,
      context: TestFeatureContext,
      parents: Seq[String] = Seq(),
      cookies: Seq[WSCookie] = Seq()
  ): Future[Unit] = {
    createGlobalContextAsync(
      tenant,
      name = context.name,
      parents = parents.mkString("/"),
      isProtected = context.isProtected,
      cookies = cookies
    )
      .map(result => {
        if (result.status >= 400) {
          throw new RuntimeException("Failed to create context")
        } else ()
      })
      .flatMap(_ => {
        Future.sequence(
          context.subContext.map(child =>
            createGlobalContextHierarchyAsync(tenant, child, parents.appended(context.name), cookies)
          )
        )
      })
      .map(s => ())
  }

  def createContext(
      tenant: String,
      project: String,
      name: String,
      parents: String = "",
      isProtected: Boolean = false,
      cookies: Seq[WSCookie] = Seq()
  ) = {
    val response = await(BaseAPISpec.createContextAsync(tenant, project, name, parents, isProtected, cookies))

    val jsonTry = Try {
      response.json
    }
    RequestResult(json = jsonTry, status = response.status, idField = "name")
  }

  def updateContext(
      tenant: String,
      project: String,
      name: String,
      isProtected: Boolean,
      parents: String = "",
      cookies: Seq[WSCookie] = Seq()
  ) = {
    val response = await(
      ws.url(s"""${ADMIN_BASE_URL}/tenants/${tenant}/projects/${project}/contexts${if (parents.nonEmpty) s"/${parents}"
      else ""}/$name""")
        .withCookies(cookies: _*)
        .put(
          Json.parse(s"""
                      |{
                      | "protected": $isProtected
                      |}
                      |""".stripMargin)
        )
    )

    val jsonTry = Try {
      response.json
    }
    RequestResult(json = jsonTry, status = response.status)
  }

  def updateGlobalContext(
      tenant: String,
      name: String,
      isProtected: Boolean,
      parents: String = "",
      cookies: Seq[WSCookie] = Seq()
  ) = {
    val response = await(
      ws.url(s"""${ADMIN_BASE_URL}/tenants/${tenant}/contexts${if (parents.nonEmpty) s"/${parents}"
      else ""}/$name""")
        .withCookies(cookies: _*)
        .put(
          Json.parse(s"""
                      |{
                      | "protected": $isProtected
                      |}
                      |""".stripMargin)
        )
    )
    val jsonTry  = Try {
      response.json
    }
    RequestResult(json = jsonTry, status = response.status)
  }

  def createContextAsync(
      tenant: String,
      project: String,
      name: String,
      parents: String = "",
      isProtected: Boolean = false,
      cookies: Seq[WSCookie] = Seq()
  ): Future[WSResponse] = {
    ws.url(s"""${ADMIN_BASE_URL}/tenants/${tenant}/projects/${project}/contexts${if (parents.nonEmpty) s"/${parents}"
    else ""}""")
      .withCookies(cookies: _*)
      .post(
        Json.parse(s"""
             |{
             | "name": "${name}",
             | "protected": ${isProtected}
             |}
             |""".stripMargin)
      )
  }

  def createGlobalContext(
      tenant: String,
      name: String,
      parents: String = "",
      isProtected: Boolean = false,
      cookies: Seq[WSCookie] = Seq()
  ): RequestResult = {
    val response = await(createGlobalContextAsync(tenant, name, parents, isProtected, cookies))

    val jsonTry = Try {
      response.json
    }
    RequestResult(json = jsonTry, status = response.status, idField = "name")
  }

  def createGlobalContextAsync(
      tenant: String,
      name: String,
      parents: String = "",
      isProtected: Boolean = false,
      cookies: Seq[WSCookie] = Seq()
  ): Future[WSResponse] = {
    ws.url(s"""${ADMIN_BASE_URL}/tenants/${tenant}/contexts${if (parents.nonEmpty) s"/${parents}"
    else ""}""")
      .withCookies(cookies: _*)
      .post(
        Json.parse(s"""
               |{
               | "name": "${name}",
               | "protected": $isProtected
               |}
               |""".stripMargin)
      )
  }

  def fetchContexts(tenant: String, project: String, cookies: Seq[WSCookie] = Seq()) = {
    val response = await(
      ws.url(s"${ADMIN_BASE_URL}/tenants/${tenant}/projects/${project}/contexts").withCookies(cookies: _*).get()
    )

    val jsonTry = Try {
      response.json
    }
    RequestResult(json = jsonTry, status = response.status, idField = "name")
  }

  def testFeature(
      tenant: String,
      feature: TestFeature,
      date: OffsetDateTime = OffsetDateTime.now(),
      user: String = null,
      cookies: Seq[WSCookie] = Seq()
  ): RequestResult = {

    val jsonFeature = Json.parse(s"""{
                                    |"name": "${feature.name}",
                                    |"tags": [${feature.tags.map(name => s""""${name}"""").mkString(",")}],
                                    |"metadata": ${Json.stringify(feature.metadata)},
                                    |"enabled": ${feature.enabled},
                                    |"resultType": "${feature.resultType}",
                                    |${if (feature.value != null) {
      if (feature.resultType == "number") s""" "value": ${feature.value}, """
      else s""" "value": "${feature.value}", """
    } else ""}
                                    |"conditions": ${Json.toJson(feature.conditions.map(c => c.json))}
                                    |${if (Objects.nonNull(feature.wasmConfig))
      s""" ,"wasmConfig": ${feature.wasmConfig.json} """
    else ""}
                                    |}""".stripMargin);
    val response    = await(
      ws.url(s"""${ADMIN_BASE_URL}/tenants/$tenant/test?date=${DateTimeFormatter.ISO_INSTANT.format(date)}${if (
        user != null
      )
        s"&user=${user}"
      else ""}""")
        .withCookies(cookies: _*)
        .post(Json.obj("feature" -> jsonFeature))
    )

    val jsonTry = Try {
      response.json
    }
    RequestResult(json = jsonTry, status = response.status)
  }

  def login(user: String, password: String, rights: Boolean = false): RequestResult = {
    val response = await(
      ws.url(s"""${ADMIN_BASE_URL}/login?rights=${rights}""")
        .withAuth(user, password, WSAuthScheme.BASIC)
        .post("")
    )
    val jsonTry  = Try {
      response.json
    }
    RequestResult(json = jsonTry, status = response.status, cookies = response.cookies.toSeq)
  }

  def createUser(
      user: String,
      password: String,
      admin: Boolean = false,
      rights: TestRights = null,
      email: String = null,
      cookies: Seq[WSCookie] = Seq()
  ): RequestResult = {
    await(createUserAsync(user, password, admin, rights, email, cookies))
  }

  def createUserAsync(
      user: String,
      password: String,
      admin: Boolean = false,
      rights: TestRights = null,
      email: String = null,
      cookies: Seq[WSCookie] = Seq()
  ): Future[RequestResult] = {
    val realEmail = Option(email).getOrElse(s"${user}@imaginarymail.frfrfezfezrf")
    sendInvitationAsync(realEmail, admin, rights, cookies).flatMap(result => {
      val url   = (result.json \ "invitationUrl").as[String]
      val token = url.split("token=")(1)
      createUserWithTokenAsync(user, password, token).map(response => {
        val jsonTry = Try {
          response.json
        }
        RequestResult(json = jsonTry, status = response.status)
      })
    })
  }

  def createWasmScript(name: String, enabled: Boolean = false): Future[Any] = {
    // FIXME pass name when it will be easy to parametrized
    val futureCreationResponse = ws
      .url(s"http://localhost:5001/api/plugins")
      .withHttpHeaders(("Content-Type", "application/json"))
      .post(
        Json.obj(
          "plugin" -> name,
          "type"   -> "ts"
        )
      )

    futureCreationResponse
      .map(resp => (resp.json \\ "pluginId").head.as[String])
      .flatMap(id => {
        val fileBytes = Files.readAllBytes(
          Paths.get(if (enabled) "test/resources/izanami-enabled.zip" else "test/resources/izanami-disabled.zip")
        )
        ws.url(s"http://localhost:5001/api/plugins/${id}")
          .withHttpHeaders(("Content-Type", "application/octet-stream"))
          .put(fileBytes)
          .flatMap(_ =>
            ws.url(s"http://localhost:5001/api/plugins/${id}/build&plugin_type=js")
              .withHttpHeaders(("Content-Type", "application/octet-stream"))
              .post(fileBytes)
          )
      })
  }

  def createUserWithToken(
      user: String,
      password: String,
      token: String
  ): RequestResult = {
    val response = await(createUserWithTokenAsync(user, password, token))

    val jsonTry = Try {
      response.json
    }
    RequestResult(json = jsonTry, status = response.status)
  }

  def createUserWithTokenAsync(
      user: String,
      password: String,
      token: String
  ): Future[WSResponse] = {
    ws.url(s"""${ADMIN_BASE_URL}/users""")
      .post(
        Json.obj(
          "username" -> user,
          "password" -> password,
          "token"    -> token
        )
      )
  }

  def sendInvitation(
      email: String,
      admin: Boolean = false,
      rights: TestRights = null,
      cookies: Seq[WSCookie] = Seq()
  ): RequestResult = {
    val response = await(sendInvitationAsync(email, admin, rights, cookies))

    val jsonTry = Try {
      response.json
    }
    RequestResult(json = jsonTry, status = response.status)
  }

  def sendInvitationAsync(
      email: String,
      admin: Boolean = false,
      rights: TestRights = null,
      cookies: Seq[WSCookie] = Seq()
  ): Future[WSResponse] = {
    val jsonRights = Option(rights).map(r => r.json).getOrElse(Json.obj())
    ws.url(s"""${ADMIN_BASE_URL}/invitation""")
      .withCookies(cookies: _*)
      .post(
        Json.obj(
          "email"  -> email,
          "admin"  -> admin,
          "rights" -> jsonRights
        )
      )
  }

  def changeFeatureStrategyForContext(
      tenant: String,
      project: String,
      contextPath: String,
      feature: String,
      enabled: Boolean,
      conditions: Set[TestCondition] = Set(),
      wasmConfig: TestWasmConfig = null,
      cookies: Seq[WSCookie] = Seq(),
      resultType: String = "boolean",
      value: String = null
  ) = {
    val response = await(
      changeFeatureStrategyForContextAsync(
        tenant,
        project,
        contextPath,
        feature,
        enabled,
        conditions,
        wasmConfig,
        cookies,
        resultType,
        value
      )
    )

    val jsonTry = Try {
      response.json
    }
    RequestResult(json = jsonTry, status = response.status, idField = "name")
  }

  def changeFeatureStrategyForContextAsync(
      tenant: String,
      project: String,
      contextPath: String,
      feature: String,
      enabled: Boolean,
      conditions: Set[TestCondition] = Set(),
      wasmConfig: TestWasmConfig = null,
      cookies: Seq[WSCookie] = Seq(),
      resultType: String = "boolean",
      value: String = null
  ) = {
    ws.url(s"""${ADMIN_BASE_URL}/tenants/${tenant}/projects/${project}/contexts/${contextPath}/features/${feature}""")
      .withCookies(cookies: _*)
      .put(
        Json.parse(s"""
               |{
               | "enabled": ${enabled},
               | "resultType": "${resultType}",
               | ${if (value != null) {
          if (resultType == "number") s""" "value": $value, """ else s""" "value": "$value", """
        } else ""}
               | "enabled": ${enabled},
               | "conditions": [${conditions.map(c => c.json).mkString(",")}]
               | ${if (Objects.nonNull(wasmConfig)) s""" ,"wasmConfig": ${Json.stringify(wasmConfig.json)}"""
        else ""}
               |}
               |""".stripMargin)
      )
  }

  private def writeTemporaryFile(prefix: String, suffix: String, content: Seq[String]): File = {
    val file   = File.createTempFile(prefix, suffix)
    val writer = new BufferedWriter(new FileWriter(file))
    writer.write(content.mkString("\n"))
    writer.close();

    file
  }

  def importWithToken(
      tenant: String,
      username: String,
      token: String,
      conflictStrategy: String = "fail",
      data: Seq[String] = Seq()
  ): RequestResult = {
    val dataFile = writeTemporaryFile("export", "ndjson", data)
    val auth     = Base64.getEncoder.encodeToString(s"${username}:$token".getBytes(StandardCharsets.UTF_8))
    val response = await(
      ws.url(
        s"${ADMIN_BASE_URL}/tenants/${tenant}/_import?version=2&conflict=${conflictStrategy}"
      ).addHttpHeaders("Authorization" -> s"Basic $auth")
        .post(
          Source(
            Seq(
              FilePart("export", "export.ndjson", Option("text/plain"), FileIO.fromPath(dataFile.toPath))
            )
          )
        )
    )
    RequestResult(json = Try { response.json }, status = response.status)
  }

  def updateMailerConfiguration(
      mailer: String,
      configuration: JsObject,
      cookies: Seq[WSCookie] = Seq()
  ) = {
    val response = await(updateMailerConfigurationAsync(mailer, configuration, cookies))

    val jsonTry = Try {
      response.json
    }
    RequestResult(json = jsonTry, status = response.status)
  }

  def updateMailerConfigurationAsync(
      mailer: String,
      configuration: JsObject,
      cookies: Seq[WSCookie] = Seq()
  ): Future[WSResponse] = {
    ws.url(s"""${ADMIN_BASE_URL}/configuration/mailer/${mailer}""")
      .withCookies(cookies: _*)
      .put(configuration)
  }

  sealed trait TestPeriod {
    def json: JsObject
  }

  sealed trait TestRule {
    def json: JsObject
  }

  case class RequestResult(
      json: Try[JsValue],
      status: Integer,
      idField: String = "id",
      cookies: Seq[WSCookie] = Seq()
  ) {
    def id: Try[String] = json.map(json => (json \ idField).as[String])
  }

  case class TestProjectRight(name: String, level: String = "Read") {
    def json: JsObject = Json.obj(
      "level" -> level
    )
  }

  case class TestKeyRight(name: String, level: String = "Read") {
    def json: JsObject = Json.obj(
      "level" -> level
    )
  }

  case class TestWebhookRight(name: String, level: String = "Read") {
    def json: JsObject = Json.obj(
      "level" -> level
    )
  }

  case class TestTenantRight(
      name: String,
      level: String = "Read",
      projects: Map[String, TestProjectRight] = Map(),
      keys: Map[String, TestKeyRight] = Map(),
      webhooks: Map[String, TestWebhookRight] = Map()
  ) {
    def json: JsObject = Json.obj(
      "level"    -> level,
      "projects" -> projects.view.mapValues(_.json),
      "keys"     -> keys.view.mapValues(_.json),
      "webhooks" -> webhooks.view.mapValues(_.json)
    )

    def addProjectRight(project: String, level: String): TestTenantRight = {
      copy(projects = projects + (project -> TestProjectRight(project, level)))
    }

    def addKeyRight(key: String, level: String): TestTenantRight = {
      copy(keys = keys + (key -> TestKeyRight(key, level)))
    }

    def addWebhookRight(webhook: String, level: String): TestTenantRight = {
      copy(webhooks = webhooks + (webhook -> TestWebhookRight(webhook, level)))
    }
  }

  case class TestRights(tenants: Map[String, TestTenantRight] = Map()) {
    def addTenantRight(name: String, level: String) = copy(tenants + (name -> TestTenantRight(name, level)))
    def addProjectRight(project: String, tenant: String, level: String) = {
      if (!tenants.contains(tenant)) {
        throw new RuntimeException("Tenant does not exist")
      }
      val newProjects = tenants(tenant).projects + (project -> TestProjectRight(name = project, level = level))

      copy(tenants + (tenant -> tenants(tenant).copy(projects = newProjects)))
    }

    def addKeyRight(key: String, tenant: String, level: String) = {
      if (!tenants.contains(tenant)) {
        throw new RuntimeException("Tenant does not exist")
      }
      val newKeys = tenants(tenant).keys + (key -> TestKeyRight(name = key, level = level))

      copy(tenants + (tenant -> tenants(tenant).copy(keys = newKeys)))
    }

    def json: JsObject = Json.obj(
      "tenants" -> tenants.view.mapValues(_.json)
    )
  }

  case class TestFeature(
      name: String,
      enabled: Boolean = true,
      tags: Seq[String] = Seq(),
      metadata: JsObject = JsObject.empty,
      conditions: Set[TestCondition] = Set(),
      wasmConfig: TestWasmConfig = null,
      id: String = null,
      resultType: String = "boolean",
      value: String = null
  ) {
    def withConditions(testCondition: TestCondition*): TestFeature = {
      copy(conditions = this.conditions ++ testCondition)
    }
  }

  case class TestWasmConfig(
      name: String,
      source: JsObject,
      memoryPages: Int = 100,
      config: Map[String, String] = Map.empty,
      wasi: Boolean = true,
      opa: Boolean = false
  ) {
    def json: JsObject = Json.obj(
      "name"        -> name,
      "source"      -> source,
      "memoryPages" -> memoryPages,
      "config"      -> config,
      "wasi"        -> wasi,
      "opa"         -> opa /*,
      "kill_options" -> Json.obj(
        "immortal" -> true
      )*/
    )
  }

  case class TestCondition(
      rule: TestRule = null,
      period: TestPeriod = null,
      value: String = null
  ) {
    def json: JsObject = Json
      .obj(
        "rule"   -> Option(rule).map(r => r.json),
        "period" -> Option(period).map(p => p.json)
      )
      .applyOnWithOpt(Option(value))((json, value) => json ++ Json.obj("value" -> value))
  }

  case class TestDateTimePeriod(
      begin: LocalDateTime = null,
      end: LocalDateTime = null,
      hourPeriods: Seq[TestHourPeriod] = Seq(),
      days: TestDayPeriod = TestDayPeriod(days = DayOfWeek.values().toSet),
      timezone: ZoneId = TimeZone.getDefault().toZoneId
  ) extends TestPeriod {
    def json: JsObject = {
      Json.obj(
        "type"           -> "DATETIME",
        "begin"          -> Option(begin).map(begin => begin.atOffset(ZoneOffset.UTC).format(DATE_TIME_FORMATTER)),
        "end"            -> Option(end).map(end => end.atOffset(ZoneOffset.UTC).format(DATE_TIME_FORMATTER)),
        "hourPeriods"    -> hourPeriods.map(p => p.json),
        "timezone"       -> timezone.toString,
        "activationDays" -> Option(days).map(d =>
          Json.obj(
            "days" -> d.days.map(_.name)
          )
        )
      )
    }

    def beginAt(begin: LocalDateTime): TestDateTimePeriod   = copy(begin = begin)
    def endAt(end: LocalDateTime): TestDateTimePeriod       = copy(end = end)
    def atHours(hours: TestHourPeriod*): TestDateTimePeriod = copy(hourPeriods = this.hourPeriods ++ hours)
    def atDays(days: DayOfWeek*): TestDateTimePeriod        = copy(days = TestDayPeriod(days = days.toSet))
  }

  case class TestHourPeriod(startTime: LocalTime = null, endTime: LocalTime = null) {
    def json: JsObject = {
      Json.obj(
        "startTime" -> startTime
          .atOffset(OffsetDateTime.now().getOffset)
          .format(DateTimeFormatter.ISO_TIME),
        "endTime"   -> endTime
          .atOffset(OffsetDateTime.now().getOffset)
          .format(DateTimeFormatter.ISO_TIME)
      )
    }
  }

  case class TestDayPeriod(days: Set[DayOfWeek])

  case class TestPercentageRule(percentage: Int)  extends TestRule {
    override def json: JsObject = Json.obj(
      "type"       -> "PERCENTAGE",
      "percentage" -> percentage
    )
  }
  case class TestUserListRule(users: Set[String]) extends TestRule {
    override def json: JsObject = Json.obj(
      "type"  -> "USER_LIST",
      "users" -> users
    )
  }

  case class TestApiKey(
      name: String,
      projects: Seq[String] = Seq(),
      description: String = "",
      enabled: Boolean = true,
      admin: Boolean = false
  )

  case class TestFeatureContext(
      name: String,
      subContext: Set[TestFeatureContext] = Set(),
      overloads: Seq[TestFeature] = Seq(),
      isProtected: Boolean = false
  ) {

    def withSubContexts(contexts: TestFeatureContext*): TestFeatureContext = {
      copy(subContext = this.subContext ++ contexts)
    }

    def withSubContextNames(contexts: String*): TestFeatureContext = {
      copy(subContext = this.subContext ++ (contexts.map(n => TestFeatureContext(name = n))))
    }

    def withFeatureOverload(
        feature: TestFeature
    ): TestFeatureContext = {
      copy(overloads = overloads.appended(feature))
    }

    def withProtected(isProtected: Boolean): TestFeatureContext = {
      copy(isProtected = isProtected)
    }
  }

  case class TestTenant(
      name: String,
      description: String = "",
      projects: Set[TestProject] = Set(),
      tags: Set[TestTag] = Set(),
      apiKeys: Set[TestApiKey] = Set(),
      allRightKeys: Set[String] = Set(),
      contexts: Set[TestFeatureContext] = Set(),
      webhooks: Set[TestWebhookByName] = Set()
  ) {
    def withProjects(projects: TestProject*): TestTenant = {
      copy(projects = this.projects ++ projects)
    }

    def withWebhooks(webhooks: TestWebhookByName*): TestTenant = {
      copy(webhooks = this.webhooks ++ webhooks)
    }

    def withProjectNames(projects: String*): TestTenant = {
      copy(projects = this.projects ++ projects.map(name => TestProject(name = name)))
    }

    def withTags(tags: TestTag*): TestTenant = {
      copy(tags = this.tags ++ tags)
    }

    def withTagNames(tags: String*): TestTenant = {
      copy(tags = this.tags ++ tags.map(t => TestTag(name = t)))
    }

    def withApiKeys(apiKeys: TestApiKey*): TestTenant = {
      copy(apiKeys = this.apiKeys ++ apiKeys)
    }

    def withAllRightsKey(name: String): TestTenant = {
      copy(allRightKeys = this.allRightKeys + name)
    }

    def withApiKeyNames(apiKeys: String*): TestTenant = {
      copy(apiKeys = this.apiKeys ++ apiKeys.map(n => TestApiKey(name = n)))
    }

    def withGlobalContext(contexts: TestFeatureContext*): TestTenant = {
      copy(contexts = this.contexts ++ contexts)
    }
  }
  case class TestProject(
      name: String,
      description: String = "",
      features: Set[TestFeature] = Set(),
      contexts: Set[TestFeatureContext] = Set()
  ) {
    def withFeatures(features: TestFeature*): TestProject = {
      copy(features = this.features ++ features)
    }

    def withFeatureNames(features: String*): TestProject = {
      copy(features = this.features ++ features.map(f => new TestFeature(name = f)))
    }

    def withContexts(contexts: TestFeatureContext*): TestProject = {
      copy(contexts = this.contexts ++ contexts)
    }

    def withContextNames(contexts: String*): TestProject = {
      copy(contexts = this.contexts ++ (contexts.map(n => TestFeatureContext(name = n))))
    }
  }
  case class TestTag(name: String, description: String = "")

  case class TestSituationKey(name: String, clientId: String, clientSecret: String, enabled: Boolean)

  case class TestFeaturePatch(op: String, path: String, value: JsValue = JsNull) {
    def json: JsObject = Json.obj(
      "op"    -> op,
      "path"  -> path,
      "value" -> value
    )
  }

  case class TestPersonnalAccessToken(
      name: String,
      allRights: Boolean,
      rights: Map[String, Set[String]] = Map(),
      expiresAt: Option[LocalDateTime] = None,
      expirationTimezone: Option[ZoneId] = None,
      id: String = null
  ) {
    def toJson: JsObject = {
      Json
        .obj("name" -> name, "allRights" -> allRights, "rights" -> rights)
        .applyOnWithOpt(expiresAt)((obj, expiration) => {
          obj + ("expiresAt" -> Json.toJson(expiration))
        })
        .applyOnWithOpt(expirationTimezone)((obj, expirationTimezone) => {
          obj + ("expirationTimezone" -> Json.toJson(expirationTimezone))
        })
        .applyOnWithOpt(Option(id))((obj, id) => {
          obj + ("id" -> Json.toJson(id))
        })
    }
  }

  case class TestWebhook(
      name: String,
      url: String,
      features: Set[String] = Set(),
      projects: Set[String] = Set(),
      headers: Map[String, String] = Map(),
      description: String = "",
      user: String = "",
      context: String = "",
      enabled: Boolean = true,
      bodyTemplate: Option[String] = None,
      global: Boolean = false
  ) {
    def toJson: JsObject = {
      Json.obj(
        "name"         -> name,
        "url"          -> url,
        "features"     -> features,
        "projects"     -> projects,
        "headers"      -> headers,
        "description"  -> description,
        "user"         -> user,
        "enabled"      -> enabled,
        "global"       -> global,
        "context"      -> context,
        "bodyTemplate" -> bodyTemplate
      )
    }
  }

  type TenantName      = String
  type ProjectName     = String
  type FeatureName     = String
  type FeatureIdByName = (TenantName, ProjectName, FeatureName)
  type ProjectIdByName = (TenantName, ProjectName)
  case class TestWebhookByName(
      name: String,
      url: String,
      features: Set[FeatureIdByName] = Set(),
      projects: Set[ProjectIdByName] = Set(),
      headers: Map[String, String] = Map(),
      description: String = "",
      user: String = "",
      context: String = "",
      enabled: Boolean = true,
      bodyTemplate: Option[String] = None,
      global: Boolean = false
  )

  case class TestSituation(
      keys: Map[String, TestSituationKey] = Map(),
      cookies: Seq[WSCookie] = Seq(),
      features: Map[String, Map[String, Map[String, String]]] = Map(),
      projects: Map[String, Map[String, String]] = Map(),
      tags: Map[String, Map[String, String]] = Map(),
      scripts: Map[String, String] = Map(),
      webhooks: Map[String, Map[String, String]] = Map(),
      user: String,
      tokenData: Map[String, Map[String, (String, String)]],
      server: RunningServer
  ) {
    BaseAPISpec.this.izanamiInstance = server

    def shutdownEventSources(tenant: String) = {
      val response = await(
        ws.url(s"${ADMIN_BASE_URL}/tenants/${tenant}/features")
          .withCookies(cookies: _*)
          .delete()
      )

      RequestResult(
        json = Try {
          response.json
        },
        status = response.status
      )
    }

    def listenEvents(
        key: String,
        features: Seq[String],
        projects: Seq[String],
        consumer: ServerSentEvent => Unit,
        user: String = "",
        conditions: Boolean = true,
        refreshInterval: Duration = Duration.ofSeconds(0L),
        keepAliveInterval: Duration = Duration.ofSeconds(25L)
    ) = {
      shouldCleanUpEvents = true
      val send: HttpRequest => Future[HttpResponse] = request => {
        val r = request.withHeaders(keyHeaders(key).map { case (name, value) => RawHeader(name, value) }.toSeq)
        Http().singleRequest(r)
      }

      val eventSource: Source[ServerSentEvent, NotUsed] =
        EventSource(
          uri = Uri(
            s"""$BASE_URL/v2/events?user=${user}&projects=${projects.mkString(",")}&features=${features.mkString(
              ","
            )}&conditions=${conditions}&refreshInterval=${refreshInterval.toSeconds}&keepAliveInterval=${keepAliveInterval.toSeconds}"""
          ),
          send,
          initialLastEventId = Some("2"),
          retryDelay = 1.second
        )

      val (killswitch, source) = eventSource
        .throttle(elements = 1, per = 200.milliseconds, maximumBurst = 1, ThrottleMode.Shaping)
        .viaMat(KillSwitches.single)(Keep.right)
        .toMat(Sink.foreach((evt: ServerSentEvent) => {
          println(s"RECEIVED $evt")
          consumer(evt)
        }))(Keep.both)
        .run()
      eventKillSwitch = killswitch
    }

    def listWebhook(tenant: String) = {
      val response = await(
        ws.url(s"${ADMIN_BASE_URL}/tenants/${tenant}/webhooks")
          .withCookies(cookies: _*)
          .get()
      )

      RequestResult(
        json = Try {
          response.json
        },
        status = response.status
      )
    }

    def updateWebhook(tenant: String, id: String, transformer: JsObject => JsObject): RequestResult = {
      def transformGetResponse(json: JsObject): JsObject = {
        val featureIds = (json \ "features").as[JsArray].value.map(json => (json \ "id").get)
        val projectIds = (json \ "projects").as[JsArray].value.map(json => (json \ "id").get)

        json ++ Json.obj("features" -> JsArray(featureIds), "projects" -> JsArray(projectIds))
      }
      val hook     =
        listWebhook(tenant).json.get.as[JsArray].value.find(json => (json \ "id").as[String] == id).get.as[JsObject]
      val response = await(
        ws.url(s"${ADMIN_BASE_URL}/tenants/$tenant/webhooks/$id")
          .withCookies(cookies: _*)
          .put(transformer(transformGetResponse(hook)))
      )

      RequestResult(
        json = Try {
          response.json
        },
        status = response.status
      )
    }

    def fetchWebhookUser(tenant: String, webhook: String): RequestResult = {
      val response = await(
        ws.url(s"${ADMIN_BASE_URL}/tenants/$tenant/webhooks/$webhook/users")
          .withCookies(cookies: _*)
          .get()
      )

      RequestResult(
        json = Try {
          response.json
        },
        status = response.status
      )
    }

    def updateUserRightForWebhook(
        tenant: String,
        webhook: String,
        user: String,
        right: Option[String]
    ): RequestResult = {
      val response = await(
        ws.url(s"${ADMIN_BASE_URL}/tenants/$tenant/webhooks/$webhook/users/${user}/rights")
          .withCookies(cookies: _*)
          .put(right.fold(Json.obj())(r => Json.obj("level" -> Json.toJson(r))))
      )

      RequestResult(
        json = Try {
          response.json
        },
        status = response.status
      )
    }

    def deleteWebhook(tenant: String, webhook: String): RequestResult = {
      val response = await(
        ws.url(s"${ADMIN_BASE_URL}/tenants/$tenant/webhooks/$webhook")
          .withCookies(cookies: _*)
          .delete()
      )

      RequestResult(
        json = Try {
          response.json
        },
        status = response.status
      )
    }

    def createWebhook(tenant: String, webhook: TestWebhook) = {
      val response = await(
        BaseAPISpec.this.createWebhook(tenant, webhook, cookies)
      )

      RequestResult(
        json = Try {
          response.json
        },
        status = response.status
      )
    }

    def createPersonnalAccessToken(token: TestPersonnalAccessToken, user: Option[String] = None): RequestResult = {
      val response = await(
        BaseAPISpec.this.createPersonnalAccessToken(
          token,
          user = user.getOrElse(this.user),
          cookies = cookies
        )
      )

      RequestResult(
        json = Try {
          response.json
        },
        status = response.status
      )
    }

    def updatePersonnalAccessToken(id: String, token: TestPersonnalAccessToken): RequestResult = {
      updatePersonnalAccessToken(id, token, user)
    }

    def updatePersonnalAccessToken(id: String, token: TestPersonnalAccessToken, user: String): RequestResult = {
      val response = await(
        ws.url(s"${ADMIN_BASE_URL}/users/${user}/tokens/${id}")
          .withCookies(cookies: _*)
          .put(token.toJson)
      )

      RequestResult(
        json = Try {
          response.json
        },
        status = response.status
      )
    }

    def deletePersonnalAccessToken(id: String): RequestResult = {
      deletePersonnalAccessToken(id, this.user)
    }

    def deletePersonnalAccessToken(id: String, user: String): RequestResult = {
      val response = await(
        ws.url(s"${ADMIN_BASE_URL}/users/${user}/tokens/$id")
          .withCookies(cookies: _*)
          .delete
      )

      RequestResult(
        json = Try {
          response.json
        },
        status = response.status
      )
    }

    def fetchPersonnalAccessTokens(user: String): RequestResult = {
      val response = await(
        ws.url(s"${ADMIN_BASE_URL}/users/${user}/tokens")
          .withCookies(cookies: _*)
          .get
      )

      RequestResult(
        json = Try {
          response.json
        },
        status = response.status
      )
    }

    def fetchPersonnalAccessTokens(): RequestResult = {
      fetchPersonnalAccessTokens(this.user)
    }

    def patchFeatures(tenant: String, patches: Seq[TestFeaturePatch]) = {
      val response = await(
        ws.url(s"${ADMIN_BASE_URL}/tenants/${tenant}/features")
          .withCookies(cookies: _*)
          .patch(Json.toJson(patches.map(p => p.json)))
      )

      RequestResult(
        json = Try {
          response.json
        },
        status = response.status
      )
    }

    def logout(): TestSituation = {
      copy(cookies = Seq(), user = null)
    }

    def loggedAs(user: String, password: String): TestSituation = {
      val res = BaseAPISpec.this.login(user, password)
      copy(cookies = res.cookies, user = user)
    }

    def loggedAsAdmin(): TestSituation = {
      val res = BaseAPISpec.this.login(ALL_RIGHTS_USERNAME, ALL_RIGHTS_USERNAME_PASSWORD)
      copy(cookies = res.cookies)
    }

    def resetPassword(email: String): RequestResult = {
      val response = await(
        ws.url(s"${ADMIN_BASE_URL}/password/_reset")
          .withCookies(cookies: _*)
          .post(
            Json.obj(
              "email" -> email
            )
          )
      )

      RequestResult(
        json = Try {
          response.json
        },
        status = response.status
      )
    }

    def reinitializePassword(password: String, token: String): RequestResult = {
      val response = await(
        ws.url(s"${ADMIN_BASE_URL}/password/_reinitialize")
          .withCookies(cookies: _*)
          .post(
            Json.obj(
              "password" -> password,
              "token"    -> token
            )
          )
      )

      RequestResult(
        json = Try {
          response.json
        },
        status = response.status
      )
    }

    def pathForScript(name: String): Option[String] = {
      scripts.get(name)
    }

    def findWebhookId(tenant: String, webhook: String): Option[String] = {
      for (
        tenantContent  <- webhooks.get(tenant);
        webhookContent <- tenantContent.get(webhook)
      ) yield webhookContent
    }

    def findFeatureId(tenant: String, project: String, feature: String): Option[String] = {
      for (
        tenantContent  <- features.get(tenant);
        projectContent <- tenantContent.get(project);
        featureId      <- projectContent.get(feature)
      ) yield featureId
    }

    def findProjectId(tenant: String, project: String): Option[String] = {
      for (
        tenantContent  <- projects.get(tenant);
        projectContent <- tenantContent.get(project)
      ) yield projectContent
    }

    def findTagId(tenant: String, tag: String): Option[String] = {
      for (
        tenantContent <- tags.get(tenant);
        tagContent    <- tenantContent.get(tag)
      ) yield tagContent
    }

    def findTokenId(user: String, token: String): String = {
      tokenData(user)(token)._1
    }

    def findTokenSecret(user: String, token: String): String = {
      tokenData(user)(token)._2
    }

    def createTenant(name: String, description: String = null): RequestResult = {
      BaseAPISpec.this.createTenant(name, description, cookies)
    }

    def updateTenant(oldName: String, newName: String = null, description: String = ""): RequestResult = {
      val name     = Objects.requireNonNull(newName, oldName)
      val response = await(
        ws.url(s"${ADMIN_BASE_URL}/tenants/${oldName}")
          .withCookies(cookies: _*)
          .put(
            Json.obj(
              "name"        -> name,
              "description" -> description
            )
          )
      )

      RequestResult(
        json = Try {
          response.json
        },
        status = response.status
      )
    }

    def createProject(name: String, tenant: String, description: String = null): RequestResult = {
      BaseAPISpec.this.createProject(name, tenant, description, cookies)
    }

    def updateProject(
        tenant: String,
        oldName: String,
        name: String = null,
        description: String = null
    ): RequestResult = {
      val newName  = Objects.requireNonNullElse(name, oldName)
      val response = await(
        ws.url(s"${ADMIN_BASE_URL}/tenants/${tenant}/projects/${oldName}")
          .withCookies(cookies: _*)
          .put(
            Json.obj(
              "name"        -> newName,
              "description" -> description
            )
          )
      )
      RequestResult(json = Try { response.json }, status = response.status)
    }

    def createTag(name: String, tenant: String, description: String = ""): RequestResult = {
      BaseAPISpec.this.createTag(name, tenant, description, cookies)
    }

    def createLegacyFeature(
        id: String,
        name: String,
        project: String,
        tenant: String,
        enabled: Boolean = true,
        tags: Seq[String] = Seq(),
        strategy: String = "NO_STRATEGY",
        parameters: JsObject = Json.obj()
    ): RequestResult = {
      val response = await(
        ws.url(s"${ADMIN_BASE_URL}/tenants/${tenant}/projects/${project}/features")
          .withCookies(cookies: _*)
          .post(Json.parse(s"""{
             |"id": "$id",
             |"name": "$name",
             |"tags": [${tags.map(name => s""""${name}"""").mkString(",")}],
             |"enabled": $enabled,
             |"activationStrategy": "$strategy",
             |"parameters": ${parameters.toString()}
             |}""".stripMargin))
      )

      RequestResult(Try { response.json }, status = response.status)
    }

    def readFeatureAsLegacy(
        pattern: String,
        clientId: String,
        clientSecret: String,
        body: Option[JsValue] = None
    ): RequestResult = {
      val response = await(
        body
          .map(json => {
            ws.url(s"${BASE_URL}/features/${pattern}/check")
              .withHttpHeaders(("Izanami-Client-Id" -> clientId), ("Izanami-Client-Secret" -> clientSecret))
              .post(json)
          })
          .getOrElse(
            ws.url(s"${BASE_URL}/features/${pattern}/check")
              .withHttpHeaders(("Izanami-Client-Id" -> clientId), ("Izanami-Client-Secret" -> clientSecret))
              .get()
          )
      )

      RequestResult(Try { response.json }, status = response.status)
    }

    def checkFeaturesLegacy(
        pattern: String,
        payload: JsObject,
        clientId: String,
        clientSecret: String,
        active: Boolean = false,
        page: Int = 1,
        pageSize: Int = 100
    ): RequestResult = {
      val response = await(
        ws
          .url(s"${BASE_URL}/features/_checks?pattern=$pattern&active=$active&pageSize=$pageSize&page=$page")
          .withHttpHeaders(
            ("Izanami-Client-Id"     -> clientId),
            ("Izanami-Client-Secret" -> clientSecret)
          )
          .post(payload)
      )

      RequestResult(
        Try {
          response.json
        },
        status = response.status
      )
    }

    def readFeaturesAsLegacy(
        pattern: String,
        clientId: String,
        clientSecret: String,
        active: Boolean = false,
        page: Int = 1,
        pageSize: Int = 100
    ): RequestResult = {
      val response = await(
        ws
          .url(s"${BASE_URL}/features?pattern=$pattern&active=$active&pageSize=$pageSize&page=$page")
          .withHttpHeaders(
            ("Izanami-Client-Id"     -> clientId),
            ("Izanami-Client-Secret" -> clientSecret)
          )
          .get()
      )

      RequestResult(
        Try {
          response.json
        },
        status = response.status
      )
    }

    def createFeature(
        name: String,
        project: String,
        tenant: String,
        enabled: Boolean = true,
        tags: Seq[String] = Seq(),
        metadata: JsObject = JsObject.empty,
        conditions: Set[TestCondition] = Set(),
        wasmConfig: TestWasmConfig = null,
        id: String = null,
        resultType: String = "boolean",
        value: String = null,
        description: String = null
    ): RequestResult = {
      BaseAPISpec.this.createFeature(
        name,
        project,
        tenant,
        enabled,
        tags,
        metadata,
        conditions,
        wasmConfig,
        id,
        cookies,
        resultType,
        value,
        description
      )
    }

    def updateFeature(tenant: String, id: String, json: JsValue): RequestResult = {
      BaseAPISpec.this.updateFeature(tenant, id, json, cookies)
    }

    def updateFeatureByName(
        tenant: String,
        project: String,
        name: String,
        transformer: JsObject => JsObject
    ): RequestResult = {
      val id              = this.findFeatureId(tenant, project, name).get
      val projectResponse = this.fetchProject(tenant, project)
      val jsonFeatures    = (projectResponse.json.get \ "features").as[JsArray]
      val jsonFeature     = jsonFeatures.value.find(js => (js \ "name").as[String] == name).map(js => js.as[JsObject]).get
      val newFeature      = transformer(jsonFeature)
      BaseAPISpec.this.updateFeature(tenant, id, newFeature, cookies)
    }

    def updateAPIKey(
        tenant: String,
        currentName: String,
        newName: String = null,
        description: String,
        projects: Seq[String],
        enabled: Boolean,
        admin: Boolean
    ): RequestResult = {
      BaseAPISpec.this.updateAPIKey(tenant, currentName, newName, description, projects, enabled, admin, cookies)
    }

    def deleteAPIKey(tenant: String, name: String): RequestResult = {
      BaseAPISpec.this.deleteAPIKey(tenant, name, cookies)
    }

    def fetchWasmManagerScripts(): RequestResult = {
      val response = await(ws.url("http://localhost:5001/api/plugins").get())

      val jsonTry = Try {
        response.json
      }
      RequestResult(json = jsonTry, status = response.status)
    }

    def deleteScript(tenant: String, script: String): RequestResult = {
      val response = await(
        ws.url(s"${ADMIN_BASE_URL}/tenants/${tenant}/local-scripts/${script}").withCookies(cookies: _*).delete()
      )

      val jsonTry = Try {
        response.json
      }
      RequestResult(json = jsonTry, status = response.status)
    }

    def updateScript(tenant: String, script: String, newConfig: TestWasmConfig): RequestResult = {
      val response = await(
        ws.url(s"${ADMIN_BASE_URL}/tenants/${tenant}/local-scripts/${script}")
          .withCookies(cookies: _*)
          .put(newConfig.json)
      )

      val jsonTry = Try {
        response.json
      }
      RequestResult(json = jsonTry, status = response.status)
    }

    def fetchTenantScripts(tenant: String): RequestResult = {
      val response = await(
        ws.url(s"${ADMIN_BASE_URL}/tenants/${tenant}/local-scripts").withCookies(cookies: _*).get()
      )

      val jsonTry = Try {
        response.json
      }
      RequestResult(json = jsonTry, status = response.status)
    }

    def fetchTenantScript(tenant: String, script: String): RequestResult = {
      val response = await(
        ws.url(s"${ADMIN_BASE_URL}/tenants/${tenant}/local-scripts/$script").withCookies(cookies: _*).get()
      )

      val jsonTry = Try {
        response.json
      }
      RequestResult(json = jsonTry, status = response.status)
    }

    def createFeatureWithRawConditions(
        name: String,
        project: String,
        tenant: String,
        enabled: Boolean = true,
        tags: Seq[String] = Seq(),
        metadata: JsObject = JsObject.empty,
        conditions: String
    ): RequestResult = {
      BaseAPISpec.this.createFeatureWithRawConditions(
        name,
        project,
        tenant,
        enabled,
        tags,
        metadata,
        conditions,
        cookies = cookies
      )
    }

    def createAPIKey(
        tenant: String,
        name: String,
        projects: Seq[String] = Seq(),
        enabled: Boolean = true,
        admin: Boolean = false,
        description: String = ""
    ): RequestResult = {
      BaseAPISpec.this.createAPIKey(tenant, name, projects, description, enabled, admin, cookies)
    }

    def changeFeatureStrategyForContext(
        tenant: String,
        project: String,
        contextPath: String,
        feature: String,
        enabled: Boolean,
        conditions: Set[TestCondition] = Set(),
        wasmConfig: TestWasmConfig = null,
        resultType: String = "boolean",
        value: String = null
    ) = {
      BaseAPISpec.this.changeFeatureStrategyForContext(
        tenant,
        project,
        contextPath,
        feature,
        enabled,
        conditions,
        wasmConfig,
        cookies,
        resultType,
        value
      )
    }

    def deleteFeatureOverload(tenant: String, project: String, path: String, feature: String): RequestResult = {
      val response = await(
        ws.url(s"${ADMIN_BASE_URL}/tenants/${tenant}/projects/${project}/contexts/${path}/features/${feature}")
          .withCookies(cookies: _*)
          .delete()
      )
      RequestResult(json = Try { response.json }, status = response.status)
    }

    def deleteContext(tenant: String, project: String, path: String): RequestResult = {
      val response = await(
        ws.url(s"${ADMIN_BASE_URL}/tenants/${tenant}/projects/${project}/contexts/${path}")
          .withCookies(cookies: _*)
          .delete()
      )
      RequestResult(json = Try { response.json }, status = response.status)
    }

    def deleteGlobalContext(tenant: String, path: String): RequestResult = {
      val response = await(
        ws.url(s"${ADMIN_BASE_URL}/tenants/${tenant}/contexts/${path}")
          .withCookies(cookies: _*)
          .delete()
      )
      RequestResult(
        json = Try {
          response.json
        },
        status = response.status
      )
    }

    def createContext(
        tenant: String,
        project: String,
        name: String,
        parents: String = "",
        isProtected: Boolean = false
    ): RequestResult = {
      BaseAPISpec.this.createContext(tenant, project, name, parents, isProtected, cookies)
    }

    def updateContext(
        tenant: String,
        project: String,
        name: String,
        isProtected: Boolean = false,
        parents: String = ""
    ): RequestResult = {
      BaseAPISpec.this.updateContext(tenant, project, name, isProtected, parents, cookies)
    }

    def updateGlobalContext(
        tenant: String,
        name: String,
        isProtected: Boolean = false,
        parents: String = ""
    ): RequestResult = {
      BaseAPISpec.this.updateGlobalContext(tenant, name, isProtected, parents, cookies)
    }

    def createGlobalContext(
        tenant: String,
        name: String,
        parents: String = "",
        isProtected: Boolean = false
    ): RequestResult = {
      BaseAPISpec.this.createGlobalContext(tenant, name, parents, isProtected, cookies)
    }

    def fetchTag(tenant: String, name: String): RequestResult = {
      BaseAPISpec.this.fetchTag(tenant, name, cookies)
    }

    def fetchTags(tenant: String): RequestResult = {
      BaseAPISpec.this.fetchTags(tenant, cookies)
    }

    def fetchAPIKeys(tenant: String): RequestResult = {
      BaseAPISpec.this.fetchAPIKeys(tenant, cookies)
    }

    def fetchProjects(tenant: String): RequestResult = {
      BaseAPISpec.this.fetchProjects(tenant, cookies)
    }

    def fetchSearchEntities(searchQuery: String): RequestResult = {
      BaseAPISpec.this.fetchSearchEntities(searchQuery, cookies)
    }

    def evaluateFeaturesAsLoggedInUser(
        tenant: String,
        oneTagIn: Seq[String] = Seq(),
        allTagsIn: Seq[String] = Seq(),
        noTagIn: Seq[String] = Seq(),
        projects: Seq[String] = Seq(),
        features: Seq[(String, String)] = Seq(),
        context: String = "",
        user: String = "",
        date: Instant = null
    ): RequestResult = {
      val url = s"${ADMIN_BASE_URL}/tenants/${tenant}/features/_test?oneTagIn=${oneTagIn
        .map(t => findTagId(tenant, t).get)
        .mkString(",")}&allTagsIn=${allTagsIn.map(t => findTagId(tenant, t).get).mkString(",")}&noTagIn=${noTagIn
        .map(t => findTagId(tenant, t).get)
        .mkString(",")}&projects=${projects.map(pname => findProjectId(tenant, pname).get).mkString(",")}&features=${features
        .map { case (project, feature) => findFeatureId(tenant, project, feature).get }
        .mkString(",")}${if (context.nonEmpty) s"&context=${context}" else ""}${if (user.nonEmpty) s"&user=${user}"
      else ""}"

      val response = await(ws.url(url).withCookies(cookies: _*).get())
      RequestResult(
        json = Try {
          response.json
        },
        status = response.status
      )
    }

    def testFeature(
        tenant: String,
        feature: TestFeature,
        date: OffsetDateTime = OffsetDateTime.now(),
        user: String = null
    ): RequestResult = {
      BaseAPISpec.this.testFeature(tenant, feature, date, user, cookies)
    }

    def testExistingFeature(
        tenant: String,
        featureId: String,
        context: String = "",
        date: OffsetDateTime = OffsetDateTime.now(),
        user: String = null
    ): RequestResult = {
      val response = await(
        ws.url(s"""${ADMIN_BASE_URL}/tenants/${tenant}/features/${featureId}/test${if (context.nonEmpty) s"/${context}"
        else ""}?date=${DateTimeFormatter.ISO_INSTANT
          .format(date)}${if (user != null)
          s"&user=${user}"
        else ""}""")
          .withCookies(cookies: _*)
          .post(Json.obj())
      )

      val jsonTry = Try {
        response.json
      }
      RequestResult(json = jsonTry, status = response.status)
    }

    def fetchContexts(tenant: String, project: String) = {
      BaseAPISpec.this.fetchContexts(tenant, project, cookies)
    }

    def fetchGlobalContext(tenant: String, all: Boolean = false) = {
      val response = await(
        ws.url(s"${ADMIN_BASE_URL}/tenants/${tenant}/contexts?all=$all").withCookies(cookies: _*).get()
      )

      val jsonTry = Try {
        response.json
      }
      RequestResult(json = jsonTry, status = response.status, idField = "name")
    }

    def fetchTenants(right: String = null): RequestResult = {
      BaseAPISpec.this.fetchTenants(right, cookies)
    }

    def fetchTenant(id: String): RequestResult = {
      BaseAPISpec.this.fetchTenant(id, cookies)
    }

    def checkFeatures(
        key: String,
        projects: Seq[String] = Seq(),
        oneTagIn: Seq[String] = Seq(),
        allTagsIn: Seq[String] = Seq(),
        noTagIn: Seq[String] = Seq(),
        user: String = null,
        contextPath: String = null,
        features: Seq[String] = Seq(),
        conditions: Boolean = false
    ): RequestResult = {
      BaseAPISpec.this.checkFeatures(
        headers = keyHeaders(key),
        projects = projects,
        oneTagIn = oneTagIn,
        allTagsIn = allTagsIn,
        noTagIn = noTagIn,
        user = user,
        contextPath = contextPath,
        features = features,
        conditions = conditions
      )
    }

    def checkFeaturesWithRawKey(
        clientId: String,
        clientSecret: String,
        projects: Seq[String] = Seq(),
        oneTagIn: Seq[String] = Seq(),
        allTagsIn: Seq[String] = Seq(),
        noTagIn: Seq[String] = Seq(),
        user: String = null,
        contextPath: String = null,
        features: Seq[String] = Seq()
    ): RequestResult = {
      BaseAPISpec.this.checkFeatures(
        headers = keyHeaders(clientId, clientSecret),
        projects = projects,
        oneTagIn = oneTagIn,
        allTagsIn = allTagsIn,
        noTagIn = noTagIn,
        user = user,
        contextPath = contextPath,
        features = features
      )
    }

    def checkFeature(
        id: String,
        key: String,
        user: String = null,
        context: String = null,
        conditions: Boolean = false
    ): RequestResult = {
      BaseAPISpec.this.checkFeature(id, user, headers = keyHeaders(key), context = context, conditions = conditions)
    }

    def keyHeaders(name: String): Map[String, String] = {
      val key = keys(name)
      Map("Izanami-Client-Id" -> key.clientId, "Izanami-Client-Secret" -> key.clientSecret)
    }

    def keyHeaders(clientId: String, clientSecret: String): Map[String, String] = {
      Map("Izanami-Client-Id" -> clientId, "Izanami-Client-Secret" -> clientSecret)
    }

    def fetchProject(tenant: String, projectId: String): RequestResult = {
      BaseAPISpec.this.fetchProject(tenant, projectId, cookies)
    }

    def createUser(
        user: String,
        password: String,
        admin: Boolean = false,
        rights: TestRights = null,
        email: String = null
    ): RequestResult = {
      BaseAPISpec.this.createUser(user, password, admin, rights, email, cookies)
    }

    def createUserWithToken(
        user: String,
        password: String,
        token: String
    ): RequestResult = {
      BaseAPISpec.this.createUserWithToken(user, password, token)
    }

    def updateUserInformation(
        oldName: String,
        email: String,
        password: String,
        newName: String = null
    ): RequestResult = {
      val name     = Option(newName).getOrElse(oldName)
      val response = await(
        ws.url(s"${ADMIN_BASE_URL}/users/${oldName}")
          .withCookies(cookies: _*)
          .put(
            Json.obj(
              "username" -> name,
              "email"    -> email,
              "password" -> password
            )
          )
      )
      RequestResult(json = Try { response.json }, status = response.status)
    }

    def updateUserRightsForTenant(
        name: String,
        rights: TestTenantRight
    ): RequestResult = {
      val jsonRight = rights.json
      val response  = await(
        ws.url(s"${ADMIN_BASE_URL}/${rights.name}/users/${name}/rights")
          .withCookies(cookies: _*)
          .put(jsonRight)
      )
      RequestResult(json = Try { response.json }, status = response.status)
    }

    def updateUserRightsForProject(
        name: String,
        tenant: String,
        project: String,
        level: String = null
    ): RequestResult = {
      val payload  = if (Objects.isNull(level)) Json.obj() else Json.obj("level" -> level)
      val response = await(
        ws.url(s"${ADMIN_BASE_URL}/tenants/${tenant}/projects/${project}/users/${name}/rights")
          .withCookies(cookies: _*)
          .put(payload)
      )
      RequestResult(
        json = Try {
          response.json
        },
        status = response.status
      )
    }

    def updateUserRights(
        name: String,
        admin: Boolean,
        rights: TestRights
    ): RequestResult = {
      val jsonRights = Option(rights).getOrElse(TestRights()).json
      val response   = await(
        ws.url(s"${ADMIN_BASE_URL}/users/${name}/rights")
          .withCookies(cookies: _*)
          .put(
            Json.obj(
              "admin"  -> admin,
              "rights" -> jsonRights
            )
          )
      )

      RequestResult(json = Try { response.json }, status = response.status)
    }
    def updateUserPassword(user: String, oldPassword: String, newPassword: String): RequestResult = {
      val response = await(
        ws.url(s"${ADMIN_BASE_URL}/users/${user}/password")
          .withCookies(cookies: _*)
          .put(
            Json.obj(
              "password"    -> newPassword,
              "oldPassword" -> oldPassword
            )
          )
      )

      RequestResult(json = Try { response.json }, status = response.status)
    }

    def sendInvitation(email: String, admin: Boolean = false, rights: TestRights = null): RequestResult = {
      BaseAPISpec.this.sendInvitation(email = email, admin = admin, rights = rights, cookies = cookies)
    }

    def fetchUserRights(): RequestResult = {
      val response = await(
        ws.url(s"${ADMIN_BASE_URL}/users/rights")
          .withCookies(cookies: _*)
          .get()
      )
      RequestResult(json = Try { response.json }, status = response.status)
    }

    def fetchUser(user: String): RequestResult = {
      val response = await(
        ws.url(s"${ADMIN_BASE_URL}/users/${user}")
          .withCookies(cookies: _*)
          .get()
      )
      RequestResult(json = Try { response.json }, status = response.status)
    }

    def searchUsers(search: String, count: Integer): RequestResult = {
      val response = await(
        ws.url(s"${ADMIN_BASE_URL}/users/search?query=${search}&count=${count}")
          .withCookies(cookies: _*)
          .get()
      )
      RequestResult(
        json = Try {
          response.json
        },
        status = response.status
      )
    }

    def fetchUserForTenant(user: String, tenant: String): RequestResult = {
      val response = await(
        ws.url(s"${ADMIN_BASE_URL}/${tenant}/users/${user}")
          .withCookies(cookies: _*)
          .get()
      )
      RequestResult(json = Try { response.json }, status = response.status)
    }

    def fetchUsersForTenant(tenant: String): RequestResult = {
      val response = await(
        ws.url(s"${ADMIN_BASE_URL}/tenants/${tenant}/users")
          .withCookies(cookies: _*)
          .get()
      )
      RequestResult(json = Try { response.json }, status = response.status)
    }

    def inviteUsersToTenants(tenant: String, users: Seq[(String, String)]): RequestResult = {
      val payload  = Json.toJson(users.map { case (username, level) =>
        Json.obj("username" -> username, "level" -> level)
      })
      val response = await(
        ws.url(s"${ADMIN_BASE_URL}/tenants/${tenant}/users")
          .withCookies(cookies: _*)
          .post(payload)
      )
      RequestResult(
        json = Try {
          response.json
        },
        status = response.status
      )
    }

    def inviteUsersToProject(tenant: String, project: String, users: Seq[(String, String)]): RequestResult = {
      val payload  = Json.toJson(users.map { case (username, level) =>
        Json.obj("username" -> username, "level" -> level)
      })
      val response = await(
        ws.url(s"${ADMIN_BASE_URL}/tenants/${tenant}/projects/${project}/users")
          .withCookies(cookies: _*)
          .post(payload)
      )
      RequestResult(
        json = Try {
          response.json
        },
        status = response.status
      )
    }

    def fetchUsersForProject(tenant: String, project: String): RequestResult = {
      val response = await(
        ws.url(s"${ADMIN_BASE_URL}/tenants/${tenant}/projects/${project}/users")
          .withCookies(cookies: _*)
          .get()
      )
      RequestResult(
        json = Try {
          response.json
        },
        status = response.status
      )
    }

    def deleteFeature(tenant: String, id: String): RequestResult = {
      BaseAPISpec.this.deleteFeature(tenant, id, cookies)
    }

    def fetchUsers(): RequestResult = {
      val response = await(
        ws.url(s"${ADMIN_BASE_URL}/users")
          .withCookies(cookies: _*)
          .get()
      )
      RequestResult(json = Try { response.json }, status = response.status)
    }

    def deleteUser(username: String): RequestResult = {
      val response = await(
        ws.url(s"${ADMIN_BASE_URL}/users/${username}")
          .withCookies(cookies: _*)
          .delete()
      )
      RequestResult(json = Try { response.json }, status = response.status)
    }

    def deleteTenant(tenant: String): RequestResult = {
      val response = await(
        ws.url(s"${ADMIN_BASE_URL}/tenants/${tenant}")
          .withCookies(cookies: _*)
          .delete()
      )
      RequestResult(json = Try { response.json }, status = response.status)
    }

    def deleteProject(project: String, tenant: String): RequestResult = {
      val response = await(
        ws.url(s"${ADMIN_BASE_URL}/tenants/${tenant}/projects/${project}")
          .withCookies(cookies: _*)
          .delete()
      )
      RequestResult(json = Try { response.json }, status = response.status)
    }

    def deleteTag(tenant: String, name: String): RequestResult = {
      val response = await(
        ws.url(s"${ADMIN_BASE_URL}/tenants/${tenant}/tags/${name}")
          .withCookies(cookies: _*)
          .delete()
      )
      RequestResult(json = Try { response.json }, status = response.status)
    }

    def updateTag(tenant: String, testTag: TestTag, currentName: String): RequestResult = {
      val response = await(
        ws.url(s"${ADMIN_BASE_URL}/tenants/${tenant}/tags/${currentName}")
          .withCookies(cookies: _*)
          .put(
            Json.obj(
              "name"        -> testTag.name,
              "description" -> testTag.description
            )
          )
      )
      RequestResult(json = Try { response.json }, status = response.status)
    }

    def updateConfiguration(
        mailerConfiguration: JsObject = Json.obj("mailer" -> "Console"),
        invitationMode: String = "Response",
        originEmail: String = null,
        oidcConfiguration: JsObject = DEFAULT_OIDC_CONFIG
    ): RequestResult = {
      BaseAPISpec.this.updateConfiguration(mailerConfiguration, invitationMode, originEmail, oidcConfiguration, cookies)
    }

    def fetchConfiguration(): RequestResult = {
      val response = await(
        ws.url(s"${ADMIN_BASE_URL}/configuration")
          .withCookies(cookies: _*)
          .get()
      )
      RequestResult(json = Try { response.json }, status = response.status)
    }

    def deleteImportResult(tenant: String, id: String): RequestResult = {
      val response = await(
        ws.url(s"${ADMIN_BASE_URL}/tenants/${tenant}/_import/$id")
          .withCookies(cookies: _*)
          .delete()
      )

      RequestResult(
        json = Try {
          response.json
        },
        status = response.status
      )
    }

    def importV2(
        tenant: String,
        conflictStrategy: String = "fail",
        data: Seq[String] = Seq()
    ): RequestResult = {
      val dataFile = writeTemporaryFile("export", "ndjson", data)

      val response = await(
        ws.url(
          s"${ADMIN_BASE_URL}/tenants/${tenant}/_import?version=2&conflict=${conflictStrategy}"
        ).withCookies(cookies: _*)
          .post(
            Source(
              Seq(
                FilePart("export", "export.ndjson", Option("text/plain"), FileIO.fromPath(dataFile.toPath))
              )
            )
          )
      )
      RequestResult(json = Try { response.json }, status = response.status)
    }

    def importAndWaitTermination(
        tenant: String,
        timezone: String = "Europe/Paris",
        conflictStrategy: String = "FAIL",
        deduceProject: Boolean = true,
        project: String = null,
        projectPartSize: Option[Int] = None,
        features: Seq[String] = Seq(),
        users: Seq[String] = Seq(),
        keys: Seq[String] = Seq(),
        scripts: Seq[String] = Seq(),
        inlineScript: Boolean = false
    ): RequestResult = {

      val response = importV1Data(
        tenant,
        timezone,
        conflictStrategy,
        deduceProject,
        project,
        projectPartSize,
        features,
        users,
        keys,
        scripts,
        inlineScript
      )

      def request(): RequestResult = {
        checkImportStatus(tenant, response.id.get)
      }

      val promise = Promise[RequestResult]()

      Future {
        var checkResponse = request()
        while (true) {
          if ((checkResponse.json.get \ "status").as[String] != "Pending") {
            promise.success(checkResponse)
          }
          Thread.sleep(200)
          checkResponse = request()
        }
      }

      await(promise.future)(120.seconds)
    }

    def checkImportStatus(tenant: String, id: String): RequestResult = {
      val response = await(
        ws.url(s"${ADMIN_BASE_URL}/tenants/${tenant}/_import/$id")
          .withCookies(cookies: _*)
          .get()
      )

      RequestResult(json = Try { response.json }, status = response.status)
    }

    def importV1Data(
        tenant: String,
        timezone: String = "Europe/Paris",
        conflictStrategy: String = "FAIL",
        deduceProject: Boolean = true,
        project: String = null,
        projectPartSize: Option[Int] = None,
        features: Seq[String] = Seq(),
        users: Seq[String] = Seq(),
        keys: Seq[String] = Seq(),
        scripts: Seq[String] = Seq(),
        inlineScript: Boolean = false
    ): RequestResult = {
      shouldCleanUpWasmServer = true
      val featureFile = writeTemporaryFile("features", "ndjson", features)
      val userFile    = writeTemporaryFile("users", "ndjson", users)
      val keyFile     = writeTemporaryFile("keys", "ndjson", keys)
      val scriptFile  = writeTemporaryFile("scripts", "ndjson", scripts)

      val response = await(
        ws.url(
          s"${ADMIN_BASE_URL}/tenants/${tenant}/_import?version=1&timezone=${timezone}&conflict=${conflictStrategy}&deduceProject=${deduceProject}${Option(project)
            .map(p => s"&project=${p}")
            .getOrElse("")}${projectPartSize.map(p => s"&projectPartSize=${p}").getOrElse("")}&inlineScript=${inlineScript}"
        ).withCookies(cookies: _*)
          .post(
            Source(
              Seq(
                FilePart("features", "features.ndjson", Option("text/plain"), FileIO.fromPath(featureFile.toPath)),
                FilePart("users", "users.ndjson", Option("text/plain"), FileIO.fromPath(userFile.toPath)),
                FilePart("keys", "keys.ndjson", Option("text/plain"), FileIO.fromPath(keyFile.toPath)),
                FilePart("scripts", "scripts.ndjson", Option("text/plain"), FileIO.fromPath(scriptFile.toPath))
              )
            )
          )
      )
      RequestResult(json = Try { response.json }, status = response.status)
    }

  }

  case class TestUser(
      username: String,
      password: String = "barbar123",
      admin: Boolean = false,
      rights: TestRights = null,
      email: String = null
  ) {
    def withAdminRights          = copy(admin = true)
    def withEmail(email: String) = copy(email = email)
    def withTenantReadRight(tenant: String) = {
      val newRights = Option(rights).getOrElse(TestRights())
      copy(rights = newRights.addTenantRight(tenant, "Read"))
    }
    def withTenantReadWriteRight(tenant: String) = {
      val newRights = Option(rights).getOrElse(TestRights())
      copy(rights = newRights.addTenantRight(tenant, "Write"))
    }
    def withTenantAdminRight(tenant: String) = {
      val newRights = Option(rights).getOrElse(TestRights())
      copy(rights = newRights.addTenantRight(tenant, "Admin"))
    }
    def withProjectReadRight(project: String, tenant: String) = {
      copy(rights = rights.addProjectRight(project, tenant, "Read"))
    }
    def withProjectReadWriteRight(project: String, tenant: String) = {
      copy(rights = rights.addProjectRight(project, tenant, "Write"))
    }
    def withProjectAdminRight(project: String, tenant: String) = {
      copy(rights = rights.addProjectRight(project, tenant, "Admin"))
    }
    def withApiKeyReadRight(key: String, tenant: String) = {
      copy(rights = rights.addKeyRight(key, tenant, "Read"))
    }

    def withApiKeyReadWriteRight(key: String, tenant: String) = {
      copy(rights = rights.addKeyRight(key, tenant, "Write"))
    }

    def withApiKeyAdminRight(key: String, tenant: String) = {
      copy(rights = rights.addKeyRight(key, tenant, "Admin"))
    }
  }

  case class TestConfiguration(
      mailerConfiguration: JsObject,
      invitationMode: String,
      originEmail: String,
      oidcConfiguration: JsObject
  )

  case class TestWasmScript(name: String, content: String)

  val ALL_RIGHTS_USERNAME          = "RESERVED_ADMIN_USER"
  val ALL_RIGHTS_USERNAME_PASSWORD = "ADMIN_DEFAULT_PASSWORD"

  case class TestSituationBuilder(
      tenants: Set[TestTenant] = Set(),
      users: Set[TestUser] = Set(),
      loggedInUser: Option[String] = None,
      configuration: TestConfiguration = TestConfiguration(
        mailerConfiguration = Json.obj("mailer" -> "Console"),
        invitationMode = "Response",
        originEmail = null,
        oidcConfiguration = DEFAULT_OIDC_CONFIG
      ),
      wasmScripts: Seq[TestWasmScript] = Seq(),
      webhookServers: Map[Int, (Int, String)] = Map(),
      personnalAccessTokens: Set[TestPersonnalAccessToken] = Set(),
      customConfiguration: Map[String, AnyRef] = Map()
  ) {

    def withWebhookServer(port: Int, responseCode: Int = OK, path: String = "/"): TestSituationBuilder = {
      copy(webhookServers = webhookServers + (port -> (responseCode, path)))
    }

    def withWasmScript(
        name: String,
        content: String
    ): TestSituationBuilder = {
      BaseAPISpec.this.shouldCleanUpWasmServer = true
      copy(wasmScripts = this.wasmScripts.appended(TestWasmScript(name, content)))
    }

    def withAllwaysActiveWasmScript(name: String): TestSituationBuilder = {
      BaseAPISpec.this.shouldCleanUpWasmServer = true
      withWasmScript(
        name,
        s"""export function execute() {
           |  Host.outputString("true");
           |  return 0;
           |}
           |
           |""".stripMargin
      )
    }

    def withAllwaysInactiveWasmScript(name: String): TestSituationBuilder = {
      BaseAPISpec.this.shouldCleanUpWasmServer = true
      withWasmScript(
        name,
        s"""export function execute() {
           |  Host.outputString("false");
           |  return 0;
           |}
           |
           |""".stripMargin
      )
    }

    /*def withWasmScript(enabled: Boolean = true): TestSituationBuilder = {
      val name = if (enabled) "izanami-enabled" else "izanami-disabled"
      copy(wasmScripts = wasmScripts + (name -> enabled))
    }*/

    def withOriginEmail(email: String): TestSituationBuilder = {
      BaseAPISpec.this.shouldCleanUpMails = true
      copy(configuration = configuration.copy(originEmail = email))
    }

    def withMailerConfiguration(mailer: String, configuration: JsObject): TestSituationBuilder = {
      BaseAPISpec.this.shouldCleanUpMails = true
      copy(configuration =
        this.configuration.copy(mailerConfiguration = configuration + ("mailer" -> JsString(mailer)))
      )
    }

    def withInvitationMode(invitationMode: String): TestSituationBuilder = {
      BaseAPISpec.this.shouldCleanUpMails = true
      copy(configuration = configuration.copy(invitationMode = invitationMode))
    }

    def loggedAs(username: String): TestSituationBuilder = {
      copy(loggedInUser = Some(username))
    }

    def loggedInWithAdminRights(): TestSituationBuilder = {
      copy(loggedInUser = Some(ALL_RIGHTS_USERNAME))
    }

    def withUsers(users: TestUser*): TestSituationBuilder = {
      copy(users = this.users ++ users)
    }

    def withPersonnalAccessToken(tokens: TestPersonnalAccessToken*): TestSituationBuilder = {
      copy(personnalAccessTokens = this.personnalAccessTokens ++ tokens)
    }

    def withTenants(tenants: TestTenant*): TestSituationBuilder = {
      copy(tenants = this.tenants ++ tenants)
    }

    def withTenantNames(tenants: String*): TestSituationBuilder = {
      copy(tenants = this.tenants ++ tenants.map(t => TestTenant(name = t)))
    }

    def withCustomConfiguration(config: Map[String, AnyRef]): TestSituationBuilder = {
      copy(customConfiguration = config)
    }

    def build(): TestSituation = {
      if (customConfiguration.nonEmpty) {
        BaseAPISpec.this.shouldRestartInstance = true
      }

      val runningServer = if (izanamiInstance == null) {
        startServer(customConfiguration)
      } else if (customConfiguration.nonEmpty) {
        println("Custom configuration was provided, restarting already running Izanami instance")
        stopServer()
        println("Already running instance was stopped, restarting new instance with custom config")
        startServer(customConfiguration)
      } else {
        BaseAPISpec.this.izanamiInstance
      }
      //val runningServer = startServer(customConfiguration)

      var scriptIds: Map[String, String]                                              = Map()
      var keyData: Map[String, TestSituationKey]                                      = Map()
      val featuresData: TrieMap[String, TrieMap[
        String,
        TrieMap[String, String]
      ]]                                                                              = scala.collection.concurrent.TrieMap()
      val projectsData: TrieMap[String, TrieMap[String, String]]                      =
        TrieMap()
      val webhooksData: TrieMap[String, TrieMap[String, String]]                      =
        TrieMap()
      val tagsData: TrieMap[String, TrieMap[String, String]]                          =
        TrieMap()
      val tokenIdAndSecretsByUser: TrieMap[String, TrieMap[String, (String, String)]] = TrieMap()

      webhookServers.foreach { case (port, (status, path)) =>
        setupWebhookServer(port = port, path = path, responseCode = status)
      }

      val buildCookies = {
        val response = BaseAPISpec.this.login(ALL_RIGHTS_USERNAME, ALL_RIGHTS_USERNAME_PASSWORD)
        response.cookies.toArray.to(scala.collection.immutable.Seq)
      }

      val futures: ArrayBuffer[Future[Any]] = ArrayBuffer()

      val wasmManagerFuture = ws
        .url(s"${ADMIN_BASE_URL}/local-scripts/_cache")
        .withCookies(buildCookies: _*)
        .delete()
        .flatMap(_ => {
          val wasmManagerClient = WasmManagerClient(ws, "http://localhost:5001")
          wasmScripts.foldLeft(Future.successful(())) {
            case (future, TestWasmScript(name, fileContent)) => {
              future
                .flatMap(_ => wasmManagerClient.createScript(name, fileContent, local = false))
                .map(ids => {
                  //scriptIds = scriptIds + (name -> ids._2);
                  ids._2
                })
            }
          }
        })
      futures.addOne(wasmManagerFuture)

      val tenantFuture = Future.sequence(tenants.map(tenant => {
        tagsData.put(tenant.name, TrieMap())
        featuresData.put(tenant.name, TrieMap())
        projectsData.put(tenant.name, TrieMap())
        createTenantAsync(name = tenant.name, description = tenant.description, cookies = buildCookies)
          .map { res =>
            if (res.status >= 400) {
              throw new RuntimeException("Failed to create tenant")
            } else ()
          }
          .flatMap(_ => {
            Future
              .sequence(tenant.contexts.map(ctx => {
                createGlobalContextHierarchyAsync(tenant.name, ctx, cookies = buildCookies)
              }))
              .flatMap(_ =>
                Future.sequence(tenant.tags.map(tag => {
                  createTagAsync(
                    name = tag.name,
                    tenant = tenant.name,
                    description = tag.description,
                    cookies = buildCookies
                  )
                    .map(res => {
                      if (res.status >= 400) {
                        throw new RuntimeException("Failed to create tags")
                      } else {
                        val id = (res.json \ "id").get.as[String]
                        tagsData
                          .getOrElse(
                            tenant.name, {
                              val map = TrieMap[String, String]()
                              tagsData.put(tenant.name, map)
                              map
                            }
                          )
                          .put(tag.name, id)
                      }
                    })
                }))
              )
              .flatMap(_ =>
                Future.sequence(tenant.projects.map(project => {
                  createProjectAsync(
                    name = project.name,
                    tenant = tenant.name,
                    description = project.description,
                    cookies = buildCookies
                  ).map(res => {
                    if (res.status >= 400) {
                      throw new RuntimeException("Failed to create projects")
                    } else {
                      val id = (res.json \ "id").get.as[String]
                      projectsData
                        .getOrElse(
                          tenant.name, {
                            val map = TrieMap[String, String]()
                            projectsData.put(tenant.name, map)
                            map
                          }
                        )
                        .put(project.name, id)
                    }
                  }).flatMap(_ => {
                    val tenantMap  = featuresData.getOrElse(
                      tenant.name, {
                        val map = TrieMap[String, TrieMap[String, String]]()
                        featuresData.put(tenant.name, map)
                        map
                      }
                    )
                    val projectMap = tenantMap.getOrElse(
                      project.name, {
                        val map = TrieMap[String, String]()
                        tenantMap.put(project.name, map)
                        map
                      }
                    )
                    Future
                      .sequence(
                        project.features
                          .map(feature => {
                            createFeatureAsync(
                              name = feature.name,
                              project = project.name,
                              tenant = tenant.name,
                              enabled = feature.enabled,
                              tags = feature.tags,
                              metadata = feature.metadata,
                              conditions = feature.conditions,
                              wasmConfig = feature.wasmConfig,
                              id = feature.id,
                              cookies = buildCookies,
                              resultType = feature.resultType,
                              value = feature.value
                            ).map(res => {
                              if (res.status >= 400) {
                                throw new RuntimeException("Failed to create features")
                              } else {
                                projectMap.put(feature.name, (res.json \ "id").as[String])
                              }
                            })
                          })
                      )
                      .flatMap(_ => {
                        Future.sequence(project.contexts.map(context => {
                          createContextHierarchyAsync(tenant.name, project.name, context, cookies = buildCookies)
                        }))
                      })
                  })
                }))
              )
              .flatMap(_ =>
                Future.sequence(
                  tenant.webhooks
                    .map(webhook => {
                      TestWebhook(
                        name = webhook.name,
                        url = webhook.url,
                        features = webhook.features.map { case (tenant, project, name) =>
                          featuresData(tenant)(project)(name)
                        },
                        projects = webhook.projects.map { case (tenant, project) =>
                          projectsData(tenant)(project)
                        },
                        headers = webhook.headers,
                        description = webhook.description,
                        user = webhook.user,
                        enabled = webhook.enabled,
                        context = webhook.context,
                        bodyTemplate = webhook.bodyTemplate,
                        global = webhook.global
                      )
                    })
                    .map(webhook => {
                      createWebhook(
                        tenant = tenant.name,
                        webhook = webhook,
                        cookies = buildCookies
                      )
                        .map(res => {
                          if (res.status >= 400) {
                            throw new RuntimeException("Failed to create tags")
                          } else {
                            val id = (res.json \ "id").get.as[String]
                            webhooksData
                              .getOrElse(
                                tenant.name, {
                                  val map = TrieMap[String, String]()
                                  webhooksData.put(tenant.name, map)
                                  map
                                }
                              )
                              .put(webhook.name, id)
                          }
                        })
                    })
                )
              )
              .flatMap(_ => {
                Future
                  .sequence(
                    tenant.apiKeys
                      .map(key => {
                        createAPIKeyAsync(
                          tenant = tenant.name,
                          name = key.name,
                          projects = key.projects,
                          description = key.description,
                          enabled = key.enabled,
                          admin = key.admin,
                          cookies = buildCookies
                        )
                      })
                      .concat(tenant.allRightKeys.map(ak => {
                        createAPIKeyAsync(
                          tenant = tenant.name,
                          name = ak,
                          projects = tenant.projects.map(p => p.name).toSeq,
                          cookies = buildCookies
                        )
                      }))
                  )
                  .map(responses => {
                    responses
                      .map(result => {
                        val json = result.json
                        TestSituationKey(
                          name = (json \ "name").as[String],
                          clientId = (json \ "clientId").as[String],
                          clientSecret = (json \ "clientSecret").as[String],
                          enabled = (json \ "enabled").as[Boolean]
                        )
                      })
                      .foreach(key => keyData = keyData + (key.name -> key))
                  })
              })
              .flatMap(_ => {
                Future
                  .sequence(
                    personnalAccessTokens
                      .map(token => {
                        createPersonnalAccessToken(
                          token,
                          user = loggedInUser.get,
                          cookies = buildCookies
                        ).map(resp => {
                          val secret = (resp.json \ "token").as[String]
                          val id     = (resp.json \ "id").as[String]
                          tokenIdAndSecretsByUser
                            .getOrElse(
                              loggedInUser.get, {
                                val newMap = TrieMap[String, (String, String)]()
                                tokenIdAndSecretsByUser.update(loggedInUser.get, newMap)
                                newMap
                              }
                            )
                            .update(token.name, (id, secret))
                          resp
                        })
                      })
                  )
              })
          })
      }))
      futures.addOne(tenantFuture)

      futures.addOne(
        updateConfigurationAsync(
          configuration.mailerConfiguration,
          configuration.invitationMode,
          configuration.originEmail,
          configuration.oidcConfiguration,
          buildCookies
        )
          .map(configurationResponse => {
            if (configurationResponse.status >= 400) {
              throw new Exception("Failed to update configuration")
            } else {
              ()
            }
          })
      )

      await(
        Future
          .sequence(futures)
          .flatMap(_ => {
            Future.sequence(users.map(user => {
              createUserAsync(
                user.username,
                user.password,
                user.admin,
                user.rights,
                email = user.email,
                cookies = buildCookies
              ).map(res => {
                if (res.status >= 400) {
                  throw new RuntimeException("Failed to create user")
                } else ()
              })
            }))
          })
      )(2.minutes)

      val cookies = {
        if (loggedInUser.exists(u => u.equals(ALL_RIGHTS_USERNAME))) {
          buildCookies
        } else {
          loggedInUser
            .map(username => {
              val user     = users.find(u => u.username.equals(username)).get
              val response = BaseAPISpec.this.login(user.username, user.password)
              response.cookies.toArray.to(scala.collection.immutable.Seq)
            })
            .getOrElse(Seq())
        }
      }

      val immutableFeatureData = featuresData.view.mapValues(v => v.view.mapValues(vv => vv.toMap).toMap).toMap
      val immutableProjectData = projectsData.view.mapValues(v => v.toMap).toMap
      val immutableTagData     = tagsData.view.mapValues(v => v.toMap).toMap
      val immutableWebhookData = webhooksData.view.mapValues(v => v.toMap).toMap
      val immutableTokenData   = tokenIdAndSecretsByUser.view.mapValues(v => v.toMap).toMap

      TestSituation(
        keys = keyData,
        cookies = cookies,
        features = immutableFeatureData,
        projects = immutableProjectData,
        tags = immutableTagData,
        scripts = scriptIds,
        webhooks = immutableWebhookData,
        user = loggedInUser.getOrElse(null),
        tokenData = immutableTokenData,
        server = runningServer
      )
    }
  }
}
