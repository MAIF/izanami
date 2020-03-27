package domains.script
import java.io.File
import java.nio.file.{Files, Path, Paths}
import java.util
import java.util.function.BiConsumer
import java.util.stream.Collectors

import akka.Done
import com.fasterxml.jackson.databind.node.ObjectNode
import domains.configuration.PlayModule
import domains.script.ScriptCache
import domains.{IsAllowed, Key}
import domains.auth.AuthInfo
import javax.script
import javax.script._
import kotlin.reflect.KClass
import kotlin.script.experimental.jvm.util.JvmClasspathUtilKt
import org.jetbrains.kotlin.script.jsr223.{
  KotlinJsr223JvmDaemonCompileScriptEngine,
  KotlinJsr223JvmDaemonLocalEvalScriptEngineFactory,
  KotlinJsr223JvmLocalScriptEngine,
  KotlinJsr223JvmLocalScriptEngineFactory,
  KotlinStandardJsr223ScriptTemplate
}
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
import libs.logs.ZLogger
import org.jetbrains.kotlin.cli.common.repl.{KotlinJsr223JvmScriptEngineFactoryBase, ScriptArgsWithTypes}
import org.jetbrains.kotlin.script.util.ContextKt
import play.api.Environment
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
          ZLogger.debug(s"Executing scala script $s") *>
          executeScalaScript(s, context)
        case s: JavascriptScript =>
          ZLogger.debug(s"Executing javascript script $s") *>
          executeJavascriptScript(s, context)
        case s: KotlinScript =>
          ZLogger.debug(s"Executing kotlin script $s") *>
          executeKotlinScript(s, context)
      }
  }

