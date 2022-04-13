package izanami

import java.time.{LocalDateTime, ZoneId}

import akka.actor.ActorSystem
import izanami.Strategy._
import izanami.commons.{IzanamiException, PatternsUtil}
import play.api.libs.json._

import scala.concurrent.duration._
import java.time.LocalTime

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

  def pollingBackend() = this.copy(backend = IzanamiBackend.PollingBackend)

  def undefined() = this.copy(backend = IzanamiBackend.Undefined)

  def withPageSize(pageSize: Int) = {
    assert(pageSize > 0, "pageSize should not be a positive number")
    this.copy(pageSize = pageSize)
  }
  def withDispatcher(dispatcher: String) = {
    assert(dispatcher != null, "dispatcher should not be null")
    this.copy(dispatcher = dispatcher)
  }
  def withDefaultBlockingDispatcher() =
    this.copy(dispatcher = "izanami.blocking-dispatcher")

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
  def dev()                                       = DevStrategy
  def fetchStrategy()                             = FetchStrategy()
  def fetchStrategy(errorStrategy: ErrorStrategy) = FetchStrategy(errorStrategy)

  def fetchWithCacheStrategy(maxElement: Int, duration: FiniteDuration) =
    FetchWithCacheStrategy(maxElement, duration)
  def fetchWithCacheStrategy(maxElement: Int, duration: FiniteDuration, errorStrategy: ErrorStrategy) =
    FetchWithCacheStrategy(maxElement, duration, errorStrategy)

  @annotation.varargs
  def smartCacheWithPollingStrategy(pollingInterval: FiniteDuration, patterns: String*) =
    CacheWithPollingStrategy(patterns, pollingInterval)
  @annotation.varargs
  def smartCacheWithSseStrategy(pollingInterval: Option[Duration], patterns: String*) =
    CacheWithSseStrategy(patterns, pollingInterval)
}

trait Strategy
trait SmartCacheStrategy extends Strategy {
  def errorStrategy: ErrorStrategy
  def patterns: Seq[String]
}

sealed trait ErrorStrategy
case object RecoverWithFallback extends ErrorStrategy
case object Crash               extends ErrorStrategy

object ErrorStrategies {
  def recoverWithFallback(): ErrorStrategy = RecoverWithFallback
  def crash(): ErrorStrategy               = Crash
}

object Strategy {

  sealed trait CacheEvent[T]
  case class ValueUpdated[T](key: String, newValue: T, oldValue: T) extends CacheEvent[T]
  case class ValueCreated[T](key: String, newValue: T)              extends CacheEvent[T]
  case class ValueDeleted[T](key: String, oldValue: T)              extends CacheEvent[T]

  case object DevStrategy extends Strategy
  case class FetchStrategy(errorStrategy: ErrorStrategy = RecoverWithFallback) extends Strategy {
    def withErrorStrategy(strategy: ErrorStrategy) = copy(errorStrategy = strategy)
  }
  case class FetchWithCacheStrategy(
      maxElement: Int,
      duration: FiniteDuration,
      errorStrategy: ErrorStrategy = RecoverWithFallback
  ) extends Strategy {
    def withErrorStrategy(strategy: ErrorStrategy) = copy(errorStrategy = strategy)
  }
  case class CacheWithSseStrategy(
      patterns: Seq[String],
      pollingInterval: Option[Duration] = Some(1.minute),
      errorStrategy: ErrorStrategy = RecoverWithFallback
  ) extends SmartCacheStrategy {
    def withPollingInterval(interval: Duration)    = copy(pollingInterval = Some(interval))
    def withPollingDisabled()                      = copy(pollingInterval = None)
    def withErrorStrategy(strategy: ErrorStrategy) = copy(errorStrategy = strategy)
  }
  case class CacheWithPollingStrategy(
      patterns: Seq[String],
      pollingInterval: Duration = 20.seconds,
      errorStrategy: ErrorStrategy = RecoverWithFallback
  ) extends SmartCacheStrategy {
    def withPollingInterval(interval: FiniteDuration) = copy(pollingInterval = interval)
    def withErrorStrategy(strategy: ErrorStrategy)    = copy(errorStrategy = strategy)
  }
}

