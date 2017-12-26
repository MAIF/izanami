package domains.events

import java.io.Closeable
import java.net.InetSocketAddress
import java.nio.charset.StandardCharsets
import java.time.LocalDateTime

import akka.actor.{Actor, ActorSystem, PoisonPill, Props}
import akka.cluster.pubsub.DistributedPubSub
import akka.cluster.pubsub.DistributedPubSubMediator.{Publish, Subscribe}
import akka.http.scaladsl.util.FastFuture
import akka.kafka.scaladsl.Consumer
import akka.kafka.{ConsumerSettings, ManualSubscription, Subscriptions}
import akka.serialization.SerializerWithStringManifest
import akka.stream.scaladsl.{
  BroadcastHub,
  Flow,
  Keep,
  Source,
  SourceQueueWithComplete
}
import akka.stream.{ActorMaterializer, Materializer, OverflowStrategy}
import akka.util.ByteString
import akka.{Done, NotUsed}
import com.typesafe.config.{Config => TsConfig}
import domains.Domain.Domain
import domains.abtesting.Experiment.ExperimentKey
import domains.abtesting._
import domains.apikey.Apikey
import domains.apikey.ApikeyStore.ApikeyKey
import domains.config.Config
import domains.config.ConfigStore.ConfigKey
import domains.events.Events.IzanamiEvent
import domains.feature.Feature
import domains.feature.FeatureStore.FeatureKey
import domains.script.GlobalScript
import domains.script.GlobalScriptStore.GlobalScriptKey
import domains.user.User
import domains.user.UserStore.UserKey
import domains.webhook.Webhook
import domains.webhook.WebhookStore.WebhookKey
import domains.{Domain, Key}
import env._
import libs.IdGenerator
import org.apache.kafka.clients.CommonClientConfigs
import org.apache.kafka.clients.consumer.{ConsumerConfig, KafkaConsumer}
import org.apache.kafka.clients.producer.{
  Callback,
  KafkaProducer,
  ProducerRecord,
  RecordMetadata
}
import org.apache.kafka.common.TopicPartition
import org.apache.kafka.common.serialization.{
  ByteArraySerializer,
  StringDeserializer,
  StringSerializer
}
import play.api.inject.ApplicationLifecycle
import play.api.libs.json._
import play.api.{Environment, Logger}
import redis.RedisClientMasterSlaves
import redis.actors.RedisSubscriberActor
import redis.api.pubsub.{Message, PMessage}

import scala.concurrent.{Future, Promise}
import scala.util.control.NonFatal
import scala.util.{Failure, Try}

object Events {

  private val gen = IdGenerator(1024)

  trait IzanamiEvent {
    def _id: Long
    def `type`: String
    def domain: Domain
    def key: Key
    def timestamp: LocalDateTime
    def payload: JsValue
    def toJson: JsValue =
      Json.obj("_id" -> _id,
               "type" -> `type`,
               "key" -> Key.writes.writes(key),
               "domain" -> Json.toJson(domain),
               "payload" -> payload,
               "timestamp" -> timestamp)
  }

  object IzanamiEvent {

