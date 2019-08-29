package izanami.scaladsl

import akka.{Done, NotUsed}
import akka.actor.ActorSystem
import akka.event.Logging
import akka.http.scaladsl.util.FastFuture
import akka.stream.scaladsl.{BroadcastHub, Keep, Sink, Source}
import akka.stream._
import izanami.FeatureEvent.{FeatureCreated, FeatureUpdated}
import izanami.IzanamiBackend.SseBackend
import izanami.Strategy.DevStrategy
import izanami._
import izanami.commons.{HttpClient, IzanamiException, PatternsUtil}
import izanami.configs._
import izanami.experiments.{FallbackExperimentStrategy, FetchExperimentsStrategy}
import izanami.features._
import izanami.scaladsl.ConfigEvent.{ConfigCreated, ConfigUpdated}
import org.reactivestreams.Publisher
import play.api.libs.json._

import scala.concurrent.Future
import scala.util.Try

object IzanamiClient {

  def apply(config: ClientConfig)(implicit actorSystem: ActorSystem): IzanamiClient =
    new IzanamiClient(config)

}

class IzanamiClient(val config: ClientConfig)(implicit val actorSystem: ActorSystem) {

  private val client: HttpClient = HttpClient(actorSystem, config)

  private implicit val materializer: Materializer =
    ActorMaterializer(ActorMaterializerSettings(actorSystem).withDispatcher(config.dispatcher))(actorSystem)

  implicit val izanamiDispatcher =
    IzanamiDispatcher(config.dispatcher, actorSystem)

  private implicit val cudFeatureClient: CUDFeatureClient = CUDFeatureClient(client)

  private val eventSource: Source[IzanamiEvent, NotUsed] = Source
    .lazily(
      () =>
        client
          .eventStream()
          .runWith(BroadcastHub.sink(1024))
    )
    .mapMaterializedValue(_ => NotUsed)

  /**
   *
   * Get a [[izanami.scaladsl.ConfigClient]] to interact with configs from server.
   *
   * {{{
   *   val configClient = client.configClient(
   *     strategy = Strategies.fetchStrategy(),
   *     fallback = Configs(
   *       "test2" -> Json.obj("value" -> 2)
   *     )
   *   )
   * }}}
   * @param strategy
   * @param fallback
   * @return
   */
  def configClient(
      strategy: Strategy,
      fallback: Configs = Configs(Seq.empty),
      autocreate: Boolean = false
  ) = {
    val source = config.backend match {
      case SseBackend => eventSource
      case _ =>
        Source.failed(new IllegalStateException("Notifications are disabled for this strategy"))
    }

    val cudClient: CUDConfigClient = CUDConfigClient(client)
    strategy match {
      case DevStrategy =>
        FallbackConfigStategy(fallback)
      case Strategy.FetchStrategy(errorStrategy) =>
        FetchConfigClient(client, config, fallback, errorStrategy, autocreate, source, CUDConfigClient(client))
      case s: Strategy.FetchWithCacheStrategy =>
        FetchWithCacheConfigClient(
          config,
          fallback,
          FetchConfigClient(client, config, fallback, s.errorStrategy, autocreate, source, cudClient),
          s
        )
      case s: Strategy.CacheWithSseStrategy =>
        SmartCacheConfigClient(config,
                               FetchConfigClient(client, config, fallback, Crash, autocreate, eventSource, cudClient),
                               fallback,
                               s)
      case s: Strategy.CacheWithPollingStrategy =>
        SmartCacheConfigClient(config,
                               FetchConfigClient(client, config, fallback, Crash, autocreate, source, cudClient),
                               fallback,
                               s)
    }
  }

  /**
   *
   * Get a [[izanami.scaladsl.FeatureClient]] to interact with features from server.
   *
   * {{{
   *   val featureClient = client.featureClient(
   *     strategy = Strategies.fetchStrategy(),
   *     fallback = Features(
   *       DefaultFeature("test2", true)
   *     )
   *   )
   * }}}
   * @param strategy
   * @param fallback
   * @return
   */
  def featureClient(
      strategy: Strategy,
      fallback: ClientConfig => Features = clientConfig => Features(clientConfig, Seq.empty, Seq.empty),
      autocreate: Boolean = false
  ) = {
    val source = config.backend match {
      case SseBackend => eventSource
      case _ =>
        Source.failed(new IllegalStateException("Notifications are disabled for this strategy"))
    }
    val fb = fallback(config)
    strategy match {
      case DevStrategy =>
        FallbackFeatureStategy(fb)
      case Strategy.FetchStrategy(errorStrategy) =>
        FetchFeatureClient(client, config, fb, errorStrategy, autocreate, source)
      case s: Strategy.FetchWithCacheStrategy =>
        FetchWithCacheFeatureClient(config,
                                    FetchFeatureClient(client, config, fb, s.errorStrategy, autocreate, source),
                                    s)
      case s: Strategy.CacheWithSseStrategy =>
        SmartCacheFeatureClient(config,
                                FetchFeatureClient(client, config, fallback(config), Crash, autocreate, eventSource),
                                fb,
                                s)
      case s: Strategy.CacheWithPollingStrategy =>
        SmartCacheFeatureClient(config, FetchFeatureClient(client, config, fb, Crash, autocreate, source), fb, s)
    }
  }

