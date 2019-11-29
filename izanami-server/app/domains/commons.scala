package domains

import akka.actor.ActorSystem
import akka.NotUsed
import akka.stream.Materializer
import akka.stream.scaladsl.{FileIO, Sink, Source}
import akka.util.ByteString
import cats.implicits._
import cats.kernel.Eq
import cats.kernel.Monoid
import com.codahale.metrics.MetricRegistry
import domains.abtesting.{ExperimentContext, ExperimentVariantEventService}
import domains.apikey.ApiKeyContext
import domains.config.ConfigContext
import domains.events.EventStore
import domains.feature.FeatureContext
import domains.script.GlobalScriptContext
import domains.script.Script.ScriptCache
import domains.user.UserContext
import domains.webhook.WebhookContext
import env.{DbDomainConfig, IzanamiConfig}
import libs.database.Drivers
import libs.logs.{IzanamiLogger, Logger, LoggerModule, ProdLogger}
import play.api.Environment
import play.api.inject.ApplicationLifecycle
import play.api.libs.json._
import play.api.libs.json.Reads.pattern
import play.api.libs.streams.Accumulator
import play.api.mvc.BodyParser
import scala.concurrent.ExecutionContext
import scala.util.{Failure, Success}
import scala.util.matching.Regex
import store.{EmptyPattern, JsonDataStore, Pattern, StringPattern}
import store.memorywithdb.InMemoryWithDbStore
import store.Result._
import zio.{RIO, Task, ZIO}
import zio.blocking.Blocking
import zio.clock.Clock
import zio.internal.Executor
import metrics.MetricsContext

trait AkkaModule {
  implicit def system: ActorSystem
  implicit def mat: Materializer
}

trait AuthInfoModule[+R] {
  def authInfo: Option[AuthInfo]
  def withAuthInfo(user: Option[AuthInfo]): R
}

trait PlayModule {
  def environment: Environment
  def wSClient: play.api.libs.ws.WSClient
  def javaWsClient: play.libs.ws.WSClient
  def ec: ExecutionContext
  def applicationLifecycle: ApplicationLifecycle
}

trait IzanamiConfigModule {
  def izanamiConfig: IzanamiConfig
}

trait DriversModule {
  def drivers: Drivers
}

trait GlobalContext
    extends AkkaModule
    with LoggerModule
    with DriversModule
    with IzanamiConfigModule
    with MetricsContext
    with ConfigContext
    with FeatureContext
    with GlobalScriptContext
    with ApiKeyContext
    with UserContext
    with WebhookContext
    with ExperimentContext
    with AuthInfoModule[GlobalContext]
    with Clock.Live

case class ProdGlobalContext(
    system: ActorSystem,
    mat: Materializer,
    izanamiConfig: IzanamiConfig,
    environment: Environment,
    wSClient: play.api.libs.ws.WSClient,
    javaWsClient: play.libs.ws.WSClient,
    ec: ExecutionContext,
    applicationLifecycle: ApplicationLifecycle,
    logger: Logger,
    metricRegistry: MetricRegistry,
    drivers: Drivers,
    eventStore: EventStore,
    globalScriptDataStore: JsonDataStore,
    configDataStore: JsonDataStore,
    featureDataStore: JsonDataStore,
    userDataStore: JsonDataStore,
    apikeyDataStore: JsonDataStore,
    webhookDataStore: JsonDataStore,
    experimentDataStore: JsonDataStore,
    experimentVariantEventService: ExperimentVariantEventService,
    getScriptCache: () => ScriptCache,
    blocking: Blocking.Service[Any],
    override val clock: Clock.Service[Any],
    override val authInfo: Option[AuthInfo]
) extends GlobalContext {
  override def scriptCache = getScriptCache()
  override def withAuthInfo(authInfo: Option[AuthInfo]): GlobalContext =
    this.copy(authInfo = authInfo)
}

object GlobalContext {

