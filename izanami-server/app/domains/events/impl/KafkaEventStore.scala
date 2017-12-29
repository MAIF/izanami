package domains.events.impl

import akka.{Done, NotUsed}
import akka.actor.ActorSystem
import akka.kafka.{ConsumerSettings, ManualSubscription, Subscriptions}
import akka.kafka.scaladsl.Consumer
import akka.stream.scaladsl.Source
import domains.Domain.Domain
import domains.events.EventStore
import domains.events.Events.IzanamiEvent
import env.{KafkaConfig, KafkaEventsConfig}
import org.apache.kafka.clients.CommonClientConfigs
import org.apache.kafka.clients.consumer.{ConsumerConfig, KafkaConsumer}
import org.apache.kafka.clients.producer.{Callback, KafkaProducer, ProducerRecord, RecordMetadata}
import org.apache.kafka.common.TopicPartition
import org.apache.kafka.common.serialization.{ByteArraySerializer, StringDeserializer, StringSerializer}
import play.api.{Environment, Logger}
import play.api.libs.json.Json

import scala.concurrent.{Future, Promise}
import scala.util.control.NonFatal
import domains.events.EventLogger._



object KafkaSettings {

  import akka.kafka.{ConsumerSettings, ProducerSettings}
  import org.apache.kafka.clients.consumer.ConsumerConfig
  import org.apache.kafka.common.config.SslConfigs
  import org.apache.kafka.common.serialization.ByteArrayDeserializer

  def consumerSettings(_env: Environment,
                       system: ActorSystem,
                       config: KafkaConfig): ConsumerSettings[Array[Byte], String] = {

    val settings = ConsumerSettings
      .create(system, new ByteArrayDeserializer, new StringDeserializer())
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
        .withProperty(SslConfigs.SSL_CLIENT_AUTH_CONFIG, "required")
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
                       config: KafkaConfig): ProducerSettings[Array[Byte], String] = {
    val settings = ProducerSettings
      .create(system, new ByteArraySerializer(), new StringSerializer())
      .withBootstrapServers(config.servers)

    val s = for {
      ks <- config.keystore.location
      ts <- config.truststore.location
      kp <- config.keyPass
    } yield {
      settings
        .withProperty(CommonClientConfigs.SECURITY_PROTOCOL_CONFIG, "SSL")
        .withProperty(SslConfigs.SSL_CLIENT_AUTH_CONFIG, "required")
        .withProperty(SslConfigs.SSL_KEY_PASSWORD_CONFIG, kp)
        .withProperty(SslConfigs.SSL_KEYSTORE_LOCATION_CONFIG, ks)
        .withProperty(SslConfigs.SSL_KEYSTORE_PASSWORD_CONFIG, kp)
        .withProperty(SslConfigs.SSL_TRUSTSTORE_LOCATION_CONFIG, ts)
        .withProperty(SslConfigs.SSL_TRUSTSTORE_PASSWORD_CONFIG, kp)
    }

    s.getOrElse(settings)
  }
}

class KafkaEventStore(_env: Environment,
                      system: ActorSystem,
                      clusterConfig: KafkaConfig,
                      eventsConfig: KafkaEventsConfig)
  extends EventStore {

  import system.dispatcher

  Logger.info(s"Initializing kafka event store $clusterConfig")

  private lazy val producerSettings =
    KafkaSettings.producerSettings(_env, system, clusterConfig)
  private lazy val producer: KafkaProducer[Array[Byte], String] =
    producerSettings.createKafkaProducer

  val settings: ConsumerSettings[Array[Byte], String] = KafkaSettings
    .consumerSettings(_env, system, clusterConfig)
    .withProperty(ConsumerConfig.ENABLE_AUTO_COMMIT_CONFIG, "false")
    .withProperty(ConsumerConfig.AUTO_OFFSET_RESET_CONFIG, "latest")

  override def publish(event: IzanamiEvent): Future[Done] = {
    val promise: Promise[RecordMetadata] = Promise[RecordMetadata]
    try {
      val message = Json.stringify(event.toJson)
      producer.send(new ProducerRecord[Array[Byte], String](eventsConfig.topic, message), callback(promise))
    } catch {
      case NonFatal(e) =>
        promise.failure(e)
    }
    promise.future.map { _ =>
      Done
    }
  }

  override def events(domains: Seq[Domain],
                      patterns: Seq[String],
                      lastEventId: Option[Long]): Source[IzanamiEvent, NotUsed] = {

    import scala.collection.JavaConverters._

    val kafkaConsumer: KafkaConsumer[Array[Byte], String] =
      settings.createKafkaConsumer()

    val subscription: ManualSubscription = lastEventId.map { id =>
      val lastDate: Long = System.currentTimeMillis() - (1000 * 60 * 60 * 24)
      val topicsInfo: Seq[(TopicPartition, Long)] =
        kafkaConsumer.partitionsFor(eventsConfig.topic).asScala.map { t =>
          new TopicPartition(eventsConfig.topic, t.partition()) -> lastDate
        }
      Subscriptions.assignmentOffsetsForTimes(topicsInfo: _*)
    } getOrElse {
      val topicsInfo: Seq[TopicPartition] =
        kafkaConsumer.partitionsFor(eventsConfig.topic).asScala.map { t =>
          new TopicPartition(eventsConfig.topic, t.partition())
        }
      Subscriptions.assignment(topicsInfo: _*)
    }

    Consumer
      .plainSource[Array[Byte], String](settings, subscription)
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

  private def callback(promise: Promise[RecordMetadata]) = new Callback {
    override def onCompletion(metadata: RecordMetadata, exception: Exception) =
      if (exception != null) {
        promise.failure(exception)
      } else {
        promise.success(metadata)
      }

  }

}
