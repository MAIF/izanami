import domains.abtesting.ExperimentDataStore
import domains.abtesting.events.ExperimentVariantEventService
import domains.apikey.ApikeyDataStore
import domains.configuration.AkkaModule
import domains.auth.AuthInfo
import domains.feature.FeatureDataStore
import domains.script.{GlobalScriptDataStore, RunnableScriptModule, ScriptCache}
import domains.webhook.WebhookDataStore
import libs.database.Drivers
import zio.blocking.Blocking

package object metrics {

  import java.io.StringWriter
  import java.text.SimpleDateFormat
  import java.time.LocalDateTime
  import java.time.format.DateTimeFormatter
  import java.util
  import java.util.Date
  import java.util.concurrent.TimeUnit

  import com.codahale.metrics.jvm.{MemoryUsageGaugeSet, ThreadStatesGaugeSet}
  import com.codahale.metrics.{ConsoleReporter, MetricRegistry, Slf4jReporter}
  import com.fasterxml.jackson.databind.ObjectMapper
  import env.configuration.IzanamiConfigModule
  import domains.config.ConfigDataStore
  import domains.events.EventStore
  import domains.events.impl.KafkaSettings
  import domains.user.UserDataStore
  import env.MetricsConfig
  import io.prometheus.client.dropwizard.DropwizardExports
  import io.prometheus.client.exporter.common.TextFormat
  import org.apache.kafka.clients.producer.{Callback, Producer, ProducerRecord, RecordMetadata}
  import org.slf4j.LoggerFactory
  import play.api.libs.json.{JsObject, Json}
  import zio.Task
  import zio.RIO
  import domains.configuration._
  import zio.ZIO
  import libs.logs.ZLogger
  import zio.duration.Duration
  import zio.clock.Clock
  import zio.Cause.Fail
  import zio.Cause.Die
  import zio.Fiber
  import domains.config.ConfigContext
  import domains.feature.FeatureContext
  import domains.script.GlobalScriptContext
  import domains.apikey.{ApiKeyContext, ApikeyService}
  import domains.webhook.WebhookContext
  import domains.abtesting.ExperimentContext
  import domains.config.ConfigService
  import store.Query
  import com.codahale.metrics.Gauge
  import domains.feature.FeatureService
  import domains.user.UserService
  import domains.script.GlobalScriptService
  import domains.webhook.WebhookService
  import domains.abtesting.ExperimentService
  import io.prometheus.client.CollectorRegistry
  import java.{util => ju}

  import io.prometheus.client.Counter
  import zio.UIO

  type MetricsModule = zio.Has[MetricsModule.Service]

  object MetricsModule {
    trait Service {
      def metricRegistry: MetricRegistry
      val prometheusRegistry: CollectorRegistry = CollectorRegistry.defaultRegistry
      val prometheus: DropwizardExports         = new DropwizardExports(metricRegistry)
    }
    val metricRegistry     = ZIO.access[MetricsModule](_.get.metricRegistry)
    val prometheusRegistry = ZIO.access[MetricsModule](_.get.prometheusRegistry)
    val prometheus         = ZIO.access[MetricsModule](_.get.prometheus)
  }

  type MetricsContext = AkkaModule
    with PlayModule
    with Drivers
    with IzanamiConfigModule
    with MetricsModule
    with AuthInfo
    with ApikeyDataStore
    with ConfigDataStore
    with UserDataStore
    with FeatureDataStore
    with GlobalScriptDataStore
    with ScriptCache
    with RunnableScriptModule
    with WebhookDataStore
    with ExperimentDataStore
    with ExperimentVariantEventService
    with EventStore
    with ZLogger
    with Blocking
    with Clock

  case class Metrics(metricRegistry: MetricRegistry,
                     prometheusRegistry: CollectorRegistry,
                     prometheus: DropwizardExports,
                     objectMapper: ObjectMapper,
                     metricsConfig: MetricsConfig) {

    def prometheusExport: String = {
      val writer             = new StringWriter()
      val prometheuseMetrics = ju.Collections.list(prometheusRegistry.metricFamilySamples())
      val dropwizardMetrics  = prometheus.collect()
      dropwizardMetrics.addAll(prometheuseMetrics)

      TextFormat.write004(writer, new SimpleEnum(dropwizardMetrics))
      writer.toString
    }

    def jsonExport: String =
      objectMapper.writeValueAsString(metricRegistry)

    def defaultHttpFormat: String = defaultFormat(metricsConfig.http.defaultFormat)

    def defaultFormat(format: String): String = format match {
      case "json"       => jsonExport
      case "prometheus" => prometheusExport
      case _            => jsonExport
    }
  }

  object MetricsService {

    private val objectMapper: ObjectMapper = {
      val objectMapper = new ObjectMapper()
      objectMapper.registerModule(
        new com.codahale.metrics.json.MetricsModule(TimeUnit.SECONDS, TimeUnit.MILLISECONDS, true)
      )
      objectMapper
    }

    private val featureCheckCount = Counter
      .build()
      .name("feature_check_count")
      .labelNames("key", "active")
      .help("Count feature check")
      .create()

    private val featureCreatedCount = Counter
      .build()
      .name("feature_created_count")
      .labelNames("key")
      .help("Count for feature creations")
      .create()

    private val featureUpdatedCount = Counter
      .build()
      .name("feature_updated_count")
      .labelNames("key")
      .help("Count for feature updates")
      .create()

    private val featureDeletedCount = Counter
      .build()
      .name("feature_deleted_count")
      .labelNames("key")
      .help("Count for feature deletions")
      .create()

    featureCheckCount.register()
    featureCreatedCount.register()
    featureUpdatedCount.register()
    featureDeletedCount.register()

    private def startMetricsLogger(metricsConfig: MetricsConfig,
                                   metricRegistry: MetricRegistry): RIO[MetricsContext, Fiber[Throwable, Unit]] =
      if (metricsConfig.log.enabled) {
        ZLogger.info("Enabling slf4j metrics reporter") *> Task {
          val reporter: Slf4jReporter = Slf4jReporter
            .forRegistry(metricRegistry)
            .outputTo(LoggerFactory.getLogger("izanami.metrics"))
            .convertRatesTo(TimeUnit.SECONDS)
            .convertDurationsTo(TimeUnit.MILLISECONDS)
            .build
          reporter.start(metricsConfig.log.interval._1, metricsConfig.log.interval._2)
          Some(reporter)
        } *> this.metrics.delay(Duration.fromScala(metricsConfig.refresh)).forever.fork
      } else {
        Task.unit.fork
      }

    private def startMetricsConsole(metricsConfig: MetricsConfig,
                                    metricRegistry: MetricRegistry): RIO[MetricsContext, Fiber[Throwable, Unit]] =
      if (metricsConfig.console.enabled) {
        ZLogger.info("Enabling console metrics reporter") *> Task {
          val reporter: ConsoleReporter = ConsoleReporter
            .forRegistry(metricRegistry)
            .convertRatesTo(TimeUnit.SECONDS)
            .convertDurationsTo(TimeUnit.MILLISECONDS)
            .build
          reporter.start(metricsConfig.console.interval._1, metricsConfig.console.interval._2)
          Some(reporter)
        } *> this.metrics.delay(Duration.fromScala(metricsConfig.refresh)).forever.fork
      } else {
        Task.unit.fork
      }
    private def startMetricsKafka(metricsConfig: MetricsConfig,
                                  metricRegistry: MetricRegistry): RIO[MetricsContext, Fiber[Throwable, Unit]] =
      if (metricsConfig.kafka.enabled) {
        val res: ZIO[MetricsContext, Any, Fiber[Throwable, Unit]] = for {
          system           <- AkkaModule.system
          izanamiConfig    <- IzanamiConfigModule.izanamiConfig
          kafkaConfig      <- ZIO.fromOption(izanamiConfig.db.kafka)
          producerSettings = KafkaSettings.producerSettings(system, kafkaConfig)
          producer         = producerSettings.createKafkaProducer
          _                <- ZLogger.info("Enabling kafka metrics reporter")
          fiber <- (this.metrics flatMap { (metrics: Metrics) =>
                    sendToKafka(producer, metricsConfig, metrics)
                  }).delay(Duration.fromScala(metricsConfig.kafka.pushInterval)).forever.fork
        } yield fiber
        res.foldM(_ => Task.unit.fork, r => Task(r))
      } else {
        Task.unit.fork
      }

    private def sendToKafka(producer: Producer[String, String],
                            metricsConfig: MetricsConfig,
                            metrics: Metrics): ZIO[ZLogger, Throwable, Unit] = {
      val message = metrics.defaultFormat(metricsConfig.kafka.format)
      Task
        .effectAsync[Unit] { cb =>
          producer.send(
            new ProducerRecord[String, String](metricsConfig.kafka.topic, message),
            new Callback {
              override def onCompletion(metadata: RecordMetadata, exception: Exception): Unit =
                if (exception != null) {
                  cb(ZIO.fail(exception))
                } else {
                  cb(ZIO.unit)
                }
            }
          )
        }
        .onError {
          case Fail(exception) =>
            ZLogger.error(s"Error pushing metrics to kafka topic ${metricsConfig.kafka.topic} : \n$message", exception)
          case Die(exception) =>
            ZLogger.error(s"Error pushing metrics to kafka topic ${metricsConfig.kafka.topic} : \n$message", exception)
          case _ => ZIO.unit
        }
        .unit
    }

    private def startMetricsElastic(metricsConfig: MetricsConfig): RIO[MetricsContext, Fiber[Throwable, Unit]] =
      if (metricsConfig.elastic.enabled) {
        import DateTimeFormatter._

        val res: ZIO[MetricsContext, Any, Fiber[Throwable, Unit]] = for {
          _       <- ZLogger.info("Enabling kafka metrics reporter")
          drivers <- Drivers.drivers
          client  <- ZIO.fromOption(drivers.elasticClient)
          fiber <- (this.metrics flatMap { (metrics: Metrics) =>
                    val message: String = metrics.jsonExport
                    val indexName       = new SimpleDateFormat(metricsConfig.elastic.index).format(new Date())
                    val jsonMessage = Json.parse(message).as[JsObject] ++ Json.obj(
                      "@timestamp" -> ISO_DATE_TIME.format(LocalDateTime.now())
                    )
                    Task
                      .fromFuture { implicit ec =>
                        import elastic.implicits._
                        import elastic.codec.PlayJson._
                        client.index(indexName / "type").index(jsonMessage)
                      }
                      .onError {
                        case Fail(exception) =>
                          ZLogger.error(s"Error pushing metrics to ES index $indexName : \n$jsonMessage", exception)
                        case Die(exception) =>
                          ZLogger.error(s"Error pushing metrics to ES index $indexName : \n$jsonMessage", exception)
                        case _ => ZIO.unit
                      }
                      .unit
                  }).delay(Duration.fromScala(metricsConfig.elastic.pushInterval)).forever.fork
        } yield fiber
        res.foldM(_ => Task.unit.fork, r => Task(r))
      } else {
        Task.unit.fork
      }

    def start: RIO[MetricsContext, Unit] =
      for {
        runtime <- ZIO.runtime[MetricsContext]
        // _              <- ZIO(featureCheckCount.register())
        // _              <- ZIO(featureCreatedCount.register())
        // _              <- ZIO(featureUpdatedCount.register())
        // _              <- ZIO(featureDeletedCount.register())
        metricRegistry       <- MetricsModule.metricRegistry
        izanamiConfig        <- IzanamiConfigModule.izanamiConfig
        applicationLifecycle <- PlayModule.applicationLifecycle
        _                    = metricRegistry.register("jvm.memory", new MemoryUsageGaugeSet())
        _                    = metricRegistry.register("jvm.thread", new ThreadStatesGaugeSet())
        metricsConfig        = izanamiConfig.metrics
        log                  <- startMetricsLogger(metricsConfig, metricRegistry)
        console              <- startMetricsConsole(metricsConfig, metricRegistry)
        kafkaScheduler       <- startMetricsKafka(metricsConfig, metricRegistry)
        esScheduler          <- startMetricsElastic(metricsConfig)
      } yield {
        applicationLifecycle.addStopHook { () =>
          runtime.unsafeRunToFuture(
            log.interrupt <&>
            console.interrupt <&>
            kafkaScheduler.interrupt <&>
            esScheduler.interrupt
          )
        }
      }

    def metrics: RIO[MetricsContext, Metrics] =
      for {
        izanamiConfig      <- IzanamiConfigModule.izanamiConfig
        metricsConfig      = izanamiConfig.metrics
        count              = metricsConfig.includeCount
        metricRegistry     <- MetricsModule.metricRegistry
        prometheusRegistry <- MetricsModule.prometheusRegistry
        prometheus         <- MetricsModule.prometheus
        _ <- (countAndStore(count, ConfigService.count(Query.oneOf("*")), "config", metricRegistry) <&>
            countAndStore(count, FeatureService.count(Query.oneOf("*")), "feature", metricRegistry) <&>
            countAndStore(count, ExperimentService.count(Query.oneOf("*")), "experiment", metricRegistry) <&>
            countAndStore(count, GlobalScriptService.count(Query.oneOf("*")), "globalScript", metricRegistry) <&>
            countAndStore(count, UserService.countWithoutPermissions(Query.oneOf("*")), "user", metricRegistry) <&>
            countAndStore(count, ApikeyService.countWithoutPermissions(Query.oneOf("*")), "user", metricRegistry) <&>
            countAndStore(count, WebhookService.count(Query.oneOf("*")), "webhook", metricRegistry))
      } yield Metrics(metricRegistry, prometheusRegistry, prometheus, objectMapper, izanamiConfig.metrics)

    def incFeatureCheckCount(key: String, active: Boolean): UIO[Unit] =
      UIO(featureCheckCount.labels(key, s"$active").inc())

    def incFeatureCreated(key: String): UIO[Unit] =
      UIO(featureCreatedCount.labels(key).inc())

    def incFeatureUpdated(key: String): UIO[Unit] =
      UIO(featureUpdatedCount.labels(key).inc())

    def incFeatureDeleted(key: String): UIO[Unit] =
      UIO(featureDeletedCount.labels(key).inc())

    // def counter(name: String,
    //             help: String,
    //             labels: (String, String)*): RIO[MetricsContext, io.prometheus.client.Counter] = {

    //   import cats._
    //   import cats.implicits._
    //   val sortedLabels     = labels.sortBy(_._1)
    //   val sortedLabelNames = sortedLabels.map(_._1)
    //   for {
    //     context <- ZIO.environment[MetricsContext]
    //     _       <- Logger.info(s"Registering counter $name $sortedLabelNames")
    //     metric <- context.prometheusMetrics.modify { metrics =>
    //                metrics.collect {
    //                  case PrometheusCounter(n, l, counter) if n === name && l == sortedLabelNames => counter
    //                }.headOption match {
    //                  case None =>
    //                    val metricBuidler = io.prometheus.client.Counter
    //                      .build()
    //                      .name(name)
    //                      .help(help)

    //                    val metric = if (sortedLabelNames.isEmpty) {
    //                      metricBuidler.create()
    //                    } else {
    //                      metricBuidler
    //                        .labelNames(sortedLabelNames.toArray: _*)
    //                        .create()
    //                    }

    //                    metric.register(context.prometheusRegistry)
    //                    (metric, metrics :+ PrometheusCounter(name, sortedLabelNames, metric))
    //                  case Some(metric) => (metric, metrics)
    //                }
    //              }
    //   } yield metric
    // }
    // def histogram(name: String,
    //               help: String,
    //               labels: (String, String)*): RIO[MetricsContext, io.prometheus.client.Histogram] = {
    //   val sortedLabels     = labels.sortBy(_._1)
    //   val sortedLabelNames = sortedLabels.map(_._1)
    //   for {
    //     context <- ZIO.environment[MetricsContext]
    //     _       <- Logger.info(s"Registering histogram $name $sortedLabelNames")
    //     metric <- context.prometheusMetrics.modify { metrics =>
    //                metrics.collect {
    //                  case PrometheusHistogram(n, l, counter) if n == name && l == sortedLabelNames => counter
    //                }.headOption match {
    //                  case None =>
    //                    val metricBuidler = io.prometheus.client.Histogram
    //                      .build()
    //                      .name(name)
    //                      .help(help)

    //                    val metric = if (sortedLabelNames.isEmpty) {
    //                      metricBuidler.create()
    //                    } else {
    //                      metricBuidler
    //                        .labelNames(sortedLabelNames.toArray: _*)
    //                        .create()
    //                    }

    //                    metric.register(context.prometheusRegistry)
    //                    (metric, metrics :+ PrometheusHistogram(name, sortedLabelNames, metric))
    //                  case Some(metric) => (metric, metrics)
    //                }
    //              }
    //   } yield metric
    // }
    private def countAndStore[Ctx](enabled: Boolean,
                                   count: => RIO[Ctx, Long],
                                   name: String,
                                   metricRegistry: MetricRegistry): RIO[Ctx, Unit] =
      if (enabled) {
        count.map { c =>
          val gaugeName = s"domains.${name}.count"
          metricRegistry.remove(gaugeName)
          metricRegistry.register(gaugeName, new Gauge[Long]() {
            override def getValue(): Long = c
          })
          ()
        }
      } else {
        ZIO.unit
      }

  }

  class SimpleEnum[T](l: util.List[T]) extends util.Enumeration[T] {
    private val it                        = l.iterator()
    override def hasMoreElements: Boolean = it.hasNext
    override def nextElement(): T         = it.next()
  }

}