    private val reads: Reads[IzanamiEvent] = Reads[IzanamiEvent] {
      //CONFIGS
      case o: JsObject if (o \ "type").as[String] == "CONFIG_CREATED" =>
        for {
          _id <- (o \ "_id").validate[Long]
          ts <- (o \ "timestamp").validate[LocalDateTime]
          payload <- (o \ "payload").validate[Config]
          key <- (o \ "key").validate[Key]
        } yield ConfigCreated(key, payload, _id, ts)
      case o: JsObject if (o \ "type").as[String] == "CONFIG_UPDATED" =>
        for {
          _id <- (o \ "_id").validate[Long]
          ts <- (o \ "timestamp").validate[LocalDateTime]
          payload <- (o \ "payload").validate[Config]
          key <- (o \ "key").validate[Key]
          oldValue <- (o \ "oldValue").validate[Config]
        } yield ConfigUpdated(key, oldValue, payload, _id, ts)
      case o: JsObject if (o \ "type").as[String] == "CONFIG_DELETED" =>
        for {
          _id <- (o \ "_id").validate[Long]
          ts <- (o \ "timestamp").validate[LocalDateTime]
          payload <- (o \ "payload").validate[Config]
          key <- (o \ "key").validate[Key]
        } yield ConfigDeleted(key, payload, _id, ts)
      case o: JsObject if (o \ "type").as[String] == "CONFIGS_DELETED" =>
        for {
          _id <- (o \ "_id").validate[Long]
          ts <- (o \ "timestamp").validate[LocalDateTime]
          count <- (o \ "payload").validate[Long]
        } yield ConfigsDeleted(count, _id, ts)
      //FEATURES
      case o: JsObject if (o \ "type").as[String] == "FEATURE_CREATED" =>
        for {
          _id <- (o \ "_id").validate[Long]
          ts <- (o \ "timestamp").validate[LocalDateTime]
          payload <- (o \ "payload").validate[Feature]
          key <- (o \ "key").validate[Key]
        } yield FeatureCreated(key, payload, _id, ts)
      case o: JsObject if (o \ "type").as[String] == "FEATURE_UPDATED" =>
        for {
          _id <- (o \ "_id").validate[Long]
          ts <- (o \ "timestamp").validate[LocalDateTime]
          payload <- (o \ "payload").validate[Feature]
          key <- (o \ "key").validate[Key]
          oldValue <- (o \ "oldValue").validate[Feature]
        } yield FeatureUpdated(key, oldValue, payload, _id, ts)
      case o: JsObject if (o \ "type").as[String] == "FEATURE_DELETED" =>
        for {
          _id <- (o \ "_id").validate[Long]
          ts <- (o \ "timestamp").validate[LocalDateTime]
          payload <- (o \ "payload").validate[Feature]
          key <- (o \ "key").validate[Key]
        } yield FeatureDeleted(key, payload, _id, ts)
      case o: JsObject if (o \ "type").as[String] == "FEATURES_DELETED" =>
        for {
          _id <- (o \ "_id").validate[Long]
          ts <- (o \ "timestamp").validate[LocalDateTime]
          count <- (o \ "payload").validate[Long]
        } yield FeaturesDeleted(count, _id, ts)
      //SCRIPTS
      case o: JsObject if (o \ "type").as[String] == "GLOBALSCRIPT_CREATED" =>
        for {
          _id <- (o \ "_id").validate[Long]
          ts <- (o \ "timestamp").validate[LocalDateTime]
          payload <- (o \ "payload").validate[GlobalScript]
          key <- (o \ "key").validate[Key]
        } yield GlobalScriptCreated(key, payload, _id, ts)
      case o: JsObject if (o \ "type").as[String] == "GLOBALSCRIPT_UPDATED" =>
        for {
          _id <- (o \ "_id").validate[Long]
          ts <- (o \ "timestamp").validate[LocalDateTime]
          payload <- (o \ "payload").validate[GlobalScript]
          key <- (o \ "key").validate[Key]
          oldValue <- (o \ "oldValue").validate[GlobalScript]
        } yield GlobalScriptUpdated(key, oldValue, payload, _id, ts)
      case o: JsObject if (o \ "type").as[String] == "GLOBALSCRIPT_DELETED" =>
        for {
          _id <- (o \ "_id").validate[Long]
          ts <- (o \ "timestamp").validate[LocalDateTime]
          payload <- (o \ "payload").validate[GlobalScript]
          key <- (o \ "key").validate[Key]
        } yield GlobalScriptDeleted(key, payload, _id, ts)
      case o: JsObject if (o \ "type").as[String] == "GLOBALSCRIPTS_DELETED" =>
        for {
          _id <- (o \ "_id").validate[Long]
          ts <- (o \ "timestamp").validate[LocalDateTime]
          count <- (o \ "payload").validate[Long]
        } yield GlobalScriptsDeleted(count, _id, ts)
      //USER
      case o: JsObject if (o \ "type").as[String] == "USER_CREATED" =>
        for {
          _id <- (o \ "_id").validate[Long]
          ts <- (o \ "timestamp").validate[LocalDateTime]
          payload <- (o \ "payload").validate[User]
          key <- (o \ "key").validate[Key]
        } yield UserCreated(key, payload, _id, ts)
      case o: JsObject if (o \ "type").as[String] == "USER_UPDATED" =>
        for {
          _id <- (o \ "_id").validate[Long]
          ts <- (o \ "timestamp").validate[LocalDateTime]
          payload <- (o \ "payload").validate[User]
          key <- (o \ "key").validate[Key]
          oldValue <- (o \ "oldValue").validate[User]
        } yield UserUpdated(key, oldValue, payload, _id, ts)
      case o: JsObject if (o \ "type").as[String] == "USER_DELETED" =>
        for {
          _id <- (o \ "_id").validate[Long]
          ts <- (o \ "timestamp").validate[LocalDateTime]
          payload <- (o \ "payload").validate[User]
          key <- (o \ "key").validate[Key]
        } yield UserDeleted(key, payload, _id, ts)
      case o: JsObject if (o \ "type").as[String] == "USERS_DELETED" =>
        for {
          _id <- (o \ "_id").validate[Long]
          ts <- (o \ "timestamp").validate[LocalDateTime]
          count <- (o \ "payload").validate[Long]
        } yield UsersDeleted(count)
      //WEBHOOK
      case o: JsObject if (o \ "type").as[String] == "WEBHOOK_CREATED" =>
        for {
          _id <- (o \ "_id").validate[Long]
          ts <- (o \ "timestamp").validate[LocalDateTime]
          payload <- (o \ "payload").validate[Webhook]
          key <- (o \ "key").validate[Key]
        } yield WebhookCreated(key, payload, _id, ts)
      case o: JsObject if (o \ "type").as[String] == "WEBHOOK_UPDATED" =>
        for {
          _id <- (o \ "_id").validate[Long]
          ts <- (o \ "timestamp").validate[LocalDateTime]
          payload <- (o \ "payload").validate[Webhook]
          key <- (o \ "key").validate[Key]
          oldValue <- (o \ "oldValue").validate[Webhook]
        } yield WebhookUpdated(key, oldValue, payload, _id, ts)
      case o: JsObject if (o \ "type").as[String] == "WEBHOOK_DELETED" =>
        for {
          _id <- (o \ "_id").validate[Long]
          ts <- (o \ "timestamp").validate[LocalDateTime]
          payload <- (o \ "payload").validate[Webhook]
          key <- (o \ "key").validate[Key]
        } yield WebhookDeleted(key, payload, _id, ts)
      case o: JsObject if (o \ "type").as[String] == "WEBHOOKS_DELETED" =>
        for {
          _id <- (o \ "_id").validate[Long]
          ts <- (o \ "timestamp").validate[LocalDateTime]
          count <- (o \ "payload").validate[Long]
        } yield WebhooksDeleted(count, _id, ts)
      //APIKEY
      case o: JsObject if (o \ "type").as[String] == "APIKEY_CREATED" =>
        for {
          _id <- (o \ "_id").validate[Long]
          ts <- (o \ "timestamp").validate[LocalDateTime]
          payload <- (o \ "payload").validate[Apikey]
          key <- (o \ "key").validate[Key]
        } yield ApikeyCreated(key, payload, _id, ts)
      case o: JsObject if (o \ "type").as[String] == "APIKEY_UPDATED" =>
        for {
          _id <- (o \ "_id").validate[Long]
          ts <- (o \ "timestamp").validate[LocalDateTime]
          payload <- (o \ "payload").validate[Apikey]
          key <- (o \ "key").validate[Key]
          oldValue <- (o \ "oldValue").validate[Apikey]
        } yield ApikeyUpdated(key, oldValue, payload, _id, ts)
      case o: JsObject if (o \ "type").as[String] == "APIKEY_DELETED" =>
        for {
          _id <- (o \ "_id").validate[Long]
          ts <- (o \ "timestamp").validate[LocalDateTime]
          payload <- (o \ "payload").validate[Apikey]
          key <- (o \ "key").validate[Key]
        } yield ApikeyDeleted(key, payload, _id, ts)
      case o: JsObject if (o \ "type").as[String] == "APIKEYS_DELETED" =>
        for {
          _id <- (o \ "_id").validate[Long]
          ts <- (o \ "timestamp").validate[LocalDateTime]
          count <- (o \ "payload").validate[Long]
        } yield ApikeysDeleted(count, _id, ts)
      //EXPERIMENT
      case o: JsObject if (o \ "type").as[String] == "EXPERIMENT_CREATED" =>
        for {
          _id <- (o \ "_id").validate[Long]
          ts <- (o \ "timestamp").validate[LocalDateTime]
          payload <- (o \ "payload").validate[Experiment]
          key <- (o \ "key").validate[Key]
        } yield ExperimentCreated(key, payload, _id, ts)
      case o: JsObject if (o \ "type").as[String] == "EXPERIMENT_UPDATED" =>
        for {
          _id <- (o \ "_id").validate[Long]
          ts <- (o \ "timestamp").validate[LocalDateTime]
          payload <- (o \ "payload").validate[Experiment]
          key <- (o \ "key").validate[Key]
          oldValue <- (o \ "oldValue").validate[Experiment]
        } yield ExperimentUpdated(key, oldValue, payload, _id, ts)
      case o: JsObject if (o \ "type").as[String] == "EXPERIMENT_DELETED" =>
        for {
          _id <- (o \ "_id").validate[Long]
          ts <- (o \ "timestamp").validate[LocalDateTime]
          payload <- (o \ "payload").validate[Experiment]
          key <- (o \ "key").validate[Key]
        } yield ExperimentDeleted(key, payload, _id, ts)
      case o: JsObject if (o \ "type").as[String] == "EXPERIMENTS_DELETED" =>
        for {
          _id <- (o \ "_id").validate[Long]
          ts <- (o \ "timestamp").validate[LocalDateTime]
          count <- (o \ "payload").validate[Long]
        } yield ExperimentsDeleted(count, _id, ts)
      //VARIANT BINDING
      case o: JsObject
          if (o \ "type").as[String] == "VARIANT_BINDING_CREATED" =>
        for {
          _id <- (o \ "_id").validate[Long]
          ts <- (o \ "timestamp").validate[LocalDateTime]
          payload <- (o \ "payload").validate[VariantBinding]
          key <- (o \ "key").validate[VariantBindingKey]
        } yield VariantBindingCreated(key, payload, _id, ts)
      //VARIANT BINDING EVENT
      case o: JsObject
          if (o \ "type").as[String] == "EXPERIMENT_VARIANT_EVENT_CREATED" =>
        for {
          _id <- (o \ "_id").validate[Long]
          ts <- (o \ "timestamp").validate[LocalDateTime]
          payload <- (o \ "payload").validate[ExperimentVariantEvent]
          key <- (o \ "key").validate[ExperimentVariantEventKey]
        } yield ExperimentVariantEventCreated(key, payload, _id, ts)
      case o: JsObject
          if (o \ "type").as[String] == "EXPERIMENT_VARIANT_EVENT_DELETED" =>
        for {
          _id <- (o \ "_id").validate[Long]
          ts <- (o \ "timestamp").validate[LocalDateTime]
          payload <- (o \ "payload").validate[Experiment]
        } yield ExperimentVariantEventsDeleted(payload, _id, ts)
      case _ =>
        JsError("events.unknow.type")
    }

