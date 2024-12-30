package fr.maif.izanami.web

import fr.maif.izanami.env.Env
import fr.maif.izanami.models.RightLevels
import fr.maif.izanami.utils.syntax.implicits.BetterSyntax
import fr.maif.izanami.wasm.WasmConfig
import fr.maif.izanami.wasm.WasmConfigWithFeatures
import io.otoroshi.wasm4s.scaladsl.WasmoSettings
import play.api.libs.json.JsValue
import play.api.libs.json.Json
import play.api.mvc._

import scala.concurrent.ExecutionContext
import scala.util.Failure
import scala.util.Success
import scala.util.Try

class PluginController(
    val env: Env,
    val controllerComponents: ControllerComponents,
    val authAction: TenantAuthActionFactory,
    val adminAuthAction: AdminAuthAction
) extends BaseController {
  implicit val ec: ExecutionContext = env.executionContext;

  // TODO authenticate
  def localScripts(tenant: String, features: Boolean): Action[AnyContent] = Action.async { implicit request =>
    if (features) {
      env.datastores.features
        .readLocalScriptsWithAssociatedFeatures(tenant)
        .map(configs =>
          Ok(Json.toJson(configs.map(w => Json.toJson(w)(WasmConfigWithFeatures.wasmConfigWithFeaturesWrites))))
        )
    } else {
      env.datastores.features
        .readLocalScripts(tenant)
        .map(configs => Ok(Json.toJson(configs.map(w => Json.toJson(w)(WasmConfig.format)))))
    }
  }

  def readScript(tenant: String, script: String): Action[AnyContent] = authAction(tenant, RightLevels.Read).async { implicit request =>
    env.datastores.features
      .readWasmScript(tenant, script)
      .map(maybeConfig =>
        maybeConfig.fold(NotFound(Json.obj("message" -> s"Script $script not found")))(script =>
          Ok(Json.toJson(script)(WasmConfig.format))
        )
      )
  }

  def deleteScript(tenant: String, script: String): Action[AnyContent] = authAction(tenant, RightLevels.Write).async {
    implicit request =>
      env.datastores.features.deleteLocalScript(tenant, script).map {
        case Left(err) => err.toHttpResponse
        case Right(_)  => NoContent
      }
  }

  def updateScript(tenant: String, script: String): Action[JsValue] =
    authAction(tenant, RightLevels.Write).async(parse.json) { implicit request =>
      request.body.asOpt[WasmConfig](WasmConfig.format) match {
        case Some(value) => env.datastores.features.updateWasmScript(tenant, script, value).map(_ => NoContent)
        case None        => BadRequest(Json.obj("message" -> "Bad body format")).future
      }
    }

  // TODO basic authentication
  def wasmFiles(): Action[AnyContent] = Action.async { implicit request =>
    env.datastores.configuration
      .readWasmConfiguration() match {
      case Some(settings @ WasmoSettings(url, _, _, pluginsFilter, _, _)) =>
        Try {
          val userHeader = io.otoroshi.wasm4s.scaladsl.ApikeyHelper.generate(settings)
          env.Ws
            .url(s"$url/plugins")
            .withFollowRedirects(false)
            .withHttpHeaders(
              "Accept" -> "application/json",
              userHeader,
              "kind"   -> pluginsFilter.getOrElse("*")
            )
            .get()
            .map(res => {
              if (res.status == 200) {
                Ok(res.json)
              } else {
                Ok(Json.arr())
              }
            })
            .recover { case e: Throwable =>
              env.logger.error(s"Failed to retrieve wasm scripts", e)
              Ok(Json.arr())
            }
        } match {
          case Failure(err) => {
            env.logger.error(s"Failed to retrieve wasm scripts", err)
            Ok(Json.arr()).future
          }
          case Success(v)   => v
        }

      case _ =>
        BadRequest(
          Json.obj(
            "message" -> "Missing config in global configuration"
          )
        ).future
    }
  }

  def clearWasmCache(): Action[AnyContent] = adminAuthAction.async { implicit request =>
    env.wasmIntegration.context.wasmScriptCache.clear().future.map(_ => NoContent)
  }

}