  /**
   *
   * @param strategy
   * @param fallback
   * @return
   */
  def experimentClient(strategy: Strategy, fallback: Experiments = Experiments(), autocreate: Boolean = false) =
    strategy match {
      case DevStrategy =>
        FallbackExperimentStrategy(fallback)
      case Strategy.FetchStrategy(errorStrategy) =>
        FetchExperimentsStrategy(client, fallback, errorStrategy)
      case s =>
        throw new IllegalArgumentException(s"This strategy $s is not not supported for experiments")
    }

  /**
   * Create a proxy to expose a part of the api of the server from your app.
   *
   * @return the [[izanami.scaladsl.Proxy]]
   */
  def proxy(): Proxy = Proxy(None, None, None)

  /**
   * Create a proxy to expose a part of the api of the server from your app.
   *
   * @param featureClient [[izanami.scaladsl.FeatureClient]]
   * @param configClient [[izanami.scaladsl.ConfigClient]]
   * @param experimentClient [[izanami.scaladsl.ExperimentsClient]]
   * @return the [[izanami.scaladsl.Proxy]]
   */
  def proxy(featureClient: FeatureClient, configClient: ConfigClient, experimentClient: ExperimentsClient): Proxy =
    Proxy(Some(featureClient), Some(configClient), Some(experimentClient))

}

case class Proxy(
    featureClient: Option[FeatureClient],
    configClient: Option[ConfigClient],
    experimentClient: Option[ExperimentsClient],
    featurePattern: Seq[String] = Seq("*"),
    configPattern: Seq[String] = Seq("*"),
    experimentPattern: Seq[String] = Seq("*")
)(implicit actorSystem: ActorSystem, izanamiDispatcher: IzanamiDispatcher) {

  import izanamiDispatcher.ec
  val logger = Logging(actorSystem, this.getClass.getSimpleName)

  def withFeatureClient(featureClient: FeatureClient) =
    this.copy(featureClient = Some(featureClient))
  def withConfigClient(configClient: ConfigClient) =
    this.copy(configClient = Some(configClient))
  def withExperimentsClient(experimentsClient: ExperimentsClient) =
    this.copy(experimentClient = Some(experimentsClient))

  def withFeaturePattern(pattern: String*) = this.copy(featurePattern = pattern)
  def withConfigPattern(pattern: String*)  = this.copy(configPattern = pattern)
  def withExperimentPattern(pattern: String*) =
    this.copy(experimentPattern = pattern)

  def statusAndJsonResponse(context: Option[JsObject] = None, userId: Option[String] = None): Future[(Int, JsValue)] = {

    require(context != null, "context should not be null")
    require(userId != null, "userId should not be null")

    val features: Future[JsObject] =
      featureClient
        .map(
          cli =>
            context
              .map(
                ctx =>
                  cli
                    .features(featurePattern, ctx)
                    .map(f => Json.obj("features" -> f.tree()))
              )
              .getOrElse(
                cli
                  .features(featurePattern)
                  .map(f => Json.obj("features" -> f.tree()))
            )
        )
        .getOrElse(FastFuture.successful(Json.obj("features" -> Json.obj())))

    val configs: Future[JsObject] = configClient
      .map(
        _.configs(configPattern)
          .map(c => Json.obj("configurations" -> c.tree()))
      )
      .getOrElse(FastFuture.successful(Json.obj("configurations" -> Json.obj())))

    val experiments: Future[JsObject] = userId
      .flatMap(id => experimentClient.map(_.tree(experimentPattern, id)))
      .getOrElse(FastFuture.successful(Json.obj()))
      .map(exp => Json.obj("experiments" -> exp))

    Future
      .sequence(Seq(features, configs, experiments))
      .map(
        _.foldLeft(Json.obj())(_ deepMerge _)
      )
      .map((200, _))
      .recover {
        case e =>
          logger.error(e, "Error getting izanami tree datas")
          (400, Json.obj("errors" -> "Error getting izanami tree datas"))
      }

  }

  def statusAndStringResponse(context: Option[JsObject] = None, userId: Option[String] = None): Future[(Int, String)] =
    statusAndJsonResponse(context, userId).map {
      case (status, json) => (status, Json.stringify(json))
    }

  def markVariantDisplayed(experimentId: String, clientId: String): Future[(Int, JsValue)] =
    experimentClient
      .map(
        _.markVariantDisplayed(experimentId, clientId)
          .map { event =>
            (200, Json.toJson(event))
          }
          .recover {
            case e =>
              logger.error(e, "Error while marking variant displayed")
              (400, Json.obj("errors" -> "Error while marking variant displayed"))
          }
      )
      .getOrElse(FastFuture.successful((200, Json.obj())))

  def markVariantDisplayedStringResp(experimentId: String, clientId: String): Future[(Int, String)] =
    markVariantDisplayed(experimentId, clientId).map {
      case (status, json) => (status, Json.stringify(json))
    }

  def markVariantWon(experimentId: String, clientId: String): Future[(Int, JsValue)] =
    experimentClient
      .map(
        _.markVariantWon(experimentId, clientId)
          .map { event =>
            (200, Json.toJson(event))
          }
          .recover {
            case e =>
              logger.error(e, "Error while marking variant displayed")
              (400, Json.obj("errors" -> "Error while marking variant displayed"))
          }
      )
      .getOrElse(FastFuture.successful((200, Json.obj())))

  def markVariantWonStringResp(experimentId: String, clientId: String): Future[(Int, String)] =
    markVariantWon(experimentId, clientId).map {
      case (status, json) => (status, Json.stringify(json))
    }

}

