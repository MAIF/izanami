package fr.maif.izanami.wasm

import fr.maif.izanami.env.Env
import fr.maif.izanami.errors.{IzanamiError, WasmError}
import fr.maif.izanami.models.RequestContext
import fr.maif.izanami.utils.syntax.implicits.BetterJsValue
import io.otoroshi.wasm4s.scaladsl._
import fr.maif.izanami.models.RequestContext
import fr.maif.izanami.utils.syntax.implicits.BetterJsValue
import io.otoroshi.wasm4s.scaladsl._
import play.api.libs.json._

import scala.concurrent.{ExecutionContext, Future}
import scala.util.{Failure, Success, Try}

case class WasmAuthorizations(
    httpAccess: Boolean = false
)                 {
  def json: JsValue = WasmAuthorizations.format.writes(this)
}

object WasmAuthorizations {
  val format = new Format[WasmAuthorizations] {
    override def writes(o: WasmAuthorizations): JsValue             = Json.obj(
      "httpAccess" -> o.httpAccess
    )
    override def reads(json: JsValue): JsResult[WasmAuthorizations] = Try {
      WasmAuthorizations(
        httpAccess = (json \ "httpAccess").asOpt[Boolean].getOrElse(false)
      )
    } match {
      case Failure(ex)    => JsError(ex.getMessage)
      case Success(value) => JsSuccess(value)
    }
  }
}

case class WasmScriptAssociatedFeatures(id: String, name: String, project: String)

case class WasmConfigWithFeatures(wasmConfig: WasmConfig, features: Seq[WasmScriptAssociatedFeatures])

object WasmConfigWithFeatures {
  implicit val wasmConfigAssociatedFeaturesWrites: Writes[WasmScriptAssociatedFeatures] = { feature =>
    Json.obj(
      "name" -> feature.name,
      "project" -> feature.project,
      "id" -> feature.id
    )
  }

  implicit val wasmConfigWithFeaturesWrites: Writes[WasmConfigWithFeatures] = { wasm =>
    Json.obj(
      "config" -> Json.toJson(wasm.wasmConfig)(WasmConfig.format),
      "features" -> wasm.features
    )
  }
}

case class WasmConfig(
    name: String,
    source: WasmSource = WasmSource(WasmSourceKind.Unknown, "", Json.obj()),
    memoryPages: Int = 100,
    functionName: Option[String] = None,
    config: Map[String, String] = Map.empty,
    allowedHosts: Seq[String] = Seq.empty,
    allowedPaths: Map[String, String] = Map.empty,
    ////
    // lifetime: WasmVmLifetime = WasmVmLifetime.Forever,
    wasi: Boolean = false,
    opa: Boolean = false,
    instances: Int = 1,
    killOptions: WasmVmKillOptions = WasmVmKillOptions.default,
    authorizations: WasmAuthorizations = WasmAuthorizations()
) extends WasmConfiguration {
  // still here for compat reason
  def json: JsValue                         = Json.obj(
    "name"           -> name,
    "source"         -> source.json,
    "memoryPages"    -> memoryPages,
    "functionName"   -> functionName,
    "config"         -> config,
    "allowedHosts"   -> allowedHosts,
    "allowedPaths"   -> allowedPaths,
    "wasi"           -> wasi,
    "opa"            -> opa,
    // "lifetime"       -> lifetime.json,
    "authorizations" -> authorizations.json,
    "instances"      -> instances,
    "killOptions"    -> killOptions.json
  )
}

