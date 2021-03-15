package filters

import java.nio.charset.StandardCharsets
import java.util.Base64

import akka.NotUsed
import akka.actor.ActorSystem
import akka.stream.alpakka.dynamodb.DynamoClient
import akka.stream.scaladsl.Source
import akka.stream.{ActorMaterializer, Materializer}
import com.auth0.jwt.JWT
import com.auth0.jwt.algorithms.Algorithm
import com.codahale.metrics.MetricRegistry
import com.datastax.driver.core.{Cluster, Session}
import domains.Key
import domains.abtesting.ExperimentDataStore
import domains.auth.AuthInfo
import domains.abtesting.events.ExperimentVariantEventService
import domains.abtesting.events.impl.ExperimentVariantEventInMemoryService
import domains.apikey.{ApiKeyContext, ApikeyDataStore}
import domains.config.ConfigDataStore
import domains.configuration.PlayModule
import domains.configuration.PlayModule.PlayModuleProd
import domains.errors.IzanamiErrors
import domains.events.EventStore
import domains.feature.FeatureDataStore
import domains.script.{CacheService, GlobalScriptDataStore, RunnableScriptModule, Script, ScriptCache}
import domains.user.UserDataStore
import domains.webhook.WebhookDataStore
import env.configuration.IzanamiConfigModule
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
import libs.http.HttpContext
import libs.logs.ZLogger
import metrics.{MetricsContext, MetricsModule}
import play.api.Mode
import play.api.libs.json.{JsValue, Json}
import play.api.mvc.{Cookie, Result, Results}
import play.api.test.FakeRequest
import play.modules.reactivemongo.ReactiveMongoApi
import store.memory.InMemoryJsonDataStore
import store.postgresql.PostgresqlClient
import store.redis.RedisWrapper
import test.{FakeConfig, IzanamiSpec, TestEventStore}
import zio.blocking.Blocking
import zio.clock.Clock
import zio.{Runtime, Task, ULayer, ZIO, ZLayer}

import scala.collection.concurrent.TrieMap
import scala.concurrent.duration._
import scala.reflect.ClassTag

class ZioIzanamiDefaultFilterTest extends IzanamiSpec {

  implicit val sys = ActorSystem("test")

  val playModule: ULayer[PlayModule] = FakeConfig.playModule(sys)

  val runnableScriptModule: ZLayer[Any, Nothing, RunnableScriptModule] = (playModule >>> RunnableScriptModule.live)

  implicit val metricsModule: HttpContext[MetricsContext] = {
    val fake = new InMemoryJsonDataStore(name = "fake")
    playModule ++
    IzanamiConfigModule.value(FakeConfig.config) ++
    (playModule >>> MetricsModule.live) ++
    AuthInfo.empty ++
    ApikeyDataStore.value(
      new InMemoryJsonDataStore(
        name = "apikey",
        TrieMap(
          Key("id") -> Json.obj(
            "clientId"           -> "id",
            "clientSecret"       -> "secret",
            "name"               -> "id",
            "authorizedPatterns" -> "*",
            "admin"              -> false
          )
        )
      )
    ) ++
    ConfigDataStore.value(fake) ++
    UserDataStore.value(fake) ++
    FeatureDataStore.value(fake) ++
    GlobalScriptDataStore.value(fake) ++
    ScriptCache.value(new CacheService[String] {
      override def get[T: ClassTag](id: String): Task[Option[T]]      = Task.succeed(None)
      override def set[T: ClassTag](id: String, value: T): Task[Unit] = Task.succeed(())
    }) ++
    runnableScriptModule ++
    WebhookDataStore.value(fake) ++
    ExperimentDataStore.value(fake) ++
    ExperimentVariantEventService.value(new ExperimentVariantEventInMemoryService("test")) ++
    EventStore.value(new TestEventStore()) ++
    ZLogger.live ++
    Blocking.live ++
    Clock.live
  }

  implicit val r = Runtime.default

  def run(z: ZIO[ApiKeyContext with MetricsContext, Throwable, Result]): Result =
    r.unsafeRun(z.provideLayer(metricsModule))

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
      val result: Result = run(filter.filter(h => Task(Results.Ok("Done")))(FakeRequest()))

      val expected = Results.Ok("Done")

      result must be(expected)
    }

    "Api key headers" in {

      val filter = new ZioIzanamiDefaultFilter(Mode.Prod, "/", metricsConfig, config, apiKeyConfig)
      val request = FakeRequest().withHeaders(
        config.apiKeys.headerClientId     -> "id",
        config.apiKeys.headerClientSecret -> "secret"
      )

      val result: Result = run(
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

      val result: Result = run(
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

      val result: Result = run(filter.filter(h => Task(Results.Ok("Done")))(request))

      val expected = Results.Unauthorized(Json.obj("error" -> "Claim error !!!"))

      result must be(expected)
    }

    "Api key authorization header" in {

      val filter = new ZioIzanamiDefaultFilter(Mode.Prod, "/", metricsConfig, config, apiKeyConfig)
      val request =
        FakeRequest().withHeaders(
          "Authorization" -> s"Basic ${Base64.getEncoder.encodeToString(s"id:secret".getBytes(StandardCharsets.UTF_8))}"
        )

      val result: Result = run(
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

      val result: Result = run(filter.filter(h => Task(Results.Ok("Done")))(request))

      val expected = Results.Unauthorized(Json.obj("error" -> "Bad request !!!"))

      result must be(expected)
    }

    "Exclusion" in {

      val filter  = new ZioIzanamiDefaultFilter(Mode.Prod, "/", metricsConfig, config, apiKeyConfig)
      val request = FakeRequest(method = "GET", path = "/excluded")

      val result: Result = run(
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

      val result: Result = run(
        filter.filter(h => Task(Results.Ok(s"Done ${h.attrs(FilterAttrs.Attrs.AuthInfo).map(_.id).getOrElse("")}")))(
          request
        )
      )

      val expected = Results.Ok("Done johndoe")
      result must be(expected)
    }
  }
}