///////////////////////////////////////////////////////////////////////
/////////////////////////   Features   ////////////////////////////////
///////////////////////////////////////////////////////////////////////

trait FeatureClient {

  def materializer: Materializer
  def izanamiDispatcher: IzanamiDispatcher
  def cudFeatureClient: CUDFeatureClient

  /**
   * Create a feature
   * @param id Feature Id
   * @param enabled If this feature is enabled by default or not
   * @param activationStrategy activationStrategy for this feature (@see {@link izanami.FeatureType})
   * @param parameters optional parameters (depends on activationStrategy)
   * @return
   */
  def createJsonFeature(id: String,
                        enabled: Boolean = true,
                        activationStrategy: FeatureType = FeatureType.NO_STRATEGY,
                        parameters: Option[JsObject] = None): Future[Feature] =
    cudFeatureClient.createJsonFeature(id, enabled, activationStrategy, parameters)

  /**
   * Create a feature
   * @param feature the feature to create
   * @param parameters optional parameters (depends on activationStrategy)
   * @return
   */
  def createFeature(feature: Feature, parameters: Option[JsObject] = None): Future[Feature] =
    cudFeatureClient.createFeature(feature, parameters)

  /**
   * Update a feature
   * @param id the previous id of the feature
   * @param feature the feature to update
   * @param parameters optional parameters (depends on activationStrategy)
   * @return
   */
  def updateFeature(id: String, feature: Feature, parameters: Option[JsObject] = None): Future[Feature] =
    cudFeatureClient.updateFeature(id, feature, parameters)

  /**
   * Enabled or disable a feature
   * @param id the id of the feature
   * @param enabled the status to set
   * @return
   */
  def switchFeature(id: String, enabled: Boolean): Future[Feature] = cudFeatureClient.switchFeature(id, enabled)

  /**
   * Delete a feature
   * @param id the id of the feature to delete
   * @return
   */
  def deleteFeature(id: String): Future[Unit] = cudFeatureClient.deleteFeature(id)

  /**
   * Get features by pattern like my:keys:*
   */
  def features(pattern: String): Future[Features] = features(Seq(pattern))

  /**
   * Get features by pattern like my:keys:*
   */
  def features(pattern: Seq[String]): Future[Features]

  /**
   * Get features by pattern like my:keys:* for a context
   */
  def features(pattern: String, context: JsObject): Future[Features] = features(Seq(pattern), context)

  /**
   * Get features by pattern like my:keys:* for a context
   */
  def features(pattern: Seq[String], context: JsObject): Future[Features]

