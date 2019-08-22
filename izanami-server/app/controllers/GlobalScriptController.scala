package controllers

import akka.actor.ActorSystem
import akka.stream.ActorMaterializer
import akka.stream.scaladsl.Sink
import akka.util.ByteString
import controllers.actions.SecuredAuthContext
import domains.script.Script.ScriptCache
import domains.script._
import domains.{Import, IsAllowed, Key}
import env.Env
import libs.patch.Patch
import libs.logs.IzanamiLogger
import libs.ziohelper.JsResults.jsResultToHttpResponse
import play.api.http.HttpEntity
import play.api.libs.json.{JsObject, JsValue, Json}
import play.api.mvc._
import store.Query
import store.Result.{AppErrors, IzanamiErrors}
import zio.{IO, Runtime, ZIO}

class GlobalScriptController(
    env: Env,
    system: ActorSystem,
    AuthAction: ActionBuilder[SecuredAuthContext, AnyContent],
    cc: ControllerComponents
)(implicit s: ScriptCache, R: Runtime[GlobalScriptContext])
    extends AbstractController(cc) {

  import system.dispatcher
  import libs.http._

  implicit val materializer = ActorMaterializer()(system)

  def list(pattern: String, name_only: Option[Boolean], page: Int = 1, nbElementPerPage: Int = 15): Action[Unit] =
    AuthAction.asyncTask[GlobalScriptContext](parse.empty) { ctx =>
      import GlobalScriptInstances._
      val query: Query = Query.oneOf(ctx.authorizedPatterns).and(pattern.split(",").toList)
      GlobalScriptService
        .findByQuery(query, page, nbElementPerPage)
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

  def create(): Action[JsValue] = AuthAction.asyncZio[GlobalScriptContext](parse.json) { ctx =>
    import GlobalScriptInstances._
    val body = ctx.request.body
    for {
      script <- jsResultToHttpResponse(body.validate[GlobalScript])
      _      <- isScriptAllowed(ctx, script)
      _      <- GlobalScriptService.create(script.id, script).mapError { IzanamiErrors.toHttpResult }
    } yield Created(Json.toJson(script))
  }

  def get(id: String): Action[Unit] = AuthAction.asyncZio[GlobalScriptContext](parse.empty) { ctx =>
    import GlobalScriptInstances._
    val key = Key(id)
    for {
      _            <- Key.isAllowed(key, ctx.auth)(Forbidden(AppErrors.error("error.forbidden").toJson))
      mayBeScript  <- GlobalScriptService.getById(key).mapError(e => InternalServerError)
      globalScript <- ZIO.fromOption(mayBeScript).mapError(_ => NotFound)
    } yield Ok(Json.toJson(globalScript))
  }

  def update(id: String): Action[JsValue] =
    AuthAction.asyncZio[GlobalScriptContext](parse.json) { ctx =>
      import GlobalScriptInstances._
      val body = ctx.request.body
      for {
        script <- jsResultToHttpResponse(body.validate[GlobalScript])
        _      <- isScriptAllowed(ctx, script)
        _      <- GlobalScriptService.update(Key(id), script.id, script).mapError { IzanamiErrors.toHttpResult }
      } yield Ok(Json.toJson(script))
    }

  def patch(id: String): Action[JsValue] =
    AuthAction.asyncZio[GlobalScriptContext](parse.json) { ctx =>
      import GlobalScriptInstances._
      val key = Key(id)
      for {
        mayBeScript <- GlobalScriptService.getById(key).mapError(e => InternalServerError)
        current     <- ZIO.fromOption(mayBeScript).mapError(_ => NotFound)
        _           <- isScriptAllowed(ctx, current)
        body        = ctx.request.body
        updated     <- jsResultToHttpResponse(Patch.patch(body, current))
        _           <- GlobalScriptService.update(key, current.id, updated).mapError { IzanamiErrors.toHttpResult }
      } yield Ok(Json.toJson(updated))
    }

  private def isScriptAllowed(ctx: SecuredAuthContext[_],
                              current: GlobalScript)(implicit A: IsAllowed[GlobalScript]): IO[Result, Unit] =
    IsAllowed[GlobalScript].isAllowed(current, ctx.auth)(Forbidden(AppErrors.error("error.forbidden").toJson))

  def delete(id: String): Action[AnyContent] = AuthAction.asyncZio[GlobalScriptContext] { ctx =>
    import GlobalScriptInstances._
    val key = Key(id)
    for {
      mayBeScript <- GlobalScriptService.getById(key).mapError(e => InternalServerError)
      script      <- ZIO.fromOption(mayBeScript).mapError(_ => NotFound)
      _           <- isScriptAllowed(ctx, script)
      deleted     <- GlobalScriptService.delete(key).mapError { IzanamiErrors.toHttpResult }
    } yield Ok(Json.toJson(script))
  }

  def deleteAll(pattern: String): Action[AnyContent] =
    AuthAction.asyncZio[GlobalScriptContext] { ctx =>
      val query: Query = Query.oneOf(ctx.authorizedPatterns).and(pattern.split(",").toList)
      GlobalScriptService
        .deleteAll(query)
        .mapError { IzanamiErrors.toHttpResult }
        .map(_ => Ok)
    }

  def download(): Action[AnyContent] = AuthAction.asyncTask[GlobalScriptContext] { ctx =>
    import GlobalScriptInstances._
    val query: Query = Query.oneOf(ctx.authorizedPatterns)
    GlobalScriptService
      .findByQuery(query)
      .map { source =>
        val s = source
          .map { case (_, data) => Json.toJson(data) }
          .map(Json.stringify)
          .intersperse("", "\n", "\n")
          .map(ByteString.apply)
        Result(
          header = ResponseHeader(200, Map("Content-Disposition" -> "attachment", "filename" -> "scripts.dnjson")),
          body = HttpEntity.Streamed(s, None, Some("application/json"))
        )
      }

  }

  def upload() = AuthAction.asyncTask[GlobalScriptContext](Import.ndJson) { ctx =>
    GlobalScriptService.importData.flatMap { flow =>
      ZIO.fromFuture { implicit ec =>
        ctx.body
          .via(flow)
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
  }

  case class DebugScript(context: JsObject, script: Script)
  object DebugScript {

    implicit val format = {
      import domains.script.ScriptInstances._
      Json.format[DebugScript]
    }
  }

  def debug() = AuthAction.asyncZio[GlobalScriptContext](parse.json) { ctx =>
    import DebugScript._
    import domains.script.ScriptInstances._
    import domains.script.syntax._
    // format: off
    val body = ctx.request.body
    for {
      debugScript                  <- jsResultToHttpResponse(DebugScript.format.reads(body))
      DebugScript(context, script) = debugScript
      execution                    <- script.run(context).mapError(e => InternalServerError(""))
    } yield Ok(Json.toJson(execution))
    // format: on
  }

}
