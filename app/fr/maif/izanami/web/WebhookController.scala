package fr.maif.izanami.web

import com.github.jknack.handlebars.Handlebars
import fr.maif.izanami.env.Env
import fr.maif.izanami.models.{LightWebhook, RightLevels, Webhook}
import fr.maif.izanami.utils.syntax.implicits.BetterSyntax
import play.api.libs.json.{JsError, JsSuccess, JsValue, Json, Reads, Writes}
import play.api.mvc.{Action, AnyContent, BaseController, ControllerComponents}
import fr.maif.izanami.utils.ControllerHelpers

import java.util.UUID
import scala.concurrent.{ExecutionContext, Future}
import scala.util.Try

class WebhookController(
    val controllerComponents: ControllerComponents,
    val tenantAuthAction: TenantAuthActionFactory,
    val webhookAuthAction: WebhookAuthActionFactory
)(implicit val env: Env)
    extends BaseController {
  implicit val executionContext: ExecutionContext    = env.executionContext
  implicit val lightWebhookRead: Reads[LightWebhook] = LightWebhook.lightWebhookRead
  implicit val webhookWrite: Writes[Webhook]         = Webhook.webhookWrite
  private val handlebars                             = new Handlebars()
  def createWebhook(tenant: String): Action[JsValue] = tenantAuthAction(tenant, RightLevels.Write).async(parse.json) {
    implicit request =>
      {
        LightWebhook.lightWebhookRead.reads(request.body) match {
          case JsSuccess(l: LightWebhook, _) if l.global && (l.features.nonEmpty || l.projects.nonEmpty) =>
            BadRequest(Json.obj("message" -> "Webhook can't be global and specify features or projects")).future
          case JsSuccess(l: LightWebhook, _) if !l.global && l.features.isEmpty && l.projects.isEmpty    =>
            BadRequest(Json.obj("message" -> "Webhook must either be global or specify features or projects")).future
          case JsSuccess(webhook, _)                                                                     => {
            webhook.bodyTemplate
              .map(template => {
                Try {
                  handlebars.compileInline(template)
                  webhook
                }.toEither
              })
              .getOrElse(Right(webhook))
              .fold(
                ex => {
                  Future.successful(BadRequest(Json.obj("message" -> "Bad handlebar template")))
                },
                wh => {
                  env.datastores.webhook.createWebhook(tenant, webhook, request.user).map {
                    case Left(err) => err.toHttpResponse
                    case Right(id) => Created(Json.obj("id" -> id))
                  }
                }
              )
          }
          case JsError(errors)                                                                           => Future.successful(BadRequest(Json.obj("message" -> "Bad body format")))
        }
      }
  }

  def listWebhooks(tenant: String): Action[AnyContent] = tenantAuthAction(tenant, RightLevels.Read).async {
    implicit request =>
      env.datastores.webhook.listWebhook(tenant, request.user).map(ws => Ok(Json.toJson(ws)))
  }

  def deleteWebhook(tenant: String, id: String): Action[JsValue] =
    webhookAuthAction(tenant = tenant, webhook = id, minimumLevel = RightLevels.Admin).async(parse.json) { implicit request =>
      ControllerHelpers.checkPassword(request.body).flatMap {
        case Left(error) => Future.successful(error)
        case Right(password) =>
          env.datastores.users
            .isUserValid(request.user, password)
            .flatMap {
              case Some(_) =>
                env.datastores.webhook
                  .deleteWebhook(tenant, id)
                  .map {
                    case Left(err) => err.toHttpResponse
                    case Right(_) => NoContent
                  }
              case None =>Future.successful(Unauthorized(Json.obj("message" -> "Your password is invalid.")))
            }
      }
    }

  def updateWebhook(tenant: String, id: String): Action[JsValue] =
    webhookAuthAction(tenant = tenant, webhook = id, minimumLevel = RightLevels.Write).async(parse.json) {
      implicit request =>
        {
          (for (
            uuid    <- Try { UUID.fromString(id) }.toOption;
            webhook <- LightWebhook.lightWebhookRead.reads(request.body).asOpt
          ) yield {
            if (webhook.global && (webhook.features.nonEmpty || webhook.projects.nonEmpty)) {
              BadRequest(Json.obj("message" -> "Webhook can't be global and specify features or projects")).future
            } else if (!webhook.global && webhook.projects.isEmpty && webhook.features.isEmpty) {
              BadRequest(Json.obj("message" -> "Webhook must either be global or specify features or projects")).future
            } else {
              env.datastores.webhook
                .updateWebhook(tenant, uuid, webhook)
                .map {
                  case Left(error) => error.toHttpResponse
                  case Right(_)    => NoContent
                }
            }
          }).getOrElse(BadRequest(Json.obj("message" -> "Bad body request and / or bad uuid provided as id")).future)

        }
    }
}
