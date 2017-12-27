package izanami

import java.time.{LocalDateTime, ZoneId}

import akka.actor.ActorSystem
import izanami.Strategy._
import izanami.commons.IzanamiException
import play.api.libs.json._
import shapeless.syntax

import scala.concurrent.duration.{DurationInt, FiniteDuration}

///////////////////////////////////////////////////////////////////////
//////////////////////  Izanami configuration   ///////////////////////
///////////////////////////////////////////////////////////////////////

sealed trait Env
object Env {
  case object Dev  extends Env
  case object Prod extends Env
}

object ClientConfig {

  /**
   * Create a new client config
   */
  def create(host: String): ClientConfig = {
    assert(host != null, "host should not be null")
    ClientConfig(host)
  }
}

case class IzanamiDispatcher(name: String = "akka.actor.default-dispatcher", system: ActorSystem) {
  implicit val ec = system.dispatchers.lookup(name)
}

case class ClientConfig(
    host: String,
    clientId: Option[String] = None,
    clientSecret: Option[String] = None,
    clientIdHeaderName: String = "Izanami-Client-Id",
    clientSecretHeaderName: String = "Izanami-Client-Secret",
    backend: IzanamiBackend = IzanamiBackend.Undefined,
    pageSize: Int = 200,
    zoneId: ZoneId = ZoneId.of("Europe/Paris"),
    dispatcher: String = "akka.actor.default-dispatcher"
) {

  def withClientId(clientId: String) = {
    assert(clientId != null, "clientId should not be null")
    this.copy(clientId = Some(clientId))
  }
  def withClientIdHeaderName(clientIdHeaderName: String) = {
    assert(clientIdHeaderName != null, "clientIdHeaderName should not be null")
    this.copy(clientIdHeaderName = clientIdHeaderName)
  }
  def withClientSecret(clientSecret: String) = {
    assert(clientSecret != null, "clientSecret should not be null")
    this.copy(clientSecret = Some(clientSecret))
  }
  def withClientSecretHeaderName(clientSecretHeaderName: String) = {
    assert(clientSecretHeaderName != null, "clientSecretHeaderName should not be null")
    this.copy(clientSecretHeaderName = clientSecretHeaderName)
  }
  def sseBackend() = this.copy(backend = IzanamiBackend.SseBackend)
  def withPageSize(pageSize: Int) = {
    assert(pageSize > 0, "pageSize should not be a positive number")
    this.copy(pageSize = pageSize)
  }
  def withDispatcher(dispatcher: String) = {
    assert(dispatcher != null, "dispatcher should not be null")
    this.copy(dispatcher = dispatcher)
  }
  def withZoneId(zoneId: ZoneId) = {
    assert(zoneId != null, "zoneId should not be null")
    this.copy(zoneId = zoneId)
  }
}

sealed trait IzanamiBackend
object IzanamiBackend {
  case object SseBackend     extends IzanamiBackend
  case object Undefined      extends IzanamiBackend
  case object PollingBackend extends IzanamiBackend
}

object Strategies {
  def dev()           = DevStrategy
  def fetchStrategy() = FetchStrategy
  def fetchWithCacheStrategy(maxElement: Int, duration: FiniteDuration) =
    FetchWithCacheStrategy(maxElement, duration)
  @annotation.varargs
  def smartCacheWithPollingStrategy(pollingInterval: FiniteDuration, patterns: String*) =
    CacheWithPollingStrategy(patterns, pollingInterval)
  @annotation.varargs
  def smartCacheWithSseStrategy(patterns: String*) =
    CacheWithSseStrategy(patterns)
}

trait Strategy
trait SmartCacheStrategy extends Strategy {
  def patterns: Seq[String]
}
object Strategy {
  case object DevStrategy                                                      extends Strategy
  case object FetchStrategy                                                    extends Strategy
  case class FetchWithCacheStrategy(maxElement: Int, duration: FiniteDuration) extends Strategy
  case class CacheWithSseStrategy(patterns: Seq[String])                       extends SmartCacheStrategy
  case class CacheWithPollingStrategy(patterns: Seq[String], pollingInterval: FiniteDuration = 20.seconds)
      extends SmartCacheStrategy
}

///////////////////////////////////////////////////////////////////////
///////////////////////////  Events   /////////////////////////////////
///////////////////////////////////////////////////////////////////////

case class IzanamiEvent(key: String,
                        `type`: String,
                        domain: String,
                        payload: JsObject,
                        oldValue: Option[JsObject],
                        timestamp: LocalDateTime)