  /**
   * Check if a feature is active
   */
  def checkFeature(key: String): Future[Boolean]

  /**
   * Check if a feature is active for a context
   */
  def checkFeature(key: String, context: JsObject): Future[Boolean]

  /**
   * Return a value if the feature is active or else a default
   */
  def featureOrElse[T](key: String)(ok: => T)(ko: => T): Future[T] =
    checkFeature(key)
      .map {
        case true  => ok
        case false => ko
      }(izanamiDispatcher.ec)

  /**
   * Return a value if the feature is active or else a default
   */
  def featureOrElseAsync[T](key: String)(ok: => Future[T])(ko: => Future[T]): Future[T] =
    checkFeature(key)
      .flatMap {
        case true  => ok
        case false => ko
      }(izanamiDispatcher.ec)

  /**
   * Return a value if the feature is active for a context, or else a default
   */
  def featureOrElse[T](key: String, context: JsObject)(ok: => T)(ko: => T): Future[T] =
    checkFeature(key, context)
      .map {
        case true  => ok
        case false => ko
      }(izanamiDispatcher.ec)

  /**
   * Return a value if the feature is active for a context, or else a default
   */
  def featureOrElseAsync[T](key: String, context: JsObject)(ok: => Future[T])(ko: => Future[T]): Future[T] =
    checkFeature(key, context)
      .flatMap {
        case true  => ok
        case false => ko
      }(izanamiDispatcher.ec)

  /**
   * Register a callback to be notified when a feature change
   */
  def onFeatureChanged(key: String)(cb: Feature => Unit): Registration =
    onEvent(key) {
      case FeatureCreated(_, id, f) if id == key =>
        cb(f)
      case FeatureUpdated(_, id, f, _) if id == key =>
        cb(f)
    }

  /**
   * Register a callback to be notified of events concerning features
   */
  def onEvent(pattern: String)(cb: FeatureEvent => Unit): Registration = {
    val (killSwitch, done) = featuresSource(pattern)
      .viaMat(KillSwitches.single)(Keep.right)
      .toMat(Sink.foreach(e => cb(e)))(Keep.both)
      .run()(materializer)
    DefaultRegistration(killSwitch, done)(izanamiDispatcher)
  }

  /**
   * Get a Akka Stream source of events
   */
  def featuresSource(pattern: String): Source[FeatureEvent, NotUsed]

  /**
   * Get a Reactive Streams publisher of events
   */
  def featuresStream(pattern: String = "*"): Publisher[FeatureEvent]
}

trait Registration {
  def onComplete(cb: Try[Done] => Unit): Unit
  def close(): Unit
}

case class FakeRegistration() extends Registration {
  override def onComplete(cb: Try[Done] => Unit): Unit = {}
  override def close(): Unit                           = {}
}

case class DefaultRegistration(killSwitch: UniqueKillSwitch, done: Future[Done])(
    implicit izanamiDispatcher: IzanamiDispatcher
) extends Registration {

  override def onComplete(cb: Try[Done] => Unit): Unit =
    done.onComplete { cb }(izanamiDispatcher.ec)

  override def close(): Unit = killSwitch.shutdown()

}

object Features {

  def features(features: Feature*): ClientConfig => Features = { clientConfig =>
    Features(clientConfig, features, Seq.empty)
  }

  def empty(): ClientConfig => Features = { clientConfig =>
    Features(clientConfig, Seq.empty, Seq.empty)
  }

  def feature(key: String, active: Boolean) = DefaultFeature(key, active)

  def apply(features: Feature*): ClientConfig => Features =
    clientConfig => new Features(clientConfig, features, fallback = Seq.empty)

  def parseJson(json: String): ClientConfig => Features = {
    implicit val f = Feature.format
    val featuresSeq = Json
      .parse(json)
      .validate[Seq[Feature]]
      .fold(
        err => throw IzanamiException(s"Error parsing json $json: \n${Json.prettyPrint(JsError.toJson(err))}"),
        identity
      )
    config =>
      Features(config, featuresSeq)
  }
}