    private val writes: Writes[IzanamiEvent] = Writes[IzanamiEvent] { event =>
      event.toJson
    }

    implicit val format = Format(reads, writes)

  }

  /////////////////////////////////////// CONFIG ////////////////////////////////////////

  sealed trait ConfigEvent extends IzanamiEvent {
    override def domain = Domain.Config
  }

  case class ConfigCreated(key: ConfigKey,
                           config: Config,
                           _id: Long = gen.nextId(),
                           timestamp: LocalDateTime = LocalDateTime.now())
      extends ConfigEvent {
    val `type`: String = "CONFIG_CREATED"
    val payload: JsValue = Json.toJson(config)
  }
  case class ConfigUpdated(key: ConfigKey,
                           oldValue: Config,
                           config: Config,
                           _id: Long = gen.nextId(),
                           timestamp: LocalDateTime = LocalDateTime.now())
      extends ConfigEvent {
    val `type`: String = "CONFIG_UPDATED"
    val payload: JsValue = Json.toJson(config)
    override def toJson: JsValue =
      super.toJson.as[JsObject] ++ Json.obj("oldValue" -> Json.toJson(oldValue))
  }
  case class ConfigDeleted(key: ConfigKey,
                           config: Config,
                           _id: Long = gen.nextId(),
                           timestamp: LocalDateTime = LocalDateTime.now())
      extends ConfigEvent {
    val `type`: String = "CONFIG_DELETED"
    val payload: JsValue = Json.toJson(config)
  }
  case class ConfigsDeleted(_id: Long = gen.nextId(),
                            count: Long,
                            timestamp: LocalDateTime = LocalDateTime.now())
      extends ConfigEvent {
    val `type`: String = "CONFIGS_DELETED"
    val key: ConfigKey = Key.Empty
    val payload: JsValue = JsNumber(count)
  }

