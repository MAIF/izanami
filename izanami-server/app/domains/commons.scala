package domains

import akka.actor.ActorSystem
import akka.NotUsed
import akka.stream.Materializer
import akka.stream.scaladsl.{FileIO, Sink, Source}
import akka.util.ByteString
import cats.implicits._
import cats.kernel.Eq
import cats.kernel.Monoid
import env.configuration.IzanamiConfigModule
import domains.abtesting.ExperimentDataStore
import domains.abtesting.events.ExperimentVariantEventService
import domains.apikey.ApikeyDataStore
import domains.config.ConfigDataStore
import domains.auth.{AuthInfo, Oauth2Service}
import domains.events.EventStore
import domains.feature.FeatureDataStore
import domains.script.{GlobalScriptDataStore, RunnableScriptModule, ScriptCache}
import domains.user.UserDataStore
import domains.webhook.WebhookDataStore
import env.{DbDomainConfig, IzanamiConfig}
import libs.logs.{IzanamiLogger, ZLogger}
import play.api.{Configuration, Environment}
import play.api.inject.ApplicationLifecycle
import play.api.libs.json._
import play.api.libs.streams.Accumulator
import play.api.mvc.BodyParser

import scala.concurrent.ExecutionContext
import scala.util.{Failure, Success}
import scala.util.matching.Regex
import store.{EmptyPattern, Pattern, StringPattern}
import errors._
import libs.http.HttpContext
import zio.{RIO, Runtime, Task, ULayer, URIO, ZEnv, ZIO, ZLayer}
import zio.blocking.Blocking
import zio.clock.Clock
import metrics.{MetricsModule, MetricsModules}
import play.api.cache.AsyncCacheApi
import play.api.libs.ws.WSClient
import play.libs.ws
import play.libs.ws.ahc.AhcWSClient
import play.shaded.ahc.org.asynchttpclient.AsyncHttpClient
import store.datastore.DataStoreLayerContext

