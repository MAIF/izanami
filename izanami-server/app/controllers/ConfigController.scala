package controllers

import akka.actor.ActorSystem
import akka.stream.{ActorMaterializer, Materializer}
import akka.stream.scaladsl.Sink
import akka.util.ByteString
import controllers.actions.SecuredAuthContext
import controllers.dto.config.ConfigListResult
import controllers.dto.meta.Metadata
import domains._
import domains.config.Config.ConfigKey
import domains.config.{Config, ConfigContext, ConfigInstances, ConfigService}
import libs.patch.Patch
import libs.ziohelper.JsResults.jsResultToHttpResponse
import play.api.http.HttpEntity
import play.api.libs.json._
import play.api.mvc._
import controllers.dto.error.ApiErrors
import store.Query
import libs.http.HttpContext

class ConfigController(AuthAction: ActionBuilder[SecuredAuthContext, AnyContent], val cc: ControllerComponents)(
    implicit system: ActorSystem,
    runtime: HttpContext[ConfigContext]
) extends AbstractController(cc) {

  import libs.http._
  import zio._
  import system.dispatcher

  implicit val mat: Materializer = Materializer(system)

  def list(pattern: String, page: Int = 1, nbElementPerPage: Int = 15, render: String): Action[Unit] =
    AuthAction.asyncTask[ConfigContext](parse.empty) { ctx =>
      import ConfigInstances._
      val query: Query = Query.oneOf(ctx.authorizedPatterns).and(pattern.split(",").toList)
      render match {
        case "flat" =>
          ConfigService
            .findByQuery(query, page, nbElementPerPage)
            .map { r =>
              Ok(Json.toJson(ConfigListResult(r.results.toList, Metadata(page, nbElementPerPage, r.count, r.nbPages))))
            }

        case "tree" =>
          import Node._
          import zio._
          ConfigService
            .findByQuery(query)
            .flatMap(
              s =>
                RIO.fromFuture(
                  _ =>
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
              _ =>
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
      _      <- ConfigService.create(config.id, config).mapError { ApiErrors.toHttpResult }
    } yield Created(Json.toJson(config))

  }

  def get(id: String): Action[Unit] = AuthAction.asyncZio[ConfigContext](parse.empty) { ctx =>
    import ConfigInstances._
    val key = Key(id)
    for {
      mayBeConfig <- ConfigService.getById(key).mapError { ApiErrors.toHttpResult }
      config      <- ZIO.fromOption(mayBeConfig).mapError(_ => NotFound)
    } yield Ok(Json.toJson(config))
  }

  def update(id: String): Action[JsValue] = AuthAction.asyncZio[ConfigContext](parse.json) { ctx =>
    import ConfigInstances._
    for {
      config <- jsResultToHttpResponse(ctx.request.body.validate[Config])
      _      <- ConfigService.update(Key(id), config.id, config).mapError { ApiErrors.toHttpResult }
    } yield Ok(Json.toJson(config))
  }

  def patch(id: String): Action[JsValue] = AuthAction.asyncZio[ConfigContext](parse.json) { ctx =>
    import ConfigInstances._
    val key = Key(id)
    for {
      mayBeConfig <- ConfigService.getById(key).mapError { ApiErrors.toHttpResult }
      current     <- ZIO.fromOption(mayBeConfig).mapError(_ => NotFound)
      updated     <- jsResultToHttpResponse(Patch.patch(ctx.request.body, current))
      _           <- ConfigService.update(key, current.id, updated).mapError { ApiErrors.toHttpResult }
    } yield Ok(Json.toJson(updated))
  }

  def delete(id: String): Action[AnyContent] = AuthAction.asyncZio[ConfigContext] { ctx =>
    import ConfigInstances._
    val key = Key(id)
    for {
      mayBeConfig <- ConfigService.getById(key).mapError { ApiErrors.toHttpResult }
      config      <- ZIO.fromOption(mayBeConfig).mapError(_ => NotFound)
      _           <- ConfigService.delete(key).mapError { ApiErrors.toHttpResult }
    } yield Ok(Json.toJson(config))
  }

  def deleteAll(patterns: Option[String]): Action[AnyContent] =
    AuthAction.asyncZio[ConfigContext] { ctx =>
      val query: Query = Query.oneOf(ctx.authorizedPatterns).and(patterns.toList.flatMap(_.split(",")))
      ConfigService
        .deleteAll(query)
        .mapError { ApiErrors.toHttpResult }
        .map { _ =>
          Ok
        }
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

  def upload(strStrategy: String) = AuthAction.asyncTask[ConfigContext](Import.ndJson) { ctx =>
    ImportData.importHttp(strStrategy, ctx.body, ConfigService.importData)
  }

}