  def apply(izanamiConfiguration: IzanamiConfig,
            zioEventStore: EventStore,
            actorSystem: ActorSystem,
            materializer: Materializer,
            env: Environment,
            client: play.api.libs.ws.WSClient,
            javaClient: play.libs.ws.WSClient,
            execCtx: ExecutionContext,
            registry: MetricRegistry,
            d: Drivers,
            playScriptCache: => ScriptCache,
            lifecycle: ApplicationLifecycle): GlobalContext = new GlobalContext {

    override implicit val system: ActorSystem               = actorSystem
    override implicit val mat: Materializer                 = materializer
    override val izanamiConfig: IzanamiConfig               = izanamiConfiguration
    override val environment: Environment                   = env
    override val wSClient: play.api.libs.ws.WSClient        = client
    override val javaWsClient: play.libs.ws.WSClient        = javaClient
    override val ec: ExecutionContext                       = execCtx
    override def applicationLifecycle: ApplicationLifecycle = lifecycle
    override val logger: Logger                             = new ProdLogger()
    override val eventStore: EventStore                     = zioEventStore
    override val metricRegistry: MetricRegistry             = registry
    override val drivers: Drivers                           = d

    override val globalScriptDataStore: JsonDataStore = {
      val conf              = izanamiConfig.globalScript.db
      lazy val eventAdapter = InMemoryWithDbStore.globalScriptEventAdapter
      JsonDataStore(drivers, izanamiConfig, conf, eventAdapter, applicationLifecycle)
    }

    override val configDataStore: JsonDataStore = {
      val conf              = izanamiConfig.config.db
      lazy val eventAdapter = InMemoryWithDbStore.configEventAdapter
      JsonDataStore(drivers, izanamiConfig, conf, eventAdapter, applicationLifecycle)
    }

    override val featureDataStore: JsonDataStore = {
      val conf              = izanamiConfig.features.db
      lazy val eventAdapter = InMemoryWithDbStore.featureEventAdapter
      JsonDataStore(drivers, izanamiConfig, conf, eventAdapter, applicationLifecycle)
    }

    override val userDataStore: JsonDataStore = {
      val conf              = izanamiConfig.user.db
      lazy val eventAdapter = InMemoryWithDbStore.userEventAdapter
      JsonDataStore(drivers, izanamiConfig, conf, eventAdapter, applicationLifecycle)
    }

    override val apikeyDataStore: JsonDataStore = {
      val conf              = izanamiConfig.apikey.db
      lazy val eventAdapter = InMemoryWithDbStore.apikeyEventAdapter
      JsonDataStore(drivers, izanamiConfig, conf, eventAdapter, applicationLifecycle)
    }

    override val webhookDataStore: JsonDataStore = {
      lazy val conf         = izanamiConfig.webhook.db
      lazy val eventAdapter = InMemoryWithDbStore.webhookEventAdapter
      JsonDataStore(drivers, izanamiConfig, conf, eventAdapter, applicationLifecycle)
    }

    override val experimentDataStore: JsonDataStore = {
      val conf              = izanamiConfig.experiment.db
      lazy val eventAdapter = InMemoryWithDbStore.experimentEventAdapter
      JsonDataStore(drivers, izanamiConfig, conf, eventAdapter, applicationLifecycle)
    }

    override val experimentVariantEventService: ExperimentVariantEventService =
      ExperimentVariantEventService(izanamiConfig, drivers, applicationLifecycle)

    override def scriptCache: ScriptCache = playScriptCache

    override val blocking: Blocking.Service[Any] = new Blocking.Service[Any] {
      def blockingExecutor: ZIO[Any, Nothing, Executor] =
        ZIO.succeed(Executor.fromExecutionContext(20)(actorSystem.dispatchers.lookup("izanami.blocking-dispatcher")))
    }

    override def authInfo: Option[AuthInfo] = None

    override def withAuthInfo(authInfo: Option[AuthInfo]): GlobalContext =
      ProdGlobalContext(
        system,
        mat,
        izanamiConfig,
        environment,
        wSClient,
        javaWsClient,
        ec,
        applicationLifecycle,
        logger,
        metricRegistry,
        drivers,
        eventStore,
        globalScriptDataStore,
        configDataStore,
        featureDataStore,
        userDataStore,
        apikeyDataStore,
        webhookDataStore,
        experimentDataStore,
        experimentVariantEventService,
        () => scriptCache,
        blocking,
        clock,
        authInfo
      )
  }
}

