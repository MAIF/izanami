package controllers

import akka.actor.ActorSystem
import akka.http.scaladsl.util.FastFuture
import akka.stream.ActorMaterializer
import akka.stream.scaladsl.Sink
import akka.util.ByteString
import cats.effect.Effect
import controllers.actions.SecuredAuthContext
import domains.user.{User, UserInstances, UserNoPasswordInstances, UserService}
import domains.{Import, ImportResult, IsAllowed, Key}
import libs.functional.EitherTSyntax
import libs.patch.Patch
import libs.logs.IzanamiLogger
import play.api.http.HttpEntity
import play.api.libs.json.{JsError, JsSuccess, JsValue, Json}
import play.api.mvc._
import store.Result.{AppErrors, ErrorMessage}

import scala.concurrent.Future

class UserController[F[_]: Effect](userStore: UserService[F],
                                   system: ActorSystem,
                                   AuthAction: ActionBuilder[SecuredAuthContext, AnyContent],
                                   val cc: ControllerComponents)
    extends AbstractController(cc)
    with EitherTSyntax[F] {

  import cats.implicits._
  import libs.functional.syntax._
  import system.dispatcher
  import libs.http._

  implicit val materializer = ActorMaterializer()(system)

  def list(pattern: String, page: Int = 1, nbElementPerPage: Int = 15): Action[AnyContent] = AuthAction.asyncF { ctx =>
    import UserNoPasswordInstances._
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

  def create(): Action[JsValue] = AuthAction.asyncEitherT(parse.json) { ctx =>
    import UserInstances._
    for {
      user <- ctx.request.body.validate[User] |> liftJsResult(err => BadRequest(AppErrors.fromJsError(err).toJson))
      _ <- IsAllowed[User].isAllowed(user)(ctx.auth) |> liftBooleanTrue(
            Forbidden(AppErrors.error("error.forbidden").toJson)
          )
      event <- userStore.create(Key(user.id), user) |> mapLeft(err => BadRequest(err.toJson))
    } yield Created(UserNoPasswordInstances.format.writes(user))

  }

  def get(id: String): Action[AnyContent] = AuthAction.asyncEitherT { ctx =>
    import UserNoPasswordInstances._
    val key = Key(id)
    for {
      user <- userStore.getById(key) |> liftFOption[Result, User](NotFound)
    } yield Ok(Json.toJson(user))
  }

  def update(id: String): Action[JsValue] = AuthAction.asyncEitherT(parse.json) { ctx =>
    import UserInstances._
    for {
      user <- UserNoPasswordInstances.format.reads(ctx.request.body) |> liftJsResult(
               err => BadRequest(AppErrors.fromJsError(err).toJson)
             )
      _ <- IsAllowed[User].isAllowed(user)(ctx.auth) |> liftBooleanTrue(
            Forbidden(AppErrors.error("error.forbidden").toJson)
          )
      _ <- userStore.update(Key(id), Key(user.id), user) |> mapLeft(err => BadRequest(err.toJson))
    } yield Ok(UserNoPasswordInstances.format.writes(user))
  }

  def patch(id: String): Action[JsValue] = AuthAction.asyncEitherT(parse.json) { ctx =>
    import UserInstances._
    val key = Key(id)
    for {
      current <- userStore.getById(key) |> liftFOption[Result, User](NotFound)
      _ <- IsAllowed[User].isAllowed(current)(ctx.auth) |> liftBooleanTrue(
            Forbidden(AppErrors.error("error.forbidden").toJson)
          )
      updated <- Patch.patch(ctx.request.body, current) |> liftJsResult(
                  err => BadRequest(AppErrors.fromJsError(err).toJson)
                )
      _ <- userStore
            .update(key, Key(current.id), updated) |> mapLeft(err => BadRequest(err.toJson))
    } yield Ok(UserNoPasswordInstances.format.writes(updated))
  }

  def delete(id: String): Action[AnyContent] = AuthAction.asyncEitherT { ctx =>
    import UserInstances._
    val key = Key(id)
    for {
      user <- userStore.getById(key) |> liftFOption[Result, User](NotFound)
      _ <- IsAllowed[User].isAllowed(user)(ctx.auth) |> liftBooleanTrue(
            Forbidden(AppErrors.error("error.forbidden").toJson)
          )
      deleted <- userStore.delete(key) |> mapLeft(err => BadRequest(err.toJson))
    } yield Ok(UserNoPasswordInstances.format.writes(user))
  }

  def deleteAll(patterns: String): Action[AnyContent] =
    AuthAction.asyncEitherT { ctx =>
      val allPatterns = ctx.authorizedPatterns :+ patterns
      for {
        deletes <- userStore.deleteAll(allPatterns) |> mapLeft(err => BadRequest(err.toJson))
      } yield Ok
    }

  def count(): Action[AnyContent] = AuthAction.asyncF { ctx =>
    val patterns: Seq[String] = ctx.authorizedPatterns
    userStore.count(patterns).map { count =>
      Ok(Json.obj("count" -> count))
    }
  }

  def download(): Action[AnyContent] = AuthAction { ctx =>
    import UserInstances._
    val source = userStore
      .getByIdLike(ctx.authorizedPatterns)
      .map { case (_, data) => Json.toJson(data) }
      .map(Json.stringify)
      .intersperse("", "\n", "\n")
      .map(ByteString.apply)
    Result(
      header = ResponseHeader(200, Map("Content-Disposition" -> "attachment", "filename" -> "users.ndjson")),
      body = HttpEntity.Streamed(source, None, Some("application/json"))
    )
  }

  def upload() = AuthAction.async(Import.ndJson) { ctx =>
    import UserInstances._
    ctx.body
      .via(userStore.importData)
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
