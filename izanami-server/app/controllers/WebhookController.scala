package controllers

import akka.actor.ActorSystem
import akka.stream.ActorMaterializer
import akka.stream.scaladsl.Sink
import akka.util.ByteString
import controllers.actions.SecuredAuthContext
import domains.webhook.{Webhook, WebhookContext, WebhookInstances, WebhookService}
import domains.{Import, ImportData, IsAllowed, Key}
import libs.patch.Patch
import libs.logs.IzanamiLogger
import play.api.http.HttpEntity
import play.api.libs.json.{JsValue, Json}
import play.api.mvc._
import store.Result.{AppErrors, IzanamiErrors}
import libs.ziohelper.JsResults.jsResultToHttpResponse
import store.Query
import zio.{IO, Runtime, ZIO}

class WebhookController(system: ActorSystem,
                        AuthAction: ActionBuilder[SecuredAuthContext, AnyContent],
                        cc: ControllerComponents)(implicit R: Runtime[WebhookContext])
    extends AbstractController(cc) {

  import libs.http._
  import system.dispatcher

  implicit val materializer = ActorMaterializer()(system)

  def list(pattern: String, page: Int = 1, nbElementPerPage: Int = 15): Action[Unit] =
    AuthAction.asyncTask[WebhookContext](parse.empty) { ctx =>
      import WebhookInstances._
      val query: Query = Query.oneOf(ctx.authorizedPatterns).and(pattern.split(",").toList)

      WebhookService
        .findByQuery(query, page, nbElementPerPage)
        .map { r =>
          Ok(
            Json.obj(
              "results" -> Json.toJson(r.results),
              "metadata" -> Json.obj(
                "page"     -> page,
                "pageSize" -> nbElementPerPage,
                "count"    -> r.count,
                "nbPages"  -> r.nbPages
              )
            )
          )
        }
    }

  def create(): Action[JsValue] = AuthAction.asyncZio[WebhookContext](parse.json) { ctx =>
    import WebhookInstances._

    val body = ctx.request.body
    for {
      webhook <- jsResultToHttpResponse(body.validate[Webhook])
      _       <- isWebhookAllowed(webhook, ctx)
      event   <- WebhookService.create(webhook.clientId, webhook).mapError { IzanamiErrors.toHttpResult }
    } yield Created(Json.toJson(webhook))

  }

  def get(id: String): Action[Unit] = AuthAction.asyncZio[WebhookContext](parse.empty) { ctx =>
    import WebhookInstances._
    val key = Key(id)
    for {
      _       <- Key.isAllowed(key, ctx.auth)(Forbidden(AppErrors.error("error.forbidden").toJson))
      mayBe   <- WebhookService.getById(key).mapError(e => InternalServerError)
      webhook <- ZIO.fromOption(mayBe).mapError(_ => NotFound)
    } yield Ok(Json.toJson(webhook))
  }

  def update(id: String): Action[JsValue] = AuthAction.asyncZio[WebhookContext](parse.json) { ctx =>
    import WebhookInstances._
    val body = ctx.request.body
    for {
      webhook <- jsResultToHttpResponse(body.validate[Webhook])
      _       <- isWebhookAllowed(webhook, ctx)
      _       <- WebhookService.update(Key(id), webhook.clientId, webhook).mapError { IzanamiErrors.toHttpResult }
    } yield Ok(Json.toJson(webhook))
  }

  def patch(id: String): Action[JsValue] = AuthAction.asyncZio[WebhookContext](parse.json) { ctx =>
    import WebhookInstances._
    val key = Key(id)
    for {
      mayBe   <- WebhookService.getById(key).mapError(e => InternalServerError)
      webhook <- ZIO.fromOption(mayBe).mapError(_ => NotFound)
      _       <- isWebhookAllowed(webhook, ctx)
      body    = ctx.request.body
      updated <- jsResultToHttpResponse(Patch.patch(body, webhook))
      _       <- WebhookService.update(key, webhook.clientId, updated).mapError { IzanamiErrors.toHttpResult }
    } yield Ok(Json.toJson(updated))
  }

  def delete(id: String): Action[AnyContent] = AuthAction.asyncZio[WebhookContext] { ctx =>
    import WebhookInstances._
    val key = Key(id)
    for {
      mayBe   <- WebhookService.getById(key).mapError(e => InternalServerError)
      webhook <- ZIO.fromOption(mayBe).mapError(_ => NotFound)
      _       <- isWebhookAllowed(webhook, ctx)
      _       <- WebhookService.delete(key).mapError { IzanamiErrors.toHttpResult }
    } yield Ok(Json.toJson(webhook))
  }

  def deleteAll(patterns: Option[String]): Action[AnyContent] =
    AuthAction.asyncZio[WebhookContext] { ctx =>
      val allPatterns = patterns.toList.flatMap(_.split(","))
      WebhookService
        .deleteAll(allPatterns)
        .mapError { IzanamiErrors.toHttpResult }
        .map { _ =>
          Ok
        }
    }

  def count(): Action[Unit] = AuthAction.asyncTask[WebhookContext](parse.empty) { ctx =>
    val query: Query = Query.oneOf(ctx.authorizedPatterns)
    WebhookService.count(query).map { count =>
      Ok(Json.obj("count" -> count))
    }
  }

  def download(): Action[AnyContent] = AuthAction.asyncTask[WebhookContext] { ctx =>
    import WebhookInstances._
    val query: Query = Query.oneOf(ctx.authorizedPatterns)
    WebhookService
      .findByQuery(query)
      .map { s =>
        val source = s
          .map { case (_, data) => Json.toJson(data) }
          .map(Json.stringify)
          .intersperse("", "\n", "\n")
          .map(ByteString.apply)
        Result(
          header = ResponseHeader(200, Map("Content-Disposition" -> "attachment", "filename" -> "webhooks.ndjson")),
          body = HttpEntity.Streamed(source, None, Some("application/json"))
        )
      }

  }

  def upload(strStrategy: String) = AuthAction.asyncTask[WebhookContext](Import.ndJson) { ctx =>
    ImportData.importHttp(strStrategy, ctx.body, WebhookService.importData)
  }

  private def isWebhookAllowed(webhook: Webhook,
                               ctx: SecuredAuthContext[_])(implicit A: IsAllowed[Webhook]): zio.IO[Result, Unit] =
    IsAllowed[Webhook].isAllowed(webhook, ctx.auth)(Forbidden(AppErrors.error("error.forbidden").toJson))

}
