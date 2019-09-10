package filters

import java.util.Base64

import akka.http.scaladsl.util.FastFuture
import akka.stream.Materializer
import cats.effect.Effect
import cats.implicits._
import com.auth0.jwt._
import com.auth0.jwt.algorithms.Algorithm
import com.auth0.jwt.interfaces._
import com.codahale.metrics.MetricRegistry.name
import com.codahale.metrics.Timer
import com.google.common.base.Charsets
import domains.apikey.{ApiKeyContext, Apikey, ApikeyService}
import domains.user.User
import domains.{AuthInfo, AuthorizedPattern, Key}
import env._
import libs.logs.IzanamiLogger
import play.api.Logger
import play.api.libs.json._
import play.api.libs.typedmap._
import play.api.mvc._
import store.Result
import store.Result.AppErrors
import zio.{DefaultRuntime, Runtime, ZIO}

import scala.concurrent.{ExecutionContext, Future}
import scala.util._
import io.prometheus.client.Gauge
import io.prometheus.client.Counter
import metrics.MetricsContext
import metrics.Metrics
import metrics.MetricsService
import io.prometheus.client.Histogram

object PrometheusMetricsHolder {

  val prometheursRequestCounter = io.prometheus.client.Counter
    .build()
    .name("request_count")
    .labelNames("http_method", "request_path", "query_params")
    .help("Count of http request")
    .create()

  val prometheursRequestHisto = io.prometheus.client.Histogram
    .build()
    .name("request_duration_details")
    .labelNames("http_method", "request_path", "query_params")
    .help("Duration of http request")
    .create()

  prometheursRequestCounter.register()
  prometheursRequestHisto.register()
}