sealed trait AuthorizedPatternTag

object AuthorizedPattern {

  import shapeless.tag
  import shapeless.tag.@@

  type AuthorizedPattern = String @@ AuthorizedPatternTag

  def apply(str: String): AuthorizedPattern = tag[AuthorizedPatternTag][String](str)

  implicit val reads: Reads[AuthorizedPattern]   = __.read[String](pattern("^[\\w@\\.0-9\\-,:\\*]+$".r)).map(apply _)
  implicit val writes: Writes[AuthorizedPattern] = Writes[AuthorizedPattern](JsString.apply)
}

object Import {
  import akka.stream.scaladsl.{Flow, Framing}
  val newLineSplit =
    Framing.delimiter(ByteString("\n"), 10000, allowTruncation = true)
  val toJson = Flow[ByteString] via newLineSplit map (_.utf8String) filterNot (_.isEmpty) map (l => (l, Json.parse(l)))

  def ndJson(implicit ec: ExecutionContext): BodyParser[Source[(String, JsValue), _]] =
    BodyParser { _ =>
      Accumulator.source[ByteString].map(s => Right(s.via(toJson)))
    }

  def importFile[Ctx <: LoggerModule](
      db: DbDomainConfig,
      process: RIO[Ctx, Flow[(String, JsValue), ImportResult, NotUsed]]
  )(implicit materializer: Materializer): RIO[Ctx, Unit] =
    process.flatMap { proc =>
      import zio.interop.catz._
      import cats.implicits._
      db.`import`.traverse { p =>
        Logger.info(s"Importing file $p for namespace ${db.conf.namespace}") *>
        Task.fromFuture { implicit ec =>
          val res = FileIO.fromPath(p).via(toJson).via(proc).runWith(Sink.head)
          res.onComplete {
            case Success(res) if res.isError =>
              IzanamiLogger.info(
                s"Import end with error for file $p and namespace ${db.conf.namespace}: \n ${res.errors}"
              )
            case Success(_) =>
              IzanamiLogger.info(s"Import end with success for file $p and namespace ${db.conf.namespace}")
            case Failure(e) =>
              IzanamiLogger.error(s"Import end with error for file $p and namespace ${db.conf.namespace}", e)
          }
          res
        }
      }.unit
    }
}

case class ImportResult(success: Int = 0, errors: ValidationErrors = ValidationErrors()) {
  def isError = !errors.isEmpty
}

object ImportResult {
  import cats.syntax.semigroup._

  implicit val format = Json.format[ImportResult]

  implicit val monoid = new Monoid[ImportResult] {
    override def empty = ImportResult()
    override def combine(x: ImportResult, y: ImportResult) = (x, y) match {
      case (ImportResult(s1, e1), ImportResult(s2, e2)) =>
        ImportResult(s1 + s2, e1 |+| e2)
    }
  }

  def error(key: String, arg: String*) =
    ImportResult(errors = ValidationErrors(errors = Seq(ErrorMessage(key, arg: _*))))
  def error(e: ErrorMessage) = ImportResult(errors = ValidationErrors(errors = Seq(e)))

//  def fromResult[T](r: Result[T]): ImportResult = r match {
//    case Right(_)  => ImportResult(success = 1)
//    case Left(err) => ImportResult(errors = err)
//  }

  def fromResult[T](r: Either[IzanamiErrors, T]): ImportResult = r match {
    case Right(_)                    => ImportResult(success = 1)
    case Left(err: ValidationErrors) => ImportResult(errors = err)
    case Left(IdMustBeTheSame(_, inParam)) =>
      ImportResult(errors = ValidationErrors.error("error.id.not.the.same", inParam.key, inParam.key))
    case Left(DataShouldExists(id))    => ImportResult(errors = ValidationErrors.error("error.data.missing", id.key))
    case Left(DataShouldNotExists(id)) => ImportResult(errors = ValidationErrors.error("error.data.exists", id.key))
  }

}

