package controllers

import akka.actor.ActorSystem
import akka.http.scaladsl.util.FastFuture
import akka.stream.ActorMaterializer
import akka.stream.scaladsl.Sink
import akka.util.ByteString
import controllers.actions.SecuredAuthContext
import domains.script.GlobalScript
import domains.user.{User, UserNoPassword, UserStore}
import domains.{Import, ImportResult, Key}
import env.Env
import libs.patch.Patch
import play.api.Logger
import play.api.http.HttpEntity
import play.api.libs.json.{JsError, JsSuccess, JsValue, Json}
import play.api.mvc._
import store.Result.{AppErrors, ErrorMessage}

class UserController(env: Env,
                     userStore: UserStore,
                     system: ActorSystem,
                     AuthAction: ActionBuilder[SecuredAuthContext, AnyContent],
                     val cc: ControllerComponents)
    extends AbstractController(cc) {

  import cats.implicits._
  import libs.functional.EitherTOps._
  import libs.functional.Implicits._
  import system.dispatcher

  implicit val materializer = ActorMaterializer()(system)

  def list(pattern: String, page: Int = 1, nbElementPerPage: Int = 15): Action[AnyContent] = AuthAction.async { ctx =>
    import UserNoPassword._
    val patternsSeq: Seq[String] = ctx.authorizedPatterns :+ pattern
    userStore
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
    import UserNoPassword._
    for {
      user  <- ctx.request.body.validate[User] |> liftJsResult(err => BadRequest(AppErrors.fromJsError(err).toJson))
      _     <- user.isAllowed(ctx.auth) |> liftBooleanTrue(Forbidden(AppErrors.error("error.forbidden").toJson))
      event <- userStore.create(Key(user.id), user) |> mapLeft(err => BadRequest(err.toJson))
    } yield Created(Json.toJson(user))

  }

  def get(id: String): Action[AnyContent] = AuthAction.async { ctx =>
    import UserNoPassword._
    val key = Key(id)
    for {
      user <- userStore.getById(key).one |> liftFOption[Result, User](NotFound)
    } yield Ok(Json.toJson(user))
  }

  def update(id: String): Action[JsValue] = AuthAction.async(parse.json) { ctx =>
    import UserNoPassword._
    for {
      user  <- ctx.request.body.validate[User] |> liftJsResult(err => BadRequest(AppErrors.fromJsError(err).toJson))
      _     <- user.isAllowed(ctx.auth) |> liftBooleanTrue(Forbidden(AppErrors.error("error.forbidden").toJson))
      event <- userStore.update(Key(id), Key(user.id), user) |> mapLeft(err => BadRequest(err.toJson))
    } yield Ok(Json.toJson(user))
  }

  def patch(id: String): Action[JsValue] = AuthAction.async(parse.json) { ctx =>
    import User._
    val key = Key(id)
    for {
      current <- userStore.getById(key).one |> liftFOption[Result, User](NotFound)
      _       <- current.isAllowed(ctx.auth) |> liftBooleanTrue(Forbidden(AppErrors.error("error.forbidden").toJson))
      updated <- Patch.patch(ctx.request.body, current) |> liftJsResult(
                  err => BadRequest(AppErrors.fromJsError(err).toJson)
                )
      event <- userStore
                .update(key, Key(current.id), updated) |> mapLeft(err => BadRequest(err.toJson))
    } yield Ok(UserNoPassword.format.writes(updated))
  }

  def delete(id: String): Action[AnyContent] = AuthAction.async { ctx =>
    import UserNoPassword._
    val key = Key(id)
    for {
      user    <- userStore.getById(key).one |> liftFOption[Result, User](NotFound)
      _       <- user.isAllowed(ctx.auth) |> liftBooleanTrue(Forbidden(AppErrors.error("error.forbidden").toJson))
      deleted <- userStore.delete(key) |> mapLeft(err => BadRequest(err.toJson))
    } yield Ok(Json.toJson(user))
  }

  def deleteAll(patterns: String): Action[AnyContent] =
    AuthAction.async { ctx =>
      val allPatterns = ctx.authorizedPatterns :+ patterns
      for {
        deletes <- userStore.deleteAll(allPatterns) |> mapLeft(err => BadRequest(err.toJson))
      } yield Ok
    }

  def count(): Action[AnyContent] = AuthAction.async { ctx =>
    val patterns: Seq[String] = ctx.authorizedPatterns
    userStore.count(patterns).map { count =>
      Ok(Json.obj("count" -> count))
    }
  }

  def download(): Action[AnyContent] = AuthAction { ctx =>
    val source = userStore
      .getByIdLike(ctx.authorizedPatterns)
      .map { case (_, data) => Json.toJson(data) }
      .map(Json.stringify _)
      .intersperse("", "\n", "\n")
      .map(ByteString.apply)
    Result(
      header = ResponseHeader(200, Map("Content-Disposition" -> "attachment", "filename" -> "users.ndjson")),
      body = HttpEntity.Streamed(source, None, Some("application/json"))
    )
  }

  def upload() = AuthAction.async(Import.ndJson) { ctx =>
    ctx.body
      .via(User.importData(userStore))
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