case class Features(clientConfig: ClientConfig, featuresSeq: Seq[Feature], fallback: Seq[Feature] = Seq.empty) {
  def isActive(key: String): Boolean =
    featuresSeq
      .find(_.id == key)
      .map(_.isActive(clientConfig))
      .getOrElse(
        fallback.find(_.id == key).exists(_.isActive(clientConfig))
      )

  def filterWith(patterns: Seq[String]): Features =
    this.copy(
      featuresSeq = featuresSeq.filter(f => patterns.exists(p => PatternsUtil.matchPattern(p)(f.id))),
      fallback = fallback.filter(f => patterns.exists(p => PatternsUtil.matchPattern(p)(f.id)))
    )

  def tree(): JsObject = {
    val fallbackTree: JsObject =
      fallback.map(json).foldLeft(Json.obj())(_ deepMerge _)
    val featuresTree: JsObject =
      featuresSeq.map(json).foldLeft(Json.obj())(_ deepMerge _)
    fallbackTree deepMerge featuresTree
  }

  private def json(feature: Feature): JsObject = {
    val jsPath: JsPath = feature.id.split(":").foldLeft[JsPath](JsPath) { (p, s) =>
      p \ s
    }
    (jsPath \ "active").write[Boolean].writes(feature.isActive(clientConfig))
  }
}

///////////////////////////////////////////////////////////////////////
/////////////////////////    Configs    ///////////////////////////////
///////////////////////////////////////////////////////////////////////

object Config {
  import play.api.libs.json._
  import play.api.libs.functional.syntax._
  private val read = (
    (__ \ "id").read[String] and
    (__ \ "value")
      .read[String]
      .flatMap(
        s =>
          Reads[JsValue] { _ =>
            try {
              JsSuccess(Json.parse(s))
            } catch {
              case _: Throwable => JsError("Error parsing json")
            }
        }
      )
      .orElse {
        (__ \ "value").read[JsValue]
      }
  )(Config.apply _)

  private val write: OWrites[Config] = OWrites[Config] { c =>
    Json.obj(
      "id"    -> c.id,
      "value" -> c.value
    )
  }

  implicit val format = OFormat(read, write)
}
case class Config(id: String, value: JsValue) {
  def asJson =
    id.split(":")
      .foldLeft[JsPath](JsPath)((path, segment) => path \ segment)
      .write[JsValue]
      .writes(value)
}

object Configs {

  def fromJson(configsJson: Seq[JsValue], fallback: Seq[Config]): Configs = {
    val configs = configsJson.flatMap(json => Config.format.reads(json).asOpt)
    Configs(configs, fallback)
  }

  def apply(configs: (String, JsValue)*): Configs =
    new Configs(configs.map(c => Config(c._1, c._2)), fallback = Seq.empty)

  def parseJson(json: String): Configs =
    Configs(
      Json
        .parse(json)
        .validate[Seq[Config]]
        .fold(
          err => throw IzanamiException(s"Error parsing json $json: \n${Json.prettyPrint(JsError.toJson(err))}"),
          identity
        )
    )

}

case class Configs(configs: Seq[Config], fallback: Seq[Config] = Seq.empty) {

  def filterWith(patterns: Seq[String]): Configs =
    this.copy(
      configs = configs.filter(f => patterns.exists(p => PatternsUtil.matchPattern(p)(f.id)))
    )

  def tree(): JsObject = {
    val fbTree = fallback
      .map(_.asJson)
      .foldLeft(Json.obj())(_ deepMerge _)
    val valuesTree = configs.map(_.asJson).foldLeft(Json.obj())(_ deepMerge _)
    fbTree deepMerge valuesTree
  }

  def get(key: String): JsValue =
    configs
      .find(_.id == key)
      .map(_.value)
      .getOrElse(
        fallback.find(_.id == key).map(_.value).getOrElse(Json.obj())
      )

}

sealed trait ConfigEvent extends Event {
  def id: String
}

object ConfigEvent {
  case class ConfigCreated(eventId: Option[Long], id: String, config: Config)                    extends ConfigEvent
  case class ConfigUpdated(eventId: Option[Long], id: String, config: Config, oldConfig: Config) extends ConfigEvent
  case class ConfigDeleted(eventId: Option[Long], id: String)                                    extends ConfigEvent
}

trait ConfigClient {

  def materializer: Materializer
  def izanamiDispatcher: IzanamiDispatcher
  def cudConfigClient: CUDConfigClient

  /**
   * Create a config
   */
  def createConfig(config: Config): Future[Config] = cudConfigClient.createConfig(config)

  /**
   * Create a config with an id and a Json value
   */
  def createConfig(id: String, config: JsValue): Future[JsValue] = cudConfigClient.createConfig(id, config)

  /**
   * Update a config with an id and a Json value
   * There is an oldId and a new id if the id has changed. In the other cases it should be the same value.
   */
  def updateConfig(oldId: String, id: String, config: JsValue): Future[JsValue] =
    cudConfigClient.updateConfig(oldId, id, config)