trait AuthInfo {
  def authorizedPattern: String
  def id: String
  def name: String
  def mayBeEmail: Option[String]
}

object AuthInfo {
  def authInfo: zio.URIO[AuthInfoModule[_], Option[AuthInfo]] = ZIO.access[AuthInfoModule[_]](_.authInfo)

  import play.api.libs.json._
  import play.api.libs.functional.syntax._

  implicit val format = Format(
    {
      (
        (__ \ "id").read[String] and
        (__ \ "authorizedPattern").read[String] and
        (__ \ "name").read[String] and
        (__ \ "mayBeEmail").readNullable[String]
      )(
        (anId: String, aPattern: String, aName: String, anEmail: Option[String]) =>
          new AuthInfo {
            def authorizedPattern: String  = aPattern
            def id: String                 = anId
            def name: String               = aName
            def mayBeEmail: Option[String] = anEmail
        }
      )
    }, {
      (
        (__ \ "id").write[String] and
        (__ \ "authorizedPattern").write[String] and
        (__ \ "name").write[String] and
        (__ \ "mayBeEmail").writeNullable[String]
      )(unlift[AuthInfo, (String, String, String, Option[String])] { info =>
        Some((info.id, info.authorizedPattern, info.name, info.mayBeEmail))
      })
    }
  )
}

trait Jsoneable {
  def toJson: JsValue
}

object Domain {
  sealed trait Domain
  case object Experiment extends Domain
  case object ApiKey     extends Domain
  case object Config     extends Domain
  case object Feature    extends Domain
  case object User       extends Domain
  case object Script     extends Domain
  case object Webhook    extends Domain
  case object Unknown    extends Domain

  val reads: Reads[Domain] = Reads[Domain] {
    case JsString(s) if s === "Experiment" => JsSuccess(Experiment)
    case JsString(s) if s === "ApiKey"     => JsSuccess(ApiKey)
    case JsString(s) if s === "Config"     => JsSuccess(Config)
    case JsString(s) if s === "Feature"    => JsSuccess(Feature)
    case JsString(s) if s === "User"       => JsSuccess(User)
    case JsString(s) if s === "Webhook"    => JsSuccess(Webhook)
    case JsString(s) if s === "Unknown"    => JsSuccess(Unknown)
    case _                                 => JsError("domain.invalid")
  }

  val writes: Writes[Domain] = Writes[Domain] {
    case Experiment => JsString("Experiment")
    case ApiKey     => JsString("ApiKey")
    case Config     => JsString("Config")
    case Feature    => JsString("Feature")
    case User       => JsString("User")
    case Script     => JsString("Script")
    case Webhook    => JsString("Webhook")
    case Unknown    => JsString("Unknown")
  }

  implicit val format: Format[Domain] = Format(reads, writes)
}

case class Key(key: String) {

  def matchPattern(str: String): Boolean = {
    val regex = Key.buildRegexPattern(str)
    key.matches(regex)
  }
  def matchPattern(pattern: Pattern): Boolean =
    pattern match {
      case EmptyPattern => false
      case StringPattern(str) =>
        val regex = Key.buildRegexPattern(str)
        key.matches(regex)
    }

  def matchAllPatterns(str: String*): Boolean =
    str.forall(s => matchPattern(s))

  def matchOneStrPatterns(str: String*): Boolean =
    str.exists(matchPattern)

  def matchOnePatterns(str: Pattern*): Boolean =
    str.exists(matchPattern)

  def /(path: String): Key = key match {
    case "" => Key(s"$path")
    case _  => Key(s"$key:$path")
  }

  def /(path: Key): Key = key match {
    case "" => path
    case _  => Key(s"$key:${path.key}")
  }

  val segments: Seq[String] = key.split(":").toIndexedSeq

  val jsPath: JsPath = segments.foldLeft[JsPath](JsPath) { (p, s) =>
    p \ s
  }

  def dropHead: Key = Key(segments.tail)

  def drop(prefix: String): Key =
    if (key.startsWith(prefix)) {
      val newKey = key.drop(prefix.length)
      if (newKey.startsWith(":")) {
        Key(newKey.drop(1))
      } else {
        Key(newKey)
      }
    } else {
      this
    }
}