  object ConfigEvent {
    implicit val configCreated = Json.format[ConfigCreated]
    implicit val configUpdated = Json.format[ConfigUpdated]
  }

  /////////////////////////////////////// FEATURE ////////////////////////////////////////

  sealed trait FeatureEvent extends IzanamiEvent {
    override def domain = Domain.Feature
  }

  case class FeatureCreated(key: FeatureKey,
                            feature: Feature,
                            _id: Long = gen.nextId(),
                            timestamp: LocalDateTime = LocalDateTime.now())
      extends FeatureEvent {
    val `type`: String = "FEATURE_CREATED"
    val payload: JsValue = Json.toJson(feature)
  }
  case class FeatureUpdated(key: FeatureKey,
                            oldValue: Feature,
                            feature: Feature,
                            _id: Long = gen.nextId(),
                            timestamp: LocalDateTime = LocalDateTime.now())
      extends FeatureEvent {
    val `type`: String = "FEATURE_UPDATED"
    val payload: JsValue = Json.toJson(feature)

    override def toJson: JsValue =
      super.toJson.as[JsObject] ++ Json.obj("oldValue" -> Json.toJson(oldValue))
  }
  case class FeatureDeleted(key: FeatureKey,
                            feature: Feature,
                            _id: Long = gen.nextId(),
                            timestamp: LocalDateTime = LocalDateTime.now())
      extends FeatureEvent {
    val `type`: String = "FEATURE_DELETED"
    val payload: JsValue = Json.toJson(feature)
  }
  case class FeaturesDeleted(count: Long,
                             _id: Long = gen.nextId(),
                             timestamp: LocalDateTime = LocalDateTime.now())
      extends FeatureEvent {
    val key: FeatureKey = Key.Empty
    val `type`: String = "FEATURES_DELETED"
    val payload: JsValue = JsNumber(count)
  }

  object FeatureEvent {
    implicit val featureCreated = Json.format[FeatureCreated]
    implicit val featureUpdated = Json.format[FeatureUpdated]
  }

  /////////////////////////////////////// SCRIPT ////////////////////////////////////////

  sealed trait GlobalScriptEvent extends IzanamiEvent {
    override def domain = Domain.Script
  }

  case class GlobalScriptCreated(key: GlobalScriptKey,
                                 globalScript: GlobalScript,
                                 _id: Long = gen.nextId(),
                                 timestamp: LocalDateTime = LocalDateTime.now())
      extends GlobalScriptEvent {
    val `type`: String = "GLOBALSCRIPT_CREATED"
    val payload: JsValue = Json.toJson(globalScript)
  }
  case class GlobalScriptUpdated(key: GlobalScriptKey,
                                 oldValue: GlobalScript,
                                 globalScript: GlobalScript,
                                 _id: Long = gen.nextId(),
                                 timestamp: LocalDateTime = LocalDateTime.now())
      extends GlobalScriptEvent {
    val `type`: String = "GLOBALSCRIPT_UPDATED"
    val payload: JsValue = Json.toJson(globalScript)
    override def toJson: JsValue =
      super.toJson.as[JsObject] ++ Json.obj("oldValue" -> Json.toJson(oldValue))
  }
  case class GlobalScriptDeleted(key: GlobalScriptKey,
                                 globalScript: GlobalScript,
                                 _id: Long = gen.nextId(),
                                 timestamp: LocalDateTime = LocalDateTime.now())
      extends GlobalScriptEvent {
    val `type`: String = "GLOBALSCRIPT_DELETED"
    val payload: JsValue = Json.toJson(globalScript)
  }
  case class GlobalScriptsDeleted(
      count: Long,
      _id: Long = gen.nextId(),
      timestamp: LocalDateTime = LocalDateTime.now())
      extends GlobalScriptEvent {
    val key = Key.Empty
    val `type`: String = "GLOBALSCRIPTS_DELETED"
    val payload: JsValue = JsNumber(count)
  }