object IzanamiEvent {
  implicit val format = Json.format[IzanamiEvent]
}

trait Event

///////////////////////////////////////////////////////////////////////
///////////////////////////  Experiment   /////////////////////////////
///////////////////////////////////////////////////////////////////////

case class Variant(id: String, name: String, description: String)

object Variant {
  implicit val format                                                = Json.format[Variant]
  def create(id: String, name: String, description: String): Variant = Variant(id, name, description)
}

sealed trait ExperimentVariantEvent {
  def id: String
  def variant: Variant
  def date: LocalDateTime
}

case class ExperimentVariantDisplayed(id: String,
                                      experimentId: String,
                                      clientId: String,
                                      variant: Variant,
                                      date: LocalDateTime = LocalDateTime.now(),
                                      transformation: Double,
                                      variantId: String)
    extends ExperimentVariantEvent

object ExperimentVariantDisplayed {
  implicit val format = Json.format[ExperimentVariantDisplayed]
}

case class ExperimentVariantWon(id: String,
                                experimentId: String,
                                clientId: String,
                                variant: Variant,
                                date: LocalDateTime = LocalDateTime.now(),
                                transformation: Double,
                                variantId: String)
    extends ExperimentVariantEvent

object ExperimentVariantWon {
  implicit val format = Json.format[ExperimentVariantWon]
}

case class ExperimentFallback(id: String, name: String, description: String, enabled: Boolean, variant: Variant) {
  def experiment = Experiment(id, name, description, enabled, Seq(variant))

  def tree: JsObject =
    (id.split(":").foldLeft[JsPath](JsPath)(_ \ _) \ "variant")
      .write[String]
      .writes(variant.id)
}

object ExperimentFallback {
  implicit val format = Json.format[ExperimentFallback]
  def create(id: String, name: String, description: String, enabled: Boolean, variant: Variant): ExperimentFallback =
    ExperimentFallback(id, name, description, enabled, variant)
}

@annotation.varargs
case class Experiments(experiments: ExperimentFallback*)

object Experiments {

  import scala.collection.JavaConverters._

  def apply(experiments: java.lang.Iterable[ExperimentFallback]): Experiments =
    new Experiments(experiments.asScala.toSeq: _*)

  @annotation.varargs
  def create(experiments: ExperimentFallback*): Experiments =
    new Experiments(experiments: _*)

  def parseJson(json: String): Experiments =
    Json
      .parse(json)
      .validate[Seq[ExperimentFallback]]
      .map(f => Experiments(f: _*))
      .fold(
        err => throw IzanamiException(s"Error parsing $json : $err"),
        identity
      )
}

case class Experiment(id: String, name: String, description: String, enabled: Boolean, variants: Seq[Variant])

object Experiment {
  implicit val format = Json.format[Experiment]
}

object Feature {

  import play.api.libs.functional.syntax._

  private[izanami] val commonWrite =
  (__ \ "id").write[String] and
  (__ \ "enabled").write[Boolean]

  val reads: Reads[Feature] = Reads[Feature] {
    case o if (o \ "activationStrategy").asOpt[String].contains("NO_STRATEGY") =>
      DefaultFeature.format.reads(o)
    case o if (o \ "activationStrategy").asOpt[String].contains("RELEASE_DATE") =>
      ReleaseDateFeature.format.reads(o)
    case o if (o \ "activationStrategy").asOpt[String].contains("SCRIPT") =>
      ScriptFeature.format.reads(o)
    case o if (o \ "activationStrategy").asOpt[String].contains("GLOBAL_SCRIPT") =>
      GlobalScriptFeature.format.reads(o)
    case other =>
      JsError("invalid json")
  }

  val writes: Writes[Feature] = Writes[Feature] {
    case s: DefaultFeature      => Json.toJson(s)(DefaultFeature.format)
    case s: ReleaseDateFeature  => Json.toJson(s)(ReleaseDateFeature.format)
    case s: ScriptFeature       => Json.toJson(s)(ScriptFeature.format)
    case s: GlobalScriptFeature => Json.toJson(s)(GlobalScriptFeature.format)
  }

  implicit val format: Format[Feature] = Format(reads, writes)

  def create(key: String, active: Boolean) = DefaultFeature(key, active)
}

///////////////////////////////////////////////////////////////////////
///////////////////////////  Features   ///////////////////////////////
///////////////////////////////////////////////////////////////////////

