package controllers

import akka.actor.ActorSystem
import akka.stream.ActorMaterializer
import akka.stream.scaladsl.Sink
import akka.util.ByteString
import controllers.actions.SecuredAuthContext
import domains._
import domains.config.Config.ConfigKey
import domains.config.{Config, ConfigContext, ConfigInstances, ConfigService}
import libs.logs.IzanamiLogger
import libs.patch.Patch
import libs.ziohelper.JsResults.jsResultToHttpResponse
import play.api.http.HttpEntity
import play.api.libs.json._
import play.api.mvc._
import store.Result.{AppErrors, IzanamiErrors}
import store.Query
import zio.Runtime

class ConfigController(system: ActorSystem,
                       AuthAction: ActionBuilder[SecuredAuthContext, AnyContent],
                       val cc: ControllerComponents)(implicit runtime: Runtime[ConfigContext])
    extends AbstractController(cc) {

  import libs.http._
  import zio._
  import system.dispatcher

  implicit val materializer = ActorMaterializer()(system)

  def list(pattern: String, page: Int = 1, nbElementPerPage: Int = 15, render: String): Action[Unit] =
    AuthAction.asyncTask[ConfigContext](parse.empty) { ctx =>
      import ConfigInstances._
      val query: Query = Query.oneOf(ctx.authorizedPatterns).and(pattern.split(",").toList)
      render match {
        case "flat" =>
          ConfigService
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

        case "tree" =>
          import Node._
          import zio._
          ConfigService
            .findByQuery(query)
            .flatMap(
              s =>
                RIO.fromFuture(
                  implicit ec =>
                    s.fold(List.empty[(ConfigKey, Config)])(_ :+ _)
                      .map { v =>
                        Node.valuesToNodes[Config](v)(ConfigInstances.format)
                      }
                      .map { v =>
                        Json.toJson(v)
                      }
                      .map(json => Ok(json))
                      .runWith(Sink.head)
              )
            )
      }
    }

  def tree(patterns: String): Action[Unit] =
    AuthAction.asyncTask[ConfigContext](parse.empty) { ctx =>
      val query: Query = Query.oneOf(ctx.authorizedPatterns).and(patterns.split(",").toList)
      ConfigService
        .findByQuery(query)
        .flatMap(
          s =>
            RIO.fromFuture(
              implicit ec =>
                s.map {
                    case (_, config) =>
                      config.id.jsPath.write[JsValue].writes(config.value)
                  }
                  .fold(Json.obj()) { (acc, js) =>
                    acc.deepMerge(js.as[JsObject])
                  }
                  .map(json => Ok(json))
                  .runWith(Sink.head)
          )
        )
    }

  def create(): Action[JsValue] = AuthAction.asyncZio[ConfigContext](parse.json) { ctx =>
    import ConfigInstances._

    for {
      config <- jsResultToHttpResponse(ctx.request.body.validate[Config])
      _      <- IsAllowed[Config].isAllowed(config, ctx.auth) { Unauthorized(AppErrors.error("error.forbidden").toJson) }
      _      <- ConfigService.create(config.id, config).mapError { IzanamiErrors.toHttpResult }
    } yield Created(Json.toJson(config))

  }

  def get(id: String): Action[Unit] = AuthAction.asyncZio[ConfigContext](parse.empty) { ctx =>
    import ConfigInstances._
    val key = Key(id)
    for {
      _           <- Key.isAllowed(key, ctx.auth)(Forbidden(AppErrors.error("error.forbidden").toJson))
      mayBeConfig <- ConfigService.getById(key).mapError(e => InternalServerError)
      config      <- ZIO.fromOption(mayBeConfig).mapError(_ => NotFound)
    } yield Ok(Json.toJson(config))
  }

  def update(id: String): Action[JsValue] = AuthAction.asyncZio[ConfigContext](parse.json) { ctx =>
    import ConfigInstances._
    for {
      config <- jsResultToHttpResponse(ctx.request.body.validate[Config])
      _      <- IsAllowed[Config].isAllowed(config, ctx.auth)(Forbidden(AppErrors.error("error.forbidden").toJson))
      _      <- ConfigService.update(Key(id), config.id, config).mapError { IzanamiErrors.toHttpResult }
    } yield Ok(Json.toJson(config))
  }

  def patch(id: String): Action[JsValue] = AuthAction.asyncZio[ConfigContext](parse.json) { ctx =>
    import ConfigInstances._
    val key = Key(id)
    for {
      mayBeConfig <- ConfigService.getById(key).mapError(e => InternalServerError)
      current     <- ZIO.fromOption(mayBeConfig).mapError(_ => NotFound)
      _           <- IsAllowed[Config].isAllowed(current, ctx.auth)(Forbidden(AppErrors.error("error.forbidden").toJson))
      updated     <- jsResultToHttpResponse(Patch.patch(ctx.request.body, current))
      event       <- ConfigService.update(key, current.id, updated).mapError { IzanamiErrors.toHttpResult }
    } yield Ok(Json.toJson(updated))
  }

  def delete(id: String): Action[AnyContent] = AuthAction.asyncZio[ConfigContext] { ctx =>
    import ConfigInstances._
    val key = Key(id)
    for {
      mayBeConfig <- ConfigService.getById(key).mapError(e => InternalServerError)
      config      <- ZIO.fromOption(mayBeConfig).mapError(_ => NotFound)
      _           <- IsAllowed[Config].isAllowed(config, ctx.auth)(Forbidden(AppErrors.error("error.forbidden").toJson))
      deleted     <- ConfigService.delete(key).mapError { IzanamiErrors.toHttpResult }
    } yield Ok(Json.toJson(config))
  }

  def deleteAll(patterns: Option[String]): Action[AnyContent] =
    AuthAction.asyncZio[ConfigContext] { ctx =>
      val query: Query = Query.oneOf(ctx.authorizedPatterns)
      for {
        deletes <- ConfigService.deleteAll(query).mapError { IzanamiErrors.toHttpResult }
      } yield Ok
    }

  def count(): Action[AnyContent] = AuthAction.asyncTask[ConfigContext] { ctx =>
    val query: Query = Query.oneOf(ctx.authorizedPatterns)
    ConfigService.count(query).map { count =>
      Ok(Json.obj("count" -> count))
    }
  }

  def download(): Action[AnyContent] = AuthAction.asyncTask[ConfigContext] { ctx =>
    import ConfigInstances._
    val query: Query = Query.oneOf(ctx.authorizedPatterns)
    ConfigService.findByQuery(query).map { values =>
      val source = values
        .map { case (_, data) => Json.toJson(data) }
        .map(Json.stringify _)
        .intersperse("", "\n", "\n")
        .map(ByteString.apply)

      Result(
        header = ResponseHeader(200, Map("Content-Disposition" -> "attachment", "filename" -> "configs.dnjson")),
        body = HttpEntity.Streamed(source, None, Some("application/json"))
      )
    }
  }

  def upload() = AuthAction.asyncTask[ConfigContext](Import.ndJson) { ctx =>
    ConfigService.importData.flatMap { flow =>
      Task.fromFuture { implicit ec =>
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

}
