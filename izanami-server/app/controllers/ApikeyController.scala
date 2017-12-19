package controllers

import akka.actor.ActorSystem
import akka.http.scaladsl.util.FastFuture
import akka.stream.ActorMaterializer
import akka.stream.scaladsl.Sink
import akka.util.ByteString
import controllers.actions.AuthContext
import domains.{Import, ImportResult, Key}
import domains.apikey.{Apikey, ApikeyStore}
import env.Env
import play.api.Logger
import play.api.http.HttpEntity
import play.api.libs.json.{JsError, JsSuccess, JsValue, Json}
import play.api.mvc._
import store.Result.{AppErrors, ErrorMessage}

class ApikeyController(env: Env,
                       apikeyStore: ApikeyStore,
                       system: ActorSystem,
                       AuthAction: ActionBuilder[AuthContext, AnyContent],
                       val cc: ControllerComponents)
    extends AbstractController(cc) {

  import cats.implicits._
  import libs.functional.EitherTOps._
  import libs.functional.Implicits._
  import system.dispatcher
  import AppErrors._

  implicit val materializer = ActorMaterializer()(system)

  def list(pattern: String,
           page: Int = 1,
           nbElementPerPage: Int = 15): Action[AnyContent] =
    AuthAction.async { ctx =>
      import Apikey._
      val patternsSeq: Seq[String] = ctx.authorizedPatterns :+ pattern

      apikeyStore
        .getByIdLike(patternsSeq, page, nbElementPerPage)
        .map { r =>
          Ok(
            Json.obj(
              "results" -> Json.toJson(r.results),
              "metadata" -> Json.obj(
                "page" -> page,
                "pageSize" -> nbElementPerPage,
                "count" -> r.count,
                "nbPages" -> r.nbPages
              )
            )
          )
        }
    }

  def create(): Action[JsValue] = AuthAction.async(parse.json) { ctx =>
    import Apikey._

    for {
      apikey <- ctx.request.body.validate[Apikey] |> liftJsResult(
        err => BadRequest(AppErrors.fromJsError(err).toJson))
      _ <- apikey.isAllowed(ctx.auth) |> liftBooleanTrue(
        Forbidden(AppErrors.error("error.forbidden").toJson))
      event <- apikeyStore.create(Key(apikey.clientId), apikey) |> mapLeft(
        err => BadRequest(err.toJson))
    } yield Created(Json.toJson(apikey))

  }

  def get(id: String): Action[AnyContent] = AuthAction.async { ctx =>
    import Apikey._
    val key = Key(id)
    for {
      apikey <- apikeyStore.getById(key).one |> liftFOption[Result, Apikey](
        NotFound)
      _ <- apikey.isAllowed(ctx.auth) |> liftBooleanTrue(
        Forbidden(AppErrors.error("error.forbidden").toJson))
    } yield Ok(Json.toJson(apikey))
  }

  def update(id: String): Action[JsValue] = AuthAction.async(parse.json) {
    ctx =>
      import Apikey._
      for {
        apikey <- ctx.request.body.validate[Apikey] |> liftJsResult(
          err => BadRequest(AppErrors.fromJsError(err).toJson))
        _ <- apikey.isAllowed(ctx.auth) |> liftBooleanTrue(
          Forbidden(AppErrors.error("error.forbidden").toJson))
        event <- apikeyStore
          .update(Key(id), Key(apikey.clientId), apikey) |> mapLeft(
          err => BadRequest(err.toJson))
      } yield Ok(Json.toJson(apikey))
  }

  def delete(id: String): Action[AnyContent] = AuthAction.async { ctx =>
    import Apikey._
    val key = Key(id)
    for {
      apikey <- apikeyStore.getById(key).one |> liftFOption[Result, Apikey](
        NotFound)
      _ <- apikey.isAllowed(ctx.auth) |> liftBooleanTrue(
        Forbidden(AppErrors.error("error.forbidden").toJson))
      deleted <- apikeyStore.delete(key) |> mapLeft(
        err => BadRequest(err.toJson))
    } yield Ok(Json.toJson(apikey))
  }

  def deleteAll(patterns: Option[String]): Action[AnyContent] =
    AuthAction.async { ctx =>
      val allPatterns = patterns.toList.flatMap(_.split(","))
      for {
        deletes <- apikeyStore.deleteAll(allPatterns) |> mapLeft(
          err => BadRequest(err.toJson))
      } yield Ok
    }

  def count(): Action[AnyContent] = AuthAction.async { ctx =>
    val patterns: Seq[String] = ctx.authorizedPatterns
    apikeyStore.count(patterns).map { count =>
      Ok(Json.obj("count" -> count))
    }
  }

  def download(): Action[AnyContent] = AuthAction { ctx =>
    val source = apikeyStore
      .getByIdLike(ctx.authorizedPatterns)
      .stream
      .map(data => Json.toJson(data))
      .map(Json.stringify _)
      .intersperse("", "\n", "\n")
      .map(ByteString.apply)
    Result(
      header = ResponseHeader(200,
                              Map("Content-Disposition" -> "attachment",
                                  "filename" -> "apikeys.dnjson")),
      body = HttpEntity.Streamed(source, None, Some("application/json"))
    )
  }

  def upload() = AuthAction.async(Import.ndJson) { ctx =>
    ctx.body
      .map { case (s, json) => (s, json.validate[Apikey]) }
      .mapAsync(4) {
        case (_, JsSuccess(obj, _)) =>
          apikeyStore.create(Key(obj.clientId), obj) map {
            ImportResult.fromResult _
          }
        case (s, JsError(_)) =>
          FastFuture.successful(
            ImportResult.error(ErrorMessage("json.parse.error", s)))
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
