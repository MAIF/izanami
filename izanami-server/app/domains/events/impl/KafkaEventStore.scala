package domains.events.impl

import akka.{Done, NotUsed}
import akka.actor.ActorSystem
import akka.kafka.{ConsumerSettings, ManualSubscription, Subscriptions}
import akka.kafka.scaladsl.Consumer
import akka.stream.scaladsl.Source
import domains.Domain.Domain
import domains.auth.AuthInfo
import domains.events.EventStore
import domains.events.Events.IzanamiEvent
import env.{KafkaConfig, KafkaEventsConfig}
import org.apache.kafka.clients.CommonClientConfigs
import org.apache.kafka.clients.consumer.{ConsumerConfig, Consumer => KConsumer}
import org.apache.kafka.clients.producer.{Callback, ProducerRecord, RecordMetadata}
import org.apache.kafka.common.{PartitionInfo, TopicPartition}
import org.apache.kafka.common.serialization.{StringDeserializer, StringSerializer}
import play.api.libs.json.Json

import scala.util.control.NonFatal
import domains.events.EventLogger._
import org.apache.kafka.common.config.internals.BrokerSecurityConfigs
import domains.errors.IzanamiErrors
import zio.{IO, Task}

import scala.collection.mutable
import libs.logs.ZLogger
import zio.RIO
import domains.events.EventStoreContext

object KafkaSettings {

  import akka.kafka.{ConsumerSettings, ProducerSettings}
  import org.apache.kafka.clients.consumer.ConsumerConfig
  import org.apache.kafka.common.config.SslConfigs

  def consumerSettings(system: ActorSystem, config: KafkaConfig): ConsumerSettings[String, String] = {

    val settings = ConsumerSettings
      .create(system, new StringDeserializer(), new StringDeserializer())
      .withProperty(ConsumerConfig.AUTO_OFFSET_RESET_CONFIG, "earliest")
      .withProperty(ConsumerConfig.ENABLE_AUTO_COMMIT_CONFIG, "false")
      .withBootstrapServers(config.servers)

    val s = for {
      ks <- config.keystore.location
      ts <- config.truststore.location
      kp <- config.keyPass
    } yield {
      settings
        .withProperty(CommonClientConfigs.SECURITY_PROTOCOL_CONFIG, "SSL")
        .withProperty(BrokerSecurityConfigs.SSL_CLIENT_AUTH_CONFIG, "required")
        .withProperty(SslConfigs.SSL_ENDPOINT_IDENTIFICATION_ALGORITHM_CONFIG, null)
        .withProperty(SslConfigs.SSL_KEY_PASSWORD_CONFIG, kp)
        .withProperty(SslConfigs.SSL_KEYSTORE_LOCATION_CONFIG, ks)
        .withProperty(SslConfigs.SSL_KEYSTORE_PASSWORD_CONFIG, kp)
        .withProperty(SslConfigs.SSL_TRUSTSTORE_LOCATION_CONFIG, ts)
        .withProperty(SslConfigs.SSL_TRUSTSTORE_PASSWORD_CONFIG, kp)
    }

    s.getOrElse(settings)
  }

  def producerSettings(system: ActorSystem, config: KafkaConfig): ProducerSettings[String, String] = {
    val settings = ProducerSettings
      .create(system, new StringSerializer(), new StringSerializer())
      .withBootstrapServers(config.servers)

    val s = for {
      ks <- config.keystore.location
      ts <- config.truststore.location
      kp <- config.keyPass
    } yield {
      settings
        .withProperty(CommonClientConfigs.SECURITY_PROTOCOL_CONFIG, "SSL")
        .withProperty(BrokerSecurityConfigs.SSL_CLIENT_AUTH_CONFIG, "required")
        .withProperty(SslConfigs.SSL_ENDPOINT_IDENTIFICATION_ALGORITHM_CONFIG, null)
        .withProperty(SslConfigs.SSL_KEY_PASSWORD_CONFIG, kp)
        .withProperty(SslConfigs.SSL_KEYSTORE_LOCATION_CONFIG, ks)
        .withProperty(SslConfigs.SSL_KEYSTORE_PASSWORD_CONFIG, kp)
        .withProperty(SslConfigs.SSL_TRUSTSTORE_LOCATION_CONFIG, ts)
        .withProperty(SslConfigs.SSL_TRUSTSTORE_PASSWORD_CONFIG, kp)
    }

    s.getOrElse(settings)
  }
}