object WasmConfig {
  val format = new Format[WasmConfig] {
    override def reads(json: JsValue): JsResult[WasmConfig] = Try {
      val compilerSource = json.select("compiler_source").asOpt[String]
      val rawSource      = json.select("raw_source").asOpt[String]
      val sourceOpt      = json.select("source").asOpt[JsObject]

      json
        .select("name")
        .asOpt[String]
        .map(name => {
          val source = if (sourceOpt.isDefined) {
            WasmSource.format.reads(sourceOpt.get).get
          } else {
            compilerSource match {
              case Some(source) => WasmSource(WasmSourceKind.Wasmo, source, Json.obj("name" -> name))
              case None         =>
                rawSource match {
                  case Some(source) if source.startsWith("http://")   => WasmSource(WasmSourceKind.Http, source, Json.obj("name" -> name))
                  case Some(source) if source.startsWith("https://")  => WasmSource(WasmSourceKind.Http, source,Json.obj("name" -> name))
                  case Some(source) if source.startsWith("file://")   =>
                    WasmSource(WasmSourceKind.File, source.replace("file://", ""), Json.obj("name" -> name))
                  case Some(source) if source.startsWith("base64://") =>
                    WasmSource(WasmSourceKind.Base64, source.replace("base64://", ""), Json.obj("name" -> name))
                  case Some(source) if source.startsWith("entity://") =>
                    WasmSource(WasmSourceKind.Local, source.replace("entity://", ""), Json.obj("name" -> name))
                  case Some(source) if source.startsWith("local://")  =>
                    WasmSource(WasmSourceKind.Local, source.replace("local://", ""), Json.obj("name" -> name))
                  case Some(source)                                   => WasmSource(WasmSourceKind.Base64, source, Json.obj("name" -> name))
                  case _                                              => WasmSource(WasmSourceKind.Unknown, "", Json.obj("name" -> name))
                }
            }
          }

          WasmConfig(
            name = name,
            source = source,
            memoryPages = (json \ "memoryPages").asOpt[Int].getOrElse(100),
            functionName = (json \ "functionName").asOpt[String].filter(_.nonEmpty),
            config = (json \ "config").asOpt[Map[String, String]].getOrElse(Map.empty),
            allowedHosts = (json \ "allowedHosts").asOpt[Seq[String]].getOrElse(Seq.empty),
            allowedPaths = (json \ "allowedPaths").asOpt[Map[String, String]].getOrElse(Map.empty),
            wasi = (json \ "wasi").asOpt[Boolean].getOrElse(true),
            opa = (json \ "opa").asOpt[Boolean].getOrElse(false),
            authorizations = (json \ "authorizations")
              .asOpt[WasmAuthorizations](WasmAuthorizations.format.reads)
              .orElse((json \ "accesses").asOpt[WasmAuthorizations](WasmAuthorizations.format.reads))
              .getOrElse {
                WasmAuthorizations()
              },
            instances = json.select("instances").asOpt[Int].getOrElse(1),
            killOptions = json
              .select("killOptions")
              .asOpt[JsValue]
              .flatMap(v => WasmVmKillOptions.format.reads(v).asOpt)
              .getOrElse(WasmVmKillOptions.default)
          )
        })

    } match {
      case Failure(ex)          => JsError(ex.getMessage)
      case Success(Some(value)) => JsSuccess(value)
      case Success(None)        => JsError("Missing wasm configuration name")
    }
    override def writes(o: WasmConfig): JsValue             = o.json
  }
}

object WasmUtils {
  def handle(config: WasmConfig, requestContext: RequestContext)(implicit ec: ExecutionContext, env: Env): Future[Either[IzanamiError, Boolean]] = {
    val context = (requestContext.wasmJson.as[JsObject] ++ Json.obj(
      "id" -> requestContext.user, "context" -> requestContext.data, "executionContext" -> requestContext.context.elements
    )).stringify
    env.wasmIntegration.withPooledVm(config) { vm =>
      if (config.opa) {
        vm.callOpa("execute", context).map {
          case Left(err) => throw new RuntimeException(s"Failed to execute wasm feature : ${err.toString()}") // TODO - fix me
          case Right((rawResult, _)) => {
            val response = Json.parse(rawResult)
            val result = response.asOpt[JsArray].getOrElse(Json.arr())
            (result.value.head \ "result").asOpt[Boolean]
              .orElse((result.value.head \ "result").asOpt[String].flatMap(s => s.toBooleanOption))
              .toRight({
                env.logger.error(s"Failed to parse wasm result (OPA), result is $result")
                WasmError()
              })
          }
        }
      } else {
        vm.callExtismFunction("execute", context).map {
          case Left(err) => throw new RuntimeException(s"Failed to execute wasm feature : ${err.toString()}") // TODO - fix me
          case Right(rawResult) => {
            if (rawResult.startsWith("{")) {
              val response = Json.parse(rawResult)

              (response \ "active").asOpt[Boolean]
                .orElse((response \ "active").asOpt[String].flatMap(s => s.toBooleanOption))
                .toRight({
                  env.logger.error(s"Failed to parse wasm result, result is $response")
                  WasmError()
                })
            } else {
              rawResult.toBooleanOption.toRight({
                env.logger.error(s"Failed to parse wasm result, result is $rawResult")
                WasmError()
              })
            }
          }
        }
      }
    }
  }
}