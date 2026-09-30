package fr.maif.izanami.web

import fr.maif.izanami.models.ReadTenant
import fr.maif.izanami.models.RightLevel
import fr.maif.izanami.models.RightLevel.Read
import fr.maif.izanami.models.Tag
import fr.maif.izanami.utils.syntax.implicits.BetterSyntax
import play.api.libs.json.JsError
import play.api.libs.json.JsSuccess
import play.api.libs.json.JsValue
import play.api.libs.json.Json
import play.api.mvc.*

import scala.concurrent.ExecutionContext
import scala.concurrent.Future
import fr.maif.izanami.services.TagService

class TagController(
    val controllerComponents: ControllerComponents,
    private val authAction: TenantAuthActionFactory,
    private val personnalAccessTokenTenantAuthAction: PersonnalAccessTokenTenantAuthActionFactory,
    private val tagService: TagService
)(implicit ec: ExecutionContext) extends BaseController {

  def createTag(tenant: String): Action[JsValue] =
    authAction(tenant, RightLevel.Write).async(parse.json) {
      implicit request =>
        Future.successful(Forbidden)
        Tag.tagRequestReads.reads(request.body) match {
          case JsError(e) =>
            BadRequest(Json.obj("message" -> "bad body format")).future
          case JsSuccess(tag, _) => {
            tagService.createTag(tag, tenant).toResult(tag =>
              Created(Json.toJson(tag))
            )
          }
        }
    }

  def deleteTag(tenant: String, name: String): Action[AnyContent] =
    authAction(tenant, RightLevel.Write).async {
      implicit request: Request[AnyContent] =>
        tagService.deleteTag(tenant, name).toResult(_ => NoContent)
    }

  def readTag(tenant: String, name: String): Action[AnyContent] =
    personnalAccessTokenTenantAuthAction(
      tenant = tenant,
      minimumLevel = Read,
      operation = ReadTenant
    ).async {
      implicit request: Request[AnyContent] =>
        tagService.readTag(tenant, name).toResult(tag => Ok(Json.toJson(tag)))
    }

  def readTags(tenant: String): Action[AnyContent] =
    personnalAccessTokenTenantAuthAction(
      tenant = tenant,
      minimumLevel = Read,
      operation = ReadTenant
    ).async { implicit request: Request[AnyContent] =>
      tagService.readTags(tenant).map(tags => Ok(Json.toJson(tags)))
    }

  def updateTag(tenant: String, currentName: String): Action[JsValue] =
    authAction(tenant, RightLevel.Write).async(parse.json) {
      implicit request =>
        Tag.tagReads.reads(request.body) match {
          case JsError(e) =>
            BadRequest(Json.obj("message" -> "bad body format")).future
          case JsSuccess(tag, _) => {
            tagService.updateTag(tag, tenant, currentName)
              .toResult(_ => NoContent)
          }
        }
    }
}
