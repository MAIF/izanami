package fr.maif.izanami.web

import fr.maif.izanami.env.Env
import fr.maif.izanami.models.RightLevels
import fr.maif.izanami.models.Tag
import fr.maif.izanami.utils.syntax.implicits.BetterSyntax
import play.api.libs.json.JsError
import play.api.libs.json.JsSuccess
import play.api.libs.json.JsValue
import play.api.libs.json.Json
import play.api.mvc._

import scala.concurrent.ExecutionContext
import scala.concurrent.Future

class TagController(
    val env: Env,
    val controllerComponents: ControllerComponents,
    val authAction: TenantAuthActionFactory
) extends BaseController {
  implicit val ec: ExecutionContext = env.executionContext;

  def createTag(tenant: String): Action[JsValue] = authAction(tenant, RightLevels.Write).async(parse.json) {
    implicit request =>
      Future.successful(Forbidden)
      Tag.tagRequestReads.reads(request.body) match {
        case JsError(e)        => BadRequest(Json.obj("message" -> "bad body format")).future
        case JsSuccess(tag, _) => {
          env.datastores.tags
            .createTag(tag, tenant)
            .map(maybeTenant =>
              maybeTenant.fold(
                err => Results.Status(err.status)(Json.toJson(err)),
                tag => Created(Json.toJson(tag))
              )
            )
        }
      }
  }

  def deleteTag(tenant: String, name: String): Action[AnyContent] = authAction(tenant, RightLevels.Write).async {
    implicit request: Request[AnyContent] => env.datastores.tags.deleteTag(tenant, name).map {
      case Left(err) => err.toHttpResponse
      case Right(value) => NoContent
    }
  }

  def readTag(tenant: String, name: String): Action[AnyContent] = authAction(tenant, RightLevels.Read).async {
    implicit request: Request[AnyContent] =>
      env.datastores.tags
        .readTag(tenant, name)
        .map(maybeTag =>
          maybeTag.fold(
            err => Results.Status(err.status)(Json.toJson(err)),
            tag => Ok(Json.toJson(tag))
          )
        )
  }

  def readTags(tenant: String): Action[AnyContent] = authAction(tenant, RightLevels.Read).async { implicit request: Request[AnyContent] =>
    env.datastores.tags.readTags(tenant).map(tags => Ok(Json.toJson(tags)))
  }

  def updateTag(tenant: String, currentName: String): Action[JsValue] = authAction(tenant, RightLevels.Write).async(parse.json) {
    implicit request =>
      Tag.tagReads.reads(request.body) match {
        case JsError(e)        => BadRequest(Json.obj("message" -> "bad body format")).future
        case JsSuccess(tag, _) => {
          env.datastores.tags
            .updateTag(tag, tenant,currentName)
            .map(maybeTenant =>
              maybeTenant.fold(
                err => Results.Status(err.status)(Json.toJson(err)),
                tag => NoContent
              )
            )
        }
      }
  }

}
