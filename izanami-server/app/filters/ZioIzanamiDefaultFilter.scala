package filters

import java.util.Base64

import akka.stream.Materializer
import cats.implicits._
import com.auth0.jwt._
import com.auth0.jwt.algorithms.Algorithm
import com.codahale.metrics.MetricRegistry.name
import com.codahale.metrics.Timer
import com.codahale.metrics.json.MetricsModule
import com.google.common.base.Charsets
import domains.auth.AuthInfo
import domains.abtesting.ExperimentDataStore
import domains.abtesting.events.ExperimentVariantEventService
import domains.apikey.{ApiKeyContext, ApikeyDataStore, ApikeyService}
import domains.config.ConfigDataStore
import env.configuration.IzanamiConfigModule
import domains.configuration.PlayModule
import domains.events.EventStore
import domains.feature.FeatureDataStore
import domains.script.{GlobalScriptDataStore, RunnableScriptModule, ScriptCache}
import domains.user.{IzanamiUser, User, UserDataStore}
import domains.webhook.WebhookDataStore
import domains.{AuthorizedPatterns, Key}
import env._
import filters.PrometheusMetricsHolder.prometheursRequestCounter
import io.prometheus.client.{Counter, Histogram}
import libs.database.Drivers
import libs.http.HttpContext
import libs.logs.ZLogger
import play.api.libs.json._
import play.api.mvc._
import zio.{Cause, RIO, Runtime, Task, UIO, ZIO}
import metrics.{MetricsContext, MetricsModule}
import play.api.Mode
import zio.clock.Clock

import scala.util.Try

object PrometheusMetricsHolder {

  val prometheursRequestCounter: Counter = io.prometheus.client.Counter
    .build()
    .name("request_count")
    .labelNames("http_method", "request_path", "request_status")
    .help("Count of http request")
    .create()

  val prometheursRequestHisto: Histogram = io.prometheus.client.Histogram
    .build()
    .name("request_duration_details")
    .labelNames("http_method", "request_path")
    .help("Duration of http request")
    .create()

  prometheursRequestCounter.register()
  prometheursRequestHisto.register()
}

case class TimerContext(timerMethod: Option[Timer.Context],
                        timerMethodPath: Timer.Context,
                        timer: Timer.Context,
                        histoWithLabels: Histogram.Timer) {
  def stop: UIO[Unit] = ZIO.effectTotal {
    timerMethod.foreach { _.stop() }
    timerMethodPath.stop()
    timer.stop()
    histoWithLabels.observeDuration()
  }
}

