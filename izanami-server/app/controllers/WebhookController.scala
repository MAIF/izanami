package controllers

import akka.actor.ActorSystem
import akka.stream.ActorMaterializer
import akka.stream.scaladsl.Sink
import akka.util.ByteString
import cats.effect.{Effect, IO}
import controllers.actions.SecuredAuthContext
import domains.webhook.{Webhook, WebhookInstances, WebhookService}
import domains.{Import, IsAllowed, Key}
import libs.patch.Patch
import libs.logs.IzanamiLogger
import play.api.http.HttpEntity
import play.api.libs.json.{JsValue, Json}
import play.api.mvc._
import store.Result.AppErrors
import libs.functional.EitherTSyntax
import store.Query

class WebhookController[F[_]: Effect](webhookStore: WebhookService[F],
                                      system: ActorSystem,
                                      AuthAction: ActionBuilder[SecuredAuthContext, AnyContent],
                                      cc: ControllerComponents)
    extends AbstractController(cc)
    with EitherTSyntax[F] {

  import AppErrors._
  import cats.implicits._
  import cats.effect.implicits._
  import libs.http._
  import libs.functional.syntax._
  import system.dispatcher

  implicit val materializer = ActorMaterializer()(system)

  def list(pattern: String, page: Int = 1, nbElementPerPage: Int = 15): Action[Unit] =
    AuthAction.asyncF(parse.empty) { ctx =>
      import WebhookInstances._
      val query: Query = Query.oneOf(ctx.authorizedPatterns).and(pattern.split(",").toList)

      webhookStore
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

  def create(): Action[JsValue] = AuthAction.asyncEitherT(parse.json) { ctx =>
    import WebhookInstances._

    for {
      webhook <- ctx.request.body.validate[Webhook] |> liftJsResult(
                  err => BadRequest(AppErrors.fromJsError(err).toJson)
                )
      _ <- IsAllowed[Webhook].isAllowed(webhook)(ctx.auth) |> liftBooleanTrue(
            Forbidden(AppErrors.error("error.forbidden").toJson)
          )
      event <- webhookStore
                .create(webhook.clientId, webhook) |> mapLeft(err => BadRequest(err.toJson))
    } yield Created(Json.toJson(webhook))

  }

  def get(id: String): Action[Unit] = AuthAction.asyncEitherT(parse.empty) { ctx =>
    import WebhookInstances._
    val key = Key(id)
    for {
      _ <- Key.isAllowed(key)(ctx.auth) |> liftBooleanTrue[Result](
            Forbidden(AppErrors.error("error.forbidden").toJson)
          )
      webhook <- webhookStore.getById(key) |> liftFOption[Result, Webhook](NotFound)
    } yield Ok(Json.toJson(webhook))
  }

  def update(id: String): Action[JsValue] = AuthAction.asyncEitherT(parse.json) { ctx =>
    import WebhookInstances._
    for {
      webhook <- ctx.request.body.validate[Webhook] |> liftJsResult(
                  err => BadRequest(AppErrors.fromJsError(err).toJson)
                )
      _ <- IsAllowed[Webhook].isAllowed(webhook)(ctx.auth) |> liftBooleanTrue(
            Forbidden(AppErrors.error("error.forbidden").toJson)
          )
      event <- webhookStore.update(Key(id), webhook.clientId, webhook) |> mapLeft(
                err => BadRequest(err.toJson)
              )
    } yield Ok(Json.toJson(webhook))
  }

  def patch(id: String): Action[JsValue] = AuthAction.asyncEitherT(parse.json) { ctx =>
    import WebhookInstances._
    val key = Key(id)
    for {
      current <- webhookStore.getById(key) |> liftFOption[Result, Webhook](NotFound)
      _ <- IsAllowed[Webhook].isAllowed(current)(ctx.auth) |> liftBooleanTrue(
            Forbidden(AppErrors.error("error.forbidden").toJson)
          )
      updated <- Patch.patch(ctx.request.body, current) |> liftJsResult(
                  err => BadRequest(AppErrors.fromJsError(err).toJson)
                )
      event <- webhookStore
                .update(key, current.clientId, updated) |> mapLeft(err => BadRequest(err.toJson))
    } yield Ok(Json.toJson(updated))
  }

  def delete(id: String): Action[AnyContent] = AuthAction.asyncEitherT { ctx =>
    import WebhookInstances._
    val key = Key(id)
    for {
      webhook <- webhookStore.getById(key) |> liftFOption[Result, Webhook](NotFound)
      _ <- IsAllowed[Webhook].isAllowed(webhook)(ctx.auth) |> liftBooleanTrue(
            Forbidden(AppErrors.error("error.forbidden").toJson)
          )
      deleted <- webhookStore.delete(key) |> mapLeft(err => BadRequest(err.toJson))
    } yield Ok(Json.toJson(webhook))
  }

  def deleteAll(patterns: Option[String]): Action[AnyContent] =
    AuthAction.asyncEitherT { ctx =>
      val allPatterns = patterns.toList.flatMap(_.split(","))
      for {
        deletes <- webhookStore.deleteAll(allPatterns) |> mapLeft(err => BadRequest(err.toJson))
      } yield Ok
    }

  def count(): Action[Unit] = AuthAction.asyncF(parse.empty) { ctx =>
    val query: Query = Query.oneOf(ctx.authorizedPatterns)
    webhookStore.count(query).map { count =>
      Ok(Json.obj("count" -> count))
    }
  }

  def download(): Action[AnyContent] = AuthAction { ctx =>
    import WebhookInstances._
    val query: Query = Query.oneOf(ctx.authorizedPatterns)
    val source = webhookStore
      .findByQuery(query)
      .map { case (_, data) => Json.toJson(data) }
      .map(Json.stringify)
      .intersperse("", "\n", "\n")
      .map(ByteString.apply)
    Result(
      header = ResponseHeader(200, Map("Content-Disposition" -> "attachment", "filename" -> "webhooks.ndjson")),
      body = HttpEntity.Streamed(source, None, Some("application/json"))
    )
  }

  def upload() = AuthAction.async(Import.ndJson) { ctx =>
    ctx.body
      .via(webhookStore.importData)
      .map {
        case r if r.isError => BadRequest(Json.toJson(r))
        case r              => Ok(Json.toJson(r))
      }
      .recover {
        case e: Throwable =>
          IzanamiLogger.error("Error importing file", e)
          InternalServerError
      }
      .runWith(Sink.head)
  }
}
