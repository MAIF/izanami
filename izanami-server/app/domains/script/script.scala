package domains.script

import akka.NotUsed
import akka.stream.scaladsl.{Flow, Source}
import domains.{
  AuthInfo,
  AuthInfoModule,
  AuthorizedPatterns,
  ImportData,
  ImportResult,
  ImportStrategy,
  Key,
  PatternRights,
  PlayModule
}
import domains.events.{EventStore, EventStoreContext}
import domains.script.Script.ScriptCache
import libs.logs.LoggerModule
import play.api.libs.json._
import domains.errors.IzanamiErrors
import store._
import zio.blocking.Blocking
import zio.{RIO, Task, ZIO}

import scala.reflect.ClassTag
import domains.errors.{DataShouldExists, IdMustBeTheSame}
import javax.script.{Invocable, ScriptEngine, ScriptEngineManager}
import org.jetbrains.kotlin.script.jsr223.KotlinJsr223JvmLocalScriptEngineFactory

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

object Script {

  type ScriptCache = CacheService[String]

  object ScriptCache {
    def apply[F[_]](implicit s: ScriptCache): ScriptCache = s
  }

}

trait ScriptCacheModule {
  def scriptCache: ScriptCache
}

case class ScriptLogs(logs: Seq[String] = Seq.empty)

trait RunnableScriptContext extends ScriptCacheModule with Blocking with LoggerModule with PlayModule {
  lazy val scriptEngineManager = new ScriptEngineManager(environment.classLoader)
  lazy val javascriptScriptEngine: ScriptEngine with Invocable =
    scriptEngineManager.getEngineByName("nashorn").asInstanceOf[ScriptEngine with Invocable]
  lazy val scalaScriptEngine: Option[ScriptEngine with Invocable] = Option(
    scriptEngineManager.getEngineByName("scala").asInstanceOf[ScriptEngine with Invocable]
  )
  lazy val kotlinScriptEngine: ScriptEngine = new KotlinJsr223JvmLocalScriptEngineFactory().getScriptEngine
}

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

trait GlobalScriptDataStoreModule {
  def globalScriptDataStore: JsonDataStore
}

trait GlobalScriptContext
    extends LoggerModule
    with DataStoreContext
    with GlobalScriptDataStoreModule
    with EventStoreContext
    with RunnableScriptContext
    with AuthInfoModule[GlobalScriptContext]

object GlobalScriptDataStore extends JsonDataStoreHelper[GlobalScriptContext] {
  override def accessStore = _.globalScriptDataStore
}

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
      created  <- GlobalScriptDataStore.create(id, GlobalScriptInstances.format.writes(data))
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
      updated     <- GlobalScriptDataStore.update(oldId, id, GlobalScriptInstances.format.writes(data))
      apikey      <- jsResultToError(updated.validate[GlobalScript])
      authInfo    <- AuthInfo.authInfo
      _           <- EventStore.publish(GlobalScriptUpdated(id, oldValue, apikey, authInfo = authInfo))
    } yield apikey
    // format: on

  def delete(id: GlobalScriptKey): ZIO[GlobalScriptContext, IzanamiErrors, GlobalScript] =
    // format: off
    for {
      _         <- AuthorizedPatterns.isAllowed(id, PatternRights.D)
      deleted   <- GlobalScriptDataStore.delete(id)
      apikey    <- jsResultToError(deleted.validate[GlobalScript])
      authInfo  <- AuthInfo.authInfo
      _         <- EventStore.publish(GlobalScriptDeleted(id, apikey, authInfo = authInfo))
    } yield apikey
    // format: on

  def deleteAll(query: Query): ZIO[GlobalScriptContext, IzanamiErrors, Unit] =
    GlobalScriptDataStore.deleteAll(query)

  def getById(id: GlobalScriptKey): ZIO[GlobalScriptContext, IzanamiErrors, Option[GlobalScript]] =
    for {
      _            <- AuthorizedPatterns.isAllowed(id, PatternRights.R)
      mayBeScript  <- GlobalScriptDataStore.getById(id).refineToOrDie[IzanamiErrors]
      parsedScript = mayBeScript.flatMap(_.validate[GlobalScript].asOpt)
    } yield parsedScript

  def findByQuery(query: Query,
                  page: Int = 1,
                  nbElementPerPage: Int = 15): RIO[GlobalScriptContext, PagingResult[GlobalScript]] =
    GlobalScriptDataStore
      .findByQuery(query, page, nbElementPerPage)
      .map(jsons => JsonPagingResult(jsons))

  def findByQuery(query: Query): RIO[GlobalScriptContext, Source[(GlobalScriptKey, GlobalScript), NotUsed]] =
    GlobalScriptDataStore.findByQuery(query).map { s =>
      s.map {
        case (k, v) => (k, v.validate[GlobalScript].get)
      }
    }

  def count(query: Query): RIO[GlobalScriptContext, Long] =
    GlobalScriptDataStore.count(query)

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
