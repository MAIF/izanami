import domains.abtesting.ExperimentDataStore
import domains.abtesting.events.ExperimentVariantEventService
import domains.apikey.ApikeyDataStore
import domains.auth.AuthInfo
import domains.feature.FeatureDataStore
import domains.script.{GlobalScriptDataStore, RunnableScriptModule, ScriptCache}
import domains.webhook.WebhookDataStore
import elastic.api.Elastic
import env.IzanamiConfig
import libs.database.Drivers
import libs.database.Drivers.{DriverLayerContext, ElasticDriver}
import metrics.MetricsModules.AllMetricsModules
import play.api.libs.json.JsValue
import zio.{Has, Layer, Managed, Ref, ULayer, URIO, ZLayer, ZManaged}
import zio.blocking.Blocking

import scala.concurrent.Future

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
  import domains.apikey.{ApikeyService}
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

    case class MetricsModuleProd(metricRegistry: MetricRegistry) extends Service

    val live: ZLayer[Any, Throwable, MetricsModule] = ZLayer.fromManaged {
      val moduleProd = MetricsModuleProd(new MetricRegistry)
      Managed.make(Task(moduleProd))(r => UIO(r.prometheusRegistry.clear()))
    }

    val metricRegistry: URIO[MetricsModule, MetricRegistry]        = ZIO.access[MetricsModule](_.get.metricRegistry)
    val prometheusRegistry: URIO[MetricsModule, CollectorRegistry] = ZIO.access[MetricsModule](_.get.prometheusRegistry)
    val prometheus: URIO[MetricsModule, DropwizardExports]         = ZIO.access[MetricsModule](_.get.prometheus)
  }

  type MetricsContext = PlayModule
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

  type MetricsModules = Has[MetricsModules.AllMetricsModules]

  object MetricsModules {

    trait Service {
      def run: RIO[MetricsContext, Fiber[Throwable, Unit]]
    }

    private def loggerMetricsModule(
        metricsConfig: MetricsConfig
    ): ZLayer[DriverLayerContext, Throwable, DriverLayerContext with Has[Option[LoggerMetricsModule]]] =
      if (metricsConfig.log.enabled) {
        val value: ZLayer[DriverLayerContext, Throwable, Has[Option[LoggerMetricsModule]]] =
          ZLayer.succeed(Some(new LoggerMetricsModule(metricsConfig)))
        value.passthrough
      } else {
        val value: ZLayer[DriverLayerContext, Throwable, Has[Option[LoggerMetricsModule]]] =
          ZLayer.succeed(Option.empty[LoggerMetricsModule])
        value.passthrough
      }

    private def consoleMetricsModule(
        metricsConfig: MetricsConfig
    ): ZLayer[DriverLayerContext, Throwable, DriverLayerContext with Has[Option[ConsoleMetricsModule]]] =
      if (metricsConfig.console.enabled) {
        val value: ZLayer[DriverLayerContext, Throwable, Has[Option[ConsoleMetricsModule]]] =
          ZLayer.succeed(Some(new ConsoleMetricsModule(metricsConfig)))
        value.passthrough
      } else {
        val value: ZLayer[DriverLayerContext, Throwable, Has[Option[ConsoleMetricsModule]]] = ZLayer.succeed(None)
        value.passthrough
      }

    private def kafkaMetricsModule(
        metricsConfig: MetricsConfig
    ): ZLayer[DriverLayerContext, Throwable, DriverLayerContext with Has[Option[KafkaMetricsModule]]] =
      if (metricsConfig.kafka.enabled) {
        val value: ZLayer[DriverLayerContext, Throwable, Has[Option[KafkaMetricsModule]]] =
          ZLayer.succeed(Some(new KafkaMetricsModule(metricsConfig)))
        value.passthrough
      } else {
        val value: ZLayer[DriverLayerContext, Throwable, Has[Option[KafkaMetricsModule]]] =
          ZLayer.succeed(None)
        value.passthrough
      }

    private def elasticMetricsModule(
        metricsConfig: MetricsConfig
    ): ZLayer[DriverLayerContext, Throwable, DriverLayerContext with Has[Option[ElasticMetricsModule]]] =
      if (metricsConfig.elastic.enabled) {
        val metricsEsModule: ZLayer[ElasticDriver, Throwable, Has[Option[ElasticMetricsModule]]] = ZLayer.fromFunction {
          mix =>
            val mayBeClient: Option[Elastic[JsValue]] = mix.get[Option[Elastic[JsValue]]]
            mayBeClient.map { elastic =>
              new ElasticMetricsModule(elastic, metricsConfig)
            }
        }
        (Drivers.elasticClientLayer.passthrough >>> metricsEsModule).passthrough
      } else {
        val metricsEsModule: ZLayer[DriverLayerContext, Throwable, Has[Option[ElasticMetricsModule]]] =
          ZLayer.succeed(None)
        metricsEsModule.passthrough
      }

    type MetricsFiber = Fiber[Throwable, Unit]

    def allMetricsModules(
        izanamiConfig: IzanamiConfig
    ): ZLayer[DriverLayerContext, Throwable, Has[AllMetricsModules]] = {
      val metricsConfig: MetricsConfig = izanamiConfig.metrics
      (loggerMetricsModule(metricsConfig) ++ consoleMetricsModule(metricsConfig) ++ kafkaMetricsModule(metricsConfig) ++ elasticMetricsModule(
        metricsConfig
      )) >>> ZLayer.fromFunctionManaged { mix =>
        Managed.make(
          Ref
            .make(
              (Option.empty[MetricsFiber],
               Option.empty[MetricsFiber],
               Option.empty[MetricsFiber],
               Option.empty[MetricsFiber])
            )
            .map { ref =>
              new AllMetricsModules(
                ref,
                mix.get[Option[LoggerMetricsModule]],
                mix.get[Option[ConsoleMetricsModule]],
                mix.get[Option[KafkaMetricsModule]],
                mix.get[Option[ElasticMetricsModule]]
              )
            }
        )(_.stop.provide(mix))

      }
    }

    class AllMetricsModules(
        r: Ref[(Option[MetricsFiber], Option[MetricsFiber], Option[MetricsFiber], Option[MetricsFiber])],
        mayBeLoggerMetricsModule: Option[LoggerMetricsModule],
        mayBeConsoleMetricsModule: Option[ConsoleMetricsModule],
        mayBeKafkaMetricsModule: Option[KafkaMetricsModule],
        mayBeElasticMetricsModule: Option[ElasticMetricsModule]
    ) {

      def stop: URIO[ZLogger, Unit] =
        for {
          fibers           <- r.get
          (f1, f2, f3, f4) = fibers
          _                <- f1.map(_.interrupt.unit *> ZLogger.info("Logger metrics stopped")).getOrElse(UIO.unit)
          _                <- f2.map(_.interrupt.unit *> ZLogger.info("Console metrics stopped")).getOrElse(UIO.unit)
          _                <- f3.map(_.interrupt.unit *> ZLogger.info("Kafka metrics stopped")).getOrElse(UIO.unit)
          _                <- f4.map(_.interrupt.unit *> ZLogger.info("Elastic metrics stopped")).getOrElse(UIO.unit)
        } yield ()

      def start: RIO[MetricsContext, Unit] =
        for {
          metricRegistry <- MetricsModule.metricRegistry
          _              = metricRegistry.register("jvm.memory", new MemoryUsageGaugeSet())
          _              = metricRegistry.register("jvm.thread", new ThreadStatesGaugeSet())
          log            <- mayBeLoggerMetricsModule.map(_.run).getOrElse(Task.unit.fork)
          console        <- mayBeConsoleMetricsModule.map(_.run).getOrElse(Task.unit.fork)
          kafkaScheduler <- mayBeKafkaMetricsModule.map(_.run).getOrElse(Task.unit.fork)
          esScheduler    <- mayBeElasticMetricsModule.map(_.run).getOrElse(Task.unit.fork)
          _              <- r.set((Some(log), Some(console), Some(kafkaScheduler), Some(esScheduler)))
        } yield ()

    }

    class LoggerMetricsModule(metricsConfig: MetricsConfig) extends Service {

      override def run: RIO[MetricsContext, MetricsFiber] = MetricsModule.metricRegistry.flatMap { metricRegistry =>
        ZLogger.info("Enabling slf4j metrics reporter") *> Task {
          val reporter: Slf4jReporter = Slf4jReporter
            .forRegistry(metricRegistry)
            .outputTo(LoggerFactory.getLogger("izanami.metrics"))
            .convertRatesTo(TimeUnit.SECONDS)
            .convertDurationsTo(TimeUnit.MILLISECONDS)
            .build
          reporter.start(metricsConfig.log.interval._1, metricsConfig.log.interval._2)
          Some(reporter)
        } *> MetricsService.metrics.delay(Duration.fromScala(metricsConfig.refresh)).forever.fork
      }

    }

    class ConsoleMetricsModule(metricsConfig: MetricsConfig) extends Service {
      override def run: RIO[MetricsContext, MetricsFiber] = MetricsModule.metricRegistry.flatMap { metricRegistry =>
        ZLogger.info("Enabling console metrics reporter") *> Task {
          val reporter: ConsoleReporter = ConsoleReporter
            .forRegistry(metricRegistry)
            .convertRatesTo(TimeUnit.SECONDS)
            .convertDurationsTo(TimeUnit.MILLISECONDS)
            .build
          reporter.start(metricsConfig.console.interval._1, metricsConfig.console.interval._2)
          Some(reporter)
        } *> MetricsService.metrics.delay(Duration.fromScala(metricsConfig.refresh)).forever.fork
      }
    }

    class KafkaMetricsModule(metricsConfig: MetricsConfig) extends Service {
      override def run: RIO[MetricsContext, MetricsFiber] = {
        val res: ZIO[MetricsContext, Any, MetricsFiber] = for {
          system           <- PlayModule.system
          izanamiConfig    <- IzanamiConfigModule.izanamiConfig
          kafkaConfig      <- ZIO.fromOption(izanamiConfig.db.kafka)
          producerSettings = KafkaSettings.producerSettings(system, kafkaConfig)
          producer         = producerSettings.createKafkaProducer
          _                <- ZLogger.info("Enabling kafka metrics reporter")
          fiber <- (MetricsService.metrics flatMap { (metrics: Metrics) =>
                    sendToKafka(producer, metricsConfig, metrics)
                  }).delay(Duration.fromScala(metricsConfig.kafka.pushInterval)).forever.fork
        } yield fiber
        res.foldM(_ => Task.unit.fork, r => Task(r))
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
              ZLogger.error(s"Error pushing metrics to kafka topic ${metricsConfig.kafka.topic} : \n$message",
                            exception)
            case Die(exception) =>
              ZLogger.error(s"Error pushing metrics to kafka topic ${metricsConfig.kafka.topic} : \n$message",
                            exception)
            case _ => ZIO.unit
          }
          .unit
      }
    }

    class ElasticMetricsModule(client: Elastic[JsValue], metricsConfig: MetricsConfig) extends Service {

      override def run: RIO[MetricsContext, MetricsFiber] = {
        import DateTimeFormatter._
        val res: ZIO[MetricsContext, Any, MetricsFiber] = for {
          _ <- ZLogger.info("Enabling elastic metrics reporter")
          fiber <- (MetricsService.metrics flatMap { (metrics: Metrics) =>
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
      }
    }

  }

  object MetricsService {

    def start: RIO[MetricsModules with MetricsContext, Unit] =
      ZIO.accessM[MetricsModules with MetricsContext](_.get[AllMetricsModules].start)

    val objectMapper: ObjectMapper = {
      val objectMapper = new ObjectMapper()
      objectMapper.registerModule(
        new com.codahale.metrics.json.MetricsModule(TimeUnit.SECONDS, TimeUnit.MILLISECONDS, true)
      )
      objectMapper
    }

    val featureCheckCount = Counter
      .build()
      .name("feature_check_count")
      .labelNames("key", "active")
      .help("Count feature check")
      .create()

    val featureCreatedCount = Counter
      .build()
      .name("feature_created_count")
      .labelNames("key")
      .help("Count for feature creations")
      .create()

    val featureUpdatedCount = Counter
      .build()
      .name("feature_updated_count")
      .labelNames("key")
      .help("Count for feature updates")
      .create()

    val featureDeletedCount = Counter
      .build()
      .name("feature_deleted_count")
      .labelNames("key")
      .help("Count for feature deletions")
      .create()

    featureCheckCount.register()
    featureCreatedCount.register()
    featureUpdatedCount.register()
    featureDeletedCount.register()

    val metrics: RIO[MetricsContext, Metrics] =
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
    private val it = l.iterator()

    override def hasMoreElements: Boolean = it.hasNext

    override def nextElement(): T = it.next()
  }

}