///////////////////////////////////////////////////////////////////////
///////////////////////////  Events   /////////////////////////////////
///////////////////////////////////////////////////////////////////////

case class IzanamiEvent(
    _id: Long,
    key: String,
    `type`: String,
    domain: String,
    payload: JsObject,
    oldValue: Option[JsObject],
    timestamp: LocalDateTime
)

object IzanamiEvent {
  implicit val format = Json.format[IzanamiEvent]
}

trait Event

///////////////////////////////////////////////////////////////////////
///////////////////////////  Experiment   /////////////////////////////
///////////////////////////////////////////////////////////////////////

case class Variant(id: String, name: String, description: Option[String])

object Variant {
  implicit val format                                                        = Json.format[Variant]
  def create(id: String, name: String, description: Option[String]): Variant = Variant(id, name, description)
}

sealed trait ExperimentVariantEvent {
  def id: String
  def variant: Variant
  def date: LocalDateTime
}

case class ExperimentVariantDisplayed(
    id: String,
    experimentId: String,
    clientId: String,
    variant: Variant,
    date: LocalDateTime = LocalDateTime.now(),
    transformation: Double,
    variantId: String
) extends ExperimentVariantEvent

object ExperimentVariantDisplayed {
  implicit val format = Json.format[ExperimentVariantDisplayed]
}

case class ExperimentVariantWon(
    id: String,
    experimentId: String,
    clientId: String,
    variant: Variant,
    date: LocalDateTime = LocalDateTime.now(),
    transformation: Double,
    variantId: String
) extends ExperimentVariantEvent

object ExperimentVariantWon {
  implicit val format = Json.format[ExperimentVariantWon]
}

case class ExperimentFallback(id: String, name: String, description: String, enabled: Boolean, variant: Variant) {
  def experiment = Experiment(id, name, description, enabled, Seq(variant))

  def matchPatterns(patterns: Seq[String]) = patterns.exists(p => PatternsUtil.matchPattern(p)(id))

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

  import scala.jdk.CollectionConverters._

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
  import FeatureType._

  private[izanami] val commonWrite =
    (__ \ "id").write[String] and
    (__ \ "enabled").write[Boolean]

  val reads = Reads[Feature] {
    case o if (o \ "activationStrategy").asOpt[String].contains(NO_STRATEGY.name) =>
      DefaultFeature.format.reads(o)
    case o if (o \ "activationStrategy").asOpt[String].contains(RELEASE_DATE.name) =>
      ReleaseDateFeature.format.reads(o)
    case o if (o \ "activationStrategy").asOpt[String].contains(DATE_RANGE.name) =>
      DateRangeFeature.format.reads(o)
    case o if (o \ "activationStrategy").asOpt[String].contains(SCRIPT.name) =>
      ScriptFeature.format.reads(o)
    case o if (o \ "activationStrategy").asOpt[String].contains(GLOBAL_SCRIPT.name) =>
      GlobalScriptFeature.format.reads(o)
    case o if (o \ "activationStrategy").asOpt[String].contains(PERCENTAGE.name) =>
      PercentageFeature.format.reads(o)
    case o if (o \ "activationStrategy").asOpt[String].contains(CUSTOMERS_LIST.name) =>
      CustomersFeature.format.reads(o)
    case o if (o \ "activationStrategy").asOpt[String].contains(HOUR_RANGE.name) =>
      HourRangeFeature.format.reads(o)
    case o if (o \ "enabled").asOpt[Boolean].isDefined && (o \ "id").asOpt[String].isDefined =>
      import play.api.libs.functional.syntax._
      import play.api.libs.json._
      ((
        (__ \ "id").read[String] and
        (__ \ "enabled").read[Boolean]
      )(DefaultFeature.apply _)).reads(o)
    case other =>
      JsError("invalid json")
  }

  val writes = OWrites[Feature] {
    case s: DefaultFeature      => Json.toJsObject(s)(DefaultFeature.format)
    case s: ReleaseDateFeature  => Json.toJsObject(s)(ReleaseDateFeature.format)
    case s: DateRangeFeature    => Json.toJsObject(s)(DateRangeFeature.format)
    case s: ScriptFeature       => Json.toJsObject(s)(ScriptFeature.format)
    case s: GlobalScriptFeature => Json.toJsObject(s)(GlobalScriptFeature.format)
    case s: PercentageFeature   => Json.toJsObject(s)(PercentageFeature.format)
    case s: CustomersFeature    => Json.toJsObject(s)(CustomersFeature.format)
    case s: HourRangeFeature    => Json.toJsObject(s)(HourRangeFeature.format)
  }

  implicit val format = OFormat(reads, writes)

  def create(key: String, active: Boolean) = DefaultFeature(key, active)
}