//  class CustomScriptEngine(environment: Environment) extends KotlinJsr223JvmScriptEngineFactoryBase {
//
//    val _underlying = new KotlinJsr223JvmLocalScriptEngineFactory()
//
//    override def getScriptEngine: script.ScriptEngine = {
//      import scala.jdk.CollectionConverters._
//      val jarDirectory = Paths.get("lib").toAbsolutePath
//
//      if (jarDirectory.toFile.exists()) {
//
//        val files = jarDirectory
//          .toFile()
//          .listFiles()
//          .toList
//          .filter { _.getName().endsWith(".jar") }
//
////        System.setProperty("kotlin.compiler.classpath", files.mkString(":"))
////
////        val tpl                 = new KotlinJsr223JvmDaemonLocalEvalScriptEngineFactory()
//        val jars: Array[String] = Array("org.jetbrains.kotlin.kotlin-script-util-1.3.70.jar")
//
//        val scriptClasspath: List[File] = Option(
//          JvmClasspathUtilKt
//            .scriptCompilationClasspathFromContextOrNull(jars, environment.classLoader, true, true)
//        ).toList.flatMap(_.asScala).filterNot(_.getName.contains("kotlin-stdlib-jdk7"))
//
//        val classpath = scriptClasspath
//
//        new KotlinJsr223JvmLocalScriptEngine(
//          this,
//          classpath.asJava,
//          classOf[KotlinStandardJsr223ScriptTemplate].getName, { (ctx: ScriptContext, types: Array[KClass[_]]) =>
//            val bindings = ctx.getBindings(ScriptContext.ENGINE_SCOPE)
//            new ScriptArgsWithTypes(Array(bindings), Option(types).getOrElse(Array.empty[KClass[_]]))
//          },
//          Array.empty[kotlin.reflect.KClass[_]]
//        )
////        tpl.getScriptEngine
//      } else {
//        _underlying.getScriptEngine
//      }
//    }
//  }

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

    import scala.jdk.CollectionConverters._
    val buildScript: RIO[RunnableScriptContext, KotlinFeatureScript] =
      for {
        scriptEngine <- RunnableScriptModule.kotlinScriptEngine
        _            <- ZLogger.debug(s"Compiling script ... ")
        tmpScript    <- Task(scriptEngine.eval(finalScript))
        _            <- ZLogger.debug(s"Cast script ... ")
        script       = tmpScript.asInstanceOf[KotlinFeatureScript]
      } yield script

    def run(featureScript: KotlinFeatureScript): RIO[RunnableScriptContext, ScriptExecution] = {
      val console = ScalaConsole()
      for {
        wSClient     <- PlayModule.wSClient
        javaWsClient <- PlayModule.javaWsClient
        envEc        <- PlayModule.ec
        scriptRun <- Task
                      .effectAsync[Boolean] { cb =>
                        implicit val ec = envEc
                        Try {
                          new KHttpClient(wSClient, cb)
                          val ctx = context.as[ObjectNode]
                          featureScript.run(
                            (p1: AnyRef) => console.println(p1),
                            ctx,
                            () => cb(ZIO.succeed(true)),
                            () => cb(ZIO.succeed(false)),
                            javaWsClient
                          )
                        } recover {
                          case e => cb(ZIO.fail(e))
                        }
                      }
                      .map { b =>
                        ScriptExecutionSuccess(b, console.scriptLogs.logs).asInstanceOf[ScriptExecution]
                      }
                      .catchSome {
                        case e: Exception =>
                          ZLogger.error(s"Error executing script, console = ${console.scriptLogs.logs}", e) *>
                          ZIO.succeed(ScriptExecutionFailure.fromThrowable(console.scriptLogs.logs, e))
                      }
      } yield scriptRun
    }

    blocking.blocking {
      (
        for {
          mayBeScript <- ScriptCache.get[KotlinFeatureScript](id)
          _           <- ZLogger.debug(s"Cache for script ? : $mayBeScript")
          script      <- mayBeScript.fold(blocking.blocking(buildScript))(s => ZIO(s))
          _           <- ZLogger.debug(s"Updating cache for id $id and script $script")
          _           <- ScriptCache.set(id, script)
          _           <- ZLogger.debug(s"Running kotlin script")
          r           <- blocking.blocking(run(script))
        } yield r
      ).catchSome {
        case e: Exception =>
          ZLogger.error(s"Error executing script", e) *>
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

    val buildScript: RIO[RunnableScriptContext, FeatureScript] =
      for {
        environment         <- PlayModule.environment
        scriptEngineManager <- RunnableScriptModule.scriptEngineManager
        scalaScriptEngine   <- RunnableScriptModule.scalaScriptEngine
        res <- environment.mode match {
                case Prod =>
                  for {
                    _ <- ZLogger.debug(
                          s"Looking for scala engine in ${scriptEngineManager.getEngineFactories.asScala.map(_.getEngineName).mkString(",")}"
                        )
                    engine <- ZIO
                               .fromOption(scalaScriptEngine)
                               .mapError(_ => new IllegalArgumentException("Scala scripts not supported in dev mode"))
                    _      <- ZLogger.debug("Compilation is done !")
                    script <- Task(engine.eval(finalScript).asInstanceOf[FeatureScript])
                    _      <- ZLogger.debug("Compiling script ...")
                  } yield script
                case _ => ZIO.fail(new IllegalArgumentException("Scala scripts not supported in dev mode"))
              }
      } yield res

    def run(featureScript: FeatureScript): RIO[RunnableScriptContext, ScriptExecution] = {
      val scalaConsole = ScalaConsole()
      (PlayModule.ec <*> PlayModule.wSClient)
        .flatMap {
          case (envEc, wSClient) =>
            Task
              .effectAsync[Boolean] { cb =>
                implicit val ec = envEc
                Try {
                  featureScript.run(scalaConsole.println,
                                    context,
                                    () => cb(ZIO.succeed(true)),
                                    () => cb(ZIO.succeed(false)),
                                    wSClient)
                } recover {
                  case e => cb(ZIO.fail(e))
                }
              }
        }
        .map { b =>
          ScriptExecutionSuccess(b, scalaConsole.scriptLogs.logs).asInstanceOf[ScriptExecution]
        }
        .catchSome {
          case e: Exception =>
            ZLogger.error(s"Error executing script, console = ${scalaConsole.scriptLogs.logs}", e) *>
            ZIO.succeed(ScriptExecutionFailure.fromThrowable(scalaConsole.scriptLogs.logs, e))
        }

    }

    blocking.blocking {
      (
        for {
          mayBeScript <- ScriptCache.get[FeatureScript](id)
          _           <- ZLogger.debug(s"Cache for script ? : $mayBeScript")
          script      <- mayBeScript.fold(blocking.blocking(buildScript))(s => ZIO(s))
          _           <- ZLogger.debug(s"Updating cache for id $id and script $script")
          _           <- ScriptCache.set(id, script)
          _           <- ZLogger.debug(s"Running scala script")
          r           <- blocking.blocking(run(script))
        } yield r
      ).catchSome {
        case e: Exception =>
          ZLogger.error(s"Error executing script", e) *>
          ZIO.succeed(ScriptExecutionFailure.fromThrowable(Seq.empty, e))
      }
    }
  }

  private def executeJavascriptScript(script: JavascriptScript,
                                      context: JsObject): zio.RIO[RunnableScriptContext, ScriptExecution] = {
    import zio._

    val exec: RIO[RunnableScriptContext, ScriptExecution] =
      for {
        _        <- ZLogger.debug(s"Creating console")
        console  = JsConsole()
        engine   <- RunnableScriptModule.javascriptScriptEngine
        _        = engine.getContext.setAttribute("console", console, ScriptContext.ENGINE_SCOPE)
        envEc    <- PlayModule.ec
        wSClient <- PlayModule.wSClient
        script <- Task
                   .effectAsync[Boolean] { cb =>
                     implicit val ec = envEc
                     Try {
                       engine.eval(script.script)
                       val enabled                                   = () => cb(ZIO.succeed(true))
                       val disabled                                  = () => cb(ZIO.succeed(false))
                       val contextMap: java.util.Map[String, AnyRef] = jsObjectToMap(context)

                       engine.invokeFunction("enabled", contextMap, enabled, disabled, new HttpClient(wSClient, cb))
                     } recover {
                       case e => cb(ZIO.fail(e))
                     }
                   }
                   .map { enabled =>
                     ScriptExecutionSuccess(enabled, console.scriptLogs.logs).asInstanceOf[ScriptExecution]
                   }
                   .catchSome {
                     case e: Exception =>
                       ZLogger.error(s"Error executing script, console = ${console.scriptLogs.logs}", e) *>
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

class PlayScriptCache(api: AsyncCacheApi) extends CacheService[String] {

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

  implicit val format = {
    import ScriptInstances._
    Json.format[GlobalScript]
  }

}
