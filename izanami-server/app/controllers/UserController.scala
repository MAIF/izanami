package controllers

import akka.actor.ActorSystem
import akka.stream.ActorMaterializer
import akka.util.ByteString
import controllers.actions.SecuredAuthContext

import controllers.dto.meta.Metadata
import controllers.dto.user.UserListResult
import domains.user.{User, UserContext, UserInstances, UserNoPasswordInstances, UserService}
import domains.{Import, ImportData, IsAllowed, Key}
import libs.patch.Patch
import libs.ziohelper.JsResults.jsResultToHttpResponse
import play.api.http.HttpEntity
import play.api.libs.json.{JsValue, Json}
import play.api.mvc._
import store.Query
import controllers.dto.error.ApiErrors
import zio.{IO, Runtime, ZIO}
import libs.http.HttpContext

class UserController(AuthAction: ActionBuilder[SecuredAuthContext, AnyContent], val cc: ControllerComponents)(
    implicit system: ActorSystem,
    R: HttpContext[UserContext]
) extends AbstractController(cc) {

  import system.dispatcher
  import libs.http._

  def list(pattern: String, page: Int = 1, nbElementPerPage: Int = 15): Action[AnyContent] =
    AuthAction.asyncZio[UserContext] { ctx =>
      import UserInstances._
      val query: Query = Query.oneOf(ctx.authorizedPatterns).and(pattern.split(",").toList)
      isUserAllowed(ctx) *> UserService
        .findByQuery(query, page, nbElementPerPage)
        .map { r =>
          Ok(Json.toJson(UserListResult(r.results.toList, Metadata(page, nbElementPerPage, r.count, r.nbPages))))
        }
        .mapError { ApiErrors.toHttpResult }
    }

  def create(): Action[JsValue] = AuthAction.asyncZio[UserContext](parse.json) { ctx =>
    import UserInstances._
    val body = ctx.request.body
    for {
      _    <- isUserAllowed(ctx)
      user <- jsResultToHttpResponse(body.validate[User])
      _    <- UserService.create(Key(user.id), user).mapError { ApiErrors.toHttpResult }
    } yield Created(UserNoPasswordInstances.format.writes(user))

  }

  def get(id: String): Action[AnyContent] = AuthAction.asyncZio[UserContext] { _ =>
    import UserNoPasswordInstances._
    val key = Key(id)
    for {
      mayBeUser <- UserService.getById(key).mapError { ApiErrors.toHttpResult }
      user      <- ZIO.fromOption(mayBeUser).mapError(_ => NotFound)
    } yield Ok(Json.toJson(user))
  }

  def update(id: String): Action[JsValue] = AuthAction.asyncZio[UserContext](parse.json) { ctx =>
    import UserInstances._
    val userOrError = UserNoPasswordInstances.format.reads(ctx.request.body)
    for {
      _    <- isUserAllowed(ctx)
      user <- jsResultToHttpResponse(userOrError)
      _    <- UserService.update(Key(id), Key(user.id), user).mapError { ApiErrors.toHttpResult }
    } yield Ok(UserNoPasswordInstances.format.writes(user))
  }

  def patch(id: String): Action[JsValue] = AuthAction.asyncZio[UserContext](parse.json) { ctx =>
    import UserInstances._
    val key = Key(id)
    for {
      _         <- isUserAllowed(ctx)
      mayBeUser <- UserService.getById(key).mapError { ApiErrors.toHttpResult }
      user      <- ZIO.fromOption(mayBeUser).mapError(_ => NotFound)
      updated   <- jsResultToHttpResponse(Patch.patch(ctx.request.body, user))
      _         <- UserService.update(key, Key(user.id), updated).mapError { ApiErrors.toHttpResult }
    } yield Ok(UserNoPasswordInstances.format.writes(updated))
  }

  def delete(id: String): Action[AnyContent] = AuthAction.asyncZio[UserContext] { ctx =>
    import UserInstances._
    val key = Key(id)
    for {
      _         <- isUserAllowed(ctx)
      mayBeUser <- UserService.getById(key).mapError { ApiErrors.toHttpResult }
      user      <- ZIO.fromOption(mayBeUser).mapError(_ => NotFound)
      _         <- UserService.delete(key).mapError { ApiErrors.toHttpResult }
    } yield Ok(UserNoPasswordInstances.format.writes(user))
  }

  def deleteAll(patterns: String): Action[AnyContent] =
    AuthAction.asyncZio[UserContext] { ctx =>
      import UserInstances._
      val allPatterns = ctx.authorizedPatterns :+ patterns
      isUserAllowed(ctx) *> UserService
        .deleteAll(allPatterns)
        .mapError { ApiErrors.toHttpResult }
        .map { _ =>
          Ok
        }
    }

  def count(): Action[AnyContent] = AuthAction.asyncZio[UserContext] { ctx =>
    val query: Query = Query.oneOf(ctx.authorizedPatterns)
    UserService
      .count(query)
      .map { count =>
        Ok(Json.obj("count" -> count))
      }
      .mapError { ApiErrors.toHttpResult }
  }

  def download(): Action[AnyContent] = AuthAction.asyncZio[UserContext] { ctx =>
    import UserInstances._
    val query: Query = Query.oneOf(ctx.authorizedPatterns)
    isUserAllowed(ctx) *> UserService
      .findByQuery(query)
      .map { s =>
        val source = s
          .map { case (_, data) => Json.toJson(data) }
          .map(Json.stringify)
          .intersperse("", "\n", "\n")
          .map(ByteString.apply)
        Result(
          header = ResponseHeader(200, Map("Content-Disposition" -> "attachment", "filename" -> "users.ndjson")),
          body = HttpEntity.Streamed(source, None, Some("application/json"))
        )
      }
      .mapError { ApiErrors.toHttpResult }

  }

  def upload(strStrategy: String) = AuthAction.asyncZio[UserContext](Import.ndJson) { ctx =>
    isUserAllowed(ctx) *> ImportData
      .importHttp(strStrategy, ctx.body, UserService.importData)
      .refineOrDie[Result](PartialFunction.empty)
  }

  private def isUserAllowed(ctx: SecuredAuthContext[_]): ZIO[UserContext, Result, Unit] =
    IO.when(!ctx.authInfo.admin)(IO.succeed(Forbidden(ApiErrors.error("error.forbidden").toJson)))

}