///////////////////////////////////////////////////////////////////////
///////////////////////////  Features   ///////////////////////////////
///////////////////////////////////////////////////////////////////////
sealed case class FeatureType(name: String)

object FeatureType {
  object NO_STRATEGY    extends FeatureType("NO_STRATEGY")
  object RELEASE_DATE   extends FeatureType("RELEASE_DATE")
  object DATE_RANGE     extends FeatureType("DATE_RANGE")
  object SCRIPT         extends FeatureType("SCRIPT")
  object GLOBAL_SCRIPT  extends FeatureType("GLOBAL_SCRIPT")
  object PERCENTAGE     extends FeatureType("PERCENTAGE")
  object CUSTOMERS_LIST extends FeatureType("CUSTOMERS_LIST")
  object HOUR_RANGE     extends FeatureType("HOUR_RANGE")
}

sealed trait Feature {
  def id: String
  def enabled: Boolean
  def isActive(config: ClientConfig): Boolean
}

object DefaultFeature {
  val reads = Json.reads[DefaultFeature]
  val writes: OWrites[DefaultFeature] = Json.writes[DefaultFeature].transform { o: JsObject =>
    o ++ Json.obj("activationStrategy" -> FeatureType.NO_STRATEGY.name)
  }
  val format = OFormat(reads, writes)
}
case class DefaultFeature(id: String, enabled: Boolean) extends Feature {
  def isActive(config: ClientConfig) = enabled
}

object GlobalScriptFeature {

  import play.api.libs.functional.syntax._
  import play.api.libs.json._

  val writes: OWrites[GlobalScriptFeature] = (
    Feature.commonWrite and
    (__ \ "active").writeNullable[Boolean] and
    (__ \ "parameters" \ "ref").write[String]
  )(unlift(GlobalScriptFeature.unapply))
    .transform { o: JsObject => o ++ Json.obj("activationStrategy" -> FeatureType.GLOBAL_SCRIPT.name) }

  private val reads: Reads[GlobalScriptFeature] = (
    (__ \ "id").read[String] and
    (__ \ "enabled").read[Boolean].orElse(Reads.pure(false)) and
    (__ \ "active").readNullable[Boolean] and
    (__ \ "parameters" \ "ref").read[String]
  )(GlobalScriptFeature.apply _)

  val format: OFormat[GlobalScriptFeature] = OFormat(reads, writes)

}
case class GlobalScriptFeature(id: String, enabled: Boolean, active: Option[Boolean], ref: String) extends Feature {
  def isActive(config: ClientConfig) = active.getOrElse(false)
}

case class Script(`type`: String, script: String)

object Script {

  private val defaultReads = Json.reads[Script]

  private val reads = Reads[Script] {
    case JsString(s) => JsSuccess(Script("javascript", s))
    case s           => defaultReads.reads(s)
  }

  private val writes = Json.writes[Script]

  implicit val format = OFormat(reads, writes)

}

object ScriptFeature {

  import play.api.libs.functional.syntax._
  import play.api.libs.json._

  val writes: OWrites[ScriptFeature] = (
    Feature.commonWrite and
    (__ \ "active").writeNullable[Boolean] and
    (__ \ "parameters" \ "script").write[Script]
  )(unlift(ScriptFeature.unapply))
    .transform { o: JsObject => o ++ Json.obj("activationStrategy" -> FeatureType.SCRIPT.name) }

