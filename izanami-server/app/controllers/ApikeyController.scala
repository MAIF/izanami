package controllers

import akka.actor.ActorSystem
import akka.stream.ActorMaterializer
import akka.util.ByteString
import controllers.actions.SecuredAuthContext
import controllers.dto.apikeys.ApikeyListResult
import controllers.dto.error.ApiErrors
import controllers.dto.meta.Metadata
import domains.apikey.{ApiKeyContext, Apikey, ApikeyInstances, ApikeyService}
import domains.{Import, ImportData, IsAllowed, Key, PatternRights}
import libs.patch.Patch
import libs.ziohelper.JsResults.jsResultToHttpResponse
import play.api.http.HttpEntity
import play.api.libs.json.{JsValue, Json}
import play.api.mvc._
import store.Query
import zio.{Runtime, ZIO}

class ApikeyController(AuthAction: ActionBuilder[SecuredAuthContext, AnyContent], val cc: ControllerComponents)(
    implicit system: ActorSystem,
    runtime: Runtime[ApiKeyContext]
) extends AbstractController(cc) {

  import libs.http._
  import system.dispatcher

  implicit val materializer = ActorMaterializer()(system)

  def list(pattern: String, page: Int = 1, nbElementPerPage: Int = 15): Action[AnyContent] =
    AuthAction.asyncZio[ApiKeyContext] { ctx =>
      import ApikeyListResult._
      val query: Query = Query.oneOf(ctx.authorizedPatterns).and(pattern.split(",").toList)

      ApikeyService
        .findByQuery(query, page, nbElementPerPage)
        .map { r =>
          Ok(
            Json.toJson(ApikeyListResult(r.results.toList, Metadata(page, nbElementPerPage, r.count, r.nbPages)))
          )
        }
        .mapError { ApiErrors.toHttpResult }
    }

  def create(): Action[JsValue] = AuthAction.asyncZio[ApiKeyContext](parse.json) { ctx =>
    import ApikeyInstances._

    for {
      apikey <- jsResultToHttpResponse(ctx.request.body.validate[Apikey])
      _      <- ApikeyService.create(Key(apikey.clientId), apikey).mapError { ApiErrors.toHttpResult }
    } yield Created(Json.toJson(apikey))

  }

  def get(id: String): Action[AnyContent] = AuthAction.asyncZio[ApiKeyContext] { ctx =>
    import ApikeyInstances._
    val key = Key(id)
    for {
      mayBe  <- ApikeyService.getById(key).mapError(_ => InternalServerError)
      apikey <- ZIO.fromOption(mayBe).mapError(_ => NotFound)
    } yield Ok(Json.toJson(apikey))
  }

  def update(id: String): Action[JsValue] = AuthAction.asyncZio[ApiKeyContext](parse.json) { ctx =>
    import ApikeyInstances._

    for {
      apikey <- jsResultToHttpResponse(ctx.request.body.validate[Apikey])
      _      <- ApikeyService.update(Key(id), Key(apikey.clientId), apikey).mapError { ApiErrors.toHttpResult }
    } yield Ok(Json.toJson(apikey))

  }

  def patch(id: String): Action[JsValue] = AuthAction.asyncZio[ApiKeyContext](parse.json) { ctx =>
    import ApikeyInstances._

    val key = Key(id)
    // format: off
    for {
      mayBe   <- ApikeyService.getById(key).mapError(_ => InternalServerError)
      current <- ZIO.fromOption(mayBe).mapError(_ => NotFound)
      updated <- jsResultToHttpResponse(Patch.patch(ctx.request.body, current))
      _ <- ApikeyService.update(key, Key(current.clientId), updated).mapError { ApiErrors.toHttpResult }
    } yield Ok(Json.toJson(updated))
  // format: on
  }

  def delete(id: String): Action[AnyContent] = AuthAction.asyncZio[ApiKeyContext] { ctx =>
    import ApikeyInstances._

    val key = Key(id)
    for {
      mayBe  <- ApikeyService.getById(key).mapError(_ => InternalServerError)
      apikey <- ZIO.fromOption(mayBe).mapError(_ => NotFound)
      _      <- ApikeyService.delete(key).mapError { ApiErrors.toHttpResult }
    } yield Ok(Json.toJson(apikey))

  }

  def deleteAll(patterns: Option[String]): Action[AnyContent] =
    AuthAction.asyncZio[ApiKeyContext] { ctx =>
      val allPatterns = patterns.toList.flatMap(_.split(","))
      ApikeyService
        .deleteAll(allPatterns)
        .mapError { ApiErrors.toHttpResult }
        .map(_ => Ok)
    }

  def count(): Action[AnyContent] = AuthAction.asyncZio[ApiKeyContext] { ctx =>
    val query: Query = Query.oneOf(ctx.authorizedPatterns)
    ApikeyService
      .count(query)
      .map { count =>
        Ok(Json.obj("count" -> count))
      }
      .mapError { ApiErrors.toHttpResult }
  }

  def download(): Action[AnyContent] = AuthAction.asyncZio[ApiKeyContext] { ctx =>
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
      .mapError { ApiErrors.toHttpResult }
  }

  def upload(strStrategy: String) = AuthAction.asyncZio[ApiKeyContext](Import.ndJson) { ctx =>
    ImportData.importHttp(strStrategy, ctx.body, ApikeyService.importData).refineToOrDie[Result]
  }

}
