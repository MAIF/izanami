package fr.maif.izanami.wasm.host.scala

import akka.http.scaladsl.model.Uri
import akka.stream.Materializer
import akka.util.ByteString
import fr.maif.izanami.env.Env
import fr.maif.izanami.utils.RegexPool
import fr.maif.izanami.utils.syntax.implicits.BetterJsValue
import fr.maif.izanami.utils.syntax.implicits.BetterSyntax
import fr.maif.izanami.wasm.WasmConfig
import io.otoroshi.wasm4s.scaladsl.EnvUserData
import io.otoroshi.wasm4s.scaladsl.HostFunctionWithAuthorization
import org.extism.sdk.ExtismCurrentPlugin
import org.extism.sdk.ExtismFunction
import org.extism.sdk.HostFunction
import org.extism.sdk.HostUserData
import org.extism.sdk.LibExtism
import play.api.Logger
import play.api.libs.json.JsValue
import play.api.libs.json.Json
import play.api.libs.typedmap.TypedMap

import java.nio.charset.StandardCharsets
import java.util.Optional
import java.util.concurrent.TimeUnit
import scala.collection.Seq
import scala.concurrent.Await
import scala.concurrent.ExecutionContext
import scala.concurrent.duration.Duration

object HFunction {
  def defineContextualFunction(
      fname: String,
      config: WasmConfig
  )(
      f: (ExtismCurrentPlugin, Array[LibExtism.ExtismVal], Array[LibExtism.ExtismVal], EnvUserData) => Unit
  )(implicit env: Env, ec: ExecutionContext, mat: Materializer): HostFunction[EnvUserData] = {
    val ev = EnvUserData(env.wasmIntegration.context, ec, mat, config)
    defineFunction[EnvUserData](
      fname,
      ev.some,
      LibExtism.ExtismValType.I64,
      LibExtism.ExtismValType.I64,
      LibExtism.ExtismValType.I64
    )((p1, p2, p3, _) => f(p1, p2, p3, ev))
  }

  def defineFunction[A <: EnvUserData](
      fname: String,
      data: Option[A],
      returnType: LibExtism.ExtismValType,
      params: LibExtism.ExtismValType*
  )(
      f: (ExtismCurrentPlugin, Array[LibExtism.ExtismVal], Array[LibExtism.ExtismVal], Option[A]) => Unit
  ): HostFunction[A] = {
    new HostFunction[A](
      fname,
      Array(params: _*),
      Array(returnType),
      new ExtismFunction[A] {
        override def invoke(
            plugin: ExtismCurrentPlugin,
            params: Array[LibExtism.ExtismVal],
            returns: Array[LibExtism.ExtismVal],
            data: Optional[A]
        ): Unit = {
          f(plugin, params, returns, if (data.isEmpty) None else Some(data.get()))
        }
      },
      data match {
        case None    => Optional.empty[A]()
        case Some(d) => Optional.of(d)
      }
    )
  }
}

object Utils {
  def rawBytePtrToString(plugin: ExtismCurrentPlugin, offset: Long, arrSize: Long): String = {
    val memoryLength = plugin.memoryLength(arrSize)
    val arr          = plugin
      .memory()
      .share(offset, memoryLength)
      .getByteArray(0, arrSize.toInt)
    new String(arr, StandardCharsets.UTF_8)
  }

  def contextParamsToString(plugin: ExtismCurrentPlugin, params: LibExtism.ExtismVal*): String = {
    rawBytePtrToString(plugin, params(0).v.i64, params(1).v.i32)
  }

  def contextParamsToJson(plugin: ExtismCurrentPlugin, params: LibExtism.ExtismVal*): JsValue = {
    Json.parse(rawBytePtrToString(plugin, params(0).v.i64, params(1).v.i32))
  }
}

object LogLevel extends Enumeration {
  type LogLevel = Value

  val LogLevelTrace, LogLevelDebug, LogLevelInfo, LogLevelWarn, LogLevelError, LogLevelCritical, LogLevelMax = Value
}

object Status extends Enumeration {
  type Status = Value

  val StatusOK, StatusNotFound, StatusBadArgument, StatusEmpty, StatusCasMismatch, StatusInternalFailure,
      StatusUnimplemented = Value
}

object Logging {

  val logger: Logger = Logger("izanami-wasm-logger")

  def proxyLog(): HostFunction[EnvUserData] = HFunction.defineFunction(
    "proxy_log",
    None,
    LibExtism.ExtismValType.I32,
    LibExtism.ExtismValType.I32,
    LibExtism.ExtismValType.I64,
    LibExtism.ExtismValType.I64
  ) { (plugin, params, returns, data) =>
    val logLevel = LogLevel(params(0).v.i32)

    val messageData = Utils.rawBytePtrToString(plugin, params(1).v.i64, params(2).v.i64)

    logLevel match {
      case LogLevel.LogLevelTrace => logger.trace(messageData)
      case LogLevel.LogLevelDebug => logger.debug(messageData)
      case LogLevel.LogLevelInfo  => logger.info(messageData)
      case LogLevel.LogLevelWarn  => logger.warn(messageData)
      case _                      => logger.error(messageData)
    }

    returns(0).v.i32 = Status.StatusOK.id
  }