  /////////////////////////////////////// USER ////////////////////////////////////////

  sealed trait UserEvent extends IzanamiEvent {
    override def domain = Domain.User
  }

  case class UserCreated(key: UserKey,
                         user: User,
                         _id: Long = gen.nextId(),
                         timestamp: LocalDateTime = LocalDateTime.now())
      extends UserEvent {
    val `type`: String = "USER_CREATED"
    val payload: JsValue = Json.toJson(user)
  }

  case class UserUpdated(key: UserKey,
                         oldValue: User,
                         user: User,
                         _id: Long = gen.nextId(),
                         timestamp: LocalDateTime = LocalDateTime.now())
      extends UserEvent {
    val `type`: String = "USER_UPDATED"
    val payload: JsValue = Json.toJson(user)
    override def toJson: JsValue =
      super.toJson.as[JsObject] ++ Json.obj("oldValue" -> Json.toJson(oldValue))
  }

  case class UserDeleted(key: UserKey,
                         user: User,
                         _id: Long = gen.nextId(),
                         timestamp: LocalDateTime = LocalDateTime.now())
      extends UserEvent {
    val `type`: String = "USER_DELETED"
    val payload: JsValue = Json.toJson(user)
  }

  case class UsersDeleted(count: Long,
                          _id: Long = gen.nextId(),
                          timestamp: LocalDateTime = LocalDateTime.now())
      extends UserEvent {
    val `type`: String = "USERS_DELETED"
    val key: UserKey = Key.Empty
    val payload: JsValue = JsNumber(count)
  }

  object UserEvent {
    implicit val userCreated = Json.format[UserCreated]
    implicit val userUpdated = Json.format[UserUpdated]
  }

  /////////////////////////////////////// WEBHOOK ////////////////////////////////////////

  sealed trait WebhookEvent extends IzanamiEvent {
    override def domain = Domain.Webhook
  }

  case class WebhookCreated(key: WebhookKey,
                            webhook: Webhook,
                            _id: Long = gen.nextId(),
                            timestamp: LocalDateTime = LocalDateTime.now())
      extends WebhookEvent {
    val `type`: String = "WEBHOOK_CREATED"
    val payload: JsValue = Json.toJson(webhook)
  }
  case class WebhookUpdated(key: WebhookKey,
                            oldValue: Webhook,
                            webhook: Webhook,
                            _id: Long = gen.nextId(),
                            timestamp: LocalDateTime = LocalDateTime.now())
      extends WebhookEvent {
    val `type`: String = "WEBHOOK_UPDATED"
    val payload: JsValue = Json.toJson(webhook)
    override def toJson: JsValue =
      super.toJson.as[JsObject] ++ Json.obj("oldValue" -> Json.toJson(oldValue))
  }
  case class WebhookDeleted(key: WebhookKey,
                            webhook: Webhook,
                            _id: Long = gen.nextId(),
                            timestamp: LocalDateTime = LocalDateTime.now())
      extends WebhookEvent {
    val `type`: String = "WEBHOOK_DELETED"
    val payload: JsValue = Json.toJson(webhook)
  }
  case class WebhooksDeleted(count: Long,
                             _id: Long = gen.nextId(),
                             timestamp: LocalDateTime = LocalDateTime.now())
      extends WebhookEvent {
    val key = Key.Empty
    val `type`: String = "WEBHOOKS_DELETED"
    val payload: JsValue = JsNumber(count)
  }

  /////////////////////////////////////// APIKEYS ////////////////////////////////////////

  sealed trait ApikeyEvent extends IzanamiEvent {
    override def domain = Domain.ApiKey
  }

  object ApikeyEvent {
    implicit val apikeyCreated = Json.format[ApikeyCreated]
    implicit val apikeyUpdated = Json.format[ApikeyUpdated]
  }

  case class ApikeyCreated(key: ApikeyKey,
                           apikey: Apikey,
                           _id: Long = gen.nextId(),
                           timestamp: LocalDateTime = LocalDateTime.now())
      extends ApikeyEvent {
    val `type`: String = "APIKEY_CREATED"
    val payload: JsValue = Json.toJson(apikey)
  }

  case class ApikeyUpdated(key: ApikeyKey,
                           oldValue: Apikey,
                           apikey: Apikey,
                           _id: Long = gen.nextId(),
                           timestamp: LocalDateTime = LocalDateTime.now())
      extends ApikeyEvent {
    val `type`: String = "APIKEY_UPDATED"
    val payload: JsValue = Json.toJson(apikey)
    override def toJson: JsValue =
      super.toJson.as[JsObject] ++ Json.obj("oldValue" -> Json.toJson(oldValue))
  }

