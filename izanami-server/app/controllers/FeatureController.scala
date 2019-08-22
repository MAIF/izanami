package controllers

import akka.actor.ActorSystem
import akka.stream.scaladsl.Sink
import akka.stream.{ActorMaterializer, Materializer}
import akka.util.ByteString
import controllers.actions.SecuredAuthContext
import domains.feature.{Feature, FeatureContext, FeatureInstances, FeatureService}
import domains._
import domains.feature.Feature.FeatureKey
import domains.webhook.Webhook
import env.Env
import libs.patch.Patch
import libs.logs.IzanamiLogger
import libs.ziohelper.JsResults.jsResultToHttpResponse
import play.api.http.HttpEntity
import play.api.libs.json._
import play.api.mvc._
import store.Query
import store.Result.{AppErrors, IzanamiErrors}
import zio.{IO, Runtime, ZIO}

class FeatureController(system: ActorSystem,
                        AuthAction: ActionBuilder[SecuredAuthContext, AnyContent],
                        cc: ControllerComponents)(implicit runtime: Runtime[FeatureContext])
    extends AbstractController(cc) {

  import system.dispatcher
  import AppErrors._
  import libs.http._
  import FeatureInstances._

  implicit lazy val mat: Materializer = ActorMaterializer()(system)

  def list(pattern: String, page: Int = 1, nbElementPerPage: Int = 15, active: Boolean, render: String): Action[Unit] =
    AuthAction.asyncZio[FeatureContext](parse.empty) { ctx =>
      import FeatureInstances._
      val query: Query = Query.oneOf(ctx.authorizedPatterns).and(Query.oneOf(pattern.split(",").toList))

      render match {
        case "flat" =>
          if (active) {
            FeatureService
              .findByQueryActive(Json.obj(), query, page, nbElementPerPage)
              .map { pagingResult =>
                val results: Seq[JsValue] = pagingResult.results.map {
                  case (feature, isActive) =>
                    FeatureInstances.format.writes(feature).as[JsObject] ++ Json.obj(
                      "active" -> (isActive && feature.enabled)
                    )
                }
                Ok(
                  Json.obj(
                    "results" -> JsArray(results),
                    "metadata" -> Json.obj(
                      "page"     -> pagingResult.page,
                      "pageSize" -> nbElementPerPage,
                      "count"    -> pagingResult.count,
                      "nbPages"  -> pagingResult.nbPages
                    )
                  )
                )
              }
              .mapError { IzanamiErrors.toHttpResult }
          } else {
            FeatureService
              .findByQuery(query, page, nbElementPerPage)
              .map { pagingResult =>
                Ok(
                  Json.obj(
                    "results" -> Json.toJson(pagingResult.results),
                    "metadata" -> Json.obj(
                      "page"     -> page,
                      "pageSize" -> nbElementPerPage,
                      "count"    -> pagingResult.count,
                      "nbPages"  -> pagingResult.nbPages
                    )
                  )
                )
              }
              .mapError(e => InternalServerError)
          }
        case "tree" =>
          if (active) {
            FeatureService
              .findByQueryActive(Json.obj(), query)
              .flatMap { source =>
                ZIO.fromFuture(
                  implicit ec =>
                    source
                      .fold(List.empty[(FeatureKey, Feature, Boolean)])(_ :+ _)
                      .map {
                        _.map { case (k, f, a) => (k, f.toJson(a)) }
                      }
                      .map { Node.valuesToNodes }
                      .map { v =>
                        Json.toJson(v)
                      }
                      .map(json => Ok(json))
                      .runWith(Sink.head)
                )
              }
              .mapError(e => InternalServerError)
          } else {
            FeatureService
              .findByQuery(query)
              .flatMap { source =>
                ZIO.fromFuture(
                  implicit ec =>
                    source
                      .fold(List.empty[(FeatureKey, Feature)])(_ :+ _)
                      .map { Node.valuesToNodes[Feature] }
                      .map { v =>
                        Json.toJson(v)
                      }
                      .map(json => Ok(json))
                      .runWith(Sink.head)
                )
              }
              .mapError(e => InternalServerError)
          }
        case _ =>
          ZIO.succeed(BadRequest(Json.toJson(AppErrors.error("unknown.render.option"))))
      }

    }

  def listWithContext(pattern: String, page: Int = 1, nbElementPerPage: Int = 15): Action[JsValue] =
    AuthAction.asyncZio[FeatureContext](parse.json) { ctx =>
      val query: Query = Query.oneOf(ctx.authorizedPatterns).and(Query.oneOf(pattern.split(",").toList))

      ctx.body match {
        case context: JsObject =>
          FeatureService
            .findByQueryActive(context, query, page, nbElementPerPage)
            .map { pagingResult =>
              val results: Seq[JsValue] = pagingResult.results.map {
                case (feature, isActive) =>
                  FeatureInstances.format.writes(feature).as[JsObject] ++ Json.obj(
                    "active" -> (isActive && feature.enabled)
                  )
              }
              Ok(
                Json.obj(
                  "results" -> JsArray(results),
                  "metadata" -> Json.obj(
                    "page"     -> pagingResult.page,
                    "pageSize" -> nbElementPerPage,
                    "count"    -> pagingResult.count,
                    "nbPages"  -> pagingResult.nbPages
                  )
                )
              )
            }
            .mapError { IzanamiErrors.toHttpResult }
        case _ =>
          ZIO.succeed(BadRequest(Json.toJson(AppErrors.error("error.json.invalid"))))
      }
    }

  def tree(patterns: String, flat: Boolean): Action[JsValue] =
    AuthAction.asyncTask[FeatureContext](parse.json) { ctx =>
      featuresTree(patterns, flat, ctx.authorizedPatterns, ctx.body)
    }

  def treeGet(patterns: String, flat: Boolean): Action[Unit] =
    AuthAction.asyncTask[FeatureContext](parse.empty) { ctx =>
      featuresTree(patterns, flat, ctx.authorizedPatterns, Json.obj())
    }

  private def featuresTree(patterns: String, flat: Boolean, authorizedPatterns: Seq[String], body: JsValue) = {
    val query: Query = Query.oneOf(authorizedPatterns).and(patterns.split(",").toList)
    body match {
      case context: JsObject =>
        FeatureService
          .getFeatureTree(query, flat, context)
          .flatMap { source =>
            ZIO.fromFuture(implicit ec => source.map(graph => Ok(graph)).runWith(Sink.head))
          }
      case _ =>
        ZIO.succeed(BadRequest(Json.toJson(AppErrors.error("error.json.invalid"))))
    }
  }

  def create(): Action[JsValue] = AuthAction.asyncZio[FeatureContext](parse.json) { ctx =>
    import FeatureInstances._
    for {
      feature <- jsResultToHttpResponse(ctx.request.body.validate[Feature])
      _       <- IsAllowed[Feature].isAllowed(feature, ctx.auth)(Forbidden(AppErrors.error("error.forbidden").toJson))
      _       <- FeatureService.create(feature.id, feature).mapError { IzanamiErrors.toHttpResult }
    } yield Created(Json.toJson(feature))
  }

  def get(id: String): Action[Unit] = AuthAction.asyncZio[FeatureContext](parse.empty) { ctx =>
    import FeatureInstances._
    val key = Key(id)
    for {
      _            <- Key.isAllowed(key, ctx.auth)(Forbidden(AppErrors.error("error.forbidden").toJson))
      mayBeFeature <- FeatureService.getById(key).mapError(e => InternalServerError)
      feature      <- ZIO.fromOption(mayBeFeature).mapError(_ => NotFound)
    } yield Ok(Json.toJson(feature))
  }

  def check(id: String): Action[Unit] = AuthAction.asyncZio[FeatureContext](parse.empty) { ctx =>
    checkFeatureWithcontext(id, ctx.auth, Json.obj())
  }

  def checkWithContext(id: String): Action[JsValue] =
    AuthAction.asyncZio[FeatureContext](parse.json) { ctx =>
      checkFeatureWithcontext(id, ctx.auth, ctx.body)
    }

  private def checkFeatureWithcontext(id: String, user: Option[AuthInfo], contextJson: JsValue) = {
    import FeatureInstances._
    val key = Key(id)
    for {
      context   <- jsResultToHttpResponse(contextJson.validate[JsObject])
      _         <- Key.isAllowed(key, user)(Forbidden(AppErrors.error("error.forbidden").toJson))
      mayBePair <- FeatureService.getByIdActive(context, key).mapError { IzanamiErrors.toHttpResult }
      pair      <- ZIO.fromOption(mayBePair).mapError(_ => NotFound)
    } yield Ok(Json.obj("active" -> (pair._2 && pair._1.enabled)))
  }

  def update(id: String): Action[JsValue] = AuthAction.asyncZio[FeatureContext](parse.json) { ctx =>
    import FeatureInstances._
    for {
      feature <- jsResultToHttpResponse(ctx.request.body.validate[Feature])
      _       <- IsAllowed[Feature].isAllowed(feature, ctx.auth)(Forbidden(AppErrors.error("error.forbidden").toJson))
      _       <- FeatureService.update(Key(id), feature.id, feature).mapError { IzanamiErrors.toHttpResult }
    } yield Ok(Json.toJson(feature))
  }

  def patch(id: String): Action[JsValue] = AuthAction.asyncZio[FeatureContext](parse.json) { ctx =>
    import FeatureInstances._
    val key = Key(id)
    for {
      mayBe   <- FeatureService.getById(key).mapError(e => InternalServerError)
      current <- ZIO.fromOption(mayBe).mapError(_ => NotFound)
      _       <- IsAllowed[Feature].isAllowed(current, ctx.auth)(Forbidden(AppErrors.error("error.forbidden").toJson))
      updated <- jsResultToHttpResponse(Patch.patch(ctx.request.body, current))
      _       <- FeatureService.update(key, current.id, updated).mapError { IzanamiErrors.toHttpResult }
    } yield Ok(Json.toJson(updated))
  }

  def delete(id: String): Action[AnyContent] = AuthAction.asyncZio[FeatureContext] { ctx =>
    val key = Key(id)
    for {
      mayBe   <- FeatureService.getById(key).mapError(e => InternalServerError)
      feature <- ZIO.fromOption(mayBe).mapError(_ => NotFound)
      _       <- IsAllowed[Feature].isAllowed(feature, ctx.auth)(Forbidden(AppErrors.error("error.forbidden").toJson))
      deleted <- FeatureService.delete(key).mapError { IzanamiErrors.toHttpResult }
    } yield Ok(FeatureInstances.format.writes(feature))
  }

  def deleteAll(pattern: String): Action[AnyContent] = AuthAction.asyncZio[FeatureContext] { ctx =>
    val query: Query = Query.oneOf(ctx.authorizedPatterns).and(pattern.split(",").toList)
    for {
      deletes <- FeatureService.deleteAll(query).mapError { IzanamiErrors.toHttpResult }
    } yield Ok
  }

  def count(): Action[Unit] = AuthAction.asyncTask[FeatureContext](parse.empty) { ctx =>
    val query: Query = Query.oneOf(ctx.authorizedPatterns)
    FeatureService.count(query).map { count =>
      Ok(Json.obj("count" -> count))
    }
  }

  def download(): Action[AnyContent] = AuthAction.asyncTask[FeatureContext] { ctx =>
    val query: Query = Query.oneOf(ctx.authorizedPatterns)
    FeatureService.findByQuery(query).map { s =>
      val source = s
        .map { case (_, data) => FeatureInstances.format.writes(data) }
        .map(Json.stringify _)
        .intersperse("", "\n", "\n")
        .map(ByteString.apply)
      Result(
        header = ResponseHeader(200, Map("Content-Disposition" -> "attachment", "filename" -> "features.dnjson")),
        body = HttpEntity.Streamed(source, None, Some("application/json"))
      )
    }
  }

  def upload() = AuthAction.asyncTask[FeatureContext](Import.ndJson) { ctx =>
    FeatureService.importData.flatMap { flow =>
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
