package fr.maif.izanami.web

import fr.maif.izanami.models.LightWebhook
import fr.maif.izanami.models.RightLevel
import fr.maif.izanami.models.Webhook
import fr.maif.izanami.utils.syntax.implicits.BetterSyntax
import play.api.libs.json.{
  JsError,
  JsObject,
  JsSuccess,
  JsValue,
  Json,
  Reads,
  Writes
}
import play.api.mvc.Action
import play.api.mvc.AnyContent
import play.api.mvc.BaseController
import play.api.mvc.ControllerComponents

import java.util.UUID
import scala.concurrent.ExecutionContext
import scala.concurrent.Future
import scala.util.Try
import fr.maif.izanami.services.WebhookService

class WebhookController(
    val controllerComponents: ControllerComponents,
    val tenantAuthAction: TenantAuthActionFactory,
    val webhookAuthAction: WebhookAuthActionFactory,
    val webhookService: WebhookService,
    val authenticatedAction: AuthenticatedAction
)(implicit val ec: ExecutionContext)
    extends BaseController {
  implicit val lightWebhookRead: Reads[LightWebhook] =
    LightWebhook.lightWebhookRead
  implicit val webhookWrite: Writes[Webhook] = Webhook.webhookWrite

  def createWebhook(tenant: String): Action[JsValue] =
    tenantAuthAction(tenant, RightLevel.Write).async(parse.json) {
      implicit request =>
        {
          LightWebhook.lightWebhookRead.reads(request.body) match {
            case JsSuccess(l: LightWebhook, _) =>
              webhookService.createWebhook(
                tenant = tenant,
                webhook = l,
                user = request.user
              ).toResult(id => Created(Json.obj("id" -> id)))
            case JsError(errors) => Future.successful(
                BadRequest(Json.obj("message" -> "Bad body format"))
              )
          }
        }
    }

  def listWebhooks(tenant: String): Action[AnyContent] =
    tenantAuthAction(tenant, RightLevel.Read).async {
      implicit request =>
        webhookService.listWebhook(tenant, request.user.username).map(ws =>
          Ok(Json.toJson(ws))
        )
    }

  def deleteWebhook(tenant: String, id: String): Action[AnyContent] =
    (webhookAuthAction(
      tenant = tenant,
      webhook = id,
      minimumLevel = RightLevel.Admin
    )).async { implicit request =>
      webhookService
        .deleteWebhook(tenant, id)
        .toResult(_ => NoContent)
    }

  def updateWebhook(tenant: String, id: String): Action[JsValue] =
    webhookAuthAction(
      tenant = tenant,
      webhook = id,
      minimumLevel = RightLevel.Write
    ).async(parse.json) {
      implicit request =>
        {
          (for (
            uuid <- Try { UUID.fromString(id) }.toOption;
            webhook <- LightWebhook.lightWebhookRead.reads(request.body).asOpt
          ) yield {
            webhookService.updateWebhook(
              tenant = tenant,
              webhook = webhook,
              id = uuid
            ).toResult(_ => NoContent)
          }).getOrElse(BadRequest(Json.obj(
            "message" -> "Bad body request and / or bad uuid provided as id"
          )).future)

        }
    }

  def executeHandleBars(): Action[JsValue] =
    authenticatedAction.async(parse.json) {
      implicit request: UserNameRequest[JsValue] =>
        {
          (for (
            objectBody <- request.body.asOpt[JsObject].toRight(
              "Expected JSON object body"
            );
            template <- (objectBody \ "template").asOpt[String].toRight(
              "Missing handlebars template in body"
            );
            payload <- (objectBody \ "payload").asOpt[String].map(str =>
              Json.parse(str)
            ).toRight("Missing payload in body");
            result <- webhookService.executeHandlebars(
              template,
              json = payload.toString()
            ).left.map(err => err.message)
          ) yield {
            result
          }).fold(
            error => Future.successful(Ok(Json.obj("error" -> error))),
            result => Future.successful(Ok(Json.obj("result" -> result)))
          )
        }
    }
}
