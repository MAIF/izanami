package domains

import akka.NotUsed
import akka.stream.scaladsl.{Flow, Source}
import domains.configuration.PlayModule
import domains.auth.AuthInfo
import domains.events.EventStore
import libs.logs.ZLogger
import play.api.libs.json._
import domains.errors.IzanamiErrors
import store._
import store.datastore._
import zio.{Has, Layer, RIO, Task, ULayer, URIO, ZIO, ZLayer}

import scala.reflect.ClassTag
import domains.errors.{DataShouldExists, IdMustBeTheSame}
import domains.script.{GlobalScriptDataStore, PlayScriptCache}
import domains.script.RunnableScriptModule.RunnableScriptModuleProd
import env.IzanamiConfig
import env.configuration.IzanamiConfigModule
import javax.script.{Invocable, ScriptEngine, ScriptEngineManager}
import libs.database.Drivers
import org.jetbrains.kotlin.script.jsr223.KotlinJsr223JvmLocalScriptEngineFactory
import store.memorywithdb.InMemoryWithDbStore
import zio.blocking.Blocking

package object script {

  sealed trait Script
  final case class JavascriptScript(script: String) extends Script
  final case class ScalaScript(script: String)      extends Script
  final case class KotlinScript(script: String)     extends Script

  sealed trait ScriptExecution
  final case class ScriptExecutionSuccess(result: Boolean, logs: Seq[String] = Seq.empty) extends ScriptExecution
  final case class ScriptExecutionFailure(logs: Seq[String] = Seq.empty, stacktrace: Seq[String] = Seq.empty)
      extends ScriptExecution

  object ScriptExecutionFailure {
    def fromThrowable(logs: Seq[String] = Seq.empty, e: Throwable): ScriptExecutionFailure =
      ScriptExecutionFailure(
        logs,
        Option(e.getCause).toSeq.flatMap { c =>
          Seq(c.toString) ++ c.getStackTrace.map(stackElt => s"  ${stackElt.toString}")
        } ++ Seq(e.toString) ++ e.getStackTrace.map(stackElt => s"  ${stackElt.toString}")
      )
  }

  type ScriptCache = zio.Has[ScriptCache.Service]

  object ScriptCache {
    trait Service {
      def scriptCache: CacheService[String]
    }

    def value(cache: CacheService[String]): ULayer[ScriptCache] =
      ZLayer.succeed(new Service {
        override def scriptCache: CacheService[String] = cache
      })

    case class PlayScriptCacheProd(scriptCache: CacheService[String]) extends Service

    val live: ZLayer[PlayModule, Nothing, ScriptCache] = ZLayer.fromFunction { mix =>
      val defaultCacheApi = mix.get.defaultCacheApi
      lazy val cache      = new PlayScriptCache(defaultCacheApi)
      PlayScriptCacheProd(cache)
    }

    def apply[F[_]](implicit s: ScriptCache): ScriptCache = s

    def get[T: ClassTag](id: String): ZIO[ScriptCache, Throwable, Option[T]] =
      ZIO.accessM[ScriptCache](_.get.scriptCache.get[T](id))

    def set[T: ClassTag](id: String, t: T): ZIO[ScriptCache, Throwable, Unit] =
      ZIO.accessM[ScriptCache](_.get.scriptCache.set[T](id, t))
  }

  case class ScriptLogs(logs: Seq[String] = Seq.empty)

  type RunnableScriptModule = zio.Has[RunnableScriptModule.Service]

  object RunnableScriptModule {
    trait Service {
      def scriptEngineManager: ScriptEngineManager
      def javascriptScriptEngine: ScriptEngine with Invocable
      def scalaScriptEngine: Option[ScriptEngine with Invocable]
      def kotlinScriptEngine: ScriptEngine
    }

    case class RunnableScriptModuleProd(scriptEngineManager: ScriptEngineManager,
                                        javascriptScriptEngine: ScriptEngine with Invocable,
                                        scalaScriptEngine: Option[ScriptEngine with Invocable],
                                        kotlinScriptEngine: ScriptEngine)
        extends Service

    object RunnableScriptModuleProd {
      def apply(classLoader: ClassLoader): RunnableScriptModuleProd = {
        val scriptEngineManager = new ScriptEngineManager(classLoader)
        val javascriptScriptEngine =
          scriptEngineManager.getEngineByName("nashorn").asInstanceOf[ScriptEngine with Invocable]
        val scalaScriptEngine: Option[ScriptEngine with Invocable] = Option(
          scriptEngineManager.getEngineByName("scala").asInstanceOf[ScriptEngine with Invocable]
        )
        lazy val kotlinScriptEngine: ScriptEngine = new KotlinJsr223JvmLocalScriptEngineFactory().getScriptEngine
        RunnableScriptModuleProd(scriptEngineManager, javascriptScriptEngine, scalaScriptEngine, kotlinScriptEngine)
      }
    }

    def scriptEngineManager: URIO[RunnableScriptModule, ScriptEngineManager] =
      ZIO.access[RunnableScriptModule](_.get.scriptEngineManager)
    def javascriptScriptEngine: URIO[RunnableScriptModule, ScriptEngine with Invocable] =
      ZIO.access[RunnableScriptModule](_.get.javascriptScriptEngine)
    def scalaScriptEngine: URIO[RunnableScriptModule, Option[ScriptEngine with Invocable]] =
      ZIO.access[RunnableScriptModule](_.get.scalaScriptEngine)
    def kotlinScriptEngine: URIO[RunnableScriptModule, ScriptEngine] =
      ZIO.access[RunnableScriptModule](_.get.kotlinScriptEngine)

    val live: ZLayer[PlayModule, Nothing, RunnableScriptModule] = ZLayer.fromFunction { mix =>
      RunnableScriptModuleProd(mix.get.environment.classLoader)
    }
    def value(runnableScriptModule: RunnableScriptModuleProd): ULayer[RunnableScriptModule] =
      ZLayer.succeed(runnableScriptModule)
  }

//  trait RunnableScriptContext extends ScriptCacheModule with Blocking with ZLogger with PlayModule {
//    lazy val scriptEngineManager = new ScriptEngineManager(environment.classLoader)
//    lazy val javascriptScriptEngine: ScriptEngine with Invocable =
//      scriptEngineManager.getEngineByName("nashorn").asInstanceOf[ScriptEngine with Invocable]
//    lazy val scalaScriptEngine: Option[ScriptEngine with Invocable] = Option(
//      scriptEngineManager.getEngineByName("scala").asInstanceOf[ScriptEngine with Invocable]
//    )
//    lazy val kotlinScriptEngine: ScriptEngine = new KotlinJsr223JvmLocalScriptEngineFactory().getScriptEngine
//  }
  type RunnableScriptContext = RunnableScriptModule with ZLogger with PlayModule with Blocking with ScriptCache

  trait RunnableScript[S] {
    def run(script: S, context: JsObject): RIO[RunnableScriptContext, ScriptExecution]
  }

  object syntax {
    implicit class RunnableScriptOps[S](script: S) {
      def run(context: JsObject)(
          implicit runnableScript: RunnableScript[S]
      ): RIO[RunnableScriptContext, ScriptExecution] =
        runnableScript.run(script, context)
    }
  }

  case class GlobalScript(id: Key, name: String, description: String, source: Script)

  object GlobalScript {
    type GlobalScriptKey = Key
  }

  type GlobalScriptDataStore = zio.Has[GlobalScriptDataStore.Service]

  object GlobalScriptDataStore {
    trait Service {
      def globalScriptDataStore: JsonDataStore.Service
    }

    case class GlobalScriptDataStoreProd(globalScriptDataStore: JsonDataStore.Service) extends Service

    object > extends JsonDataStoreHelper[GlobalScriptDataStore with DataStoreContext] {
      override def getStore: URIO[GlobalScriptDataStore with DataStoreContext, JsonDataStore.Service] =
        ZIO.access[GlobalScriptDataStore with DataStoreContext](
          _.get[GlobalScriptDataStore.Service].globalScriptDataStore
        )
    }

    def value(globalScriptDataStore: JsonDataStore.Service): ZLayer[Any, Nothing, GlobalScriptDataStore] =
      ZLayer.succeed(GlobalScriptDataStoreProd(globalScriptDataStore))

    def live(izanamiConfig: IzanamiConfig): ZLayer[DataStoreLayerContext, Throwable, GlobalScriptDataStore] =
      JsonDataStore
        .live(izanamiConfig, c => c.globalScript.db, InMemoryWithDbStore.globalScriptEventAdapter)
        .map(s => Has(GlobalScriptDataStoreProd(s.get)))
  }

  type GlobalScriptContext = ZLogger
    with PlayModule
    with EventStore
    with ScriptCache
    with GlobalScriptDataStore
    with RunnableScriptModule
    with AuthInfo
    with Blocking

  object GlobalScriptService {

    import cats.implicits._
    import zio._
    import GlobalScript._
    import GlobalScriptInstances._
    import libs.ziohelper.JsResults._
    import domains.events.Events._
    import IzanamiErrors._

    def create(id: GlobalScriptKey, data: GlobalScript): ZIO[GlobalScriptContext, IzanamiErrors, GlobalScript] =
      for {
        _        <- AuthorizedPatterns.isAllowed(id, PatternRights.C)
        _        <- IO.when(data.id =!= id)(IO.fail(IdMustBeTheSame(data.id, id).toErrors))
        created  <- GlobalScriptDataStore.>.create(id, GlobalScriptInstances.format.writes(data))
        apikey   <- jsResultToError(created.validate[GlobalScript])
        authInfo <- AuthInfo.authInfo
        _        <- EventStore.publish(GlobalScriptCreated(id, apikey, authInfo = authInfo))
      } yield apikey

    def update(oldId: GlobalScriptKey,
               id: GlobalScriptKey,
               data: GlobalScript): ZIO[GlobalScriptContext, IzanamiErrors, GlobalScript] =
      // format: off
      for {
        _           <- AuthorizedPatterns.isAllowed(id, PatternRights.U)
        mayBeScript <- getById(oldId)
        oldValue    <- ZIO.fromOption(mayBeScript).mapError(_ => DataShouldExists(oldId).toErrors)
        updated     <- GlobalScriptDataStore.>.update(oldId, id, GlobalScriptInstances.format.writes(data))
        apikey      <- jsResultToError(updated.validate[GlobalScript])
        authInfo    <- AuthInfo.authInfo
        _           <- EventStore.publish(GlobalScriptUpdated(id, oldValue, apikey, authInfo = authInfo))
      } yield apikey
      // format: on

    def delete(id: GlobalScriptKey): ZIO[GlobalScriptContext, IzanamiErrors, GlobalScript] =
      // format: off
      for {
        _         <- AuthorizedPatterns.isAllowed(id, PatternRights.D)
        deleted   <- GlobalScriptDataStore.>.delete(id)
        apikey    <- jsResultToError(deleted.validate[GlobalScript])
        authInfo  <- AuthInfo.authInfo
        _         <- EventStore.publish(GlobalScriptDeleted(id, apikey, authInfo = authInfo))
      } yield apikey
      // format: on

    def deleteAll(query: Query): ZIO[GlobalScriptContext, IzanamiErrors, Unit] =
      GlobalScriptDataStore.>.deleteAll(query)

    def getById(id: GlobalScriptKey): ZIO[GlobalScriptContext, IzanamiErrors, Option[GlobalScript]] =
      for {
        _            <- AuthorizedPatterns.isAllowed(id, PatternRights.R)
        mayBeScript  <- GlobalScriptDataStore.>.getById(id).orDie
        parsedScript = mayBeScript.flatMap(_.validate[GlobalScript].asOpt)
      } yield parsedScript

    def findByQuery(query: Query,
                    page: Int = 1,
                    nbElementPerPage: Int = 15): RIO[GlobalScriptContext, PagingResult[GlobalScript]] =
      GlobalScriptDataStore.>.findByQuery(query, page, nbElementPerPage)
        .map(jsons => JsonPagingResult(jsons))

    def findByQuery(query: Query): RIO[GlobalScriptContext, Source[(GlobalScriptKey, GlobalScript), NotUsed]] =
      GlobalScriptDataStore.>.findByQuery(query).map { s =>
        s.map {
          case (k, v) => (k, v.validate[GlobalScript].get)
        }
      }

    def count(query: Query): RIO[GlobalScriptContext, Long] =
      GlobalScriptDataStore.>.count(query)

    def importData(
        strategy: ImportStrategy = ImportStrategy.Keep
    ): RIO[GlobalScriptContext, Flow[(String, JsValue), ImportResult, NotUsed]] =
      ImportData
        .importDataFlow[GlobalScriptContext, GlobalScriptKey, GlobalScript](
          strategy,
          _.id,
          key => getById(key),
          (key, data) => create(key, data),
          (key, data) => update(key, key, data)
        )(GlobalScriptInstances.format)

    val eventAdapter = Flow[IzanamiEvent].collect {
      case GlobalScriptCreated(_, _, _, _, _) =>
    }
  }

  trait CacheService[K] {

    def get[T: ClassTag](id: K): Task[Option[T]]

    def set[T: ClassTag](id: K, value: T): Task[Unit]

  }
}