  def getFunctions(config: WasmConfig)(implicit
      env: Env,
      executionContext: ExecutionContext,
      mat: Materializer
  ): Seq[HostFunctionWithAuthorization] = {
    Seq(
      HostFunctionWithAuthorization(proxyLog(), _ => true)
    )
  }
}

object HttpCall {
  def proxyHttpCall(config: WasmConfig)(implicit env: Env, executionContext: ExecutionContext, mat: Materializer): HostFunction[EnvUserData] = {
    HFunction
      .defineContextualFunction("proxy_http_call", config) {
        (
            plugin: ExtismCurrentPlugin,
            params: Array[LibExtism.ExtismVal],
            returns: Array[LibExtism.ExtismVal],
            hostData: EnvUserData
        ) =>
          {
            val context      = Json.parse(Utils.contextParamsToString(plugin, params.toIndexedSeq: _*))
            val url          = (context \ "url").asOpt[String].getOrElse("https://request.otoroshi.io")
            val allowedHosts = hostData.config.allowedHosts
            val urlHost      = Uri(url).authority.host.toString()
            val allowed      = allowedHosts.isEmpty || allowedHosts.contains("*") || allowedHosts.exists(h =>
              RegexPool(h).matches(urlHost)
            )
            if (allowed) {
              val builder = env.Ws
                .url(url)
                .withMethod((context \ "method").asOpt[String].getOrElse("GET"))
                .withHttpHeaders((context \ "headers").asOpt[Map[String, String]].getOrElse(Map.empty).toSeq: _*)
                .withRequestTimeout(
                  Duration(
                    (context \ "request_timeout").asOpt[Long].getOrElse(30000L), // TODO
                    TimeUnit.MILLISECONDS
                  )
                )
                .withFollowRedirects((context \ "follow_redirects").asOpt[Boolean].getOrElse(false))
                .withQueryStringParameters(
                  (context \ "query").asOpt[Map[String, String]].getOrElse(Map.empty).toSeq: _*
                )
              val bodyAsBytes              = context.select("body_bytes").asOpt[Array[Byte]].map(bytes => ByteString(bytes))
              val bodyBase64               = context.select("body_base64").asOpt[String].map(str => ByteString(str).decodeBase64)
              val bodyJson                 = context.select("body_json").asOpt[JsValue].map(str => ByteString(str.stringify))
              val bodyStr                  = context
                .select("body_str")
                .asOpt[String]
                .orElse(context.select("body").asOpt[String])
                .map(str => ByteString(str))
              val body: Option[ByteString] = bodyStr.orElse(bodyJson).orElse(bodyBase64).orElse(bodyAsBytes)
              val request                  = body match {
                case Some(bytes) => builder.withBody(bytes)
                case None        => builder
              }
              val out                      = Await.result(
                request
                  .execute()
                  .map { res =>
                    val body    = res.bodyAsBytes.encodeBase64.utf8String
                    val headers = res.headers.view.mapValues(_.head)
                    Json.obj(
                      "status"      -> res.status,
                      "headers"     -> headers,
                      "body_base64" -> body
                    )
                  },
                Duration(1, TimeUnit.MINUTES) // TODO
              )
              plugin.returnString(returns(0), Json.stringify(out))
            } else {
              plugin.returnString(
                returns(0),
                Json.stringify(
                  Json.obj(
                    "status"      -> 403,
                    "headers"     -> Json.obj("content-type" -> "text/plain"),
                    "body_base64" -> ByteString(s"you cannot access host: ${urlHost}").encodeBase64.utf8String
                  )
                )
              )
            }
          }
      }
      .withNamespace("env")
  }

  def getFunctions(config: WasmConfig, attrs: Option[TypedMap])(implicit
      env: Env,
      executionContext: ExecutionContext,
      mat: Materializer
  ): Seq[HostFunctionWithAuthorization] = {
    Seq(
      HostFunctionWithAuthorization(proxyHttpCall(config), _.asInstanceOf[WasmConfig].authorizations.httpAccess)
    )
  }
}

object HostFunctions {

  def getFunctions(config: WasmConfig, pluginId: String, attrs: Option[TypedMap])(implicit
      env: Env,
      executionContext: ExecutionContext
  ): Array[HostFunction[_ <: HostUserData]] = {

    implicit val mat = env.materializer

    val httpFunctions: Seq[HostFunctionWithAuthorization] = HttpCall.getFunctions(config, attrs)

    val functions: Seq[HostFunctionWithAuthorization] = httpFunctions

    functions
      .collect {
        case func if func.authorized(config) => func.function
      }
      .seffectOn(_.map(_.name).mkString(", "))
      .toArray
  }
}
