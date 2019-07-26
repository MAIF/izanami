package controllers

import akka.actor.ActorSystem
import akka.stream.ActorMaterializer
import akka.stream.scaladsl.Sink
import akka.util.ByteString
import controllers.actions.SecuredAuthContext
import domains.apikey.{ApiKeyContext, Apikey, ApikeyInstances, ApikeyService}
import domains.{Import, IsAllowed, Key}
import libs.patch.Patch
import libs.logs.IzanamiLogger
import libs.ziohelper.JsResults.jsResultToHttpResponse
import play.api.http.HttpEntity
import play.api.libs.json.{JsValue, Json}
import play.api.mvc._
import store.Query
import store.Result.{AppErrors, IzanamiErrors}
import zio.{Runtime, ZIO}

class ApikeyController(AuthAction: ActionBuilder[SecuredAuthContext, AnyContent], val cc: ControllerComponents)(
    implicit system: ActorSystem,
    runtime: Runtime[ApiKeyContext]
) extends AbstractController(cc) {

  import libs.http._
  import system.dispatcher

  implicit val materializer = ActorMaterializer()(system)

  def list(pattern: String, page: Int = 1, nbElementPerPage: Int = 15): Action[AnyContent] =
    AuthAction.asyncTask[ApiKeyContext] { ctx =>
      import ApikeyInstances._
      val query: Query = Query.oneOf(ctx.authorizedPatterns).and(pattern.split(",").toList)

      ApikeyService
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

  def create(): Action[JsValue] = AuthAction.asyncZio[ApiKeyContext](parse.json) { ctx =>
    import ApikeyInstances._

    for {
      apikey <- jsResultToHttpResponse(ctx.request.body.validate[Apikey])
      _      <- IsAllowed[Apikey].isAllowed(apikey, ctx.auth)(Forbidden(AppErrors.error("error.forbidden").toJson))
      _      <- ApikeyService.create(Key(apikey.clientId), apikey).mapError { IzanamiErrors.toHttpResult }
    } yield Created(Json.toJson(apikey))

  }

  def get(id: String): Action[AnyContent] = AuthAction.asyncZio[ApiKeyContext] { ctx =>
    import ApikeyInstances._
    val key = Key(id)
    for {
      mayBe  <- ApikeyService.getById(key).mapError(e => InternalServerError)
      apikey <- ZIO.fromOption(mayBe).mapError(_ => NotFound)
      _      <- IsAllowed[Apikey].isAllowed(apikey, ctx.auth)(Forbidden(AppErrors.error("error.forbidden").toJson))
    } yield Ok(Json.toJson(apikey))
  }

  def update(id: String): Action[JsValue] = AuthAction.asyncZio[ApiKeyContext](parse.json) { ctx =>
    import ApikeyInstances._

    for {
      apikey <- jsResultToHttpResponse(ctx.request.body.validate[Apikey])
      _      <- IsAllowed[Apikey].isAllowed(apikey, ctx.auth)(Forbidden(AppErrors.error("error.forbidden").toJson))
      _      <- ApikeyService.update(Key(id), Key(apikey.clientId), apikey).mapError { IzanamiErrors.toHttpResult }
    } yield Ok(Json.toJson(apikey))

  }

  def patch(id: String): Action[JsValue] = AuthAction.asyncZio[ApiKeyContext](parse.json) { ctx =>
    import ApikeyInstances._

    val key = Key(id)
    // format: off
    for {
      mayBe   <- ApikeyService.getById(key).mapError(e => InternalServerError)
      current <- ZIO.fromOption(mayBe).mapError(_ => NotFound)
      _       <- IsAllowed[Apikey].isAllowed(current, ctx.auth)(Forbidden(AppErrors.error("error.forbidden").toJson))
      updated <- jsResultToHttpResponse(Patch.patch(ctx.request.body, current))
      _ <- ApikeyService.update(key, Key(current.clientId), updated).mapError { IzanamiErrors.toHttpResult }
    } yield Ok(Json.toJson(updated))
  // format: on
  }

  def delete(id: String): Action[AnyContent] = AuthAction.asyncZio[ApiKeyContext] { ctx =>
    import ApikeyInstances._

    val key = Key(id)
    for {
      mayBe  <- ApikeyService.getById(key).mapError(e => InternalServerError)
      apikey <- ZIO.fromOption(mayBe).mapError(_ => NotFound)
      _      <- IsAllowed[Apikey].isAllowed(apikey, ctx.auth)(Forbidden(AppErrors.error("error.forbidden").toJson))
      _      <- ApikeyService.delete(key).mapError { IzanamiErrors.toHttpResult }
    } yield Ok(Json.toJson(apikey))

  }

  def deleteAll(patterns: Option[String]): Action[AnyContent] =
    AuthAction.asyncZio[ApiKeyContext] { ctx =>
      val allPatterns = patterns.toList.flatMap(_.split(","))
      ApikeyService
        .deleteAll(allPatterns)
        .mapError { IzanamiErrors.toHttpResult }
        .map(_ => Ok)
    }

  def count(): Action[AnyContent] = AuthAction.asyncTask[ApiKeyContext] { ctx =>
    val query: Query = Query.oneOf(ctx.authorizedPatterns)
    ApikeyService.count(query).map { count =>
      Ok(Json.obj("count" -> count))
    }
  }

  def download(): Action[AnyContent] = AuthAction.asyncTask[ApiKeyContext] { ctx =>
    import ApikeyInstances._
    val query: Query = Query.oneOf(ctx.authorizedPatterns)
    ApikeyService
      .findByQuery(query)
      .map { s =>
        val source = s
          .map { case (_, data) => Json.toJson(data) }
          .map(Json.stringify _)
          .intersperse("", "\n", "\n")
          .map(ByteString.apply)
        Result(
          header = ResponseHeader(200, Map("Content-Disposition" -> "attachment", "filename" -> "apikeys.dnjson")),
          body = HttpEntity.Streamed(source, None, Some("application/json"))
        )
      }
  }

  def upload() = AuthAction.asyncTask[ApiKeyContext](Import.ndJson) { ctx =>
    ApikeyService.importData.flatMap { flow =>
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
