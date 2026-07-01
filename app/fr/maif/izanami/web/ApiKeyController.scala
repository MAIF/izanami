package fr.maif.izanami.web

import play.api.libs.json.JsError.toJson
import play.api.libs.json.JsValue
import play.api.libs.json.Json
import play.api.mvc.*

import scala.concurrent.ExecutionContext
import scala.concurrent.Future
import fr.maif.izanami.services.RightService
import fr.maif.izanami.services.APIKeyService
import fr.maif.izanami.models.RightLevel
import fr.maif.izanami.models.ApiKey
import fr.maif.izanami.models.ReadTenantKeys
import fr.maif.izanami.models.DeleteKey
import fr.maif.izanami.errors.BadBodyFormat
import fr.maif.izanami.utils.FutureEither

class ApiKeyController(
    val controllerComponents: ControllerComponents,
    val tenantAuthAction: TenantAuthActionFactory,
    val keyAuthAction: KeyAuthActionFactory,
    val tokenAuthAction: PersonnalAccessTokenKeyAuthActionFactory,
    val pacTenantAuthAction: PersonnalAccessTokenTenantAuthActionFactory,
    val rightService: RightService,
    val apiKeyService: APIKeyService
)(implicit val ec: ExecutionContext)
    extends BaseController {

  def createApiKey(tenant: String): Action[JsValue] =
    tenantAuthAction(tenant, RightLevel.Write).async(parse.json) {
      implicit request =>
        FutureEither.from(ApiKey
          .read(request.body, tenant)
          .asEither
          .left
          .map(_ => BadBodyFormat()))
          .flatMap(key => {
            apiKeyService.createAPIKey(
              tenant = tenant,
              key = key,
              user = request.user
            )
          })
          .toResult(key => Created(Json.toJson(key)))
    }

  def updateApiKey(tenant: String, name: String): Action[JsValue] =
    keyAuthAction(tenant, name, RightLevel.Write).async(parse.json) {
      implicit request: UserNameRequest[JsValue] =>
        FutureEither.from(ApiKey
          .read(request.body, tenant)
          .asEither
          .left
          .map(_ => BadBodyFormat()))
          .flatMap(key => {
            apiKeyService.updateAPIKey(
              tenant = tenant,
              oldName = name,
              newKey = key,
              user = request.user
            )
          })
          .toResult(_ => {
            NoContent
          })

    }

  def readApiKey(tenant: String): Action[AnyContent] =
    pacTenantAuthAction(tenant, RightLevel.Read, ReadTenantKeys).async {
      implicit request: UserNameRequest[AnyContent] =>
        apiKeyService
          .readVisibleAPIKeysForUser(tenant, request.user.username)
          .map(keys => Ok(Json.toJson(keys)))
    }

  def deleteApiKey(tenant: String, name: String): Action[AnyContent] =
    (tokenAuthAction(tenant, name, RightLevel.Admin, DeleteKey)).async {
      implicit request =>
        apiKeyService
          .deleteApiKey(tenant, name)
          .toResult(_ => NoContent)
    }
}