  case class ApikeyDeleted(key: ApikeyKey,
                           apikey: Apikey,
                           _id: Long = gen.nextId(),
                           timestamp: LocalDateTime = LocalDateTime.now())
      extends ApikeyEvent {
    val `type`: String = "APIKEY_DELETED"
    val payload: JsValue = Json.toJson(apikey)
  }

  case class ApikeysDeleted(count: Long,
                            _id: Long = gen.nextId(),
                            timestamp: LocalDateTime = LocalDateTime.now())
      extends ApikeyEvent {
    val `type`: String = "APIKEYS_DELETED"
    val key: ApikeyKey = Key.Empty
    val payload: JsValue = JsNumber(count)
  }

  /////////////////////////////////////// EXPERIMENTS ////////////////////////////////////////
  sealed trait ExperimentEvent extends IzanamiEvent {
    override def domain = Domain.Experiment
  }

  case class ExperimentCreated(key: ExperimentKey,
                               experiment: Experiment,
                               _id: Long = gen.nextId(),
                               timestamp: LocalDateTime = LocalDateTime.now())
      extends ExperimentEvent {
    val `type`: String = "EXPERIMENT_CREATED"
    val payload: JsValue = Json.toJson(experiment)
  }

  case class ExperimentUpdated(key: ExperimentKey,
                               oldValue: Experiment,
                               experiment: Experiment,
                               _id: Long = gen.nextId(),
                               timestamp: LocalDateTime = LocalDateTime.now())
      extends ExperimentEvent {
    val `type`: String = "EXPERIMENT_UPDATED"
    val payload: JsValue = Json.toJson(experiment)
    override def toJson: JsValue =
      super.toJson.as[JsObject] ++ Json.obj("oldValue" -> Json.toJson(oldValue))
  }

  case class ExperimentDeleted(key: ExperimentKey,
                               experiment: Experiment,
                               _id: Long = gen.nextId(),
                               timestamp: LocalDateTime = LocalDateTime.now())
      extends ExperimentEvent {
    val `type`: String = "EXPERIMENT_DELETED"
    val payload: JsValue = Json.toJson(experiment)
  }

  case class ExperimentsDeleted(count: Long,
                                _id: Long = gen.nextId(),
                                timestamp: LocalDateTime = LocalDateTime.now())
      extends ExperimentEvent {
    val `type`: String = "EXPERIMENTS_DELETED"
    val payload: JsValue = JsNumber(count)
    val key: ExperimentKey = Key.Empty
  }

  sealed trait VariantBindingEvent extends ExperimentEvent

  case class VariantBindingCreated(variantBindingKey: VariantBindingKey,
                                   variantBinding: VariantBinding,
                                   _id: Long = gen.nextId(),
                                   timestamp: LocalDateTime =
                                     LocalDateTime.now())
      extends VariantBindingEvent {
    val `type`: String = "VARIANT_BINDING_CREATED"
    val key: ExperimentKey = variantBindingKey.key
    val payload: JsValue = Json.toJson(variantBinding)
  }

  sealed trait ExperimentVariantEventEvent extends ExperimentEvent

  case class ExperimentVariantEventCreated(id: ExperimentVariantEventKey,
                                           data: ExperimentVariantEvent,
                                           _id: Long = gen.nextId(),
                                           timestamp: LocalDateTime =
                                             LocalDateTime.now())
      extends ExperimentVariantEventEvent {
    override def `type`: String = "EXPERIMENT_VARIANT_EVENT_CREATED"
    override def key: ExperimentKey = id.key
    override def payload: JsValue = Json.toJson(data)
  }

  case class ExperimentVariantEventsDeleted(experiment: Experiment,
                                            _id: Long = gen.nextId(),
                                            timestamp: LocalDateTime =
                                              LocalDateTime.now())
      extends ExperimentVariantEventEvent {
    override def `type`: String = "EXPERIMENT_VARIANT_EVENT_DELETED"
    override def key: ExperimentKey = experiment.id
    override def payload: JsValue =
      Json.toJson("experimentId" -> experiment.id.key)
  }

}

object EventLogger {
  val logger = Logger("events")
}

import domains.events.EventLogger._

trait EventStore extends Closeable {

  private[events] def eventMatch(patterns: Seq[String], domains: Seq[Domain])(
      e: IzanamiEvent): Boolean =
    (domains.isEmpty || domains.contains(e.domain)) && (patterns.isEmpty || e.key
      .matchPatterns(
        patterns: _*
      ))

  def dropUntilLastId(
      lastId: Option[Long]): Flow[IzanamiEvent, IzanamiEvent, NotUsed] =
    lastId.map { id =>
      Flow[IzanamiEvent].dropWhile(_._id < id)
    } getOrElse {
      Flow[IzanamiEvent]
    }

  def publish(event: IzanamiEvent): Future[Done]

  def events(domains: Seq[Domain] = Seq.empty[Domain],
             patterns: Seq[String] = Seq.empty[String],
             lastEventId: Option[Long] = None): Source[IzanamiEvent, NotUsed]

}

class BasicEventStore(system: ActorSystem) extends EventStore {

  private implicit val s = system
  private implicit val mat: Materializer = ActorMaterializer()

  logger.info("Starting default event store")

