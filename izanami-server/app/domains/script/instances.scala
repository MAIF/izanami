package domains.script
import java.util.function.BiConsumer

import akka.Done
import com.fasterxml.jackson.databind.node.ObjectNode
import domains.PlayModule
import domains.script.Script.ScriptCache
import domains.{AuthInfo, IsAllowed, Key}
import javax.script._
import org.jetbrains.kotlin.script.jsr223.KotlinJsr223JvmLocalScriptEngineFactory
import libs.logs.IzanamiLogger
import play.api.Mode.Prod
import play.api.cache.AsyncCacheApi
import play.api.libs.json._
import play.api.libs.ws.{WSRequest, WSResponse}
import zio.{blocking, RIO, Task, ZIO}

import scala.annotation.varargs
import scala.collection.mutable
import scala.concurrent.{ExecutionContext, Future}
import scala.reflect.ClassTag
import scala.util.hashing.MurmurHash3
import scala.util.{Failure, Success, Try}
import libs.logs.Logger
import libs.logs.LoggerModule
import zio.blocking.Blocking

case class ScalaConsole(logs: mutable.ArrayBuffer[String] = mutable.ArrayBuffer.empty) {

  def println(args: AnyRef): Unit =
    logs += args.toString

  def scriptLogs = ScriptLogs(logs.toSeq)
}

case class JsConsole(logs: mutable.ArrayBuffer[String] = mutable.ArrayBuffer.empty) {
  @varargs
  def log(args: AnyRef*): Unit =
    logs += args.mkString(", ")

  def scriptLogs = ScriptLogs(logs.toSeq)
}

trait FeatureScript {

  def run(println: AnyRef => Unit,
          context: play.api.libs.json.JsObject,
          enabled: () => Unit,
          disabled: () => Unit,
          http: play.api.libs.ws.WSClient)(implicit ec: ExecutionContext): Unit

}

trait KotlinFeatureScript {

  def run(println: kotlin.jvm.functions.Function1[AnyRef, Unit],
          context: com.fasterxml.jackson.databind.JsonNode,
          enabled: kotlin.jvm.functions.Function0[Unit],
          disabled: kotlin.jvm.functions.Function0[Unit],
          http: play.libs.ws.WSClient): Unit
}

object ScriptInstances {

