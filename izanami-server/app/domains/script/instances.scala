package domains.script
import java.util.function.BiConsumer

import akka.Done
import akka.actor.ActorSystem
import cats.effect.{Async, IO}
import domains.script.Script.ScriptCache
import domains.{AuthInfo, IsAllowed, Key}
import env.Env
import javax.script._
import play.api.{Logger, Play}
import play.api.Mode.Prod
import play.api.cache.AsyncCacheApi
import play.api.libs.json
import play.api.libs.json._
import play.api.libs.ws.{WSClient, WSRequest, WSResponse}

import scala.collection.mutable
import scala.concurrent.{ExecutionContext, Future}
import scala.util.hashing.MurmurHash3
import scala.util.{Failure, Success, Try}
import cats.implicits._

import scala.annotation.varargs

case class ScalaConsole(logs: mutable.ArrayBuffer[String] = mutable.ArrayBuffer.empty) {

  def println(args: AnyRef): Unit =
    logs += args.toString

  def scriptLogs = ScriptLogs(logs)
}

case class JsConsole(logs: mutable.ArrayBuffer[String] = mutable.ArrayBuffer.empty) {
  @varargs
  def log(args: AnyRef*): Unit =
    logs += args.mkString(", ")

  def scriptLogs = ScriptLogs(logs)
}

trait FeatureScript {

  def run(println: AnyRef => Unit,
          context: play.api.libs.json.JsObject,
          enabled: () => Unit,
          disabled: () => Unit,
          http: play.api.libs.ws.WSClient)(implicit ec: ExecutionContext): Unit

}

object ScriptInstances {

  implicit val reads: Reads[Script] = Reads[Script] {
    case js: JsObject if (js \ "type").asOpt[String].contains("scala") =>
      (js \ "script")
        .asOpt[String]
        .map(s => JsSuccess(ScalaScript(s)))
        .getOrElse(JsError("missing.field.script"))
    case js: JsObject if (js \ "type").asOpt[String].contains("javascript") =>
      (js \ "script")
        .asOpt[String]
        .map(s => JsSuccess(JavascriptScript(s)))
        .getOrElse(JsError("missing.field.script"))
    case js: JsObject =>
      (js \ "script")
        .asOpt[String]
        .map(s => JsSuccess(JavascriptScript(s)))
        .getOrElse(JsError("missing.field.script"))
    case _ =>
      JsError("invalid.script")
  }

  implicit val writes: Writes[Script] = Writes[Script] {
    case JavascriptScript(script) =>
      Json.obj("type" -> "javascript", "script" -> script)
    case ScalaScript(script) =>
      Json.obj("type" -> "scala", "script" -> script)
  }

  implicit val executionScriptWrites = Writes[ScriptExecution] {
    case ScriptExecutionSuccess(result, logs) =>
      Json.obj(
        "status" -> "Success",
        "result" -> result,
        "logs"   -> logs
      )
    case ScriptExecutionFailure(logs, stacktrace) =>
      Json.obj(
        "status" -> "Failure",
        "errors" -> stacktrace,
        "logs"   -> logs
      )
  }

  implicit def runnableScript[F[_]: Async: ScriptCache]: RunnableScript[F, Script] = new RunnableScript[F, Script] {
    override def run(script: Script, context: JsObject, env: Env): F[ScriptExecution] = {
      import env.scriptExecutionContext
      script match {
        case s: ScalaScript =>
          Logger.debug(s"Executing scala script $s")
          executeScalaScript[F](s, context, env)
        case s: JavascriptScript =>
          Logger.debug(s"Executing javascript script $s")
          executeJavascriptScript[F](s, context, env)
      }
    }
  }