class KafkaEventStore(system: ActorSystem, clusterConfig: KafkaConfig, eventsConfig: KafkaEventsConfig)
    extends EventStore.Service {

  import scala.jdk.CollectionConverters._
  import system.dispatcher

  override def start: RIO[ZLogger with AuthInfo, Unit] =
    ZLogger.info(s"Initializing kafka event store $clusterConfig")

  private lazy val producerSettings =
    KafkaSettings.producerSettings(system, clusterConfig)

  private lazy val producer =
    producerSettings.createKafkaProducer

  val settings: ConsumerSettings[String, String] = KafkaSettings
    .consumerSettings(system, clusterConfig)
    .withProperty(ConsumerConfig.ENABLE_AUTO_COMMIT_CONFIG, "false")
    .withProperty(ConsumerConfig.AUTO_OFFSET_RESET_CONFIG, "latest")

  private val tmpConsumer: KConsumer[String, String]    = settings.createKafkaConsumer()
  private val partitions: mutable.Buffer[PartitionInfo] = tmpConsumer.partitionsFor(eventsConfig.topic).asScala
  tmpConsumer.close()

  override def publish(event: IzanamiEvent): IO[IzanamiErrors, Done] = {
    system.eventStream.publish(event)
    IO.effectAsync[Throwable, Done] { cb =>
        try {
          val message = Json.stringify(event.toJson)
          producer.send(new ProducerRecord[String, String](eventsConfig.topic, event.key.key, message), callback(cb))
        } catch {
          case NonFatal(e) =>
            cb(IO.fail(e))
        }
      }
      .orDie
  }

  override def events(domains: Seq[Domain],
                      patterns: Seq[String],
                      lastEventId: Option[Long]): Source[IzanamiEvent, NotUsed] = {

    val kafkaConsumer: KConsumer[String, String] =
      settings.createKafkaConsumer()

    val partitionSeq = partitions.toSeq

    val subscription: ManualSubscription = lastEventId.map { _ =>
      val lastDate: Long = System.currentTimeMillis() - (1000 * 60 * 60 * 24)
      val topicsInfo: Seq[(TopicPartition, Long)] =
        partitionSeq.map { t =>
          new TopicPartition(eventsConfig.topic, t.partition()) -> lastDate
        }
      Subscriptions.assignmentOffsetsForTimes(topicsInfo: _*)
    } getOrElse {
      val topicsInfo: Seq[TopicPartition] =
        partitionSeq.map { t =>
          new TopicPartition(eventsConfig.topic, t.partition())
        }
      Subscriptions.assignment(topicsInfo: _*)
    }

    Consumer
      .plainSource[String, String](settings, subscription)
      .map(_.value())
      .map(Json.parse)
      .mapConcat(
        json =>
          json
            .validate[IzanamiEvent]
            .fold(
              err => {
                logger.error(s"Error deserializing event of type ${json \ "type"} : $err")
                List.empty[IzanamiEvent]
              },
              e => List(e)
          )
      )
      .watchTermination() {
        case (control, done) =>
          done.onComplete { _ =>
            control.shutdown()
            kafkaConsumer.close()
          }
      }
      .via(dropUntilLastId(lastEventId))
      .filter(eventMatch(patterns, domains))
      .mapMaterializedValue(_ => NotUsed)
  }

  override def close(): Task[Unit] =
    Task(producer.close())

  private def callback(cb: IO[Throwable, Done] => Unit) = new Callback {
    override def onCompletion(metadata: RecordMetadata, exception: Exception): Unit =
      if (exception != null) {
        cb(IO.fail(exception))
      } else {
        cb(IO.succeed(Done))
      }
  }

  override def check(): Task[Unit] =
    IO.effectAsync { cb =>
      system.dispatchers.lookup("izanami.blocking-dispatcher").execute { () =>
        try {
          val consumer = KafkaSettings.consumerSettings(system, clusterConfig).createKafkaConsumer()
          consumer.partitionsFor(eventsConfig.topic)
          consumer.close()
          cb(IO.succeed(()))
        } catch {
          case NonFatal(e) =>
            cb(IO.fail(e))
        }
      }
    }
}
