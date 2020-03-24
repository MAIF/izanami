package filters

import java.nio.charset.StandardCharsets
import java.util.Base64

import akka.NotUsed
import akka.actor.ActorSystem
import akka.stream.scaladsl.Source
import akka.stream.{ActorMaterializer, Materializer}
import com.auth0.jwt.JWT
import com.auth0.jwt.algorithms.Algorithm
import com.codahale.metrics.MetricRegistry
import domains.{AuthInfo, Key}
import domains.abtesting.ExperimentVariantEventService
import domains.apikey.ApiKeyContext
import domains.errors.IzanamiErrors
import domains.events.EventStore
import domains.script.Script.ScriptCache
import env.{
  ApiKeyHeaders,
  ApikeyConfig,
  DefaultFilter,
  InitializeApiKey,
  IzanamiConfig,
  MetricsConfig,
  MetricsConsoleConfig,
  MetricsElasticConfig,
  MetricsHttpConfig,
  MetricsKafkaConfig,
  MetricsLogConfig
}
import libs.database.Drivers
import libs.logs.{Logger, ProdLogger}
import metrics.MetricsContext
import org.apache.commons.io.Charsets
import play.api.{Environment, Mode}
import play.api.inject.ApplicationLifecycle
import play.api.libs.json.{JsValue, Json}
import play.api.mvc.{Cookie, Result, Results}
import play.api.test.FakeRequest
import store.{DataStoreContext, JsonDataStore, PagingResult, Query}
import test.IzanamiSpec
import zio.blocking.Blocking
import zio.clock.Clock
import zio.{RIO, Runtime, Task, ZIO}
import zio.internal.{Executor, PlatformLive}

import scala.concurrent.ExecutionContext
import scala.concurrent.duration._

class ZioIzanamiDefaultFilterTest extends IzanamiSpec {

  implicit val sys = ActorSystem("test")

  private val env = new ApiKeyContext with MetricsContext with Clock.Live {
    override def logger: Logger                 = new ProdLogger
    override def metricRegistry: MetricRegistry = new MetricRegistry()
    override implicit def system: ActorSystem   = sys
    override implicit def mat: Materializer     = Materializer(sys)
    override def authInfo: Option[AuthInfo]     = None
    override def apikeyDataStore: JsonDataStore = new JsonDataStore {
      override def create(id: Key, data: JsValue): ZIO[DataStoreContext, IzanamiErrors, JsValue]             = ???
      override def update(oldId: Key, id: Key, data: JsValue): ZIO[DataStoreContext, IzanamiErrors, JsValue] = ???
      override def delete(id: Key): ZIO[DataStoreContext, IzanamiErrors, JsValue]                            = ???
      override def deleteAll(query: Query): ZIO[DataStoreContext, IzanamiErrors, Unit]                       = ???
      override def getById(id: Key): RIO[DataStoreContext, Option[JsValue]] = id.key match {
        case "id" =>
          RIO(
            Some(
              Json.obj(
                "clientId"           -> id,
                "clientSecret"       -> "secret",
                "name"               -> id,
                "authorizedPatterns" -> "*",
                "admin"              -> false
              )
            )
          )
        case _ => ZIO(None)
      }
      override def findByQuery(query: Query,
                               page: Int,
                               nbElementPerPage: Int): RIO[DataStoreContext, PagingResult[JsValue]]  = ???
      override def findByQuery(query: Query): RIO[DataStoreContext, Source[(Key, JsValue), NotUsed]] = ???
      override def count(query: Query): RIO[DataStoreContext, Long]                                  = ???
    }
    override def featureDataStore: JsonDataStore                              = ???
    override def webhookDataStore: JsonDataStore                              = ???
    override def userDataStore: JsonDataStore                                 = ???
    override def scriptCache: ScriptCache                                     = ???
    override def izanamiConfig: IzanamiConfig                                 = ???
    override def globalScriptDataStore: JsonDataStore                         = ???
    override def experimentDataStore: JsonDataStore                           = ???
    override def experimentVariantEventService: ExperimentVariantEventService = ???
    override def eventStore: EventStore                                       = ???
    override def configDataStore: JsonDataStore                               = ???
    override def withAuthInfo(user: Option[AuthInfo]): MetricsContext         = ???
    override def environment: Environment                                     = ???
    override def wSClient: play.api.libs.ws.WSClient                          = ???
    override def javaWsClient: play.libs.ws.WSClient                          = ???
    override def ec: ExecutionContext                                         = ???
    override def applicationLifecycle: ApplicationLifecycle                   = ???
    override val blocking: Blocking.Service[Any] = new Blocking.Service[Any] {
      def blockingExecutor: ZIO[Any, Nothing, Executor] =
        ZIO.succeed(Executor.makeDefault(20))
    }
    override def drivers: Drivers = ???
  }

  implicit val r: Runtime[ApiKeyContext with MetricsContext] = Runtime(env, PlatformLive.Default)

  private val config = DefaultFilter(
    allowedPaths = Seq("/excluded"),
    issuer = "issuer",
    sharedKey = "key",
    cookieClaim = "claim",
    apiKeys = ApiKeyHeaders(headerClientId = "Izanami-Client-Id", headerClientSecret = "Izanami-Client-Secret")
  )

  private val algorithm = Algorithm.HMAC512(config.sharedKey)

  private val metricsConfig = MetricsConfig(
    verbose = true,
    includeCount = true,
    refresh = 30.seconds,
    console = MetricsConsoleConfig(false, 30.seconds),
    log = MetricsLogConfig(false, 30.seconds),
    http = MetricsHttpConfig("json"),
    kafka = MetricsKafkaConfig(false, "topic", "json", 30.seconds),
    elastic = MetricsElasticConfig(false, "index", 30.seconds)
  )

