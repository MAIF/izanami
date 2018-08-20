package domains.script

import java.util.function.BiConsumer

import javax.script.{Invocable, ScriptEngine, ScriptEngineManager}
import akka.{Done, NotUsed}
import akka.actor.ActorSystem
import akka.http.scaladsl.util.FastFuture
import akka.stream.scaladsl.{Flow, Source}
import cats.Monad
import cats.data.EitherT
import domains.events.EventStore
import domains.events.Events.{GlobalScriptCreated, IzanamiEvent}
import domains.script.GlobalScript.GlobalScriptKey
import domains.{AuthInfo, ImportResult, Key}
import env.Env
import libs.functional.EitherTSyntax
import play.api.Logger
import play.api.libs.json._
import play.api.libs.ws.{WSRequest, WSResponse}
import store.Result.{ErrorMessage, Result}
import store.SourceUtils.SourceKV
import store._

import scala.collection.mutable
import scala.concurrent.{ExecutionContext, Future, Promise}
import scala.util.{Failure, Success, Try}

case class ScriptExecutionContext(actorSystem: ActorSystem) extends ExecutionContext {
  private val executionContext: ExecutionContext =
    actorSystem.dispatchers.lookup("izanami.script-dispatcher")
  override def execute(runnable: Runnable): Unit =
    executionContext.execute(runnable)
  override def reportFailure(cause: Throwable): Unit =
    executionContext.reportFailure(cause)
}

case class Script(script: String) {
  def run(context: JsObject, env: Env): Future[Boolean] = {
    import env.scriptExecutionContext
    val exec: Future[Boolean] = Script.executeScript(script, context, env)
    exec.onComplete {
      case Failure(e) => Logger.error("Error executing script", e)
      case _          =>
    }
    exec
  }
}

object Script {

  val reads: Reads[Script] = __.read[String].map(Script.apply)
  val writes: Writes[Script] = Writes[Script] { k =>
    JsString(k.script)
  }
  implicit val format: Format[Script] = Format(reads, writes)

  def executeScript(script: String, context: JsObject, env: Env)(
      implicit ec: ScriptExecutionContext
  ): Future[Boolean] = {
    val engineManager: ScriptEngineManager = new ScriptEngineManager
    val engine = engineManager
      .getEngineByName("nashorn")
      .asInstanceOf[ScriptEngine with Invocable]
    val reference = Promise[Boolean]()
    Future {
      engine.eval(script)
      val enabled                                   = () => reference.trySuccess(true)
      val disabled                                  = () => reference.trySuccess(false)
      val contextMap: java.util.Map[String, AnyRef] = jsObjectToMap(context)
      Try {
        engine.invokeFunction("enabled", contextMap, enabled, disabled, new HttpClient(env, reference))
      } recover {
        case e => reference.failure(e)
      }
    }(ec).onComplete {
      case Failure(e) => reference.failure(e)
      case _          =>
    }

    reference.future
  }

  private def jsObjectToMap(jsObject: JsObject): java.util.Map[String, AnyRef] = {
    import scala.collection.JavaConverters._
    jsObject.value.mapValues(asMap).toMap.asJava
  }

  private def asMap(jsValue: JsValue): AnyRef = {
    import scala.collection.JavaConverters._
    jsValue match {
      case JsString(s)        => s
      case JsNumber(value)    => value
      case JsArray(arr)       => arr.map(v => asMap(v)).asJava
      case jsObj: JsObject    => jsObjectToMap(jsObj)
      case JsBoolean(b) if b  => java.lang.Boolean.TRUE
      case JsBoolean(b) if !b => java.lang.Boolean.FALSE
      case _                  => null
    }
  }

}

class HttpClient(env: Env, promise: Promise[Boolean])(implicit ec: ScriptExecutionContext) {
  def call(optionsMap: java.util.Map[String, AnyRef], callback: BiConsumer[String, String]): Unit = {
    import play.api.libs.ws.JsonBodyWritables._

    import scala.collection.JavaConverters._
    val options: mutable.Map[String, AnyRef] = optionsMap.asScala
    val url: String                          = options("url").asInstanceOf[String]
    val method: String                       = options.getOrElse("method", "get").asInstanceOf[String]
    val headers: mutable.Map[String, String] =
      options
        .getOrElse("headers", new java.util.HashMap[String, String]())
        .asInstanceOf[java.util.Map[String, String]]
        .asScala
    val body: String =
      options.get("body").asInstanceOf[Option[String]].getOrElse("")

    val req: WSRequest =
      env.wSClient.url(url).withHttpHeaders(headers.toSeq: _*)
    val call: Future[WSResponse] = method.toLowerCase() match {
      case "get"    => req.get()
      case "post"   => req.post(body)
      case "put"    => req.put(body)
      case "delete" => req.delete()
      case "option" => req.options()
      case "patch"  => req.delete()
    }
    call.onComplete {
      case Success(response) =>
        Logger.debug(
          s"Script call $url, method=[$method], headers: $headers, body=[$body], response: code=${response.status} body=${response.body}"
        )
        Try {
          callback.accept(null, response.body)
        }.recover {
          case e => promise.failure(e)
        }
      case Failure(e) =>
        Logger.debug(s"Script call $url, method=[$method], headers: $headers, body=[$body], call failed", e)
        Try {
          callback.accept(e.getMessage, null)
        }.recover {
          case e => promise.failure(e)
        }
    }

  }
}

