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
import akka.stream.scaladsl.{BroadcastHub, Flow, Keep, Source, SourceQueueWithComplete}
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
import org.apache.kafka.clients.producer.{Callback, KafkaProducer, ProducerRecord, RecordMetadata}
import org.apache.kafka.common.TopicPartition
import org.apache.kafka.common.serialization.{ByteArraySerializer, StringDeserializer, StringSerializer}
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
      Json.obj("_id"       -> _id,
               "type"      -> `type`,
               "key"       -> Key.writes.writes(key),
               "domain"    -> Json.toJson(domain),
               "payload"   -> payload,
               "timestamp" -> timestamp)
  }

  object IzanamiEvent {

    private val reads: Reads[IzanamiEvent] = Reads[IzanamiEvent] {
      //CONFIGS
      case o: JsObject if (o \ "type").as[String] == "CONFIG_CREATED" =>
        for {
          _id     <- (o \ "_id").validate[Long]
          ts      <- (o \ "timestamp").validate[LocalDateTime]
          payload <- (o \ "payload").validate[Config]
          key     <- (o \ "key").validate[Key]
        } yield ConfigCreated(key, payload, _id, ts)
      case o: JsObject if (o \ "type").as[String] == "CONFIG_UPDATED" =>
        for {
          _id      <- (o \ "_id").validate[Long]
          ts       <- (o \ "timestamp").validate[LocalDateTime]
          payload  <- (o \ "payload").validate[Config]
          key      <- (o \ "key").validate[Key]
          oldValue <- (o \ "oldValue").validate[Config]
        } yield ConfigUpdated(key, oldValue, payload, _id, ts)
      case o: JsObject if (o \ "type").as[String] == "CONFIG_DELETED" =>
        for {
          _id     <- (o \ "_id").validate[Long]
          ts      <- (o \ "timestamp").validate[LocalDateTime]
          payload <- (o \ "payload").validate[Config]
          key     <- (o \ "key").validate[Key]
        } yield ConfigDeleted(key, payload, _id, ts)
      case o: JsObject if (o \ "type").as[String] == "CONFIGS_DELETED" =>
        for {
          _id   <- (o \ "_id").validate[Long]
          ts    <- (o \ "timestamp").validate[LocalDateTime]
          count <- (o \ "payload").validate[Long]
        } yield ConfigsDeleted(count, _id, ts)
      //FEATURES
      case o: JsObject if (o \ "type").as[String] == "FEATURE_CREATED" =>
        for {
          _id     <- (o \ "_id").validate[Long]
          ts      <- (o \ "timestamp").validate[LocalDateTime]
          payload <- (o \ "payload").validate[Feature]
          key     <- (o \ "key").validate[Key]
        } yield FeatureCreated(key, payload, _id, ts)
      case o: JsObject if (o \ "type").as[String] == "FEATURE_UPDATED" =>
        for {
          _id      <- (o \ "_id").validate[Long]
          ts       <- (o \ "timestamp").validate[LocalDateTime]
          payload  <- (o \ "payload").validate[Feature]
          key      <- (o \ "key").validate[Key]
          oldValue <- (o \ "oldValue").validate[Feature]
        } yield FeatureUpdated(key, oldValue, payload, _id, ts)
      case o: JsObject if (o \ "type").as[String] == "FEATURE_DELETED" =>
        for {
          _id     <- (o \ "_id").validate[Long]
          ts      <- (o \ "timestamp").validate[LocalDateTime]
          payload <- (o \ "payload").validate[Feature]
          key     <- (o \ "key").validate[Key]
        } yield FeatureDeleted(key, payload, _id, ts)
      case o: JsObject if (o \ "type").as[String] == "FEATURES_DELETED" =>
        for {
          _id   <- (o \ "_id").validate[Long]
          ts    <- (o \ "timestamp").validate[LocalDateTime]
          count <- (o \ "payload").validate[Long]
        } yield FeaturesDeleted(count, _id, ts)
      //SCRIPTS
      case o: JsObject if (o \ "type").as[String] == "GLOBALSCRIPT_CREATED" =>
        for {
          _id     <- (o \ "_id").validate[Long]
          ts      <- (o \ "timestamp").validate[LocalDateTime]
          payload <- (o \ "payload").validate[GlobalScript]
          key     <- (o \ "key").validate[Key]
        } yield GlobalScriptCreated(key, payload, _id, ts)
      case o: JsObject if (o \ "type").as[String] == "GLOBALSCRIPT_UPDATED" =>
        for {
          _id      <- (o \ "_id").validate[Long]
          ts       <- (o \ "timestamp").validate[LocalDateTime]
          payload  <- (o \ "payload").validate[GlobalScript]
          key      <- (o \ "key").validate[Key]
          oldValue <- (o \ "oldValue").validate[GlobalScript]
        } yield GlobalScriptUpdated(key, oldValue, payload, _id, ts)
      case o: JsObject if (o \ "type").as[String] == "GLOBALSCRIPT_DELETED" =>
        for {
          _id     <- (o \ "_id").validate[Long]
          ts      <- (o \ "timestamp").validate[LocalDateTime]
          payload <- (o \ "payload").validate[GlobalScript]
          key     <- (o \ "key").validate[Key]
        } yield GlobalScriptDeleted(key, payload, _id, ts)
      case o: JsObject if (o \ "type").as[String] == "GLOBALSCRIPTS_DELETED" =>
        for {
          _id   <- (o \ "_id").validate[Long]
          ts    <- (o \ "timestamp").validate[LocalDateTime]
          count <- (o \ "payload").validate[Long]
        } yield GlobalScriptsDeleted(count, _id, ts)
      //USER
      case o: JsObject if (o \ "type").as[String] == "USER_CREATED" =>
        for {
          _id     <- (o \ "_id").validate[Long]
          ts      <- (o \ "timestamp").validate[LocalDateTime]
          payload <- (o \ "payload").validate[User]
          key     <- (o \ "key").validate[Key]
        } yield UserCreated(key, payload, _id, ts)
      case o: JsObject if (o \ "type").as[String] == "USER_UPDATED" =>
        for {
          _id      <- (o \ "_id").validate[Long]
          ts       <- (o \ "timestamp").validate[LocalDateTime]
          payload  <- (o \ "payload").validate[User]
          key      <- (o \ "key").validate[Key]
          oldValue <- (o \ "oldValue").validate[User]
        } yield UserUpdated(key, oldValue, payload, _id, ts)
      case o: JsObject if (o \ "type").as[String] == "USER_DELETED" =>
        for {
          _id     <- (o \ "_id").validate[Long]
          ts      <- (o \ "timestamp").validate[LocalDateTime]
          payload <- (o \ "payload").validate[User]
          key     <- (o \ "key").validate[Key]
        } yield UserDeleted(key, payload, _id, ts)
      case o: JsObject if (o \ "type").as[String] == "USERS_DELETED" =>
        for {
          _id   <- (o \ "_id").validate[Long]
          ts    <- (o \ "timestamp").validate[LocalDateTime]
          count <- (o \ "payload").validate[Long]
        } yield UsersDeleted(count)
      //WEBHOOK
      case o: JsObject if (o \ "type").as[String] == "WEBHOOK_CREATED" =>
        for {
          _id     <- (o \ "_id").validate[Long]
          ts      <- (o \ "timestamp").validate[LocalDateTime]
          payload <- (o \ "payload").validate[Webhook]
          key     <- (o \ "key").validate[Key]
        } yield WebhookCreated(key, payload, _id, ts)
      case o: JsObject if (o \ "type").as[String] == "WEBHOOK_UPDATED" =>
        for {
          _id      <- (o \ "_id").validate[Long]
          ts       <- (o \ "timestamp").validate[LocalDateTime]
          payload  <- (o \ "payload").validate[Webhook]
          key      <- (o \ "key").validate[Key]
          oldValue <- (o \ "oldValue").validate[Webhook]
        } yield WebhookUpdated(key, oldValue, payload, _id, ts)
      case o: JsObject if (o \ "type").as[String] == "WEBHOOK_DELETED" =>
        for {
          _id     <- (o \ "_id").validate[Long]
          ts      <- (o \ "timestamp").validate[LocalDateTime]
          payload <- (o \ "payload").validate[Webhook]
          key     <- (o \ "key").validate[Key]
        } yield WebhookDeleted(key, payload, _id, ts)
      case o: JsObject if (o \ "type").as[String] == "WEBHOOKS_DELETED" =>
        for {
          _id   <- (o \ "_id").validate[Long]
          ts    <- (o \ "timestamp").validate[LocalDateTime]
          count <- (o \ "payload").validate[Long]
        } yield WebhooksDeleted(count, _id, ts)
      //APIKEY
      case o: JsObject if (o \ "type").as[String] == "APIKEY_CREATED" =>
        for {
          _id     <- (o \ "_id").validate[Long]
          ts      <- (o \ "timestamp").validate[LocalDateTime]
          payload <- (o \ "payload").validate[Apikey]
          key     <- (o \ "key").validate[Key]
        } yield ApikeyCreated(key, payload, _id, ts)
      case o: JsObject if (o \ "type").as[String] == "APIKEY_UPDATED" =>
        for {
          _id      <- (o \ "_id").validate[Long]
          ts       <- (o \ "timestamp").validate[LocalDateTime]
          payload  <- (o \ "payload").validate[Apikey]
          key      <- (o \ "key").validate[Key]
          oldValue <- (o \ "oldValue").validate[Apikey]
        } yield ApikeyUpdated(key, oldValue, payload, _id, ts)
      case o: JsObject if (o \ "type").as[String] == "APIKEY_DELETED" =>
        for {
          _id     <- (o \ "_id").validate[Long]
          ts      <- (o \ "timestamp").validate[LocalDateTime]
          payload <- (o \ "payload").validate[Apikey]
          key     <- (o \ "key").validate[Key]
        } yield ApikeyDeleted(key, payload, _id, ts)
      case o: JsObject if (o \ "type").as[String] == "APIKEYS_DELETED" =>
        for {
          _id   <- (o \ "_id").validate[Long]
          ts    <- (o \ "timestamp").validate[LocalDateTime]
          count <- (o \ "payload").validate[Long]
        } yield ApikeysDeleted(count, _id, ts)
      //EXPERIMENT
      case o: JsObject if (o \ "type").as[String] == "EXPERIMENT_CREATED" =>
        for {
          _id     <- (o \ "_id").validate[Long]
          ts      <- (o \ "timestamp").validate[LocalDateTime]
          payload <- (o \ "payload").validate[Experiment]
          key     <- (o \ "key").validate[Key]
        } yield ExperimentCreated(key, payload, _id, ts)
      case o: JsObject if (o \ "type").as[String] == "EXPERIMENT_UPDATED" =>
        for {
          _id      <- (o \ "_id").validate[Long]
          ts       <- (o \ "timestamp").validate[LocalDateTime]
          payload  <- (o \ "payload").validate[Experiment]
          key      <- (o \ "key").validate[Key]
          oldValue <- (o \ "oldValue").validate[Experiment]
        } yield ExperimentUpdated(key, oldValue, payload, _id, ts)
      case o: JsObject if (o \ "type").as[String] == "EXPERIMENT_DELETED" =>
        for {
          _id     <- (o \ "_id").validate[Long]
          ts      <- (o \ "timestamp").validate[LocalDateTime]
          payload <- (o \ "payload").validate[Experiment]
          key     <- (o \ "key").validate[Key]
        } yield ExperimentDeleted(key, payload, _id, ts)
      case o: JsObject if (o \ "type").as[String] == "EXPERIMENTS_DELETED" =>
        for {
          _id   <- (o \ "_id").validate[Long]
          ts    <- (o \ "timestamp").validate[LocalDateTime]
          count <- (o \ "payload").validate[Long]
        } yield ExperimentsDeleted(count, _id, ts)
      //VARIANT BINDING
      case o: JsObject if (o \ "type").as[String] == "VARIANT_BINDING_CREATED" =>
        for {
          _id     <- (o \ "_id").validate[Long]
          ts      <- (o \ "timestamp").validate[LocalDateTime]
          payload <- (o \ "payload").validate[VariantBinding]
          key     <- (o \ "key").validate[VariantBindingKey]
        } yield VariantBindingCreated(key, payload, _id, ts)
      //VARIANT BINDING EVENT
      case o: JsObject if (o \ "type").as[String] == "EXPERIMENT_VARIANT_EVENT_CREATED" =>
        for {
          _id     <- (o \ "_id").validate[Long]
          ts      <- (o \ "timestamp").validate[LocalDateTime]
          payload <- (o \ "payload").validate[ExperimentVariantEvent]
          key     <- (o \ "key").validate[ExperimentVariantEventKey]
        } yield ExperimentVariantEventCreated(key, payload, _id, ts)
      case o: JsObject if (o \ "type").as[String] == "EXPERIMENT_VARIANT_EVENT_DELETED" =>
        for {
          _id     <- (o \ "_id").validate[Long]
          ts      <- (o \ "timestamp").validate[LocalDateTime]
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
    val `type`: String   = "CONFIG_CREATED"
    val payload: JsValue = Json.toJson(config)
  }
  case class ConfigUpdated(key: ConfigKey,
                           oldValue: Config,
                           config: Config,
                           _id: Long = gen.nextId(),
                           timestamp: LocalDateTime = LocalDateTime.now())
      extends ConfigEvent {
    val `type`: String   = "CONFIG_UPDATED"
    val payload: JsValue = Json.toJson(config)
    override def toJson: JsValue =
      super.toJson.as[JsObject] ++ Json.obj("oldValue" -> Json.toJson(oldValue))
  }
  case class ConfigDeleted(key: ConfigKey,
                           config: Config,
                           _id: Long = gen.nextId(),
                           timestamp: LocalDateTime = LocalDateTime.now())
      extends ConfigEvent {
    val `type`: String   = "CONFIG_DELETED"
    val payload: JsValue = Json.toJson(config)
  }
  case class ConfigsDeleted(_id: Long = gen.nextId(), count: Long, timestamp: LocalDateTime = LocalDateTime.now())
      extends ConfigEvent {
    val `type`: String   = "CONFIGS_DELETED"
    val key: ConfigKey   = Key.Empty
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
    val `type`: String   = "FEATURE_CREATED"
    val payload: JsValue = Json.toJson(feature)
  }
  case class FeatureUpdated(key: FeatureKey,
                            oldValue: Feature,
                            feature: Feature,
                            _id: Long = gen.nextId(),
                            timestamp: LocalDateTime = LocalDateTime.now())
      extends FeatureEvent {
    val `type`: String   = "FEATURE_UPDATED"
    val payload: JsValue = Json.toJson(feature)

    override def toJson: JsValue =
      super.toJson.as[JsObject] ++ Json.obj("oldValue" -> Json.toJson(oldValue))
  }
  case class FeatureDeleted(key: FeatureKey,
                            feature: Feature,
                            _id: Long = gen.nextId(),
                            timestamp: LocalDateTime = LocalDateTime.now())
      extends FeatureEvent {
    val `type`: String   = "FEATURE_DELETED"
    val payload: JsValue = Json.toJson(feature)
  }
  case class FeaturesDeleted(count: Long, _id: Long = gen.nextId(), timestamp: LocalDateTime = LocalDateTime.now())
      extends FeatureEvent {
    val key: FeatureKey  = Key.Empty
    val `type`: String   = "FEATURES_DELETED"
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
    val `type`: String   = "GLOBALSCRIPT_CREATED"
    val payload: JsValue = Json.toJson(globalScript)
  }
  case class GlobalScriptUpdated(key: GlobalScriptKey,
                                 oldValue: GlobalScript,
                                 globalScript: GlobalScript,
                                 _id: Long = gen.nextId(),
                                 timestamp: LocalDateTime = LocalDateTime.now())
      extends GlobalScriptEvent {
    val `type`: String   = "GLOBALSCRIPT_UPDATED"
    val payload: JsValue = Json.toJson(globalScript)
    override def toJson: JsValue =
      super.toJson.as[JsObject] ++ Json.obj("oldValue" -> Json.toJson(oldValue))
  }
  case class GlobalScriptDeleted(key: GlobalScriptKey,
                                 globalScript: GlobalScript,
                                 _id: Long = gen.nextId(),
                                 timestamp: LocalDateTime = LocalDateTime.now())
      extends GlobalScriptEvent {
    val `type`: String   = "GLOBALSCRIPT_DELETED"
    val payload: JsValue = Json.toJson(globalScript)
  }
  case class GlobalScriptsDeleted(count: Long, _id: Long = gen.nextId(), timestamp: LocalDateTime = LocalDateTime.now())
      extends GlobalScriptEvent {
    val key              = Key.Empty
    val `type`: String   = "GLOBALSCRIPTS_DELETED"
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
    val `type`: String   = "USER_CREATED"
    val payload: JsValue = Json.toJson(user)
  }

  case class UserUpdated(key: UserKey,
                         oldValue: User,
                         user: User,
                         _id: Long = gen.nextId(),
                         timestamp: LocalDateTime = LocalDateTime.now())
      extends UserEvent {
    val `type`: String   = "USER_UPDATED"
    val payload: JsValue = Json.toJson(user)
    override def toJson: JsValue =
      super.toJson.as[JsObject] ++ Json.obj("oldValue" -> Json.toJson(oldValue))
  }

  case class UserDeleted(key: UserKey,
                         user: User,
                         _id: Long = gen.nextId(),
                         timestamp: LocalDateTime = LocalDateTime.now())
      extends UserEvent {
    val `type`: String   = "USER_DELETED"
    val payload: JsValue = Json.toJson(user)
  }

  case class UsersDeleted(count: Long, _id: Long = gen.nextId(), timestamp: LocalDateTime = LocalDateTime.now())
      extends UserEvent {
    val `type`: String   = "USERS_DELETED"
    val key: UserKey     = Key.Empty
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
    val `type`: String   = "WEBHOOK_CREATED"
    val payload: JsValue = Json.toJson(webhook)
  }
  case class WebhookUpdated(key: WebhookKey,
                            oldValue: Webhook,
                            webhook: Webhook,
                            _id: Long = gen.nextId(),
                            timestamp: LocalDateTime = LocalDateTime.now())
      extends WebhookEvent {
    val `type`: String   = "WEBHOOK_UPDATED"
    val payload: JsValue = Json.toJson(webhook)
    override def toJson: JsValue =
      super.toJson.as[JsObject] ++ Json.obj("oldValue" -> Json.toJson(oldValue))
  }
  case class WebhookDeleted(key: WebhookKey,
                            webhook: Webhook,
                            _id: Long = gen.nextId(),
                            timestamp: LocalDateTime = LocalDateTime.now())
      extends WebhookEvent {
    val `type`: String   = "WEBHOOK_DELETED"
    val payload: JsValue = Json.toJson(webhook)
  }
  case class WebhooksDeleted(count: Long, _id: Long = gen.nextId(), timestamp: LocalDateTime = LocalDateTime.now())
      extends WebhookEvent {
    val key              = Key.Empty
    val `type`: String   = "WEBHOOKS_DELETED"
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
    val `type`: String   = "APIKEY_CREATED"
    val payload: JsValue = Json.toJson(apikey)
  }

  case class ApikeyUpdated(key: ApikeyKey,
                           oldValue: Apikey,
                           apikey: Apikey,
                           _id: Long = gen.nextId(),
                           timestamp: LocalDateTime = LocalDateTime.now())
      extends ApikeyEvent {
    val `type`: String   = "APIKEY_UPDATED"
    val payload: JsValue = Json.toJson(apikey)
    override def toJson: JsValue =
      super.toJson.as[JsObject] ++ Json.obj("oldValue" -> Json.toJson(oldValue))
  }

  case class ApikeyDeleted(key: ApikeyKey,
                           apikey: Apikey,
                           _id: Long = gen.nextId(),
                           timestamp: LocalDateTime = LocalDateTime.now())
      extends ApikeyEvent {
    val `type`: String   = "APIKEY_DELETED"
    val payload: JsValue = Json.toJson(apikey)
  }

  case class ApikeysDeleted(count: Long, _id: Long = gen.nextId(), timestamp: LocalDateTime = LocalDateTime.now())
      extends ApikeyEvent {
    val `type`: String   = "APIKEYS_DELETED"
    val key: ApikeyKey   = Key.Empty
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
    val `type`: String   = "EXPERIMENT_CREATED"
    val payload: JsValue = Json.toJson(experiment)
  }

  case class ExperimentUpdated(key: ExperimentKey,
                               oldValue: Experiment,
                               experiment: Experiment,
                               _id: Long = gen.nextId(),
                               timestamp: LocalDateTime = LocalDateTime.now())
      extends ExperimentEvent {
    val `type`: String   = "EXPERIMENT_UPDATED"
    val payload: JsValue = Json.toJson(experiment)
    override def toJson: JsValue =
      super.toJson.as[JsObject] ++ Json.obj("oldValue" -> Json.toJson(oldValue))
  }

  case class ExperimentDeleted(key: ExperimentKey,
                               experiment: Experiment,
                               _id: Long = gen.nextId(),
                               timestamp: LocalDateTime = LocalDateTime.now())
      extends ExperimentEvent {
    val `type`: String   = "EXPERIMENT_DELETED"
    val payload: JsValue = Json.toJson(experiment)
  }

  case class ExperimentsDeleted(count: Long, _id: Long = gen.nextId(), timestamp: LocalDateTime = LocalDateTime.now())
      extends ExperimentEvent {
    val `type`: String     = "EXPERIMENTS_DELETED"
    val payload: JsValue   = JsNumber(count)
    val key: ExperimentKey = Key.Empty
  }

  sealed trait VariantBindingEvent extends ExperimentEvent

  case class VariantBindingCreated(variantBindingKey: VariantBindingKey,
                                   variantBinding: VariantBinding,
                                   _id: Long = gen.nextId(),
                                   timestamp: LocalDateTime = LocalDateTime.now())
      extends VariantBindingEvent {
    val `type`: String     = "VARIANT_BINDING_CREATED"
    val key: ExperimentKey = variantBindingKey.key
    val payload: JsValue   = Json.toJson(variantBinding)
  }

  sealed trait ExperimentVariantEventEvent extends ExperimentEvent

  case class ExperimentVariantEventCreated(id: ExperimentVariantEventKey,
                                           data: ExperimentVariantEvent,
                                           _id: Long = gen.nextId(),
                                           timestamp: LocalDateTime = LocalDateTime.now())
      extends ExperimentVariantEventEvent {
    override def `type`: String     = "EXPERIMENT_VARIANT_EVENT_CREATED"
    override def key: ExperimentKey = id.key
    override def payload: JsValue   = Json.toJson(data)
  }

  case class ExperimentVariantEventsDeleted(experiment: Experiment,
                                            _id: Long = gen.nextId(),
                                            timestamp: LocalDateTime = LocalDateTime.now())
      extends ExperimentVariantEventEvent {
    override def `type`: String     = "EXPERIMENT_VARIANT_EVENT_DELETED"
    override def key: ExperimentKey = experiment.id
    override def payload: JsValue =
      Json.toJson("experimentId" -> experiment.id.key)
  }

}

object EventLogger {
  val logger = Logger("events")
}

trait EventStore extends Closeable {

  private[events] def eventMatch(patterns: Seq[String], domains: Seq[Domain])(e: IzanamiEvent): Boolean =
    (domains.isEmpty || domains.contains(e.domain)) && (patterns.isEmpty || e.key
      .matchPatterns(
        patterns: _*
      ))

  def dropUntilLastId(lastId: Option[Long]): Flow[IzanamiEvent, IzanamiEvent, NotUsed] =
    lastId.map { id =>
      Flow[IzanamiEvent].dropWhile(_._id <= id)
    } getOrElse {
      Flow[IzanamiEvent]
    }

  def publish(event: IzanamiEvent): Future[Done]

  def events(domains: Seq[Domain] = Seq.empty[Domain],
             patterns: Seq[String] = Seq.empty[String],
             lastEventId: Option[Long] = None): Source[IzanamiEvent, NotUsed]

}