  private def executeScalaScript[F[_]: Async: ScriptCache](script: ScalaScript, context: JsObject, env: Env)(
      implicit ec: ScriptExecutionContext
  ): F[ScriptExecution] = {

    import scala.collection.JavaConverters._

    val finalScript: String =
      s"""
         |import domains.script.FeatureScript
         |import play.api.libs.ws._
         |import scala.concurrent._
         |import play.api.libs.json._
         |
         |new FeatureScript {
         |  override def run(println: AnyRef => Unit,
         |           context: play.api.libs.json.JsObject,
         |           e: () => Unit,
         |           d: () => Unit,
         |           http: play.api.libs.ws.WSClient)(implicit ec: ExecutionContext): Unit = {
         |      ${script.script}
         |      enabled(context, e, d, http)
         |    }
         |}
         |
         |
         |
      """.stripMargin

    val id = MurmurHash3.stringHash(finalScript).toString

    val buildScript: F[FeatureScript] =
      Async[F].async { cb =>
        ec.execute { () =>
          Try {
            val engineManager: ScriptEngineManager = new ScriptEngineManager(env.environment.classLoader)
            Logger.debug(
              s"Looking for scala engine in ${engineManager.getEngineFactories.asScala.map(_.getEngineName).mkString(",")}"
            )

            env.environment.mode match {
              case Prod =>
                val scriptEngine = engineManager.getEngineByName("scala")
                val engine       = scriptEngine.asInstanceOf[ScriptEngine with Invocable]
                Logger.debug("Compiling script ...")
                val script: FeatureScript = engine.eval(finalScript).asInstanceOf[FeatureScript]
                Logger.debug("Compilation is done !")
                cb(Right(script))
              case _ =>
                cb(Left(new IllegalArgumentException("Scala scripts not supported in dev mode")))
//
//                val engineFactory =
//                  engineManager.getEngineFactories.asScala.find(_.getEngineName == "Scala REPL").get
//                Logger.debug(s"Dev, factory: $engineFactory")
//
//                val interpreter =
//                  engineFactory.getScriptEngine
//                Logger.debug(s"Dev, interpreter: $interpreter")
//
//                val settings = interpreter.asInstanceOf[scala.tools.nsc.interpreter.IMain].settings
//                Logger.debug(s"Dev, setting classpath to $sbtClasspath")
//                settings.classpath.value = s".:$sbtClasspath"
//                interpreter
            }

          } recover {
            case e =>
              Logger.error(s"Error building scala script \n $finalScript", e)
              cb(Left(e))
          }
        }
      }

    def run(featureScript: FeatureScript): F[ScriptExecution] = {
      val console = ScalaConsole()
      Async[F]
        .async { cb: (Either[Throwable, Boolean] => Unit) =>
          ec.execute { () =>
            Try {
              val enabled  = () => cb(Right(true))
              val disabled = () => cb(Right(false))
              featureScript.run(console.println, context, enabled, disabled, env.wSClient)(ec)
            } recover {
              case e => cb(Left(e))
            }
          }
        }
        .map { b =>
          ScriptExecutionSuccess(b, console.scriptLogs.logs).asInstanceOf[ScriptExecution]
        }
        .recover {
          case e: Exception =>
            Logger.error(s"Error executing script, console = ${console.scriptLogs.logs}", e)
            ScriptExecutionFailure(console.scriptLogs.logs, e.getStackTrace.map(stackElt => stackElt.toString))
        }
    }

    val scriptCache = ScriptCache[F]

    (
      for {
        mayBeScript <- scriptCache.get(id)
        _           = Logger.debug(s"Cache for script ? : $mayBeScript")
        script      <- mayBeScript.fold(buildScript)(_.pure[F])
        _           = Logger.debug(s"Updating cache for id $id and script $script")
        _           <- scriptCache.set(id, script)
        _           = Logger.debug(s"Running scala script")
        r           <- run(script)
      } yield r
    ).recover {
      case e: Exception =>
        Logger.error(s"Error executing script", e)
        ScriptExecutionFailure(Seq.empty, e.getStackTrace.map(stackElt => stackElt.toString))
    }
  }

  private def executeJavascriptScript[F[_]: Async](script: JavascriptScript, context: JsObject, env: Env)(
      implicit ec: ScriptExecutionContext
  ): F[ScriptExecution] = {

    Logger.debug(s"Creating console")
    val console = JsConsole()

    Async[F]
      .async { cb: (Either[Throwable, Boolean] => Unit) =>
        ec.execute { () =>
          Try {
            val engineManager: ScriptEngineManager = new ScriptEngineManager
            val engine = engineManager
              .getEngineByName("nashorn")
              .asInstanceOf[ScriptEngine with Invocable]

            engine.getContext.setAttribute("console", console, ScriptContext.ENGINE_SCOPE)

            engine.eval(script.script)
            val enabled                                   = () => cb(Right(true))
            val disabled                                  = () => cb(Right(false))
            val contextMap: java.util.Map[String, AnyRef] = jsObjectToMap(context)

            engine.invokeFunction("enabled", contextMap, enabled, disabled, new HttpClient(env, cb))
          } recover {
            case e => cb(Left(e))
          }
        }
      }
      .map { b =>
        ScriptExecutionSuccess(b, console.scriptLogs.logs).asInstanceOf[ScriptExecution]
      }
      .recover {
        case e: Exception =>
          Logger.error(s"Error executing script, console = ${console.scriptLogs.logs}", e)
          ScriptExecutionFailure(console.scriptLogs.logs, e.getStackTrace.map(stackElt => stackElt.toString))
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

class PlayScriptCache[F[_]: Async](api: AsyncCacheApi) extends ScriptCache[F] {

  import cats._
  import cats.implicits._

  override def get(id: String): F[Option[FeatureScript]] =
    IO.fromFuture(IO(api.get[FeatureScript](id))).to[F]

  override def set(id: String, value: FeatureScript): F[Unit] = {
    val update = IO.fromFuture(IO(api.set(id, value))).to[F]
    for {
      mayBeResult <- get(id)
      _           <- mayBeResult.fold(update)(_ => Async[F].pure(Done))
    } yield ()
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

class HttpClient[F[_]](env: Env, promise: Either[Throwable, Boolean] => Unit)(
    implicit ec: ScriptExecutionContext
) {
  def call(optionsMap: java.util.Map[String, AnyRef], callback: BiConsumer[String, String]): Unit = {
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