  val source = Source
    .queue[IzanamiEvent](1000, OverflowStrategy.dropBuffer)
    .mapMaterializedValue { q =>
      system.actorOf(EventStreamActor.props(q))
      NotUsed
    }
    .runWith(BroadcastHub.sink(1024))

  override def publish(event: IzanamiEvent): Future[Done] =
    //Already published
    FastFuture.successful(Done)

  override def events(
      domains: Seq[Domain],
      patterns: Seq[String],
      lastEventId: Option[Long]): Source[IzanamiEvent, NotUsed] =
    source
      .via(dropUntilLastId(lastEventId))
      .filter { eventMatch(patterns, domains) }

  override def close() = {}

}

private[events] object EventStreamActor {
  def props(queue: SourceQueueWithComplete[IzanamiEvent]) =
    Props(new EventStreamActor(queue))
}

private[events] class EventStreamActor(
    queue: SourceQueueWithComplete[IzanamiEvent])
    extends Actor {

  import context.dispatcher

  override def receive = {
    case e: IzanamiEvent =>
      logger.debug(s"New event : $e")
      queue.offer(e)
  }

  override def preStart(): Unit = {
    queue
      .watchCompletion()
      .onComplete(_ => Try(context.system.eventStream.unsubscribe(self)))
    context.system.eventStream.subscribe(self, classOf[IzanamiEvent])
  }

  override def postStop(): Unit = {
    context.system.eventStream.unsubscribe(self)
    queue.complete()
  }
}

////////////////////////////////////////////////////////////////////////////////////////////////////////////////////////////////
///////////////////////////////////////////////////////   REDIS   //////////////////////////////////////////////////////////////
////////////////////////////////////////////////////////////////////////////////////////////////////////////////////////////////

class RedisEventStore(client: RedisClientMasterSlaves,
                      config: RedisEventsConfig,
                      system: ActorSystem)
    extends EventStore {

  import system.dispatcher

  implicit private val s = system
  implicit private val mat = ActorMaterializer()

  logger.info(s"Starting redis event store")

  private val (queue, source) = Source
    .queue[IzanamiEvent](1000, OverflowStrategy.dropHead)
    .toMat(BroadcastHub.sink[IzanamiEvent](1024))(Keep.both)
    .run()

  val actor = system.actorOf(
    Props(new SubscribeActor(client, config, queue))
      .withDispatcher("rediscala.rediscala-client-worker-dispatcher"),
    "eventRedisActor"
  )

  override def publish(event: IzanamiEvent): Future[Done] = {
    logger.debug(s"Publishing event $event to Redis topic izanamiEvents")
    val publish =
      client.publish(config.topic, Json.stringify(event.toJson)).map { _ =>
        Done
      }
    publish.onComplete {
      case Failure(e) =>
        logger.error(s"Error publishing event to Redis", e)
      case _ =>
    }
    publish
  }

  override def events(
      domains: Seq[Domain],
      patterns: Seq[String],
      lastEventId: Option[Long]): Source[IzanamiEvent, NotUsed] =
    source
      .via(dropUntilLastId(lastEventId))
      .filter(eventMatch(patterns, domains))

  override def close() = actor ! PoisonPill
}

private[events] class SubscribeActor(
    client: RedisClientMasterSlaves,
    config: RedisEventsConfig,
    queue: SourceQueueWithComplete[IzanamiEvent])
    extends RedisSubscriberActor(
      new InetSocketAddress(client.master.host, client.master.port),
      Seq(config.topic),
      Nil,
      authPassword = client.master.password,
      onConnectStatus = connected => {
        logger.info(s"Connected to redis pub sub: $connected")
      }
    ) {

  import context.dispatcher

  override def onMessage(m: Message): Unit =
    sendMessage(m.data)

  override def onPMessage(pm: PMessage): Unit =
    sendMessage(pm.data)

  private def sendMessage(bs: ByteString) = {
    val json = Json.parse(bs.utf8String)
    val result: JsResult[IzanamiEvent] = json.validate[IzanamiEvent]
    result match {
      case JsSuccess(e, _) =>
        logger.debug(s"Receiving new event $e from Redis topic")
        queue.offer(e).onComplete {
          case Failure(e) => Logger.error(s"Error publishing event to queue", e)
          case r          => Logger.debug(s"Event published to queue $r")
        }
      case JsError(errors) =>
        logger.error(
          s"Error deserializing event of type ${json \ "type"} : $errors")
    }
  }

  override def preStart(): Unit = {
    super.preStart()
    logger.debug(s"Creating redis events handler")
    queue
      .watchCompletion()
      .onComplete(_ => Try(context.system.eventStream.unsubscribe(self)))
  }

  override def postStop(): Unit = {
    super.postStop()
    queue.complete()
  }
}

////////////////////////////////////////////////////////////////////////////////////////////////////////////////////////////////
///////////////////////////////////////////////////////   KAFKA   //////////////////////////////////////////////////////////////
////////////////////////////////////////////////////////////////////////////////////////////////////////////////////////////////

object KafkaSettings {

  import akka.kafka.{ConsumerSettings, ProducerSettings}
  import org.apache.kafka.clients.consumer.ConsumerConfig
  import org.apache.kafka.common.config.SslConfigs
  import org.apache.kafka.common.serialization.ByteArrayDeserializer