sealed trait Feature {
  def id: String
  def enabled: Boolean
  def isActive(config: ClientConfig): Boolean
}

object DefaultFeature {
  val reads = Json.reads[DefaultFeature]
  val writes: Writes[DefaultFeature] = Json.writes[DefaultFeature].transform { o: JsObject =>
    o ++ Json.obj("activationStrategy" -> "NO_STRATEGY")
  }
  val format: Format[DefaultFeature] = Format(reads, writes)
}
case class DefaultFeature(id: String, enabled: Boolean) extends Feature {
  def isActive(config: ClientConfig) = enabled
}

object GlobalScriptFeature {

  import play.api.libs.functional.syntax._
  import play.api.libs.json._
  import playjson.all._

  val writes: Writes[GlobalScriptFeature] = (
    Feature.commonWrite and
    (__ \ "active").writeNullable[Boolean] and
    (__ \ "parameters" \ "ref").write[String]
  )(unlift(GlobalScriptFeature.unapply))
    .transform { o: JsObject =>
      o ++ Json.obj("activationStrategy" -> "GLOBAL_SCRIPT")
    }

  private val reads: Reads[GlobalScriptFeature] = transform(
    (__ \ 'parameters \ 'ref) to (__ \ 'ref)
  ) andThen Json.reads[GlobalScriptFeature]

  val format: Format[GlobalScriptFeature] = Format(reads, writes)

}
case class GlobalScriptFeature(id: String, enabled: Boolean, active: Option[Boolean], ref: String) extends Feature {
  def isActive(config: ClientConfig) = active.getOrElse(false)
}

object ScriptFeature {

  import play.api.libs.functional.syntax._
  import play.api.libs.json._
  import playjson.all._

  val writes: Writes[ScriptFeature] = (
    Feature.commonWrite and
    (__ \ "active").writeNullable[Boolean] and
    (__ \ "parameters" \ "script").write[String]
  )(unlift(ScriptFeature.unapply))
    .transform { o: JsObject =>
      o ++ Json.obj("activationStrategy" -> "SCRIPT")
    }

  private val reads: Reads[ScriptFeature] = transform(
    (__ \ "parameters" \ "script") to (__ \ "script")
  ) andThen Json.reads[ScriptFeature]

  val format: Format[ScriptFeature] = Format(reads, writes)

}
case class ScriptFeature(id: String, enabled: Boolean, active: Option[Boolean], script: String) extends Feature {
  def isActive(config: ClientConfig) = active.getOrElse(false)
}

object ReleaseDateFeature {
  import play.api.libs.functional.syntax._
  import play.api.libs.json.Reads.localDateTimeReads
  import play.api.libs.json.Writes.temporalWrites
  import play.api.libs.json._
  import playjson.all._
  import syntax.singleton._

  private val pattern  = "dd/MM/yyyy HH:mm:ss"
  private val pattern2 = "dd/MM/yyyy HH:mm"

  private val reads: Reads[ReleaseDateFeature] = transform(
    (__ \ "parameters" \ "releaseDate") to (__ \ "date")
  ) andThen jsonRead[ReleaseDateFeature].withRules(
    'date ->> read[LocalDateTime](localDateTimeReads(pattern).orElse(localDateTimeReads(pattern2)))
  )

  private val writes: Writes[ReleaseDateFeature] = (
    Feature.commonWrite and
    (__ \ "parameters" \ "releaseDate")
      .write[LocalDateTime](temporalWrites[LocalDateTime, String](pattern))
  )(unlift(ReleaseDateFeature.unapply)).transform { o: JsObject =>
    o ++ Json.obj("activationStrategy" -> "RELEASE_DATE")
  }

  val format: Format[ReleaseDateFeature] = Format(reads, writes)
}

case class ReleaseDateFeature(id: String, enabled: Boolean, date: LocalDateTime) extends Feature {
  override def isActive(config: ClientConfig) = {
    val now: LocalDateTime = LocalDateTime.now(config.zoneId)
    enabled && now.isAfter(date)
  }
}

sealed trait FeatureEvent extends Event {
  def id: String
}

object FeatureEvent {
  case class FeatureCreated(id: String, feature: Feature)                      extends FeatureEvent
  case class FeatureUpdated(id: String, feature: Feature, oldFeature: Feature) extends FeatureEvent
  case class FeatureDeleted(id: String)                                        extends FeatureEvent
}