case class Node(id: Key, key: String, childs: List[Node] = Nil, value: Option[JsValue] = None)

object Node {
  implicit val format = Json.format[Node]

  def valuesToNodes[T](vals: List[(Key, T)])(implicit writes: Writes[T]): List[Node] =
    deepMerge(Key.Empty, vals.map {
      case (k, v) => keyValueToNodes(k, k.segments.toList, writes.writes(v))
    })

  def valuesToNodes(vals: List[(Key, JsValue)]): List[Node] =
    deepMerge(Key.Empty, vals.map {
      case (k, v) => keyValueToNodes(k, k.segments.toList, v)
    })

  def keyValueToNodes(key: Key, segments: List[String], jsValue: JsValue): Node =
    segments match {
      case Nil          => throw new IllegalArgumentException("Should not append")
      case head :: Nil  => Node(key / head, head, Nil, Some(jsValue))
      case head :: tail => Node(key / head, head, List(keyValueToNodes(key / head, tail, jsValue)), None)
    }

  def deepMerge(key: Key, values: List[Node]): List[Node] =
    values
      .groupBy(_.key)
      .map {
        case (k, nodes) =>
          val value      = nodes.flatMap(_.value).headOption
          val currentKey = key / k
          Node(currentKey, k, deepMerge(currentKey, nodes.flatMap(_.childs)), value)
      }
      .toList
}

trait IsAllowed[T] {
  def isAllowed(value: T)(auth: Option[AuthInfo]): Boolean

  def isAllowed[R](value: T, auth: Option[AuthInfo])(ifNotAllowed: => R): zio.IO[R, Unit] =
    isAllowed(value)(auth) match {
      case true  => zio.IO.succeed(())
      case false => zio.IO.fail(ifNotAllowed)
    }

}

object IsAllowed {
  def apply[T](implicit IsAllowed: IsAllowed[T]): IsAllowed[T] = IsAllowed
}

object Key {

  import play.api.libs.json.Reads._
  import play.api.libs.json._

  val Empty: Key = Key("")

  def apply(path: Seq[String]): Key = new Key(path.mkString(":"))

  private[domains] def buildRegexPattern(pattern: String): String =
    if (pattern.isEmpty) "$^"
    else {
      val newPattern = pattern.replaceAll("\\*", ".*")
      s"^$newPattern$$"
    }

  private[domains] def buildRegex(pattern: String): Regex =
    buildRegexPattern(pattern).r

  def isAllowed(key: Key)(auth: Option[AuthInfo]): Boolean = {
    val pattern = buildRegex(auth.map(_.authorizedPattern).getOrElse(""))
    key.key match {
      case pattern(_*) => true
      case _           => false
    }
  }

  def isAllowed[R](key: Key, auth: Option[AuthInfo])(ifNotAllowed: => R): zio.IO[R, Unit] =
    isAllowed(key)(auth) match {
      case true  => zio.IO.succeed(())
      case false => zio.ZIO.fail(ifNotAllowed)
    }

  def isAllowed(patternToCheck: String)(auth: Option[AuthInfo]): Boolean = {
    val pattern = buildRegex(auth.map(_.authorizedPattern).getOrElse(""))
    patternToCheck match {
      case pattern(_*) => true
      case _           => false
    }
  }

  val reads: Reads[Key] =
    __.read[String](pattern("(([\\w@\\.0-9\\-]+)(:?))+".r)).map(Key.apply)
  val writes: Writes[Key] = Writes[Key] { k =>
    JsString(k.key)
  }

  implicit val format: Format[Key] = Format(reads, writes)

  implicit val eqKey: Eq[Key] = new Eq[Key] {
    override def eqv(x: Key, y: Key): Boolean = x.key.equals(y.key)
  }

}