package object configuration {

  type PlayModule = zio.Has[PlayModule.Service]

  object PlayModule {
    trait Service {
      def system: ActorSystem
      def mat: Materializer
      def defaultCacheApi: AsyncCacheApi
      def configuration: Configuration
      def environment: Environment
      def wSClient: play.api.libs.ws.WSClient
      def javaWsClient: play.libs.ws.WSClient
      def ec: ExecutionContext
      def applicationLifecycle: ApplicationLifecycle
    }

    case class PlayModuleProd(
        system: ActorSystem,
        mat: Materializer,
        defaultCacheApi: AsyncCacheApi,
        configuration: Configuration,
        environment: Environment,
        wSClient: play.api.libs.ws.WSClient,
        javaWsClient: play.libs.ws.WSClient,
        ec: ExecutionContext,
        applicationLifecycle: ApplicationLifecycle
    ) extends Service

    def live(playModuleProd: PlayModuleProd): ULayer[PlayModule] = ZLayer.succeed(playModuleProd)

    def live(system: ActorSystem,
             mat: Materializer,
             defaultCacheApi: AsyncCacheApi,
             configuration: Configuration,
             environment: Environment,
             wSClient: play.api.libs.ws.WSClient,
             ec: ExecutionContext,
             applicationLifecycle: ApplicationLifecycle): ULayer[PlayModule] = ZLayer.succeed(
      PlayModuleProd(system,
                     mat,
                     defaultCacheApi,
                     configuration,
                     environment,
                     wSClient,
                     new AhcWSClient(wSClient.underlying[AsyncHttpClient], mat),
                     ec,
                     applicationLifecycle)
    )

    def mat: URIO[PlayModule, Materializer]         = ZIO.access[PlayModule](_.get.mat)
    def system: URIO[PlayModule, ActorSystem]       = ZIO.access[PlayModule](_.get.system)
    def environment: URIO[PlayModule, Environment]  = ZIO.access[PlayModule](_.get.environment)
    def wSClient: URIO[PlayModule, WSClient]        = ZIO.access[PlayModule](_.get.wSClient)
    def javaWsClient: URIO[PlayModule, ws.WSClient] = ZIO.access[PlayModule](_.get.javaWsClient)
    def ec: URIO[PlayModule, ExecutionContext]      = ZIO.access[PlayModule](_.get.ec)
    def applicationLifecycle: URIO[PlayModule, ApplicationLifecycle] =
      ZIO.access[PlayModule](_.get.applicationLifecycle)
  }

  type GlobalContext = PlayModule
    with IzanamiConfigModule
    with MetricsModule
    with MetricsModules
    with AuthInfo
    with ZLogger
    with ExperimentDataStore
    with ExperimentVariantEventService
    with ApikeyDataStore
    with ConfigDataStore
    with FeatureDataStore
    with GlobalScriptDataStore
    with UserDataStore
    with WebhookDataStore
    with EventStore
    with ScriptCache
    with RunnableScriptModule
    with Oauth2Service
    with Clock
    with Blocking

  object GlobalContext {
    def live(system: ActorSystem,
             mat: Materializer,
             defaultCacheApi: AsyncCacheApi,
             configuration: Configuration,
             environment: Environment,
             wSClient: play.api.libs.ws.WSClient,
             ec: ExecutionContext,
             izanamiConfig: IzanamiConfig,
             applicationLifecycle: ApplicationLifecycle): HttpContext[GlobalContext] = {

      val playModule =
        PlayModule.live(system, mat, defaultCacheApi, configuration, environment, wSClient, ec, applicationLifecycle)

      val izanamiConfigModule = IzanamiConfigModule.value(izanamiConfig)

      val configAndScript
        : ZLayer[ZEnv,
                 Throwable,
                 ScriptCache with RunnableScriptModule with MetricsModule with ZLogger with Oauth2Service] =
      playModule >>> (ScriptCache.live ++ RunnableScriptModule.live ++ MetricsModule.live ++ ZLogger.live ++ Oauth2Service
        .live(izanamiConfig))

      val dataStoreLayerContext: ZLayer[ZEnv, Throwable, DataStoreLayerContext] =
      playModule ++ izanamiConfigModule ++ ZLogger.live

      val stores: ZLayer[
        ZEnv,
        Throwable,
        MetricsModules with EventStore with ExperimentDataStore with ExperimentVariantEventService with ApikeyDataStore with ConfigDataStore with FeatureDataStore with GlobalScriptDataStore with UserDataStore with WebhookDataStore
      ] =
      dataStoreLayerContext >>> (
        MetricsModules.allMetricsModules(izanamiConfig) ++
        EventStore.live(izanamiConfig) ++
        ExperimentDataStore.live(izanamiConfig) ++
        ExperimentVariantEventService.live(izanamiConfig) ++
        ApikeyDataStore.live(izanamiConfig) ++
        ConfigDataStore.live(izanamiConfig) ++
        FeatureDataStore.live(izanamiConfig) ++
        GlobalScriptDataStore.live(izanamiConfig) ++
        UserDataStore.live(izanamiConfig) ++
        WebhookDataStore.live(izanamiConfig)
      )

      playModule ++
      izanamiConfigModule ++
      AuthInfo.empty ++
      ZLogger.live ++
      stores ++
      configAndScript ++
      Clock.live ++ Blocking.live
    }

  }
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

  def importFile[Ctx <: ZLogger with IzanamiConfigModule](
      getDb: IzanamiConfig => DbDomainConfig,
      process: RIO[Ctx, Flow[(String, JsValue), ImportResult, NotUsed]]
  )(implicit materializer: Materializer): RIO[Ctx, Unit] =
    for {
      proc          <- process
      izanamiConfig <- IzanamiConfigModule.izanamiConfig
      db            = getDb(izanamiConfig)
      res <- {
        import zio.interop.catz._
        import cats.implicits._
        db.`import`.traverse { p =>
          ZLogger.info(s"Importing file $p for namespace ${db.conf.namespace}") *>
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
    } yield res
}

case class ImportResult(success: Int = 0, errors: List[IzanamiError] = List.empty) {
  def isError = !errors.isEmpty
}

object ImportResult {
  import cats.syntax.semigroup._

  implicit val monoid = new Monoid[ImportResult] {
    override def empty = ImportResult()
    override def combine(x: ImportResult, y: ImportResult) = (x, y) match {
      case (ImportResult(s1, e1), ImportResult(s2, e2)) =>
        ImportResult(s1 + s2, e1 |+| e2)
    }
  }

  def error(key: String, arg: String*) =
    ImportResult(errors = List(ValidationError(errors = Seq(ErrorMessage(key, arg: _*)))))
  def error(e: ErrorMessage) = ImportResult(errors = List(ValidationError(errors = Seq(e))))

  def fromResult[T](r: Either[IzanamiErrors, T]): ImportResult = r match {
    case Right(_)     => ImportResult(success = 1)
    case Left(errors) => ImportResult(errors = errors.toList)
  }

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
  def isAllowed(value: T, right: PatternRights)(auth: Option[AuthInfo.Service]): Boolean

  def isAllowed[R](value: T, right: PatternRights, auth: Option[AuthInfo.Service])(
      ifNotAllowed: => R
  ): zio.IO[R, Unit] =
    isAllowed(value, right)(auth) match {
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
