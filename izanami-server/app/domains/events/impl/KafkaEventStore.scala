package domains.events.impl

import akka.{Done, NotUsed}
import akka.actor.ActorSystem
import akka.kafka.{ConsumerSettings, ManualSubscription, Subscriptions}
import akka.kafka.scaladsl.Consumer
import akka.stream.scaladsl.Source
import cats.effect.Async
import domains.Domain.Domain
import domains.events.EventStore
import domains.events.Events.IzanamiEvent
import env.{KafkaConfig, KafkaEventsConfig}
import org.apache.kafka.clients.CommonClientConfigs
import org.apache.kafka.clients.consumer.{ConsumerConfig, Consumer => KConsumer}
import org.apache.kafka.clients.producer.{Callback, KafkaProducer, ProducerRecord, RecordMetadata}
import org.apache.kafka.common.{PartitionInfo, TopicPartition}
import org.apache.kafka.common.serialization.{ByteArraySerializer, StringDeserializer, StringSerializer}
import play.api.{Environment, Logger}
import play.api.libs.json.Json

import scala.util.control.NonFatal
import domains.events.EventLogger._
import org.apache.kafka.common.config.internals.BrokerSecurityConfigs

import scala.collection.mutable

object KafkaSettings {

  import akka.kafka.{ConsumerSettings, ProducerSettings}
  import org.apache.kafka.clients.consumer.ConsumerConfig
  import org.apache.kafka.common.config.SslConfigs
  import org.apache.kafka.common.serialization.ByteArrayDeserializer

  def consumerSettings(_env: Environment,
                       system: ActorSystem,
                       config: KafkaConfig): ConsumerSettings[String, String] = {

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
        .withProperty(SslConfigs.SSL_KEY_PASSWORD_CONFIG, kp)
        .withProperty(SslConfigs.SSL_KEYSTORE_LOCATION_CONFIG, ks)
        .withProperty(SslConfigs.SSL_KEYSTORE_PASSWORD_CONFIG, kp)
        .withProperty(SslConfigs.SSL_TRUSTSTORE_LOCATION_CONFIG, ts)
        .withProperty(SslConfigs.SSL_TRUSTSTORE_PASSWORD_CONFIG, kp)
    }

    s.getOrElse(settings)
  }

  def producerSettings(_env: Environment,
                       system: ActorSystem,
                       config: KafkaConfig): ProducerSettings[String, String] = {
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
        .withProperty(SslConfigs.SSL_KEY_PASSWORD_CONFIG, kp)
        .withProperty(SslConfigs.SSL_KEYSTORE_LOCATION_CONFIG, ks)
        .withProperty(SslConfigs.SSL_KEYSTORE_PASSWORD_CONFIG, kp)
        .withProperty(SslConfigs.SSL_TRUSTSTORE_LOCATION_CONFIG, ts)
        .withProperty(SslConfigs.SSL_TRUSTSTORE_PASSWORD_CONFIG, kp)
    }

    s.getOrElse(settings)
  }
}

class KafkaEventStore[F[_]: Async](_env: Environment,
                                   system: ActorSystem,
                                   clusterConfig: KafkaConfig,
                                   eventsConfig: KafkaEventsConfig)
    extends EventStore[F] {

  import scala.collection.JavaConverters._
  import system.dispatcher

  Logger.info(s"Initializing kafka event store $clusterConfig")

  private lazy val producerSettings =
    KafkaSettings.producerSettings(_env, system, clusterConfig)

  private lazy val producer: KafkaProducer[String, String] =
    producerSettings.createKafkaProducer

  val settings: ConsumerSettings[String, String] = KafkaSettings
    .consumerSettings(_env, system, clusterConfig)
    .withProperty(ConsumerConfig.ENABLE_AUTO_COMMIT_CONFIG, "false")
    .withProperty(ConsumerConfig.AUTO_OFFSET_RESET_CONFIG, "latest")

  private val tmpConsumer: KConsumer[String, String]    = settings.createKafkaConsumer()
  private val partitions: mutable.Buffer[PartitionInfo] = tmpConsumer.partitionsFor(eventsConfig.topic).asScala
  tmpConsumer.close()

  override def publish(event: IzanamiEvent): F[Done] = {
    import cats.implicits._
    system.eventStream.publish(event)
    Async[F]
      .async[Done] { cb =>
        try {
          val message = Json.stringify(event.toJson)
          producer.send(new ProducerRecord[String, String](eventsConfig.topic, event.key.key, message), callback(cb))
        } catch {
          case NonFatal(e) =>
            cb(Left(e))
        }
      }
      .map { _ =>
        Done
      }
  }

  override def events(domains: Seq[Domain],
                      patterns: Seq[String],
                      lastEventId: Option[Long]): Source[IzanamiEvent, NotUsed] = {

    val kafkaConsumer: KConsumer[String, String] =
      settings.createKafkaConsumer()

    val subscription: ManualSubscription = lastEventId.map { id =>
      val lastDate: Long = System.currentTimeMillis() - (1000 * 60 * 60 * 24)
      val topicsInfo: Seq[(TopicPartition, Long)] =
        partitions.map { t =>
          new TopicPartition(eventsConfig.topic, t.partition()) -> lastDate
        }
      Subscriptions.assignmentOffsetsForTimes(topicsInfo: _*)
    } getOrElse {
      val topicsInfo: Seq[TopicPartition] =
        partitions.map { t =>
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

  override def close() =
    producer.close()

  private def callback(cb: Either[Throwable, Done] => Unit) = new Callback {
    override def onCompletion(metadata: RecordMetadata, exception: Exception) =
      if (exception != null) {
        cb(Left(exception))
      } else {
        cb(Right(Done))
      }
  }

  override def check(): F[Unit] =
    Async[F].async { cb =>
      system.dispatchers.lookup("izanami.blocking-dispatcher").execute { () =>
        try {
          val consumer = KafkaSettings.consumerSettings(_env, system, clusterConfig).createKafkaConsumer()
          consumer.partitionsFor(eventsConfig.topic)
          consumer.close()
          cb(Right(()))
        } catch {
          case NonFatal(e) =>
            cb(Left(e))
        }
      }
    }
}
