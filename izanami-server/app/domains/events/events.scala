package domains.events

import java.io.Closeable
import java.time.LocalDateTime

import akka.stream.scaladsl.{Flow, Source}
import akka.{Done, NotUsed}
import domains.Domain.Domain
import domains.abtesting.Experiment.ExperimentKey
import domains.abtesting._
import domains.apikey.{Apikey, ApikeyInstances}
import domains.apikey.Apikey.ApikeyKey
import domains.config.{Config, ConfigInstances}
import domains.config.Config.ConfigKey
import domains.events.Events.IzanamiEvent
import domains.feature.{Feature, FeatureInstances}
import domains.feature.Feature.FeatureKey
import domains.script.{GlobalScript, GlobalScriptInstances}
import domains.script.GlobalScript.GlobalScriptKey
import domains.user.{User, UserInstances}
import domains.user.User.UserKey
import domains.webhook.{Webhook, WebhookInstances}
import domains.webhook.Webhook.WebhookKey
import domains.{Domain, Key}
import libs.IdGenerator
import play.api.libs.json._
import play.api.{Environment, Logger}

object Events {

  import domains.apikey.ApikeyInstances._

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
          payload <- (o \ "payload").validate[Config](ConfigInstances.format)
          key     <- (o \ "key").validate[Key]
        } yield ConfigCreated(key, payload, _id, ts)
      case o: JsObject if (o \ "type").as[String] == "CONFIG_UPDATED" =>
        for {
          _id      <- (o \ "_id").validate[Long]
          ts       <- (o \ "timestamp").validate[LocalDateTime]
          payload  <- (o \ "payload").validate[Config](ConfigInstances.format)
          key      <- (o \ "key").validate[Key]
          oldValue <- (o \ "oldValue").validate[Config](ConfigInstances.format)
        } yield ConfigUpdated(key, oldValue, payload, _id, ts)
      case o: JsObject if (o \ "type").as[String] == "CONFIG_DELETED" =>
        for {
          _id     <- (o \ "_id").validate[Long]
          ts      <- (o \ "timestamp").validate[LocalDateTime]
          payload <- (o \ "payload").validate[Config](ConfigInstances.format)
          key     <- (o \ "key").validate[Key]
        } yield ConfigDeleted(key, payload, _id, ts)
      case o: JsObject if (o \ "type").as[String] == "CONFIGS_DELETED" =>
        for {
          _id      <- (o \ "_id").validate[Long]
          ts       <- (o \ "timestamp").validate[LocalDateTime]
          count    <- (o \ "payload" \ "count").validate[Long]
          patterns <- (o \ "payload" \ "patterns").validate[Seq[String]]
        } yield ConfigsDeleted(count, patterns, _id, ts)
      //FEATURES
      case o: JsObject if (o \ "type").as[String] == "FEATURE_CREATED" =>
        for {
          _id     <- (o \ "_id").validate[Long]
          ts      <- (o \ "timestamp").validate[LocalDateTime]
          payload <- (o \ "payload").validate[Feature](FeatureInstances.format)
          key     <- (o \ "key").validate[Key]
        } yield FeatureCreated(key, payload, _id, ts)
      case o: JsObject if (o \ "type").as[String] == "FEATURE_UPDATED" =>
        for {
          _id      <- (o \ "_id").validate[Long]
          ts       <- (o \ "timestamp").validate[LocalDateTime]
          payload  <- (o \ "payload").validate[Feature](FeatureInstances.format)
          key      <- (o \ "key").validate[Key]
          oldValue <- (o \ "oldValue").validate[Feature](FeatureInstances.format)
        } yield FeatureUpdated(key, oldValue, payload, _id, ts)
      case o: JsObject if (o \ "type").as[String] == "FEATURE_DELETED" =>
        for {
          _id     <- (o \ "_id").validate[Long]
          ts      <- (o \ "timestamp").validate[LocalDateTime]
          payload <- (o \ "payload").validate[Feature](FeatureInstances.format)
          key     <- (o \ "key").validate[Key]
        } yield FeatureDeleted(key, payload, _id, ts)
      case o: JsObject if (o \ "type").as[String] == "FEATURES_DELETED" =>
        for {
          _id      <- (o \ "_id").validate[Long]
          ts       <- (o \ "timestamp").validate[LocalDateTime]
          count    <- (o \ "payload" \ "count").validate[Long]
          patterns <- (o \ "payload" \ "patterns").validate[Seq[String]]
        } yield FeaturesDeleted(count, patterns, _id, ts)
      //SCRIPTS
      case o: JsObject if (o \ "type").as[String] == "GLOBALSCRIPT_CREATED" =>
        for {
          _id     <- (o \ "_id").validate[Long]
          ts      <- (o \ "timestamp").validate[LocalDateTime]
          payload <- (o \ "payload").validate[GlobalScript](GlobalScriptInstances.format)
          key     <- (o \ "key").validate[Key]
        } yield GlobalScriptCreated(key, payload, _id, ts)
      case o: JsObject if (o \ "type").as[String] == "GLOBALSCRIPT_UPDATED" =>
        for {
          _id      <- (o \ "_id").validate[Long]
          ts       <- (o \ "timestamp").validate[LocalDateTime]
          payload  <- (o \ "payload").validate[GlobalScript](GlobalScriptInstances.format)
          key      <- (o \ "key").validate[Key]
          oldValue <- (o \ "oldValue").validate[GlobalScript](GlobalScriptInstances.format)
        } yield GlobalScriptUpdated(key, oldValue, payload, _id, ts)
      case o: JsObject if (o \ "type").as[String] == "GLOBALSCRIPT_DELETED" =>
        for {
          _id     <- (o \ "_id").validate[Long]
          ts      <- (o \ "timestamp").validate[LocalDateTime]
          payload <- (o \ "payload").validate[GlobalScript](GlobalScriptInstances.format)
          key     <- (o \ "key").validate[Key]
        } yield GlobalScriptDeleted(key, payload, _id, ts)
      case o: JsObject if (o \ "type").as[String] == "GLOBALSCRIPTS_DELETED" =>
        for {
          _id      <- (o \ "_id").validate[Long]
          ts       <- (o \ "timestamp").validate[LocalDateTime]
          count    <- (o \ "payload" \ "count").validate[Long]
          patterns <- (o \ "payload" \ "patterns").validate[Seq[String]]
        } yield GlobalScriptsDeleted(count, patterns, _id, ts)
      //USER
      case o: JsObject if (o \ "type").as[String] == "USER_CREATED" =>
        for {
          _id     <- (o \ "_id").validate[Long]
          ts      <- (o \ "timestamp").validate[LocalDateTime]
          payload <- (o \ "payload").validate[User](UserInstances.format)
          key     <- (o \ "key").validate[Key]
        } yield UserCreated(key, payload, _id, ts)
      case o: JsObject if (o \ "type").as[String] == "USER_UPDATED" =>
        for {
          _id      <- (o \ "_id").validate[Long]
          ts       <- (o \ "timestamp").validate[LocalDateTime]
          payload  <- (o \ "payload").validate[User](UserInstances.format)
          key      <- (o \ "key").validate[Key]
          oldValue <- (o \ "oldValue").validate[User](UserInstances.format)
        } yield UserUpdated(key, oldValue, payload, _id, ts)
      case o: JsObject if (o \ "type").as[String] == "USER_DELETED" =>
        for {
          _id     <- (o \ "_id").validate[Long]
          ts      <- (o \ "timestamp").validate[LocalDateTime]
          payload <- (o \ "payload").validate[User](UserInstances.format)
          key     <- (o \ "key").validate[Key]
        } yield UserDeleted(key, payload, _id, ts)
      case o: JsObject if (o \ "type").as[String] == "USERS_DELETED" =>
        for {
          _id      <- (o \ "_id").validate[Long]
          ts       <- (o \ "timestamp").validate[LocalDateTime]
          count    <- (o \ "payload" \ "count").validate[Long]
          patterns <- (o \ "payload" \ "patterns").validate[Seq[String]]
        } yield UsersDeleted(count, patterns)
      //WEBHOOK
      case o: JsObject if (o \ "type").as[String] == "WEBHOOK_CREATED" =>
        for {
          _id     <- (o \ "_id").validate[Long]
          ts      <- (o \ "timestamp").validate[LocalDateTime]
          payload <- (o \ "payload").validate[Webhook](WebhookInstances.format)
          key     <- (o \ "key").validate[Key]
        } yield WebhookCreated(key, payload, _id, ts)
      case o: JsObject if (o \ "type").as[String] == "WEBHOOK_UPDATED" =>
        for {
          _id      <- (o \ "_id").validate[Long]
          ts       <- (o \ "timestamp").validate[LocalDateTime]
          payload  <- (o \ "payload").validate[Webhook](WebhookInstances.format)
          key      <- (o \ "key").validate[Key]
          oldValue <- (o \ "oldValue").validate[Webhook](WebhookInstances.format)
        } yield WebhookUpdated(key, oldValue, payload, _id, ts)
      case o: JsObject if (o \ "type").as[String] == "WEBHOOK_DELETED" =>
        for {
          _id     <- (o \ "_id").validate[Long]
          ts      <- (o \ "timestamp").validate[LocalDateTime]
          payload <- (o \ "payload").validate[Webhook](WebhookInstances.format)
          key     <- (o \ "key").validate[Key]
        } yield WebhookDeleted(key, payload, _id, ts)
      case o: JsObject if (o \ "type").as[String] == "WEBHOOKS_DELETED" =>
        for {
          _id      <- (o \ "_id").validate[Long]
          ts       <- (o \ "timestamp").validate[LocalDateTime]
          count    <- (o \ "payload" \ "count").validate[Long]
          patterns <- (o \ "payload" \ "patterns").validate[Seq[String]]
        } yield WebhooksDeleted(count, patterns, _id, ts)
      //APIKEY
      case o: JsObject if (o \ "type").as[String] == "APIKEY_CREATED" =>
        for {
          _id     <- (o \ "_id").validate[Long]
          ts      <- (o \ "timestamp").validate[LocalDateTime]
          payload <- (o \ "payload").validate[Apikey](ApikeyInstances.format)
          key     <- (o \ "key").validate[Key]
        } yield ApikeyCreated(key, payload, _id, ts)
      case o: JsObject if (o \ "type").as[String] == "APIKEY_UPDATED" =>
        for {
          _id      <- (o \ "_id").validate[Long]
          ts       <- (o \ "timestamp").validate[LocalDateTime]
          payload  <- (o \ "payload").validate[Apikey](ApikeyInstances.format)
          key      <- (o \ "key").validate[Key]
          oldValue <- (o \ "oldValue").validate[Apikey](ApikeyInstances.format)
        } yield ApikeyUpdated(key, oldValue, payload, _id, ts)
      case o: JsObject if (o \ "type").as[String] == "APIKEY_DELETED" =>
        for {
          _id     <- (o \ "_id").validate[Long]
          ts      <- (o \ "timestamp").validate[LocalDateTime]
          payload <- (o \ "payload").validate[Apikey](ApikeyInstances.format)
          key     <- (o \ "key").validate[Key]
        } yield ApikeyDeleted(key, payload, _id, ts)
      case o: JsObject if (o \ "type").as[String] == "APIKEYS_DELETED" =>
        for {
          _id      <- (o \ "_id").validate[Long]
          ts       <- (o \ "timestamp").validate[LocalDateTime]
          count    <- (o \ "payload" \ "count").validate[Long]
          patterns <- (o \ "payload" \ "patterns").validate[Seq[String]]
        } yield ApikeysDeleted(count, patterns, _id, ts)
      //EXPERIMENT
      case o: JsObject if (o \ "type").as[String] == "EXPERIMENT_CREATED" =>
        for {
          _id     <- (o \ "_id").validate[Long]
          ts      <- (o \ "timestamp").validate[LocalDateTime]
          payload <- (o \ "payload").validate[Experiment](ExperimentInstances.format)
          key     <- (o \ "key").validate[Key]
        } yield ExperimentCreated(key, payload, _id, ts)
      case o: JsObject if (o \ "type").as[String] == "EXPERIMENT_UPDATED" =>
        for {
          _id      <- (o \ "_id").validate[Long]
          ts       <- (o \ "timestamp").validate[LocalDateTime]
          payload  <- (o \ "payload").validate[Experiment](ExperimentInstances.format)
          key      <- (o \ "key").validate[Key]
          oldValue <- (o \ "oldValue").validate[Experiment](ExperimentInstances.format)
        } yield ExperimentUpdated(key, oldValue, payload, _id, ts)
      case o: JsObject if (o \ "type").as[String] == "EXPERIMENT_DELETED" =>
        for {
          _id     <- (o \ "_id").validate[Long]
          ts      <- (o \ "timestamp").validate[LocalDateTime]
          payload <- (o \ "payload").validate[Experiment](ExperimentInstances.format)
          key     <- (o \ "key").validate[Key]
        } yield ExperimentDeleted(key, payload, _id, ts)
      case o: JsObject if (o \ "type").as[String] == "EXPERIMENTS_DELETED" =>
        for {
          _id      <- (o \ "_id").validate[Long]
          ts       <- (o \ "timestamp").validate[LocalDateTime]
          count    <- (o \ "payload" \ "count").validate[Long]
          patterns <- (o \ "payload" \ "patterns").validate[Seq[String]]
        } yield ExperimentsDeleted(count, patterns, _id, ts)
      //VARIANT BINDING EVENT
      case o: JsObject if (o \ "type").as[String] == "EXPERIMENT_VARIANT_EVENT_CREATED" =>
        for {
          _id     <- (o \ "_id").validate[Long]
          ts      <- (o \ "timestamp").validate[LocalDateTime]
          payload <- (o \ "payload").validate[ExperimentVariantEvent](ExperimentVariantEventInstances.format)
          key     <- (o \ "key").validate[ExperimentVariantEventKey](ExperimentVariantEventKeyInstances.format)
        } yield ExperimentVariantEventCreated(key, payload, _id, ts)
      case o: JsObject if (o \ "type").as[String] == "EXPERIMENT_VARIANT_EVENT_DELETED" =>
        for {
          _id     <- (o \ "_id").validate[Long]
          ts      <- (o \ "timestamp").validate[LocalDateTime]
          payload <- (o \ "payload").validate[Experiment](ExperimentInstances.format)
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
    val payload: JsValue = ConfigInstances.format.writes(config)
  }
  case class ConfigUpdated(key: ConfigKey,
                           oldValue: Config,
                           config: Config,
                           _id: Long = gen.nextId(),
                           timestamp: LocalDateTime = LocalDateTime.now())
      extends ConfigEvent {
    val `type`: String   = "CONFIG_UPDATED"
    val payload: JsValue = ConfigInstances.format.writes(config)
    override def toJson: JsValue =
      super.toJson.as[JsObject] ++ Json.obj("oldValue" -> ConfigInstances.format.writes(oldValue))
  }
  case class ConfigDeleted(key: ConfigKey,
                           config: Config,
                           _id: Long = gen.nextId(),
                           timestamp: LocalDateTime = LocalDateTime.now())
      extends ConfigEvent {
    val `type`: String   = "CONFIG_DELETED"
    val payload: JsValue = ConfigInstances.format.writes(config)
  }
  case class ConfigsDeleted(_id: Long = gen.nextId(),
                            patterns: Seq[String],
                            count: Long,
                            timestamp: LocalDateTime = LocalDateTime.now())
      extends ConfigEvent {
    val `type`: String   = "CONFIGS_DELETED"
    val key: ConfigKey   = Key.Empty
    val payload: JsValue = Json.obj("count" -> count, "patterns" -> patterns)
  }

  object ConfigEvent {
    import ConfigInstances._
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
    val payload: JsValue = FeatureInstances.format.writes(feature)
  }
  case class FeatureUpdated(key: FeatureKey,
                            oldValue: Feature,
                            feature: Feature,
                            _id: Long = gen.nextId(),
                            timestamp: LocalDateTime = LocalDateTime.now())
      extends FeatureEvent {
    val `type`: String   = "FEATURE_UPDATED"
    val payload: JsValue = FeatureInstances.format.writes(feature)

    override def toJson: JsValue =
      super.toJson.as[JsObject] ++ Json.obj("oldValue" -> FeatureInstances.format.writes(oldValue))
  }
  case class FeatureDeleted(key: FeatureKey,
                            feature: Feature,
                            _id: Long = gen.nextId(),
                            timestamp: LocalDateTime = LocalDateTime.now())
      extends FeatureEvent {
    val `type`: String   = "FEATURE_DELETED"
    val payload: JsValue = FeatureInstances.format.writes(feature)
  }
  case class FeaturesDeleted(count: Long,
                             patterns: Seq[String],
                             _id: Long = gen.nextId(),
                             timestamp: LocalDateTime = LocalDateTime.now())
      extends FeatureEvent {
    val key: FeatureKey  = Key.Empty
    val `type`: String   = "FEATURES_DELETED"
    val payload: JsValue = Json.obj("count" -> count, "patterns" -> patterns)
  }

  object FeatureEvent {
    import FeatureInstances._
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
    val payload: JsValue = GlobalScriptInstances.format.writes(globalScript)
  }
  case class GlobalScriptUpdated(key: GlobalScriptKey,
                                 oldValue: GlobalScript,
                                 globalScript: GlobalScript,
                                 _id: Long = gen.nextId(),
                                 timestamp: LocalDateTime = LocalDateTime.now())
      extends GlobalScriptEvent {
    val `type`: String   = "GLOBALSCRIPT_UPDATED"
    val payload: JsValue = GlobalScriptInstances.format.writes(globalScript)
    override def toJson: JsValue =
      super.toJson.as[JsObject] ++ Json.obj("oldValue" -> GlobalScriptInstances.format.writes(oldValue))
  }
  case class GlobalScriptDeleted(key: GlobalScriptKey,
                                 globalScript: GlobalScript,
                                 _id: Long = gen.nextId(),
                                 timestamp: LocalDateTime = LocalDateTime.now())
      extends GlobalScriptEvent {
    val `type`: String   = "GLOBALSCRIPT_DELETED"
    val payload: JsValue = GlobalScriptInstances.format.writes(globalScript)
  }
  case class GlobalScriptsDeleted(count: Long,
                                  patterns: Seq[String],
                                  _id: Long = gen.nextId(),
                                  timestamp: LocalDateTime = LocalDateTime.now())
      extends GlobalScriptEvent {
    val key              = Key.Empty
    val `type`: String   = "GLOBALSCRIPTS_DELETED"
    val payload: JsValue = Json.obj("count" -> count, "patterns" -> patterns)
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
    val payload: JsValue = UserInstances.format.writes(user)
  }

  case class UserUpdated(key: UserKey,
                         oldValue: User,
                         user: User,
                         _id: Long = gen.nextId(),
                         timestamp: LocalDateTime = LocalDateTime.now())
      extends UserEvent {
    val `type`: String   = "USER_UPDATED"
    val payload: JsValue = UserInstances.format.writes(user)
    override def toJson: JsValue =
      super.toJson.as[JsObject] ++ Json.obj("oldValue" -> UserInstances.format.writes(oldValue))
  }

  case class UserDeleted(key: UserKey,
                         user: User,
                         _id: Long = gen.nextId(),
                         timestamp: LocalDateTime = LocalDateTime.now())
      extends UserEvent {
    val `type`: String   = "USER_DELETED"
    val payload: JsValue = UserInstances.format.writes(user)
  }

  case class UsersDeleted(count: Long,
                          patterns: Seq[String],
                          _id: Long = gen.nextId(),
                          timestamp: LocalDateTime = LocalDateTime.now())
      extends UserEvent {
    val `type`: String   = "USERS_DELETED"
    val key: UserKey     = Key.Empty
    val payload: JsValue = Json.obj("count" -> count, "patterns" -> patterns)
  }

  object UserEvent {
    import UserInstances._
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
    val payload: JsValue = WebhookInstances.format.writes(webhook)
  }
  case class WebhookUpdated(key: WebhookKey,
                            oldValue: Webhook,
                            webhook: Webhook,
                            _id: Long = gen.nextId(),
                            timestamp: LocalDateTime = LocalDateTime.now())
      extends WebhookEvent {
    val `type`: String   = "WEBHOOK_UPDATED"
    val payload: JsValue = WebhookInstances.format.writes(webhook)
    override def toJson: JsValue =
      super.toJson.as[JsObject] ++ Json.obj("oldValue" -> WebhookInstances.format.writes(oldValue))
  }
  case class WebhookDeleted(key: WebhookKey,
                            webhook: Webhook,
                            _id: Long = gen.nextId(),
                            timestamp: LocalDateTime = LocalDateTime.now())
      extends WebhookEvent {
    val `type`: String   = "WEBHOOK_DELETED"
    val payload: JsValue = WebhookInstances.format.writes(webhook)
  }
  case class WebhooksDeleted(count: Long,
                             patterns: Seq[String],
                             _id: Long = gen.nextId(),
                             timestamp: LocalDateTime = LocalDateTime.now())
      extends WebhookEvent {
    val key              = Key.Empty
    val `type`: String   = "WEBHOOKS_DELETED"
    val payload: JsValue = Json.obj("count" -> count, "patterns" -> patterns)
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

  case class ApikeysDeleted(count: Long,
                            patterns: Seq[String],
                            _id: Long = gen.nextId(),
                            timestamp: LocalDateTime = LocalDateTime.now())
      extends ApikeyEvent {
    val `type`: String   = "APIKEYS_DELETED"
    val key: ApikeyKey   = Key.Empty
    val payload: JsValue = Json.obj("count" -> count, "patterns" -> patterns)
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
    val payload: JsValue = ExperimentInstances.format.writes(experiment)
  }

  case class ExperimentUpdated(key: ExperimentKey,
                               oldValue: Experiment,
                               experiment: Experiment,
                               _id: Long = gen.nextId(),
                               timestamp: LocalDateTime = LocalDateTime.now())
      extends ExperimentEvent {
    val `type`: String   = "EXPERIMENT_UPDATED"
    val payload: JsValue = ExperimentInstances.format.writes(experiment)
    override def toJson: JsValue =
      super.toJson.as[JsObject] ++ Json.obj("oldValue" -> ExperimentInstances.format.writes(oldValue))
  }

  case class ExperimentDeleted(key: ExperimentKey,
                               experiment: Experiment,
                               _id: Long = gen.nextId(),
                               timestamp: LocalDateTime = LocalDateTime.now())
      extends ExperimentEvent {
    val `type`: String   = "EXPERIMENT_DELETED"
    val payload: JsValue = ExperimentInstances.format.writes(experiment)
  }

  case class ExperimentsDeleted(count: Long,
                                patterns: Seq[String],
                                _id: Long = gen.nextId(),
                                timestamp: LocalDateTime = LocalDateTime.now())
      extends ExperimentEvent {
    val `type`: String     = "EXPERIMENTS_DELETED"
    val payload: JsValue   = Json.obj("count" -> count, "patterns" -> patterns)
    val key: ExperimentKey = Key.Empty
  }

  sealed trait VariantBindingEvent extends ExperimentEvent

  sealed trait ExperimentVariantEventEvent extends ExperimentEvent

  case class ExperimentVariantEventCreated(id: ExperimentVariantEventKey,
                                           data: ExperimentVariantEvent,
                                           _id: Long = gen.nextId(),
                                           timestamp: LocalDateTime = LocalDateTime.now())
      extends ExperimentVariantEventEvent {
    override def `type`: String     = "EXPERIMENT_VARIANT_EVENT_CREATED"
    override def key: ExperimentKey = id.key
    override def payload: JsValue   = ExperimentVariantEventInstances.format.writes(data)
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

trait EventStore[F[_]] extends Closeable {

  private[events] def eventMatch(patterns: Seq[String], domains: Seq[Domain])(e: IzanamiEvent): Boolean =
    (domains.isEmpty || domains.contains(e.domain)) && (patterns.isEmpty || e.key
      .matchPatterns(
        patterns: _*
      ))

  def dropUntilLastId(lastId: Option[Long]): Flow[IzanamiEvent, IzanamiEvent, NotUsed] =
    lastId.map { id =>
      Flow[IzanamiEvent].filter(_._id > id)
    } getOrElse {
      Flow[IzanamiEvent]
    }

  def publish(event: IzanamiEvent): F[Done]

  def events(domains: Seq[Domain] = Seq.empty[Domain],
             patterns: Seq[String] = Seq.empty[String],
             lastEventId: Option[Long] = None): Source[IzanamiEvent, NotUsed]

  def check(): F[Unit]

}
