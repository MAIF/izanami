package controllers

import akka.actor.ActorSystem
import akka.stream.ActorMaterializer
import akka.stream.scaladsl.Sink
import akka.util.ByteString
import cats.effect.Effect
import controllers.actions.SecuredAuthContext
import domains.script.Script.ScriptCache
import domains.script._
import domains.{Import, IsAllowed, Key}
import env.Env
import libs.functional.EitherTSyntax
import libs.patch.Patch
import libs.logs.IzanamiLogger
import play.api.http.HttpEntity
import play.api.libs.json.{JsObject, JsValue, Json}
import play.api.mvc._
import store.Result.AppErrors

class GlobalScriptController[F[_]: Effect](env: Env,
                                           globalScriptStore: GlobalScriptService[F],
                                           system: ActorSystem,
                                           AuthAction: ActionBuilder[SecuredAuthContext, AnyContent],
                                           cc: ControllerComponents)(implicit s: ScriptCache[F])
    extends AbstractController(cc)
    with EitherTSyntax[F] {

  import AppErrors._
  import cats.implicits._
  import libs.functional.syntax._
  import system.dispatcher
  import libs.http._

  implicit val materializer = ActorMaterializer()(system)

  def list(pattern: String, name_only: Option[Boolean], page: Int = 1, nbElementPerPage: Int = 15): Action[Unit] =
    AuthAction.asyncF(parse.empty) { ctx =>
      import GlobalScriptInstances._
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
                    "page"     -> page,
                    "pageSize" -> nbElementPerPage,
                    "count"    -> r.count,
                    "nbPages"  -> r.nbPages
                  )
                )
              )
            case _ =>
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
    }

  def create(): Action[JsValue] = AuthAction.asyncEitherT(parse.json) { ctx =>
    import GlobalScriptInstances._
    for {
      globalScript <- ctx.request.body.validate[GlobalScript] |> liftJsResult(
                       err => BadRequest(AppErrors.fromJsError(err).toJson)
                     )
      _ <- IsAllowed[GlobalScript].isAllowed(globalScript)(ctx.auth) |> liftBooleanTrue[Result](
            Forbidden(AppErrors.error("error.forbidden").toJson)
          )
      event <- globalScriptStore
                .create(globalScript.id, globalScript) |> mapLeft(err => BadRequest(err.toJson))
    } yield Created(Json.toJson(globalScript))
  }

  def get(id: String): Action[Unit] = AuthAction.asyncEitherT(parse.empty) { ctx =>
    import GlobalScriptInstances._
    val key = Key(id)
    for {
      _ <- Key.isAllowed(key)(ctx.auth) |> liftBooleanTrue[Result](
            Forbidden(AppErrors.error("error.forbidden").toJson)
          )
      globalScript <- globalScriptStore
                       .getById(key) |> liftFOption[Result, GlobalScript](NotFound)
    } yield Ok(Json.toJson(globalScript))
  }

  def update(id: String): Action[JsValue] = AuthAction.asyncEitherT(parse.json) { ctx =>
    import GlobalScriptInstances._
    for {
      globalScript <- ctx.request.body.validate[GlobalScript] |> liftJsResult(
                       err => BadRequest(AppErrors.fromJsError(err).toJson)
                     )
      _ <- IsAllowed[GlobalScript].isAllowed(globalScript)(ctx.auth) |> liftBooleanTrue[Result](
            Forbidden(AppErrors.error("error.forbidden").toJson)
          )
      event <- globalScriptStore.update(Key(id), globalScript.id, globalScript) |> mapLeft(
                err => BadRequest(err.toJson)
              )
    } yield Ok(Json.toJson(globalScript))
  }

  def patch(id: String): Action[JsValue] = AuthAction.asyncEitherT(parse.json) { ctx =>
    import GlobalScriptInstances._
    val key = Key(id)
    for {
      current <- globalScriptStore.getById(key) |> liftFOption[Result, GlobalScript](NotFound)
      _ <- IsAllowed[GlobalScript].isAllowed(current)(ctx.auth) |> liftBooleanTrue(
            Forbidden(AppErrors.error("error.forbidden").toJson)
          )
      updated <- Patch.patch(ctx.request.body, current) |> liftJsResult(
                  err => BadRequest(AppErrors.fromJsError(err).toJson)
                )
      event <- globalScriptStore
                .update(key, current.id, updated) |> mapLeft(err => BadRequest(err.toJson))
    } yield Ok(Json.toJson(updated))
  }

  def delete(id: String): Action[AnyContent] = AuthAction.asyncEitherT { ctx =>
    import GlobalScriptInstances._
    val key = Key(id)
    for {
      globalScript <- globalScriptStore
                       .getById(key) |> liftFOption[Result, GlobalScript](NotFound)
      _ <- IsAllowed[GlobalScript].isAllowed(globalScript)(ctx.auth) |> liftBooleanTrue[Result](
            Forbidden(AppErrors.error("error.forbidden").toJson)
          )
      deleted <- globalScriptStore.delete(key) |> mapLeft(err => BadRequest(err.toJson))
    } yield Ok(Json.toJson(globalScript))
  }

  def deleteAll(pattern: String): Action[AnyContent] = AuthAction.asyncEitherT { ctx =>
    val patternsSeq: Seq[String] = ctx.authorizedPatterns :+ pattern
    for {
      deletes <- globalScriptStore.deleteAll(patternsSeq) |> mapLeft(err => BadRequest(err.toJson))
    } yield Ok
  }

  def download(): Action[AnyContent] = AuthAction { ctx =>
    import GlobalScriptInstances._
    val source = globalScriptStore
      .getByIdLike(ctx.authorizedPatterns)
      .map { case (_, data) => Json.toJson(data) }
      .map(Json.stringify)
      .intersperse("", "\n", "\n")
      .map(ByteString.apply)
    Result(
      header = ResponseHeader(200, Map("Content-Disposition" -> "attachment", "filename" -> "scripts.dnjson")),
      body = HttpEntity.Streamed(source, None, Some("application/json"))
    )
  }

  def upload() = AuthAction.async(Import.ndJson) { ctx =>
    ctx.body
      .via(globalScriptStore.importData)
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

  case class DebugScript(context: JsObject, script: Script)
  object DebugScript {

    implicit val format = {
      import domains.script.ScriptInstances._
      Json.format[DebugScript]
    }
  }

  def debug() = AuthAction.asyncEitherT(parse.json) { ctx =>
    import DebugScript._
    import domains.script.ScriptInstances._
    import domains.script.syntax._
    // format: off
    for {
      debugScript                  <- ctx.request.body.validate[DebugScript]  |> liftJsResult(err => BadRequest(AppErrors.fromJsError(err).toJson))
      DebugScript(context, script) = debugScript
      execution                    <- script.run(context, env)                |> liftF[Result, ScriptExecution]
    } yield Ok(Json.toJson(execution))
    // format: on
  }

}