case class GlobalScript(id: Key, name: String, description: String, source: Script) {
  def isAllowed = Key.isAllowed(id) _
}

object GlobalScript {

  type GlobalScriptKey = Key

  def isAllowed(key: GlobalScriptKey)(auth: Option[AuthInfo]) =
    Key.isAllowed(key)(auth)

  implicit val format = Json.format[GlobalScript]

  def importData(
      globalScriptStore: GlobalScriptStore[Future]
  )(implicit ec: ExecutionContext): Flow[(String, JsValue), ImportResult, NotUsed] = {
    import cats.implicits._
    import store.Result.AppErrors._

    Flow[(String, JsValue)]
      .map { case (s, json) => (s, json.validate[GlobalScript]) }
      .mapAsync(4) {
        case (_, JsSuccess(obj, _)) =>
          globalScriptStore.create(obj.id, obj) map { ImportResult.fromResult }
        case (s, JsError(_)) =>
          FastFuture.successful(ImportResult.error(ErrorMessage("json.parse.error", s)))
      }
      .fold(ImportResult()) { _ |+| _ }
  }
}

trait GlobalScriptStore[F[_]] extends DataStore[F, GlobalScriptKey, GlobalScript]

object GlobalScriptStore {

  val eventAdapter = Flow[IzanamiEvent].collect {
    case GlobalScriptCreated(key, script, _, _) =>
  }

}

class GlobalScriptStoreImpl[F[_]: Monad](jsonStore: JsonDataStore[F], eventStore: EventStore[F])
    extends GlobalScriptStore[F]
    with EitherTSyntax[F] {

  import GlobalScript._
  import cats.implicits._
  import store.Result._
  import libs.functional.syntax._
  import domains.events.Events._

  override def create(id: GlobalScriptKey, data: GlobalScript): F[Result[GlobalScript]] = {
    // format: off
    val r: EitherT[F, AppErrors, GlobalScript] = for {
      created     <- jsonStore.create(id, GlobalScript.format.writes(data))   |> liftFEither
      apikey      <- created.validate[GlobalScript]                           |> liftJsResult{ handleJsError }
      _           <- eventStore.publish(GlobalScriptCreated(id, apikey))      |> liftF[AppErrors, Done]
    } yield apikey
    // format: on
    r.value
  }

  override def update(oldId: GlobalScriptKey, id: GlobalScriptKey, data: GlobalScript): F[Result[GlobalScript]] = {
    // format: off
    val r: EitherT[F, AppErrors, GlobalScript] = for {
      oldValue    <- getById(oldId)                                                     |> liftFOption(AppErrors.error("error.data.missing", oldId.key))
      updated     <- jsonStore.update(oldId, id, GlobalScript.format.writes(data))      |> liftFEither
      apikey      <- updated.validate[GlobalScript]                                     |> liftJsResult{ handleJsError }
      _           <- eventStore.publish(GlobalScriptUpdated(id, oldValue, apikey))      |> liftF[AppErrors, Done]
    } yield apikey
    // format: on
    r.value
  }

  override def delete(id: GlobalScriptKey): F[Result[GlobalScript]] = {
    // format: off
    val r: EitherT[F, AppErrors, GlobalScript] = for {
      deleted <- jsonStore.delete(id)                                   |> liftFEither
      apikey  <- deleted.validate[GlobalScript]                         |> liftJsResult{ handleJsError }
      _       <- eventStore.publish(GlobalScriptDeleted(id, apikey))    |> liftF[AppErrors, Done]
    } yield apikey
    // format: on
    r.value
  }

  override def deleteAll(patterns: Seq[String]): F[Result[Done]] =
    jsonStore.deleteAll(patterns)

  override def getById(id: GlobalScriptKey): F[Option[GlobalScript]] =
    jsonStore.getById(id).map(_.flatMap(_.validate[GlobalScript].asOpt))

  override def getByIdLike(patterns: Seq[String], page: Int, nbElementPerPage: Int): F[PagingResult[GlobalScript]] =
    jsonStore
      .getByIdLike(patterns, page, nbElementPerPage)
      .map(jsons => JsonPagingResult(jsons))

  override def getByIdLike(patterns: Seq[String]): Source[(Key, GlobalScript), NotUsed] =
    jsonStore.getByIdLike(patterns).readsKV[GlobalScript]

  override def count(patterns: Seq[String]): F[Long] =
    jsonStore.count(patterns)

  private def handleJsError(err: Seq[(JsPath, Seq[JsonValidationError])]): AppErrors = {
    Logger.error(s"Error parsing json from database $err")
    AppErrors.error("error.json.parsing")
  }
}
