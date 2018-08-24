package domains.script
import java.util.function.BiConsumer

import akka.actor.ActorSystem
import cats.effect.{Async, Effect}
import domains.{AuthInfo, IsAllowed, Key}
import env.Env
import javax.script.{Invocable, ScriptEngine, ScriptEngineManager}
import play.api.Logger
import play.api.libs.json._
import play.api.libs.ws.{WSRequest, WSResponse}

import scala.collection.mutable
import scala.concurrent.{ExecutionContext, Future}
import scala.util.{Failure, Success, Try}

object ScriptInstances {

  implicit val reads: Reads[Script] = __.read[String].map(Script.apply)
  implicit val writes: Writes[Script] = Writes[Script] { k =>
    JsString(k.script)
  }

  implicit def runnableScript[F[_]: Async] = new RunnableScript[F, Script] {
    override def run(script: Script, context: JsObject, env: Env): F[Boolean] = {
      import env.scriptExecutionContext
      val exec: F[Boolean] = executeScript[F](script.script, context, env)
      exec
    }
  }

  private def executeScript[F[_]: Async](script: String, context: JsObject, env: Env)(
      implicit ec: ScriptExecutionContext
  ): F[Boolean] = {
    val engineManager: ScriptEngineManager = new ScriptEngineManager
    val engine = engineManager
      .getEngineByName("nashorn")
      .asInstanceOf[ScriptEngine with Invocable]

    Async[F].async { cb =>
      ec.execute { () =>
        engine.eval(script)
        val enabled                                   = () => cb(Right(true))
        val disabled                                  = () => cb(Right(false))
        val contextMap: java.util.Map[String, AnyRef] = jsObjectToMap(context)
        Try {
          engine.invokeFunction("enabled", contextMap, enabled, disabled, new HttpClient(env, cb))
        } recover {
          case e => cb(Left(e))
        }
      }
    }
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

case class ScriptExecutionContext(actorSystem: ActorSystem) extends ExecutionContext {
  private val executionContext: ExecutionContext =
    actorSystem.dispatchers.lookup("izanami.script-dispatcher")
  override def execute(runnable: Runnable): Unit =
    executionContext.execute(runnable)
  override def reportFailure(cause: Throwable): Unit =
    executionContext.reportFailure(cause)
}

class HttpClient[F[_]](env: Env, promise: Either[Throwable, Boolean] => Unit)(implicit ec: ScriptExecutionContext) {
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
          case e => promise(Left(e))
        }
      case Failure(e) =>
        Logger.debug(s"Script call $url, method=[$method], headers: $headers, body=[$body], call failed", e)
        Try {
          callback.accept(e.getMessage, null)
        }.recover {
          case e => promise(Left(e))
        }
    }
  }
}

object GlobalScriptInstances {

  implicit val isAllowed: IsAllowed[GlobalScript] = new IsAllowed[GlobalScript] {
    override def isAllowed(value: GlobalScript)(auth: Option[AuthInfo]): Boolean = Key.isAllowed(value.id)(auth)
  }

  implicit val format = {
    import ScriptInstances._
    Json.format[GlobalScript]
  }

}
