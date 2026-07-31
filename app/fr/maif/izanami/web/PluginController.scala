package fr.maif.izanami.web

import fr.maif.izanami.datastores.{ConfigurationDatastore, FeaturesDatastore}
import fr.maif.izanami.models.RightLevel
import fr.maif.izanami.utils.syntax.implicits.BetterSyntax
import fr.maif.izanami.wasm.WasmConfig
import fr.maif.izanami.wasm.WasmConfigWithFeatures
import io.otoroshi.wasm4s.scaladsl.{WasmIntegration, WasmoSettings}
import play.api.Logger
import play.api.libs.json.JsValue
import play.api.libs.json.Json
import play.api.libs.ws.WSClient
import play.api.mvc.*

import scala.concurrent.ExecutionContext
import scala.util.Failure
import scala.util.Success
import scala.util.Try

class PluginController(
    val controllerComponents: ControllerComponents,
    authAction: =>TenantAuthActionFactory,
    adminAuthAction: =>AdminAuthAction,
    featuresDatastore: =>FeaturesDatastore,
    configurationDatastore: =>ConfigurationDatastore,
    wsClient: =>WSClient,
    wasmIntegration: =>WasmIntegration
)(implicit val ec: ExecutionContext) extends BaseController {
  private val logger = Logger("PlutinController")

  // TODO authenticate
  def localScripts(tenant: String, features: Boolean): Action[AnyContent] =
    Action.async { implicit request =>
      if (features) {
        featuresDatastore
          .readLocalScriptsWithAssociatedFeatures(tenant)
          .map(configs =>
            Ok(Json.toJson(configs.map(w =>
              Json.toJson(w)(
                WasmConfigWithFeatures.wasmConfigWithFeaturesWrites
              )
            )))
          )
      } else {
        featuresDatastore
          .readLocalScripts(tenant)
          .map(configs =>
            Ok(Json.toJson(configs.map(w => Json.toJson(w)(WasmConfig.format))))
          )
      }
    }

  def readScript(tenant: String, script: String): Action[AnyContent] =
    authAction(tenant, RightLevel.Read).async { implicit request =>
      featuresDatastore
        .readWasmScript(tenant, script)
        .map(maybeConfig =>
          maybeConfig.fold(
            NotFound(Json.obj("message" -> s"Script $script not found"))
          )(script =>
            Ok(Json.toJson(script)(WasmConfig.format))
          )
        )
    }

  def deleteScript(tenant: String, script: String): Action[AnyContent] =
    authAction(tenant, RightLevel.Write).async {
      implicit request =>
        featuresDatastore.deleteLocalScript(tenant, script).toResult(_ => NoContent)
    }

  def updateScript(tenant: String, script: String): Action[JsValue] =
    authAction(tenant, RightLevel.Write).async(parse.json) { implicit request =>
      request.body.asOpt[WasmConfig](WasmConfig.format) match {
        case Some(value) =>
          featuresDatastore.updateWasmScript(tenant, script, value).map(
            _ => NoContent
          )
        case None => BadRequest(Json.obj("message" -> "Bad body format")).future
      }
    }

  // TODO basic authentication
  def wasmFiles(): Action[AnyContent] = Action.async { implicit request =>
    configurationDatastore
      .readWasmConfiguration() match {
      case Some(settings @ WasmoSettings(url, _, _, pluginsFilter, _, _)) =>
        Try {
          val userHeader =
            io.otoroshi.wasm4s.scaladsl.ApikeyHelper.generate(settings)
          wsClient
            .url(s"$url/plugins")
            .withFollowRedirects(false)
            .withHttpHeaders(
              "Accept" -> "application/json",
              userHeader,
              "kind" -> pluginsFilter.getOrElse("*")
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
              logger.error(s"Failed to retrieve wasm scripts", e)
              Ok(Json.arr())
            }
        } match {
          case Failure(err) => {
            logger.error(s"Failed to retrieve wasm scripts", err)
            Ok(Json.arr()).future
          }
          case Success(v) => v
        }

      case _ =>
        BadRequest(
          Json.obj(
            "message" -> "Missing config in global configuration"
          )
        ).future
    }
  }

  def clearWasmCache(): Action[AnyContent] = adminAuthAction.async {
    implicit request =>
      wasmIntegration.context.wasmScriptCache.clear().future.map(_ =>
        NoContent
      )
  }

}
