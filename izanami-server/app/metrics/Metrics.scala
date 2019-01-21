package metrics
import java.io.StringWriter
import java.text.SimpleDateFormat
import java.time.LocalDateTime
import java.time.format.DateTimeFormatter
import java.util
import java.util.Date
import java.util.concurrent.TimeUnit

import akka.actor.{ActorSystem, Cancellable}
import akka.http.scaladsl.util.FastFuture
import com.codahale.metrics.json.MetricsModule
import com.codahale.metrics.jvm.{MemoryUsageGaugeSet, ThreadStatesGaugeSet}
import com.codahale.metrics.{ConsoleReporter, MetricRegistry, Slf4jReporter}
import com.fasterxml.jackson.databind.ObjectMapper
import domains.events.impl.KafkaSettings
import env.{Env, IzanamiConfig, MetricsConfig}
import io.prometheus.client.dropwizard.DropwizardExports
import io.prometheus.client.exporter.common.TextFormat
import libs.database.Drivers
import org.apache.kafka.clients.producer.{KafkaProducer, ProducerRecord}
import org.slf4j.LoggerFactory
import play.api.Logger
import play.api.inject.ApplicationLifecycle
import play.api.libs.json.{JsObject, Json}

import scala.util.Failure

class Metrics[F[_]](_env: Env,
                    drivers: Drivers[F],
                    izanamiConfig: IzanamiConfig,
                    applicationLifecycle: ApplicationLifecycle)(
    implicit system: ActorSystem
) {

  import system.dispatcher
  private val metricRegistry: MetricRegistry = _env.metricRegistry
  private val metricsConfig: MetricsConfig   = izanamiConfig.metrics

  metricRegistry.register("jvm.memory", new MemoryUsageGaugeSet())
  metricRegistry.register("jvm.thread", new ThreadStatesGaugeSet())

  private val objectMapper = new ObjectMapper()
  objectMapper.registerModule(new MetricsModule(TimeUnit.SECONDS, TimeUnit.MILLISECONDS, true))
  private val prometheus = new DropwizardExports(metricRegistry)

  def prometheusExport: String = {
    val writer = new StringWriter()
    TextFormat.write004(writer, new SimpleEnum(prometheus.collect()))
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

  private val log: Option[Slf4jReporter] =
    if (metricsConfig.log.enabled) {
      Logger.info("Enabling slf4j metrics reporter")
      val reporter: Slf4jReporter = Slf4jReporter
        .forRegistry(metricRegistry)
        .outputTo(LoggerFactory.getLogger("izanami.metrics"))
        .convertRatesTo(TimeUnit.SECONDS)
        .convertDurationsTo(TimeUnit.MILLISECONDS)
        .build
      reporter.start(metricsConfig.log.interval._1, metricsConfig.log.interval._2)
      Some(reporter)
    } else {
      None
    }

  private val console: Option[ConsoleReporter] =
    if (metricsConfig.console.enabled) {
      Logger.info("Enabling console metrics reporter")
      val reporter: ConsoleReporter = ConsoleReporter
        .forRegistry(metricRegistry)
        .convertRatesTo(TimeUnit.SECONDS)
        .convertDurationsTo(TimeUnit.MILLISECONDS)
        .build
      reporter.start(metricsConfig.console.interval._1, metricsConfig.console.interval._2)
      Some(reporter)
    } else {
      None
    }

  val kafkaScheduler: Option[Cancellable] = if (metricsConfig.kafka.enabled) {
    izanamiConfig.db.kafka.map { kafkaConfig =>
      val producerSettings                        = KafkaSettings.producerSettings(_env.environment, system, kafkaConfig)
      val producer: KafkaProducer[String, String] = producerSettings.createKafkaProducer
      system.scheduler.schedule(
        metricsConfig.kafka.pushInterval,
        metricsConfig.kafka.pushInterval,
        new Runnable {
          override def run(): Unit = {
            val message: String = defaultFormat(metricsConfig.kafka.format)
            producer.send(new ProducerRecord[String, String](metricsConfig.kafka.topic, message))
          }
        }
      )
    }
  } else {
    None
  }
  val esScheduler: Option[Cancellable] = if (metricsConfig.elastic.enabled) {
    drivers.elasticClient.map { client =>
      import elastic.implicits._
      import elastic.codec.PlayJson._
      system.scheduler.schedule(
        metricsConfig.elastic.pushInterval,
        metricsConfig.elastic.pushInterval,
        new Runnable {
          override def run(): Unit = {
            val message: String = jsonExport
            val indexName       = new SimpleDateFormat(metricsConfig.elastic.index).format(new Date())
            val jsonMessage = Json.parse(message).as[JsObject] ++ Json.obj(
              "@timestamp" -> DateTimeFormatter.ISO_DATE_TIME.format(LocalDateTime.now())
            )
            client.index(indexName / "type").index(jsonMessage).onComplete {
              case Failure(exception) =>
                Logger.error(s"Error pushing metrics to ES index $indexName : \n$jsonMessage", exception)
              case _ =>
            }
          }
        }
      )
    }
  } else {
    None
  }

  applicationLifecycle.addStopHook { () =>
    log.foreach(_.stop())
    console.foreach(_.stop())
    esScheduler.foreach(_.cancel())
    kafkaScheduler.foreach(_.cancel())
    FastFuture.successful(())
  }

}

class SimpleEnum[T](l: util.List[T]) extends util.Enumeration[T] {
  private val it                        = l.iterator()
  override def hasMoreElements: Boolean = it.hasNext
  override def nextElement(): T         = it.next()
}
