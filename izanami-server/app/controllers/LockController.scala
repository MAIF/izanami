package controllers

import akka.actor.ActorSystem
import controllers.actions.SecuredAuthContext
import controllers.dto.error.ApiErrors
import domains._
import domains.lock._
import libs.http.HttpContext
import libs.ziohelper.JsResults.jsResultToHttpResponse
import play.api.mvc._
import store.Query
import zio.ZIO

class LockController(AuthAction: ActionBuilder[SecuredAuthContext, AnyContent], cc: ControllerComponents)(
    implicit system: ActorSystem,
    runtime: HttpContext[LockContext]
) extends AbstractController(cc) {

  import libs.http._
  import play.api.libs.json._

  def create(): Action[JsValue] = AuthAction.asyncZio[LockContext](parse.json) { ctx =>
    import LockInstances._
    for {
      lock <- jsResultToHttpResponse(ctx.request.body.validate[IzanamiLock])
      _    <- LockService.create(lock.id, lock).mapError(ApiErrors.toHttpResult)
    } yield Created(Json.toJson(lock))
  }

  def get(id: String): Action[Unit] = AuthAction.asyncZio[LockContext](parse.empty) { _ =>
    import LockInstances._
    val key = Key(id)
    val value = for {
      mayBeLock <- LockService.getBy(key).mapError(ApiErrors.toHttpResult)
      lock      <- ZIO.fromOption(mayBeLock).mapError(_ => NotFound)
    } yield Ok(Json.toJson(lock))
    value
  }

  def list(pattern: String): Action[Unit] = AuthAction.asyncZio[LockContext](parse.empty) { ctx =>
    import LockInstances._
    val query: Query = Query.oneOf(ctx.authorizedPatterns).and(Query.oneOf(pattern.split(",").toList))
    val value = for {
      locks <- LockService.findByQuery(query).mapError(_ => InternalServerError)
    } yield Ok(Json.toJson(locks))
    value
  }

  def update(id: String): Action[JsValue] = AuthAction.asyncZio[LockContext](parse.json) { ctx =>
    import LockInstances._
    val key = Key(id)
    for {
      lock <- jsResultToHttpResponse(ctx.request.body.validate[IzanamiLock])
      _    <- LockService.update(key, lock).mapError(ApiErrors.toHttpResult)
    } yield Ok(Json.toJson(lock))
  }

  def delete(id: String): Action[AnyContent] = AuthAction.asyncZio[LockContext] { _ =>
    val key = Key(id)
    for {
      mayBeLock <- LockService.getBy(key).mapError(ApiErrors.toHttpResult)
      lock      <- ZIO.fromOption(mayBeLock).mapError(_ => NotFound)
      _         <- LockService.delete(key).mapError(ApiErrors.toHttpResult)
    } yield Ok(LockInstances.format.writes(lock))
  }
}
