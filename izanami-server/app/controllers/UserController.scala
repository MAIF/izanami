package controllers

import akka.actor.ActorSystem
import akka.stream.ActorMaterializer
import akka.stream.scaladsl.Sink
import akka.util.ByteString
import controllers.actions.SecuredAuthContext
import domains.user.{User, UserContext, UserInstances, UserNoPasswordInstances, UserService}
import domains.{Import, IsAllowed, Key}
import libs.patch.Patch
import libs.logs.IzanamiLogger
import libs.ziohelper.JsResults.jsResultToHttpResponse
import play.api.http.HttpEntity
import play.api.libs.json.{JsValue, Json}
import play.api.mvc._
import store.Query
import store.Result.{AppErrors, IzanamiErrors}
import zio.{IO, Runtime, ZIO}

class UserController(system: ActorSystem,
                     AuthAction: ActionBuilder[SecuredAuthContext, AnyContent],
                     val cc: ControllerComponents)(implicit R: Runtime[UserContext])
    extends AbstractController(cc) {

  import system.dispatcher
  import libs.http._

  implicit val materializer = ActorMaterializer()(system)

  def list(pattern: String, page: Int = 1, nbElementPerPage: Int = 15): Action[AnyContent] =
    AuthAction.asyncTask[UserContext] { ctx =>
      import UserNoPasswordInstances._
      val query: Query = Query.oneOf(ctx.authorizedPatterns).and(pattern.split(",").toList)
      UserService
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

  def create(): Action[JsValue] = AuthAction.asyncZio[UserContext](parse.json) { ctx =>
    import UserInstances._
    val body = ctx.request.body
    for {
      user  <- jsResultToHttpResponse(body.validate[User])
      _     <- isUserAllowed(ctx, user)
      event <- UserService.create(Key(user.id), user).mapError { IzanamiErrors.toHttpResult }
    } yield Created(UserNoPasswordInstances.format.writes(user))

  }

  private def isUserAllowed(ctx: SecuredAuthContext[_], user: User)(implicit A: IsAllowed[User]): IO[Result, Unit] =
    IsAllowed[User].isAllowed(user, ctx.auth)(Forbidden(AppErrors.error("error.forbidden").toJson))

  def get(id: String): Action[AnyContent] = AuthAction.asyncZio[UserContext] { ctx =>
    import UserNoPasswordInstances._
    val key = Key(id)
    for {
      mayBeUser <- UserService.getById(key).mapError(e => InternalServerError)
      user      <- ZIO.fromOption(mayBeUser).mapError(_ => NotFound)
    } yield Ok(Json.toJson(user))
  }

  def update(id: String): Action[JsValue] = AuthAction.asyncZio[UserContext](parse.json) { ctx =>
    import UserInstances._
    val userOrError = UserNoPasswordInstances.format.reads(ctx.request.body)
    for {
      user <- jsResultToHttpResponse(userOrError)
      _    <- isUserAllowed(ctx, user)
      _    <- UserService.update(Key(id), Key(user.id), user).mapError { IzanamiErrors.toHttpResult }
    } yield Ok(UserNoPasswordInstances.format.writes(user))
  }

  def patch(id: String): Action[JsValue] = AuthAction.asyncZio[UserContext](parse.json) { ctx =>
    import UserInstances._
    val key = Key(id)
    for {
      mayBeUser <- UserService.getById(key).mapError(e => InternalServerError)
      user      <- ZIO.fromOption(mayBeUser).mapError(_ => NotFound)
      _         <- isUserAllowed(ctx, user)
      updated   <- jsResultToHttpResponse(Patch.patch(ctx.request.body, user))
      _         <- UserService.update(key, Key(user.id), updated).mapError { IzanamiErrors.toHttpResult }
    } yield Ok(UserNoPasswordInstances.format.writes(updated))
  }

  def delete(id: String): Action[AnyContent] = AuthAction.asyncZio[UserContext] { ctx =>
    import UserInstances._
    val key = Key(id)
    for {
      mayBeUser <- UserService.getById(key).mapError(e => InternalServerError)
      user      <- ZIO.fromOption(mayBeUser).mapError(_ => NotFound)
      _         <- isUserAllowed(ctx, user)
      _         <- UserService.delete(key).mapError { IzanamiErrors.toHttpResult }
    } yield Ok(UserNoPasswordInstances.format.writes(user))
  }

  def deleteAll(patterns: String): Action[AnyContent] =
    AuthAction.asyncZio[UserContext] { ctx =>
      val allPatterns = ctx.authorizedPatterns :+ patterns
      UserService
        .deleteAll(allPatterns)
        .mapError { IzanamiErrors.toHttpResult }
        .map { _ =>
          Ok
        }
    }

  def count(): Action[AnyContent] = AuthAction.asyncTask[UserContext] { ctx =>
    val query: Query = Query.oneOf(ctx.authorizedPatterns)
    UserService.count(query).map { count =>
      Ok(Json.obj("count" -> count))
    }
  }

  def download(): Action[AnyContent] = AuthAction.asyncTask[UserContext] { ctx =>
    import UserInstances._
    val query: Query = Query.oneOf(ctx.authorizedPatterns)
    UserService
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

  }

  def upload() = AuthAction.asyncTask[UserContext](Import.ndJson) { ctx =>
    import UserInstances._
    UserService.importData.flatMap { flow =>
      ZIO.fromFuture(
        implicit ec =>
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
      )
    }
  }

}