  private val reads: Reads[ScriptFeature] = (
    (__ \ "id").read[String] and
    (__ \ "enabled").read[Boolean].orElse(Reads.pure(false)) and
    (__ \ "active").readNullable[Boolean] and
    (__ \ "parameters" \ "script").read[Script](Script.format)
  )(ScriptFeature.apply _)

  val format = OFormat(reads, writes)

}

case class ScriptFeature(id: String, enabled: Boolean, active: Option[Boolean], script: Script) extends Feature {
  def isActive(config: ClientConfig) = active.getOrElse(false)
}

object ReleaseDateFeature {
  import play.api.libs.functional.syntax._
  import play.api.libs.json.Reads.localDateTimeReads
  import play.api.libs.json.Writes.temporalWrites
  import play.api.libs.json._

  private val pattern  = "dd/MM/yyyy HH:mm:ss"
  private val pattern2 = "dd/MM/yyyy HH:mm"
  private val pattern3 = "yyyy-MM-dd HH:mm:ss"

  val reads: Reads[ReleaseDateFeature] = (
    (__ \ "id").read[String] and
    (__ \ "enabled").read[Boolean].orElse(Reads.pure(false)) and
    (__ \ "parameters" \ "releaseDate")
      .read[LocalDateTime](
        localDateTimeReads(pattern).orElse(localDateTimeReads(pattern2)).orElse(localDateTimeReads(pattern3))
      )
  )(ReleaseDateFeature.apply _)

  private val writes: OWrites[ReleaseDateFeature] = (
    Feature.commonWrite and
    (__ \ "parameters" \ "releaseDate")
      .write[LocalDateTime](temporalWrites[LocalDateTime, String](pattern3))
  )(unlift(ReleaseDateFeature.unapply)).transform { o: JsObject =>
    o ++ Json.obj("activationStrategy" -> FeatureType.RELEASE_DATE.name)
  }

  val format = OFormat(reads, writes)
}

case class ReleaseDateFeature(id: String, enabled: Boolean, date: LocalDateTime) extends Feature {
  override def isActive(config: ClientConfig): Boolean = {
    val now: LocalDateTime = LocalDateTime.now(config.zoneId)
    enabled && now.isAfter(date)
  }
}

case class DateRangeFeature(id: String, enabled: Boolean, from: LocalDateTime, to: LocalDateTime) extends Feature {
  override def isActive(config: ClientConfig): Boolean = {
    val now: LocalDateTime = LocalDateTime.now(config.zoneId)
    (now.isAfter(from) || now.isEqual(from)) && (now.isBefore(to) || now.isEqual(to))
  }
}

object DateRangeFeature {
  import play.api.libs.functional.syntax._
  import play.api.libs.json.Reads.localDateTimeReads
  import play.api.libs.json.Writes.temporalWrites
  import play.api.libs.json._

  private val pattern = "yyyy-MM-dd HH:mm:ss"

  val reads: Reads[DateRangeFeature] = (
    (__ \ "id").read[String] and
    (__ \ "enabled").read[Boolean] and
    (__ \ "parameters" \ "from")
      .read[LocalDateTime](localDateTimeReads(pattern)) and
    (__ \ "parameters" \ "to")
      .read[LocalDateTime](localDateTimeReads(pattern))
  )(DateRangeFeature.apply _)

  private val dateWrite: Writes[LocalDateTime] = temporalWrites[LocalDateTime, String](pattern)

  val writes: OWrites[DateRangeFeature] = (
    Feature.commonWrite and
    (__ \ "parameters" \ "from").write[LocalDateTime](dateWrite) and
    (__ \ "parameters" \ "to").write[LocalDateTime](dateWrite)
  )(unlift(DateRangeFeature.unapply)).transform { o: JsObject =>
    o ++ Json.obj("activationStrategy" -> FeatureType.DATE_RANGE.name)
  }

  implicit val format = OFormat(reads, writes)
}

case class PercentageFeature(id: String, enabled: Boolean, active: Option[Boolean], percentage: Int) extends Feature {

  override def isActive(config: ClientConfig): Boolean =
    active.getOrElse(false)
}