  def consumerSettings(
      _env: Environment,
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

  def producerSettings(
      _env: Environment,
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
      producer.send(
        new ProducerRecord[Array[Byte], String](eventsConfig.topic, message),
        callback(promise))
    } catch {
      case NonFatal(e) =>
        promise.failure(e)
    }
    promise.future.map { _ =>
      Done
    }
  }

  override def events(
      domains: Seq[Domain],
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
                logger.error(
                  s"Error deserializing event of type ${json \ "type"} : $err")
                List.empty[IzanamiEvent]
              },
              e => List(e)
          )
      )
      .watchTermination() {
        case (control, done) =>
          done.onComplete { _ =>
            control.shutdown()
          }
      }
      .via(dropUntilLastId(lastEventId))
      .filter(eventMatch(patterns, domains))
      .mapMaterializedValue(_ => NotUsed)
  }

  override def close() = {
    kafkaConsumer.close()
    producer.close()
  }

  private def callback(promise: Promise[RecordMetadata]) = new Callback {
    override def onCompletion(metadata: RecordMetadata, exception: Exception) =
      if (exception != null) {
        promise.failure(exception)
      } else {
        promise.success(metadata)
      }

  }

}

////////////////////////////////////////////////////////////////////////////////////////////////////////////////////////////////
///////////////////////////////////////////////   DISTRIBUTED PUB/SUB   ////////////////////////////////////////////////////////
////////////////////////////////////////////////////////////////////////////////////////////////////////////////////////////////

class DistributedPubSubEventStore(globalConfig: TsConfig,
                                  config: DistributedEventsConfig,
                                  lifecycle: ApplicationLifecycle)
    extends EventStore {

  Logger.info(
    s"Starting akka cluster with config ${globalConfig.getConfig("cluster")}")

  private val actorSystemName: String =
    globalConfig.getString("cluster.system-name")
  implicit private val s =
    ActorSystem(actorSystemName, globalConfig.getConfig("cluster"))
  implicit private val mat = ActorMaterializer()

  logger.info(s"Creating distributed event store")

  private val (queue, source) = Source
    .queue[IzanamiEvent](1000, OverflowStrategy.dropHead)
    .toMat(BroadcastHub.sink[IzanamiEvent](1024))(Keep.both)
    .run()

  private val actor =
    s.actorOf(DistributedEventsPublisherActor.props(queue, config))

  override def publish(event: IzanamiEvent): Future[Done] = {
    actor ! DistributedEventsPublisherActor.Publish(event)
    FastFuture.successful(Done)
  }

  override def events(
      domains: Seq[Domain],
      patterns: Seq[String],
      lastEventId: Option[Long]): Source[IzanamiEvent, NotUsed] =
    source
      .via(dropUntilLastId(lastEventId))
      .filter(eventMatch(patterns, domains))

  override def close() = actor ! PoisonPill

  lifecycle.addStopHook { () =>
    Logger.info(s"Stopping actor system $actorSystemName")
    s.terminate()
  }
}

class CustomSerializer extends SerializerWithStringManifest {
  private val UTF_8 = StandardCharsets.UTF_8.name()

  private val MessageManifest = "MessageManifest"

  def manifest(obj: AnyRef): String =
    obj match {
      case _: DistributedEventsPublisherActor.Message => MessageManifest
    }

  def identifier = 1000
  def toBinary(obj: AnyRef): Array[Byte] =
    obj match {
      case DistributedEventsPublisherActor.Message(json) =>
        Json.stringify(json).getBytes(UTF_8)
      case other =>
        throw new IllegalStateException(
          s"MessageSerializer : Unknow object $other")
    }

  def fromBinary(bytes: Array[Byte], manifest: String): AnyRef =
    manifest match {
      case MessageManifest =>
        DistributedEventsPublisherActor.Message(Json.parse(bytes))
    }
}

object DistributedEventsPublisherActor {
  case class Publish(event: IzanamiEvent)
  case class Message(event: JsValue)

  def props(queue: SourceQueueWithComplete[IzanamiEvent],
            config: DistributedEventsConfig): Props =
    Props(new DistributedEventsPublisherActor(queue, config))
}

private[events] class DistributedEventsPublisherActor(
    queue: SourceQueueWithComplete[IzanamiEvent],
    config: DistributedEventsConfig)
    extends Actor {
  import context.dispatcher

  private val mediator = DistributedPubSub(context.system).mediator
  mediator ! Subscribe(config.topic, self)

  override def receive = {
    case DistributedEventsPublisherActor.Publish(event) =>
      mediator ! Publish(
        config.topic,
        DistributedEventsPublisherActor.Message(Json.toJson(event)))
    case DistributedEventsPublisherActor.Message(json) =>
      logger.debug(s"New event $json")
      json
        .validate[IzanamiEvent]
        .fold(
          err =>
            logger.error(
              s"Error deserializing event of type ${json \ "type"} : $err"),
          e => queue.offer(e)
        )
  }

  override def preStart(): Unit =
    queue
      .watchCompletion()
      .onComplete(_ => Try(context.system.eventStream.unsubscribe(self)))

  override def postStop(): Unit =
    queue.complete()
}