class ZioIzanamiDefaultFilter(env: Mode,
                              contextPath: String,
                              metricsConfig: MetricsConfig,
                              config: DefaultFilter,
                              apikeyConfig: ApikeyConfig)(
    implicit runtime: HttpContext[ApiKeyContext with MetricsContext],
    override val mat: Materializer
) extends ZioFilter[ApiKeyContext with MetricsContext] {

  private val decoder   = Base64.getDecoder
  private val algorithm = Algorithm.HMAC512(config.sharedKey)
  private val verifier  = JWT.require(algorithm).withIssuer(config.issuer).build()

  private val allowedPath: Seq[String] = contextPath match {
    case "/" => config.allowedPaths
    case path =>
      val buildPath = if (path.endsWith("/")) {
        path.dropRight(1)
      } else {
        path
      }
      buildPath +: config.allowedPaths.map(p => s"$buildPath$p")
  }

  override def filter(
      nextFilter: RequestHeader => Task[Result]
  )(requestHeader: RequestHeader): ZIO[ApiKeyContext with MetricsContext, Throwable, Result] =
    for {
      ctx                <- ZIO.environment[ApiKeyContext with MetricsContext]
      metricRegistry     <- metrics.MetricsModule.metricRegistry
      logger             <- getLogger
      startTime          <- ZIO(System.currentTimeMillis)
      timerContext       <- startMetrics(requestHeader)
      maybeClientId      = requestHeader.headers.get(config.apiKeys.headerClientId)
      maybeClientSecret  = requestHeader.headers.get(config.apiKeys.headerClientSecret)
      maybeClaim         = Try(requestHeader.cookies.get(config.cookieClaim).get.value).toOption
      maybeAuthorization = mayBeAuth(requestHeader)

      resp <- ((env, maybeClientId, maybeClientSecret, maybeClaim, maybeAuthorization) match {
               // dev or test mode :
               case (devOrTest, _, _, _, _) if devOrTest == Mode.Dev || devOrTest == Mode.Test =>
                 devFilter(nextFilter, requestHeader, startTime)
               // Prod && Api key :
               case (Mode.Prod, Some(clientId), Some(clientSecret), _, _) =>
                 passByApiKey(clientId, clientSecret, startTime, nextFilter, requestHeader)
               // Prod & Authorization header
               case (Mode.Prod, _, _, _, Some((clientId, clientSecret))) =>
                 passByApiKey(clientId, clientSecret, startTime, nextFilter, requestHeader)
               // Prod && Exclusions :
               case (Mode.Prod, _, _, Some(claim), _) if allowedPath.exists(path => requestHeader.path.matches(path)) =>
                 decodeTokenFilter(nextFilter, requestHeader, startTime, claim)
               // Prod && Exclusions && Claim empty :
               case (Mode.Prod, _, _, _, _) if allowedPath.exists(path => requestHeader.path.matches(path)) =>
                 handleExclusionWithoutClaims(nextFilter, requestHeader, startTime)
               // Prod && Claim => decoding jwt :
               case (Mode.Prod, _, _, Some(claim), _) =>
                 decodeTokenFilter(nextFilter, requestHeader, startTime, claim)
               case _ =>
                 ZIO(Results.Unauthorized(Json.obj("error" -> "Bad claims !!!")))
             }).onError(handleFailure(logger, timerContext, requestHeader))

      _ <- logger.debug(s" ${requestHeader.method} ${requestHeader.uri} resp : $resp")
      _ = metricRegistry.meter(name("request", resp.header.status.toString, "rate")).mark()
      _ <- timerContext.stop
      _ = prometheursRequestCounter.labels(requestHeader.method, requestHeader.path, s"${resp.header.status}").inc()
    } yield resp

  private def mayBeAuth(requestHeader: RequestHeader): Option[(String, String)] =
    requestHeader.headers
      .get("Authorization")
      .map(_.replace("Basic ", ""))
      .map(a => new String(decoder.decode(a), Charsets.UTF_8))
      .filter(_.contains(":"))
      .map(_.split(":").toList)
      .collect {
        case user :: password :: Nil => (user, password)
      }

  private def handleFailure(logger: ZLogger.Service, timerContext: TimerContext, requestHeader: RequestHeader)(
      cause: Cause[Throwable]
  ): ZIO[Any, Nothing, Unit] =
    ZIO.effectTotal(prometheursRequestCounter.labels(requestHeader.method, requestHeader.path, "500").inc()) *>
    cause.failureOption.fold(ZIO.unit) { e =>
      logger.error(s"Error for request ${requestHeader.method} ${requestHeader.uri}", e) *>
      logger.error(s"Error for request ${requestHeader.method} ${requestHeader.uri}", e.getCause)
    } *> timerContext.stop

  private def handleExclusionWithoutClaims(nextFilter: RequestHeader => Task[Result],
                                           requestHeader: RequestHeader,
                                           startTime: Long) =
    for {
      result <- nextFilter(requestHeader.addAttr(FilterAttrs.Attrs.AuthInfo, None))
      logger <- getLogger
      _      <- logRequestResult("Request no claim with exclusion", requestHeader, startTime, result, logger)
    } yield result

  private def logRequestResult(desc: String,
                               requestHeader: RequestHeader,
                               startTime: Long,
                               result: Result,
                               logger: ZLogger.Service) =
    logger.debug(
      s"$desc => ${requestHeader.method} ${requestHeader.uri} with request headers ${requestHeader.headers.headers
        .map(h => s"""   "${h._1}": "${h._2}"\n""")
        .mkString(",")} took ${System.currentTimeMillis - startTime}ms and returned ${result.header.status} hasBody ${requestHeader.hasBody}"
    )

  type FilterContext = PlayModule
    with Drivers
    with IzanamiConfigModule
    with metrics.MetricsModule
    with AuthInfo
    with ConfigDataStore
    with UserDataStore
    with FeatureDataStore
    with GlobalScriptDataStore
    with ScriptCache
    with RunnableScriptModule
    with ApikeyDataStore
    with WebhookDataStore
    with ExperimentDataStore
    with ExperimentVariantEventService
    with EventStore
    with ZLogger
    with Clock

  private def decodeTokenFilter(nextFilter: RequestHeader => Task[Result],
                                requestHeader: RequestHeader,
                                startTime: Long,
                                claim: String): ZIO[FilterContext, Throwable, Result] = {
    val res = for {
      mayBeUser <- decodeToken(claim)
      result <- nextFilter(requestHeader.addAttr(FilterAttrs.Attrs.AuthInfo, mayBeUser))
                 .refineOrDie[Result](PartialFunction.empty)
      logger <- getLogger
      _      <- logRequestResult("Request claim with exclusion", requestHeader, startTime, result, logger)
    } yield result
    res.either.map(_.merge)
  }

  private val getLogger: ZIO[ZLogger, Nothing, ZLogger.Service] = ZLogger("filter")

  private def decodeToken(claim: String): ZIO[FilterContext, Result, Option[User]] =
    for {
      decoded <- ZIO(verifier.verify(claim)).mapError { _ =>
                  Results
                    .Unauthorized(
                      Json.obj("error" -> "Claim error !!!")
                    )
                }
      user = User.fromJwtToken(decoded)
    } yield user

  private def devFilter(nextFilter: RequestHeader => Task[Result],
                        requestHeader: RequestHeader,
                        startTime: Long): ZIO[FilterContext, Throwable, Result] = {
    val devUser = Some(
      IzanamiUser(id = "id",
                  name = "Ragnard",
                  email = "ragnard@viking.com",
                  admin = false,
                  password = None,
                  authorizedPatterns = AuthorizedPatterns.All)
    )
    for {
      result <- nextFilter(requestHeader.addAttr(FilterAttrs.Attrs.AuthInfo, devUser))
      logger <- getLogger
      _      <- logRequestResult("Request dev ", requestHeader, startTime, result, logger)
    } yield result
  }

  private def passByApiKey(clientId: String,
                           clientSecret: String,
                           startTime: Long,
                           nextFilter: RequestHeader => Task[Result],
                           requestHeader: RequestHeader): ZIO[FilterContext, Throwable, Result] =
    for {
      logger      <- getLogger
      mayBeKey    <- ApikeyService.getByIdWithoutPermissions(Key(clientId))
      mayBeApiKey = mayBeKey.orElse(apikeyConfig.keys).filter(_.clientId === clientId)
      result <- mayBeApiKey match {
                 case Some(apikey) if apikey.clientSecret === clientSecret =>
                   nextFilter(requestHeader.addAttr(FilterAttrs.Attrs.AuthInfo, Some(apikey)))
                 case _ =>
                   logger.debug(
                     s"${requestHeader.method} ${requestHeader.path} Not authorized for $clientId $clientSecret"
                   ) *> ZIO(
                     Results.Unauthorized(
                       Json.obj("error" -> "Bad request !!!")
                     )
                   )
               }
      _ <- logRequestResult("Request api key", requestHeader, startTime, result, logger)

    } yield result

  private def startMetrics(requestHeader: RequestHeader): RIO[FilterContext, TimerContext] =
    metrics.MetricsModule.metricRegistry.map { metricRegistry =>
      metricRegistry.meter(name("request", "rate")).mark()
      metricRegistry.meter(name("request", requestHeader.method, "rate")).mark()

      val timerMethod: Option[Timer.Context] = if (metricsConfig.verbose) {
        metricRegistry.meter(name("request", requestHeader.method, requestHeader.path, "rate")).mark()
        Some(metricRegistry.timer(name("request", requestHeader.method, requestHeader.path, "duration")).time())
      } else {
        None
      }
      val timerMethodPath: Timer.Context =
        metricRegistry.timer(name("request", requestHeader.method, "duration")).time()
      val timer: Timer.Context = metricRegistry.timer(name("request", "duration")).time()

      val histoWithLabels =
        PrometheusMetricsHolder.prometheursRequestHisto
          .labels(requestHeader.method, requestHeader.path)
          .startTimer()

      TimerContext(timerMethod, timerMethodPath, timer, histoWithLabels)
    }
}