  implicit val reads: Reads[Script] = Reads[Script] {
    case js: JsObject if (js \ "type").asOpt[String].contains("scala") =>
      (js \ "script")
        .asOpt[String]
        .map(s => JsSuccess(ScalaScript(s)))
        .getOrElse(JsError("missing.field.script"))
    case js: JsObject if (js \ "type").asOpt[String].contains("kotlin") =>
      (js \ "script")
        .asOpt[String]
        .map(s => JsSuccess(KotlinScript(s)))
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
    case KotlinScript(script) =>
      Json.obj("type" -> "kotlin", "script" -> script)
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

  implicit def runnableScript[ScriptCache]: RunnableScript[Script] = new RunnableScript[Script] {
    override def run(script: Script, context: JsObject): zio.RIO[RunnableScriptContext, ScriptExecution] =
      script match {
        case s: ScalaScript =>
          Logger.debug(s"Executing scala script $s") *>
          executeScalaScript(s, context)
        case s: JavascriptScript =>
          Logger.debug(s"Executing javascript script $s") *>
          executeJavascriptScript(s, context)
        case s: KotlinScript =>
          Logger.debug(s"Executing kotlin script $s") *>
          executeKotlinScript(s, context)
      }
  }

  private def executeKotlinScript(script: KotlinScript,
                                  context: JsObject): RIO[RunnableScriptContext, ScriptExecution] = {

    val finalScript: String =
      s"""
         |import domains.script.KotlinFeatureScript
         |import domains.script.KHttpClient
         |import play.libs.ws.WSClient
         |import play.libs.ws.WSResponse
         |import scala.runtime.BoxedUnit
         |import com.fasterxml.jackson.databind.JsonNode
         |import play.libs.Json
         |import java.util.function.BiConsumer
         |
         |private interface Intermediate: KotlinFeatureScript {
         |  override fun run(println: (Any) -> BoxedUnit,
         |                 context: JsonNode,
         |                 enabled: () -> BoxedUnit,
         |                 disabled: () -> BoxedUnit,
         |                 http: WSClient) : Unit
         |}
         |
         |object : Intermediate {
         |  override fun run(println: (Any) -> BoxedUnit,
         |                 context: JsonNode,
         |                 e: () -> BoxedUnit,
         |                 d: () -> BoxedUnit,
         |                 http: WSClient) {
         |
         |       ${script.script}
         |       enabled(context, { e() }, { d() }, http)
         |  }
         |}
      """.stripMargin

    val id = MurmurHash3.stringHash(finalScript).toString

    val buildScript: RIO[LoggerModule, KotlinFeatureScript] =
      for {
        scriptEngine <- Task(new KotlinJsr223JvmLocalScriptEngineFactory().getScriptEngine)
        _            <- Logger.debug(s"Compiling script ... ")
        script       <- Task(scriptEngine.eval(finalScript).asInstanceOf[KotlinFeatureScript])
      } yield script

    def run(featureScript: KotlinFeatureScript): RIO[LoggerModule with PlayModule, ScriptExecution] = {
      val console = ScalaConsole()
      ZIO
        .accessM[PlayModule] { env =>
          Task
            .effectAsync[Boolean] { cb =>
              implicit val ec = env.ec
              Try {
                new KHttpClient(env.wSClient, cb)
                val ctx = context.as[ObjectNode]
                featureScript.run(
                  (p1: AnyRef) => console.println(p1),
                  ctx,
                  () => cb(ZIO.succeed(true)),
                  () => cb(ZIO.succeed(false)),
                  env.javaWsClient
                )
              } recover {
                case e => cb(ZIO.fail(e))
              }
            }
        }
        .map { b =>
          ScriptExecutionSuccess(b, console.scriptLogs.logs).asInstanceOf[ScriptExecution]
        }
        .catchSome {
          case e: Exception =>
            Logger.error(s"Error executing script, console = ${console.scriptLogs.logs}", e) *>
            ZIO.succeed(ScriptExecutionFailure.fromThrowable(console.scriptLogs.logs, e))
        }
    }
    blocking.blocking {
      (
        for {
          mayBeScript <- ZIO.accessM[ScriptCacheModule](_.scriptCache.get[KotlinFeatureScript](id))
          _           <- Logger.debug(s"Cache for script ? : $mayBeScript")
          script      <- mayBeScript.fold(blocking.blocking(buildScript))(ZIO.succeed)
          _           <- Logger.debug(s"Updating cache for id $id and script $script")
          _           <- ZIO.accessM[ScriptCacheModule](_.scriptCache.set(id, script))
          _           <- Logger.debug(s"Running kotlin script")
          r           <- blocking.blocking(run(script))
        } yield r
      ).catchSome {
        case e: Exception =>
          Logger.error(s"Error executing script", e) *>
          ZIO.succeed(ScriptExecutionFailure.fromThrowable(Seq.empty, e))
      }
    }
  }

  private def executeScalaScript(script: ScalaScript,
                                 context: JsObject): RIO[RunnableScriptContext, ScriptExecution] = {

    import scala.jdk.CollectionConverters._
    import zio._
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

    val buildScript: RIO[LoggerModule with PlayModule, FeatureScript] =
      ZIO.accessM[LoggerModule with PlayModule] { env =>
        env.environment.mode match {
          case Prod =>
            for {
              engineManager <- Task(new ScriptEngineManager(env.environment.classLoader))
              _ <- Logger.debug(
                    s"Looking for scala engine in ${engineManager.getEngineFactories.asScala.map(_.getEngineName).mkString(",")}"
                  )
              scriptEngine <- Task(engineManager.getEngineByName("scala"))
              engine       = scriptEngine.asInstanceOf[ScriptEngine with Invocable]
              _            <- Logger.debug("Compilation is done !")
              script       <- Task(engine.eval(finalScript).asInstanceOf[FeatureScript])
              _            <- Logger.debug("Compiling script ...")
            } yield script
          case _ => ZIO.fail(new IllegalArgumentException("Scala scripts not supported in dev mode"))
        }
      }

    def run(featureScript: FeatureScript): RIO[LoggerModule with PlayModule, ScriptExecution] = {
      val scalaConsole = ScalaConsole()
      ZIO.accessM { env =>
        Task
          .effectAsync[Boolean] { cb =>
            implicit val ec = env.ec
            Try {
              featureScript.run(scalaConsole.println,
                                context,
                                () => cb(ZIO.succeed(true)),
                                () => cb(ZIO.succeed(false)),
                                env.wSClient)
            } recover {
              case e => cb(ZIO.fail(e))
            }
          }
          .map { b =>
            ScriptExecutionSuccess(b, scalaConsole.scriptLogs.logs).asInstanceOf[ScriptExecution]
          }
          .catchSome {
            case e: Exception =>
              Logger.error(s"Error executing script, console = ${scalaConsole.scriptLogs.logs}", e) *>
              ZIO.succeed(ScriptExecutionFailure.fromThrowable(scalaConsole.scriptLogs.logs, e))
          }
      }
    }

    blocking.blocking {
      (
        for {
          mayBeScript <- ZIO.accessM[ScriptCacheModule](_.scriptCache.get[FeatureScript](id))
          _           <- Logger.debug(s"Cache for script ? : $mayBeScript")
          script      <- mayBeScript.fold(blocking.blocking(buildScript))(ZIO.succeed)
          _           <- Logger.debug(s"Updating cache for id $id and script $script")
          _           <- ZIO.accessM[ScriptCacheModule](_.scriptCache.set(id, script))
          _           <- Logger.debug(s"Running scala script")
          r           <- blocking.blocking(run(script))
        } yield r
      ).catchSome {
        case e: Exception =>
          Logger.error(s"Error executing script", e) *>
          ZIO.succeed(ScriptExecutionFailure.fromThrowable(Seq.empty, e))
      }
    }
  }

  private def executeJavascriptScript(script: JavascriptScript,
                                      context: JsObject): zio.RIO[RunnableScriptContext, ScriptExecution] = {
    import zio._

    val exec: RIO[LoggerModule with Blocking with PlayModule, ScriptExecution] =
      for {
        _             <- Logger.debug(s"Creating console")
        console       = JsConsole()
        engineManager <- Task(new ScriptEngineManager)
        engine        <- Task(engineManager.getEngineByName("nashorn").asInstanceOf[ScriptEngine with Invocable])
        _             = engine.getContext.setAttribute("console", console, ScriptContext.ENGINE_SCOPE)
        env           <- RIO.environment[LoggerModule with Blocking with PlayModule]
        script <- Task
                   .effectAsync[Boolean] { cb =>
                     implicit val ec = env.ec
                     Try {
                       engine.eval(script.script)
                       val enabled                                   = () => cb(ZIO.succeed(true))
                       val disabled                                  = () => cb(ZIO.succeed(false))
                       val contextMap: java.util.Map[String, AnyRef] = jsObjectToMap(context)

                       engine.invokeFunction("enabled", contextMap, enabled, disabled, new HttpClient(env.wSClient, cb))
                     } recover {
                       case e => cb(ZIO.fail(e))
                     }
                   }
                   .map { enabled =>
                     ScriptExecutionSuccess(enabled, console.scriptLogs.logs).asInstanceOf[ScriptExecution]
                   }
                   .catchSome {
                     case e: Exception =>
                       Logger.error(s"Error executing script, console = ${console.scriptLogs.logs}", e) *>
                       ZIO.succeed(ScriptExecutionFailure.fromThrowable(console.scriptLogs.logs, e))
                   }
      } yield script

    blocking.blocking { exec }
  }

  private def jsObjectToMap(jsObject: JsObject): java.util.Map[String, AnyRef] = {
    import scala.jdk.CollectionConverters._
    jsObject.value.view.mapValues(asMap).toMap.asJava
  }

  private def asMap(jsValue: JsValue): AnyRef = {
    import scala.jdk.CollectionConverters._
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

class PlayScriptCache(api: AsyncCacheApi) extends ScriptCache {

  override def get[T: ClassTag](id: String): Task[Option[T]] =
    Task.fromFuture(_ => api.get[T](id))

  override def set[T: ClassTag](id: String, value: T): Task[Unit] = {
    val update = Task.fromFuture(_ => api.set(id, value))
    for {
      mayBeResult <- get(id)
      _           <- mayBeResult.fold(update)(_ => Task.succeed(Done))
    } yield ()
  }
}

class HttpClient(wSClient: play.api.libs.ws.WSClient, promise: (ZIO[Any, Throwable, Boolean] => Unit))(
    implicit ec: ExecutionContext
) {
  def call(optionsMap: java.util.Map[String, AnyRef], callback: BiConsumer[String, String]): Unit = {
    import scala.jdk.CollectionConverters._
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
      wSClient.url(url).withHttpHeaders(headers.toSeq: _*)
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
        IzanamiLogger.debug(
          s"Script call $url, method=[$method], headers: $headers, body=[$body], response: code=${response.status} body=${response.body}"
        )
        Try {
          callback.accept(null, response.body)
        }.recover {
          case e => promise(ZIO.fail(e))
        }
      case Failure(e) =>
        IzanamiLogger.debug(s"Script call $url, method=[$method], headers: $headers, body=[$body], call failed", e)
        Try {
          callback.accept(e.getMessage, null)
        }.recover {
          case e => promise(ZIO.fail(e))
        }
    }
  }
}

class KHttpClient(wSClient: play.api.libs.ws.WSClient, promise: (ZIO[Any, Throwable, Boolean] => Unit))(
    implicit ec: ExecutionContext
) extends HttpClient(wSClient, promise)(ec)

object GlobalScriptInstances {

  implicit val isAllowed: IsAllowed[GlobalScript] = new IsAllowed[GlobalScript] {
    override def isAllowed(value: GlobalScript)(auth: Option[AuthInfo]): Boolean = Key.isAllowed(value.id)(auth)
  }

  implicit val format = {
    import ScriptInstances._
    Json.format[GlobalScript]
  }

}
