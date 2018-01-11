package controllers

import akka.actor.ActorSystem
import akka.http.scaladsl.util.FastFuture
import akka.stream.ActorMaterializer
import akka.stream.scaladsl.Sink
import akka.util.ByteString
import controllers.actions.AuthContext
import controllers.patch.Patch
import controllers.patch.Patch.Patch
import domains.user.{User, UserNoPassword}
import domains.webhook.{Webhook, WebhookStore}
import domains.{Import, ImportResult, Key}
import env.Env
import play.api.Logger
import play.api.http.HttpEntity
import play.api.libs.json.{JsError, JsSuccess, JsValue, Json}
import play.api.mvc._
import store.Result.{AppErrors, ErrorMessage}

class WebhookController(env: Env,
                        webhookStore: WebhookStore,
                        system: ActorSystem,
                        AuthAction: ActionBuilder[AuthContext, AnyContent],
                        cc: ControllerComponents)
    extends AbstractController(cc) {

  import AppErrors._
  import cats.implicits._
  import libs.functional.EitherTOps._
  import libs.functional.Implicits._
  import system.dispatcher

  implicit val materializer = ActorMaterializer()(system)

  def list(pattern: String, page: Int = 1, nbElementPerPage: Int = 15): Action[Unit] =
    AuthAction.async(parse.empty) { ctx =>
      import Webhook._
      val patternsSeq: Seq[String] = ctx.authorizedPatterns :+ pattern

      webhookStore
        .getByIdLike(patternsSeq, page, nbElementPerPage)
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

  def create(): Action[JsValue] = AuthAction.async(parse.json) { ctx =>
    import Webhook._

    for {
      webhook <- ctx.request.body.validate[Webhook] |> liftJsResult(
                  err => BadRequest(AppErrors.fromJsError(err).toJson)
                )
      _ <- webhook.isAllowed(ctx.auth) |> liftBooleanTrue(Forbidden(AppErrors.error("error.forbidden").toJson))
      event <- webhookStore
                .create(webhook.clientId, webhook) |> mapLeft(err => BadRequest(err.toJson))
    } yield Created(Json.toJson(webhook))

  }

  def get(id: String): Action[Unit] = AuthAction.async(parse.empty) { ctx =>
    import Webhook._
    val key = Key(id)
    for {
      _ <- Webhook.isAllowed(key)(ctx.auth) |> liftBooleanTrue[Result](
            Forbidden(AppErrors.error("error.forbidden").toJson)
          )
      webhook <- webhookStore.getById(key).one |> liftFOption[Result, Webhook](NotFound)
    } yield Ok(Json.toJson(webhook))
  }

  def update(id: String): Action[JsValue] = AuthAction.async(parse.json) { ctx =>
    import Webhook._
    for {
      webhook <- ctx.request.body.validate[Webhook] |> liftJsResult(
                  err => BadRequest(AppErrors.fromJsError(err).toJson)
                )
      _ <- webhook.isAllowed(ctx.auth) |> liftBooleanTrue(Forbidden(AppErrors.error("error.forbidden").toJson))
      event <- webhookStore.update(Key(id), webhook.clientId, webhook) |> mapLeft(
                err => BadRequest(err.toJson)
              )
    } yield Ok(Json.toJson(webhook))
  }

  def patch(id: String): Action[JsValue] = AuthAction.async(parse.json) { ctx =>
    import Webhook._
    val key = Key(id)
    for {
      patch <- ctx.request.body.validate[Seq[Patch]] |> liftJsResult(
                err => BadRequest(AppErrors.fromJsError(err).toJson)
              )
      current <- webhookStore.getById(key).one |> liftFOption[Result, Webhook](NotFound)
      _       <- current.isAllowed(ctx.auth) |> liftBooleanTrue(Forbidden(AppErrors.error("error.forbidden").toJson))
      updated <- Patch.patchAs(patch, current) |> liftJsResult(err => BadRequest(AppErrors.fromJsError(err).toJson))
      event <- webhookStore
                .update(key, current.clientId, updated) |> mapLeft(err => BadRequest(err.toJson))
    } yield Ok(Json.toJson(updated))
  }

  def delete(id: String): Action[AnyContent] = AuthAction.async { ctx =>
    import Webhook._
    val key = Key(id)
    for {
      webhook <- webhookStore.getById(key).one |> liftFOption[Result, Webhook](NotFound)
      _       <- webhook.isAllowed(ctx.auth) |> liftBooleanTrue(Forbidden(AppErrors.error("error.forbidden").toJson))
      deleted <- webhookStore.delete(key) |> mapLeft(err => BadRequest(err.toJson))
    } yield Ok(Json.toJson(webhook))
  }

  def deleteAll(patterns: Option[String]): Action[AnyContent] =
    AuthAction.async { ctx =>
      val allPatterns = patterns.toList.flatMap(_.split(","))
      for {
        deletes <- webhookStore.deleteAll(allPatterns) |> mapLeft(err => BadRequest(err.toJson))
      } yield Ok
    }

  def count(): Action[Unit] = AuthAction.async(parse.empty) { ctx =>
    val patterns: Seq[String] = ctx.authorizedPatterns
    webhookStore.count(patterns).map { count =>
      Ok(Json.obj("count" -> count))
    }
  }

  def download(): Action[AnyContent] = AuthAction { ctx =>
    val source = webhookStore
      .getByIdLike(ctx.authorizedPatterns)
      .stream
      .map(data => Json.toJson(data))
      .map(Json.stringify _)
      .intersperse("", "\n", "\n")
      .map(ByteString.apply)
    Result(
      header = ResponseHeader(200, Map("Content-Disposition" -> "attachment", "filename" -> "webhooks.ndjson")),
      body = HttpEntity.Streamed(source, None, Some("application/json"))
    )
  }

  def upload() = AuthAction.async(Import.ndJson) { ctx =>
    ctx.body
      .map { case (s, json) => (s, json.validate[Webhook]) }
      .mapAsync(4) {
        case (_, JsSuccess(obj, _)) =>
          webhookStore.create(obj.clientId, obj) map {
            ImportResult.fromResult _
          }
        case (s, JsError(_)) =>
          FastFuture.successful(ImportResult.error(ErrorMessage("json.parse.error", s)))
      }
      .fold(ImportResult()) { _ |+| _ }
      .map {
        case r if r.isError => BadRequest(Json.toJson(r))
        case r              => Ok(Json.toJson(r))
      }
      .recover {
        case e: Throwable =>
          Logger.error("Error importing file", e)
          InternalServerError
      }
      .runWith(Sink.head)
  }
}
