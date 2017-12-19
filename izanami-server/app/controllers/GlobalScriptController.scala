package controllers

import akka.actor.ActorSystem
import akka.http.scaladsl.util.FastFuture
import akka.stream.ActorMaterializer
import akka.stream.scaladsl.Sink
import akka.util.ByteString
import controllers.actions.AuthContext
import domains.script.{GlobalScript, GlobalScriptStore}
import domains.{Import, ImportResult, Key}
import env.Env
import play.api.Logger
import play.api.http.HttpEntity
import play.api.libs.json.{JsError, JsSuccess, JsValue, Json}
import play.api.mvc._
import store.Result.{AppErrors, ErrorMessage}

class GlobalScriptController(env: Env,
                             globalScriptStore: GlobalScriptStore,
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

  def list(pattern: String,
           name_only: Option[Boolean],
           page: Int = 1,
           nbElementPerPage: Int = 15): Action[Unit] =
    AuthAction.async(parse.empty) { ctx =>
      import GlobalScript._
      val patternsSeq: Seq[String] = ctx.authorizedPatterns :+ pattern
      globalScriptStore
        .getByIdLike(patternsSeq, page, nbElementPerPage)
        .map { r =>
          name_only match {
            case Some(true) =>
              Ok(
                Json.obj(
                  "results" -> Json.toJson(r.results.map {
                    case GlobalScript(id, name, _, _) =>
                      Json.obj("label" -> name, "value" -> id)
                  }),
                  "metadata" -> Json.obj(
                    "page" -> page,
                    "pageSize" -> nbElementPerPage,
                    "count" -> r.count,
                    "nbPages" -> r.nbPages
                  )
                )
              )
            case _ =>
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
    }

  def create(): Action[JsValue] = AuthAction.async(parse.json) { ctx =>
    import GlobalScript._
    for {
      globalScript <- ctx.request.body.validate[GlobalScript] |> liftJsResult(
        err => BadRequest(AppErrors.fromJsError(err).toJson)
      )
      _ <- globalScript.isAllowed(ctx.auth) |> liftBooleanTrue[Result](
        Forbidden(AppErrors.error("error.forbidden").toJson)
      )
      event <- globalScriptStore
        .create(globalScript.id, globalScript) |> mapLeft(
        err => BadRequest(err.toJson))
    } yield Created(Json.toJson(globalScript))
  }

  def get(id: String): Action[Unit] = AuthAction.async(parse.empty) { ctx =>
    import GlobalScript._
    val key = Key(id)
    for {
      _ <- GlobalScript.isAllowed(key)(ctx.auth) |> liftBooleanTrue[Result](
        Forbidden(AppErrors.error("error.forbidden").toJson)
      )
      globalScript <- globalScriptStore
        .getById(key)
        .one |> liftFOption[Result, GlobalScript](NotFound)
    } yield Ok(Json.toJson(globalScript))
  }

  def update(id: String): Action[JsValue] = AuthAction.async(parse.json) {
    ctx =>
      import GlobalScript._
      for {
        globalScript <- ctx.request.body.validate[GlobalScript] |> liftJsResult(
          err => BadRequest(AppErrors.fromJsError(err).toJson)
        )
        _ <- globalScript.isAllowed(ctx.auth) |> liftBooleanTrue[Result](
          Forbidden(AppErrors.error("error.forbidden").toJson)
        )
        event <- globalScriptStore.update(Key(id),
                                          globalScript.id,
                                          globalScript) |> mapLeft(
          err => BadRequest(err.toJson)
        )
      } yield Ok(Json.toJson(globalScript))
  }

  def delete(id: String): Action[AnyContent] = AuthAction.async { ctx =>
    import GlobalScript._
    val key = Key(id)
    for {
      globalScript <- globalScriptStore
        .getById(key)
        .one |> liftFOption[Result, GlobalScript](NotFound)
      _ <- globalScript.isAllowed(ctx.auth) |> liftBooleanTrue[Result](
        Forbidden(AppErrors.error("error.forbidden").toJson)
      )
      deleted <- globalScriptStore.delete(key) |> mapLeft(
        err => BadRequest(err.toJson))
    } yield Ok(Json.toJson(globalScript))
  }

  def deleteAll(pattern: String): Action[AnyContent] = AuthAction.async { ctx =>
    val patternsSeq: Seq[String] = ctx.authorizedPatterns :+ pattern
    for {
      deletes <- globalScriptStore.deleteAll(patternsSeq) |> mapLeft(
        err => BadRequest(err.toJson))
    } yield Ok
  }

  def download(): Action[AnyContent] = AuthAction { ctx =>
    val source = globalScriptStore
      .getByIdLike(ctx.authorizedPatterns)
      .stream
      .map(data => Json.toJson(data))
      .map(Json.stringify _)
      .intersperse("", "\n", "\n")
      .map(ByteString.apply)
    Result(
      header = ResponseHeader(200,
                              Map("Content-Disposition" -> "attachment",
                                  "filename" -> "scripts.dnjson")),
      body = HttpEntity.Streamed(source, None, Some("application/json"))
    )
  }

  def upload() = AuthAction.async(Import.ndJson) { ctx =>
    ctx.body
      .map { case (s, json) => (s, json.validate[GlobalScript]) }
      .mapAsync(4) {
        case (_, JsSuccess(obj, _)) =>
          globalScriptStore.create(obj.id, obj) map {
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