  /**
   * Update a config with an id and a config. If the id has changed, the id in param should be the old value.
   */
  def updateConfig(id: String, config: Config): Future[Config] =
    cudConfigClient.updateConfig(id, config)

  /**
   * Delete a config by id.
   */
  def deleteConfig(id: String): Future[Unit] = cudConfigClient.deleteConfig(id)

  /**
   * Get configs by pattern like my:keys:*
   */
  def configs(pattern: String = "*"): Future[Configs] = configs(Seq(pattern))

  /**
   * Get configs by pattern like my:keys:*
   */
  def configs(pattern: Seq[String]): Future[Configs]

  /**
   * Get a config by his key
   */
  def config(key: String): Future[JsValue]

  /**
   * Register a callback to be notified when a config change
   */
  def onConfigChanged(key: String)(cb: JsValue => Unit): Registration =
    onEvent(key) {
      case ConfigCreated(_, id, c) if id == key =>
        cb(c.value)
      case ConfigUpdated(_, id, c, _) if id == key =>
        cb(c.value)
    }

  /**
   * Register a callback to be notified of events concerning configs
   */
  def onEvent(pattern: String)(cb: ConfigEvent => Unit): Registration = {
    val (killSwitch, done) = configsSource(pattern)
      .viaMat(KillSwitches.single)(Keep.right)
      .toMat(Sink.foreach(e => cb(e)))(Keep.both)
      .run()(materializer)
    DefaultRegistration(killSwitch, done)(izanamiDispatcher)
  }

  /**
   * Get a Akka Stream source of events
   */
  def configsSource(pattern: String): Source[ConfigEvent, NotUsed]

  /**
   * Get a Reactive Streams publisher of events
   */
  def configsStream(pattern: String = "*"): Publisher[ConfigEvent]

}

///////////////////////////////////////////////////////////////////////
/////////////////////////    Experiments    ///////////////////////////
///////////////////////////////////////////////////////////////////////

trait ExperimentsClient {

  /**
   * Get an experiment if exists
   */
  def experiment(id: String): Future[Option[ExperimentClient]]

  /**
   * Get experiments by pattern like my:keys:*
   */
  def list(pattern: String): Future[Seq[ExperimentClient]] = list(Seq(pattern))

  /**
   * Get experiments by pattern like my:keys:*
   */
  def list(pattern: Seq[String]): Future[Seq[ExperimentClient]]

  /**
   * Get experiments and the variant associated to the user id, for by pattern like my:keys:*.
   * The result is formatted as a tree form.
   */
  def tree(pattern: String, clientId: String): Future[JsObject] = tree(Seq(pattern), clientId)

  /**
   * Get experiments and the variant associated to the user id, for by pattern like my:keys:*.
   * The result is formatted as a tree form.
   */
  def tree(pattern: Seq[String], clientId: String): Future[JsObject]

  /**
   * Get the variant if exists, associated to the user id for an experiment.
   */
  def getVariantFor(experimentId: String, clientId: String): Future[Option[Variant]]

  /**
   * Notify the server that the variant has been displayed for this client id
   */
  def markVariantDisplayed(experimentId: String, clientId: String): Future[ExperimentVariantDisplayed]

  /**
   * Notify the server that the variant has won for this client id
   */
  def markVariantWon(experimentId: String, clientId: String): Future[ExperimentVariantWon]
}

case class ExperimentClient(experimentsClient: ExperimentsClient, experiment: Experiment) {

  def id: String             = experiment.id
  def name: String           = experiment.name
  def description: String    = experiment.description
  def enabled: Boolean       = experiment.enabled
  def variants: Seq[Variant] = experiment.variants

  def matchPattern(patterns: Seq[String]): Boolean =
    patterns.exists(p => PatternsUtil.matchPattern(p)(id))

  /**
   * Get the variant if exists, associated to the user id for an experiment.
   */
  def getVariantFor(clientId: String): Future[Option[Variant]] =
    experimentsClient.getVariantFor(experiment.id, clientId)

  /**
   * Notify the server that the variant has been displayed for this client id
   */
  def markVariantDisplayed(clientId: String): Future[ExperimentVariantDisplayed] =
    experimentsClient.markVariantDisplayed(experiment.id, clientId)

  /**
   * Notify the server that the variant has won for this client id
   */
  def markVariantWon(clientId: String): Future[ExperimentVariantWon] =
    experimentsClient.markVariantWon(experiment.id, clientId)

}