object PercentageFeature {
  import play.api.libs.json._
  import play.api.libs.json.Reads._
  import play.api.libs.functional.syntax._

  val reads: Reads[PercentageFeature] = (
    (__ \ "id").read[String] and
    (__ \ "enabled").read[Boolean] and
    (__ \ "active").readNullable[Boolean] and
    (__ \ "parameters" \ "percentage").read[Int](min(0) keepAnd max(100))
  )(PercentageFeature.apply _)

  val writes: OWrites[PercentageFeature] = (
    Feature.commonWrite and
    (__ \ "active").writeNullable[Boolean] and
    (__ \ "parameters" \ "percentage").write[Int]
  )(unlift(PercentageFeature.unapply)).transform { o: JsObject =>
    o ++ Json.obj("activationStrategy" -> FeatureType.PERCENTAGE.name)
  }

  implicit val format = OFormat(reads, writes)
}

case class CustomersFeature(id: String, enabled: Boolean, active: Option[Boolean], customer: List[String])
    extends Feature {

  override def isActive(config: ClientConfig): Boolean =
    active.getOrElse(false)
}

object CustomersFeature {
  import play.api.libs.json._
  import play.api.libs.json.Reads._
  import play.api.libs.functional.syntax._

  val reads: Reads[CustomersFeature] = (
    (__ \ "id").read[String] and
    (__ \ "enabled").read[Boolean] and
    (__ \ "active").readNullable[Boolean] and
    (__ \ "parameters" \ "customers").read[List[String]]
  )(CustomersFeature.apply _)

  val writes: OWrites[CustomersFeature] = (
    Feature.commonWrite and
    (__ \ "active").writeNullable[Boolean] and
    (__ \ "parameters" \ "customers").write[List[String]]
  )(unlift(CustomersFeature.unapply)).transform { o: JsObject =>
    o ++ Json.obj("activationStrategy" -> FeatureType.CUSTOMERS_LIST.name)
  }

  implicit val format = OFormat(reads, writes)
}

case class HourRangeFeature(id: String, enabled: Boolean, active: Option[Boolean], startAt: LocalTime, endAt: LocalTime)
    extends Feature {
  override def isActive(config: ClientConfig): Boolean = {
    val now = LocalTime.now()
    now.isAfter(startAt) && now.isBefore(endAt)
  }
}

object HourRangeFeature {
  import play.api.libs.json._
  import play.api.libs.json.Reads._
  import play.api.libs.functional.syntax._
  import play.api.libs.json.Writes.temporalWrites

  private val pattern = "HH:mm"

  private val dateWrite: Writes[LocalTime] = temporalWrites[LocalTime, String](pattern)

  val reads: Reads[HourRangeFeature] = (
    (__ \ "id").read[String] and
    (__ \ "enabled").read[Boolean] and
    (__ \ "active").readNullable[Boolean] and
    (__ \ "parameters" \ "startAt").read[LocalTime](localTimeReads(pattern)) and
    (__ \ "parameters" \ "endAt").read[LocalTime](localTimeReads(pattern))
  )(HourRangeFeature.apply _)

  val writes: OWrites[HourRangeFeature] = (
    Feature.commonWrite and
    (__ \ "active").writeNullable[Boolean] and
    (__ \ "parameters" \ "startAt").write[LocalTime](dateWrite) and
    (__ \ "parameters" \ "endAt").write[LocalTime](dateWrite)
  )(unlift(HourRangeFeature.unapply)).transform { o: JsObject =>
    o ++ Json.obj("activationStrategy" -> FeatureType.HOUR_RANGE.name)
  }

  implicit val format = OFormat(reads, writes)
}

sealed trait FeatureEvent extends Event {
  def eventId: Option[Long]
  def id: String
}

object FeatureEvent {
  case class FeatureCreated(eventId: Option[Long], id: String, feature: Feature) extends FeatureEvent
  case class FeatureUpdated(eventId: Option[Long], id: String, feature: Feature, oldFeature: Feature)
      extends FeatureEvent
  case class FeatureDeleted(eventId: Option[Long], id: String) extends FeatureEvent
}