class IzanamiDefaultFilter[F[_]: Effect](env: Env,
                                         izanamiConfig: IzanamiConfig,
                                         config: DefaultFilter,
                                         apikeyConfig: ApikeyConfig)(
    implicit ec: ExecutionContext,
    runtime: Runtime[ApiKeyContext with MetricsContext],
    val mat: Materializer
) extends Filter {

  import cats.effect.implicits._
  import scala.collection.JavaConverters._

  private val logger  = Logger("filter")
  private val decoder = Base64.getDecoder
  // private val knownQueryParams = Seq("active", "clientId", "configs", "domains", "experiments", "features", "flat", "name_only", "newLevel",
  // "page", "pageSize", "pattern", "patterns", "render", "scripts")

  //private val labelNames = Seq("http_method", "request_path", "query_params").sorted.toArray

  private val allowedPath: Seq[String] = izanamiConfig.contextPath match {
    case "/" => config.allowedPaths
    case path =>
      val buildPath = if (path.endsWith("/")) {
        path.dropRight(1)
      } else {
        path
      }
      buildPath +: config.allowedPaths.map(p => s"$buildPath$p")
  }

  def apply(nextFilter: RequestHeader => Future[Result])(requestHeader: RequestHeader): Future[Result] = {
    env.metricRegistry.meter(name("request", "rate")).mark()
    env.metricRegistry.meter(name("request", requestHeader.method, "rate")).mark()

    val timerMethod: Option[Timer.Context] = if (izanamiConfig.metrics.verbose) {
      env.metricRegistry.meter(name("request", requestHeader.method, requestHeader.path, "rate")).mark()
      Some(env.metricRegistry.timer(name("request", requestHeader.method, requestHeader.path, "duration")).time())
    } else {
      None
    }
    val timerMethodPath: Timer.Context =
      env.metricRegistry.timer(name("request", requestHeader.method, "duration")).time()
    val timer: Timer.Context = env.metricRegistry.timer(name("request", "duration")).time()

    val startTime: Long = System.currentTimeMillis
    val maybeClaim      = Try(requestHeader.cookies.get(config.cookieClaim).get.value).toOption

    val queryParamsStr = requestHeader.queryString.toSeq
      .map {
        case (name, values) => s"$name=${values.mkString(",")}"
      }
      .mkString("&")

    val counterWithLabels =
      PrometheusMetricsHolder.prometheursRequestCounter.labels(requestHeader.method, requestHeader.path, queryParamsStr)
    val histoWithLabels =
      PrometheusMetricsHolder.prometheursRequestHisto
        .labels(requestHeader.method, requestHeader.path, queryParamsStr)
        .startTimer()

    val maybeAuthorization = requestHeader.headers
      .get("Authorization")
      .map(_.replace("Basic ", ""))
      .map(_.replace("basic ", ""))
      .map(a => new String(decoder.decode(a), Charsets.UTF_8))
      .filter(_.contains(":"))
      .map(_.split(":").toList)
      .collect {
        case user :: password :: Nil => (user, password)
      }
    val maybeClientId = requestHeader.headers.get(config.apiKeys.headerClientId)
    val maybeClientSecret =
      requestHeader.headers.get(config.apiKeys.headerClientSecret)

    def passByApiKey(clientId: String, clientSecret: String): Future[Result] =
      runtime
        .unsafeRunToFuture(
          ApikeyService
            .getById(Key(clientId))
        )
        .map { mayBeKey =>
          mayBeKey
            .orElse(apikeyConfig.keys)
            .filter(_.clientId === clientId)
        }
        .flatMap {
          case Some(apikey) if apikey.clientSecret === clientSecret =>
            nextFilter(requestHeader.addAttr(FilterAttrs.Attrs.AuthInfo, Some(apikey)))
              .map { result =>
                val requestTime = System.currentTimeMillis - startTime
                logger.debug(
                  s"Request api key => ${requestHeader.method} ${requestHeader.uri} with request headers ${requestHeader.headers.headers
                    .map(h => s"""   "${h._1}": "${h._2}"\n""")
                    .mkString(",")} took ${requestTime}ms and returned ${result.header.status} hasBody ${requestHeader.hasBody}"
                )
                result
              }
          case _ =>
            FastFuture.successful(
              Results.Unauthorized(
                Json.obj("error" -> "Bad request !!!")
              )
            )
        }

    val t = Try((env.env, maybeClientId, maybeClientSecret, maybeClaim, maybeAuthorization) match {
      // dev or test mode :
      case (devOrTest, _, _, _, _) if devOrTest == "test" || devOrTest == "dev" =>
        nextFilter(
          requestHeader.addAttr(
            FilterAttrs.Attrs.AuthInfo,
            Some(
              User(id = "id",
                   name = "Ragnard",
                   email = "ragnard@viking.com",
                   admin = false,
                   authorizedPattern = AuthorizedPattern(""))
            )
          )
        ).map { result =>
          val requestTime = System.currentTimeMillis - startTime
          logger.debug(
            s"Request => ${requestHeader.method} ${requestHeader.uri} took ${requestTime}ms and returned ${result.header.status}"
          )
          result
        }
      // Prod && Api key :
      case ("prod", Some(clientId), Some(clientSecret), _, _) => passByApiKey(clientId, clientSecret)
      // Prod & Authorization header
      case ("prod", _, _, _, Some((clientId, clientSecret))) => passByApiKey(clientId, clientSecret)
      // Prod && Exclusions :
      case ("prod", _, _, Some(claim), _) if allowedPath.exists(path => requestHeader.path.matches(path)) =>
        val tryDecode = Try {
          val algorithm = Algorithm.HMAC512(config.sharedKey)
          val verifier =
            JWT.require(algorithm).withIssuer(config.issuer).build()
          val decoded: DecodedJWT = verifier.verify(claim)

          nextFilter(requestHeader.addAttr(FilterAttrs.Attrs.AuthInfo, User.fromJwtToken(decoded))).map { result =>
            val requestTime = System.currentTimeMillis - startTime
            logger.debug(
              s"Request claim with exclusion => ${requestHeader.method} ${requestHeader.uri} with request headers ${requestHeader.headers.headers
                .map(h => s"""   "${h._1}": "${h._2}"\n""")
                .mkString(",")} took ${requestTime}ms and returned ${result.header.status} hasBody ${requestHeader.hasBody}"
            )
            result
          }
        } recoverWith {
          case e =>
            Success(
              Future.successful(
                Results
                  .Unauthorized(
                    Json.obj("error" -> "Claim error !!!", "m" -> e.getMessage)
                  )
              )
            )
        }
        tryDecode.get
      case ("prod", _, _, _, _) if allowedPath.exists(path => requestHeader.path.matches(path)) =>
        nextFilter(requestHeader.addAttr(FilterAttrs.Attrs.AuthInfo, None))
          .map {
            result =>
              val requestTime = System.currentTimeMillis - startTime
              logger.debug(
                s"Request no claim with exclusion => ${requestHeader.method} ${requestHeader.uri} with request headers ${requestHeader.headers.headers
                  .map(h => s"""   "${h._1}": "${h._2}"\n""")
                  .mkString(",")} took ${requestTime}ms and returned ${result.header.status} hasBody ${requestHeader.hasBody}"
              )
              result
          }
      // Prod && Claim empty :
      case ("prod", _, _, None, _) =>
        Future.successful(
          Results
            .Unauthorized(
              Json.obj("error" -> "Bad claim !!!")
            )
        )
      // Prod && Claim => decoding jwt :
      case ("prod", _, _, Some(claim), _) =>
        val tryDecode = Try {
          val algorithm = Algorithm.HMAC512(config.sharedKey)
          val verifier =
            JWT.require(algorithm).withIssuer(config.issuer).build()
          val decoded: DecodedJWT = verifier.verify(claim)

          nextFilter(requestHeader.addAttr(FilterAttrs.Attrs.AuthInfo, User.fromJwtToken(decoded))).map { result =>
            val requestTime = System.currentTimeMillis - startTime
            logger.debug(
              s"Request claim => ${requestHeader.method} ${requestHeader.uri} with request headers ${requestHeader.headers.headers
                .map(h => s"""   "${h._1}": "${h._2}"\n""")
                .mkString(",")} took ${requestTime}ms and returned ${result.header.status} hasBody ${requestHeader.hasBody}"
            )
            result
          }
        } recoverWith {
          case e =>
            Success(
              Future.successful(
                Results
                  .Unauthorized(
                    Json.obj("error" -> "Claim error !!!", "m" -> e.getMessage)
                  )
              )
            )
        }
        tryDecode.get
      case _ =>
        Future.successful(
          Results
            .Unauthorized(
              Json.obj("error" -> "Bad env !!!")
            )
        )
    }) recoverWith {
      case e =>
        Success(
          Future.successful(
            Results
              .InternalServerError(
                Json.obj("error" -> e.getMessage)
              )
          )
        )
    }
    val result: Future[Result] = t.get
    result.onComplete {
      case Success(resp) =>
        logger.debug(s" ${requestHeader.method} ${requestHeader.uri} resp : $resp")
        env.metricRegistry.meter(name("request", resp.header.status.toString, "rate")).mark()
        timer.stop()
        timerMethod.foreach(_.stop())
        timerMethodPath.stop()

        counterWithLabels.inc()
        histoWithLabels.observeDuration()

      case Failure(e) =>
        logger.error(s"Error for request ${requestHeader.method} ${requestHeader.uri}", e)
        env.metricRegistry.meter(name("request", "500", "rate")).mark()
        timer.stop()
        timerMethod.foreach(_.stop())
        timerMethodPath.stop()

        counterWithLabels.inc()
        histoWithLabels.observeDuration()
    }
    result
  }
}
