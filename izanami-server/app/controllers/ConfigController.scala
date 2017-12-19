package controllers

import akka.actor.ActorSystem
import akka.http.scaladsl.util.FastFuture
import akka.stream.ActorMaterializer
import akka.stream.scaladsl.Sink
import akka.util.ByteString
import controllers.actions.AuthContext
import domains.config.{Config, ConfigStore}
import domains.{Import, ImportResult, Key}
import env.Env
import play.api.Logger
import play.api.http.HttpEntity
import play.api.libs.json._
import play.api.mvc._
import store.Result.{AppErrors, ErrorMessage}

class ConfigController(env: Env,
                       configStore: ConfigStore,
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
           nbElementPerPage: Int = 15): Action[Unit] =
    AuthAction.async(parse.empty) { ctx =>
      import Config._
      val patternsSeq: Seq[String] = ctx.authorizedPatterns :+ pattern
      configStore
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

  def tree(patterns: String): Action[Unit] =
    AuthAction.async(parse.empty) { ctx =>
      import Config._
      val patternsSeq: Seq[String] = ctx.authorizedPatterns ++ patterns.split(
        ",")
      configStore
        .getByIdLike(patternsSeq)
        .stream
        .map { config =>
          config.id.jsPath.write[JsValue].writes(Json.parse(config.value))
        }
        .fold(Json.obj()) { (acc, js) =>
          acc.deepMerge(js.as[JsObject])
        }
        .map(json => Ok(json))
        .runWith(Sink.head)
    }

  def create(): Action[JsValue] = AuthAction.async(parse.json) { ctx =>
    import Config._

    for {
      config <- ctx.request.body.validate[Config] |> liftJsResult(
        err => BadRequest(AppErrors.fromJsError(err).toJson))
      _ <- config.isAllowed(ctx.auth) |> liftBooleanTrue(
        Unauthorized(AppErrors.error("error.forbidden").toJson))
      event <- configStore.create(config.id, config) |> mapLeft(
        err => BadRequest(err.toJson))
    } yield Created(Json.toJson(config))

  }

  def get(id: String): Action[Unit] = AuthAction.async(parse.empty) { ctx =>
    import Config._
    val key = Key(id)
    for {
      _ <- Config.isAllowed(key)(ctx.auth) |> liftBooleanTrue[Result](
        Forbidden(AppErrors.error("error.forbidden").toJson)
      )
      config <- configStore.getById(key).one |> liftFOption[Result, Config](
        NotFound)
    } yield Ok(Json.toJson(config))
  }

  def update(id: String): Action[JsValue] = AuthAction.async(parse.json) {
    ctx =>
      import Config._
      for {
        config <- ctx.request.body.validate[Config] |> liftJsResult(
          err => BadRequest(AppErrors.fromJsError(err).toJson))
        _ <- config.isAllowed(ctx.auth) |> liftBooleanTrue(
          Forbidden(AppErrors.error("error.forbidden").toJson))
        event <- configStore.update(Key(id), config.id, config) |> mapLeft(
          err => BadRequest(err.toJson))
      } yield Ok(Json.toJson(config))
  }

  def delete(id: String): Action[AnyContent] = AuthAction.async { ctx =>
    import Config._
    val key = Key(id)
    for {
      config <- configStore.getById(key).one |> liftFOption[Result, Config](
        NotFound)
      _ <- config.isAllowed(ctx.auth) |> liftBooleanTrue(
        Forbidden(AppErrors.error("error.forbidden").toJson))
      deleted <- configStore.delete(key) |> mapLeft(
        err => BadRequest(err.toJson))
    } yield Ok(Json.toJson(config))
  }

  def deleteAll(patterns: Option[String]): Action[AnyContent] =
    AuthAction.async { ctx =>
      val allPatterns = patterns.toList.flatMap(_.split(","))
      for {
        deletes <- configStore.deleteAll(allPatterns) |> mapLeft(
          err => BadRequest(err.toJson))
      } yield Ok
    }

  def count(): Action[AnyContent] = AuthAction.async { ctx =>
    val patterns: Seq[String] = ctx.authorizedPatterns
    configStore.count(patterns).map { count =>
      Ok(Json.obj("count" -> count))
    }
  }

  def download(): Action[AnyContent] = AuthAction { ctx =>
    val source = configStore
      .getByIdLike(ctx.authorizedPatterns)
      .stream
      .map(data => Json.toJson(data))
      .map(Json.stringify _)
      .intersperse("", "\n", "\n")
      .map(ByteString.apply)
    Result(
      header = ResponseHeader(200,
                              Map("Content-Disposition" -> "attachment",
                                  "filename" -> "configs.dnjson")),
      body = HttpEntity.Streamed(source, None, Some("application/json"))
    )
  }

  def upload() = AuthAction.async(Import.ndJson) { ctx =>
    ctx.body
      .map { case (s, json) => (s, json.validate[Config]) }
      .mapAsync(4) {
        case (_, JsSuccess(obj, _)) =>
          configStore.create(obj.id, obj) map { ImportResult.fromResult _ }
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