  val apiKeyConfig: ApikeyConfig = ApikeyConfig(null, InitializeApiKey(None, None, authorizedPatterns = ""))

  "ZioIzanamiDefaultFilter" must {

    "Test or dev mode" in {
      val filter         = new ZioIzanamiDefaultFilter(Mode.Dev, "/", metricsConfig, config, apiKeyConfig)
      val result: Result = r.unsafeRun(filter.filter(h => Task(Results.Ok("Done")))(FakeRequest()))

      val expected = Results.Ok("Done")

      result must be(expected)
    }

    "Api key headers" in {

      val filter = new ZioIzanamiDefaultFilter(Mode.Prod, "/", metricsConfig, config, apiKeyConfig)
      val request = FakeRequest().withHeaders(
        config.apiKeys.headerClientId     -> "id",
        config.apiKeys.headerClientSecret -> "secret"
      )

      val result: Result = r.unsafeRun(
        filter.filter(h => Task(Results.Ok(s"Done ${h.attrs(FilterAttrs.Attrs.AuthInfo).map(_.id).getOrElse("")}")))(
          request
        )
      )

      val expected = Results.Ok("Done id")

      result must be(expected)
    }

    "Cookie claim" in {
      val token: String = JWT
        .create()
        .withIssuer(config.issuer)
        .withClaim("name", "johndoe")
        .withClaim("user_id", "johndoe")
        .withClaim("email", "johndoe@gmail.com")
        .withClaim("izanami_authorized_patterns", "*")
        .withClaim("izanami_admin", "false")
        .sign(algorithm)
      val filter  = new ZioIzanamiDefaultFilter(Mode.Prod, "/", metricsConfig, config, apiKeyConfig)
      val request = FakeRequest().withCookies(Cookie(config.cookieClaim, token))

      val result: Result = r.unsafeRun(
        filter.filter(h => Task(Results.Ok(s"Done ${h.attrs(FilterAttrs.Attrs.AuthInfo).map(_.id).getOrElse("")}")))(
          request
        )
      )

      val expected = Results.Ok("Done johndoe")

      result must be(expected)
    }

    "Cookie bad claim" in {
      val token: String = "Mouhahaha"
      val filter        = new ZioIzanamiDefaultFilter(Mode.Prod, "/", metricsConfig, config, apiKeyConfig)
      val request       = FakeRequest().withCookies(Cookie(config.cookieClaim, token))

      val result: Result = r.unsafeRun(filter.filter(h => Task(Results.Ok("Done")))(request))

      val expected = Results.Unauthorized(Json.obj("error" -> "Claim error !!!"))

      result must be(expected)
    }

    "Api key authorization header" in {

      val filter = new ZioIzanamiDefaultFilter(Mode.Prod, "/", metricsConfig, config, apiKeyConfig)
      val request =
        FakeRequest().withHeaders(
          "Authorization" -> s"Basic ${Base64.getEncoder.encodeToString(s"id:secret".getBytes(StandardCharsets.UTF_8))}"
        )

      val result: Result = r.unsafeRun(
        filter.filter(h => Task(Results.Ok(s"Done ${h.attrs(FilterAttrs.Attrs.AuthInfo).map(_.id).getOrElse("")}")))(
          request
        )
      )

      val expected = Results.Ok("Done id")

      result must be(expected)
    }

    "Api key headers unauthorized" in {

      val filter = new ZioIzanamiDefaultFilter(Mode.Prod, "/", metricsConfig, config, apiKeyConfig)
      val request = FakeRequest().withHeaders(
        config.apiKeys.headerClientId     -> "id",
        config.apiKeys.headerClientSecret -> "secret2"
      )

      val result: Result = r.unsafeRun(filter.filter(h => Task(Results.Ok("Done")))(request))

      val expected = Results.Unauthorized(Json.obj("error" -> "Bad request !!!"))

      result must be(expected)
    }

    "Exclusion" in {

      val filter  = new ZioIzanamiDefaultFilter(Mode.Prod, "/", metricsConfig, config, apiKeyConfig)
      val request = FakeRequest(method = "GET", path = "/excluded")

      val result: Result = r.unsafeRun(
        filter.filter(h => Task(Results.Ok(s"Done ${h.attrs(FilterAttrs.Attrs.AuthInfo).map(_.id).getOrElse("")}")))(
          request
        )
      )

      val expected = Results.Ok("Done ")
      result must be(expected)
    }

    "Exclusion + claims" in {
      val token: String = JWT
        .create()
        .withIssuer(config.issuer)
        .withClaim("name", "johndoe")
        .withClaim("user_id", "johndoe")
        .withClaim("email", "johndoe@gmail.com")
        .withClaim("izanami_authorized_patterns", "*")
        .withClaim("izanami_admin", "false")
        .sign(algorithm)

      val filter  = new ZioIzanamiDefaultFilter(Mode.Prod, "/", metricsConfig, config, apiKeyConfig)
      val request = FakeRequest(method = "GET", path = "/excluded").withCookies(Cookie(config.cookieClaim, token))

      val result: Result = r.unsafeRun(
        filter.filter(h => Task(Results.Ok(s"Done ${h.attrs(FilterAttrs.Attrs.AuthInfo).map(_.id).getOrElse("")}")))(
          request
        )
      )

      val expected = Results.Ok("Done johndoe")
      result must be(expected)
    }
  }
}
