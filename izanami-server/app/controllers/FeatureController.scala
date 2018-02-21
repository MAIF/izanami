package controllers

import akka.actor.ActorSystem
import akka.http.scaladsl.util.FastFuture
import akka.stream.scaladsl.Sink
import akka.stream.{ActorMaterializer, Materializer}
import akka.util.ByteString
import controllers.actions.SecuredAuthContext
import domains.abtesting.Experiment
import domains.feature.{Feature, FeatureStore}
import domains.{AuthInfo, Import, ImportResult, Key}
import env.Env
import libs.patch.Patch
import play.api.Logger
import play.api.http.HttpEntity
import play.api.libs.json._
import play.api.mvc._
import store.Result.{AppErrors, ErrorMessage}

import scala.concurrent.Future

class FeatureController(env: Env,
                        featureStore: FeatureStore,
                        system: ActorSystem,
                        AuthAction: ActionBuilder[SecuredAuthContext, AnyContent],
                        cc: ControllerComponents)
    extends AbstractController(cc) {

  import cats.implicits._
  import libs.functional.EitherTOps._
  import libs.functional.Implicits._
  import system.dispatcher
  import AppErrors._

  implicit lazy val mat: Materializer = ActorMaterializer()(system)

  def list(pattern: String, page: Int = 1, nbElementPerPage: Int = 15, active: Boolean): Action[Unit] =
    AuthAction.async(parse.empty) { ctx =>
      import Feature._

      val patternsSeq: Seq[String] = ctx.authorizedPatterns :+ pattern

      featureStore
        .getByIdLike(patternsSeq, page, nbElementPerPage)
        .flatMap {
          case pagingResult if active =>
            Future
              .sequence(pagingResult.results.map(f => f.isActive(Json.obj(), env).map(active => (f, active))))
              .map { pairs =>
                val results: Seq[JsValue] = pairs.map {
                  case (feature, isAllowed) =>
                    Json.toJson(feature).as[JsObject] ++ Json.obj("active" -> (isAllowed && feature.enabled))
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
          case pagingResult =>
            FastFuture.successful(
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
            )
        }
    }

  def listWithContext(pattern: String, page: Int = 1, nbElementPerPage: Int = 15): Action[JsValue] =
    AuthAction.async(parse.json) { ctx =>
      import Feature._

      val patternsSeq: Seq[String] = ctx.authorizedPatterns :+ pattern
      ctx.body match {
        case context: JsObject =>
          featureStore
            .getByIdLike(patternsSeq, page, nbElementPerPage)
            .flatMap { pagingResult =>
              Future
                .sequence(pagingResult.results.map(f => f.isActive(context, env).map(active => (f, active))))
                .map { pairs =>
                  val results: Seq[JsValue] = pairs.map {
                    case (feature, isAllowed) =>
                      Json.toJson(feature).as[JsObject] ++ Json.obj("active" -> (isAllowed && feature.enabled))
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
            }
        case _ =>
          FastFuture.successful(BadRequest(Json.toJson(AppErrors.error("error.json.invalid"))))
      }
    }

  def tree(patterns: String, flat: Boolean): Action[JsValue] =
    AuthAction.async(parse.json) { ctx =>
      featuresTree(patterns, flat, ctx.authorizedPatterns, ctx.body)
    }

  def treeGet(patterns: String, flat: Boolean): Action[Unit] =
    AuthAction.async(parse.empty) { ctx =>
      featuresTree(patterns, flat, ctx.authorizedPatterns, Json.obj())
    }

  private def featuresTree(patterns: String, flat: Boolean, authorizedPatterns: Seq[String], body: JsValue) = {
    val patternsSeq: Seq[String] = authorizedPatterns ++ patterns.split(",")
    val repr                     = Feature.tree(flat) _
    body match {
      case context: JsObject =>
        featureStore
          .getByIdLike(patternsSeq)
          .stream
          .via(repr(context, env))
          .map(graph => Ok(graph))
          .runWith(Sink.head)
      case _ =>
        FastFuture.successful(BadRequest(Json.toJson(AppErrors.error("error.json.invalid"))))
    }
  }

  def create(): Action[JsValue] = AuthAction.async(parse.json) { ctx =>
    import Feature._
    for {
      feature <- ctx.request.body.validate[Feature] |> liftJsResult(
                  err => BadRequest(AppErrors.fromJsError(err).toJson)
                )
      _     <- feature.isAllowed(ctx.auth) |> liftBooleanTrue(Forbidden(AppErrors.error("error.forbidden").toJson))
      event <- featureStore.create(feature.id, feature) |> mapLeft(err => BadRequest(err.toJson))
    } yield Created(Json.toJson(feature))
  }

  def get(id: String): Action[Unit] = AuthAction.async(parse.empty) { ctx =>
    import Feature._
    val key = Key(id)
    for {
      _ <- Feature.isAllowed(key)(ctx.auth) |> liftBooleanTrue[Result](
            Forbidden(AppErrors.error("error.forbidden").toJson)
          )
      feature <- featureStore.getById(key).one |> liftFOption[Result, Feature](NotFound)
    } yield Ok(Json.toJson(feature))
  }

  def check(id: String): Action[Unit] = AuthAction.async(parse.empty) { ctx =>
    checkFeatureWithcontext(id, ctx.auth, Json.obj())
  }

  def checkWithContext(id: String): Action[JsValue] =
    AuthAction.async(parse.json) { ctx =>
      checkFeatureWithcontext(id, ctx.auth, ctx.body)
    }

  private def checkFeatureWithcontext(id: String, user: Option[AuthInfo], contextJson: JsValue): Future[Result] = {
    import Feature._
    val key = Key(id)
    for {
      context   <- contextJson.validate[JsObject] |> liftJsResult(err => BadRequest(AppErrors.fromJsError(err).toJson))
      _         <- Feature.isAllowed(key)(user) |> liftBooleanTrue[Result](Forbidden(AppErrors.error("error.forbidden").toJson))
      feature   <- featureStore.getById(key).one |> liftFOption[Result, Feature](NotFound)
      isAllowed <- feature.isActive(context, env) |> liftFuture[Result, Boolean]
    } yield Ok(Json.obj("active" -> (isAllowed && feature.enabled)))
  }

  def update(id: String): Action[JsValue] = AuthAction.async(parse.json) { ctx =>
    import Feature._
    for {
      feature <- ctx.request.body.validate[Feature] |> liftJsResult(
                  err => BadRequest(AppErrors.fromJsError(err).toJson)
                )
      _     <- feature.isAllowed(ctx.auth) |> liftBooleanTrue[Result](Forbidden(AppErrors.error("error.forbidden").toJson))
      event <- featureStore.update(Key(id), feature.id, feature) |> mapLeft(err => BadRequest(err.toJson))
    } yield Ok(Json.toJson(feature))
  }

  def patch(id: String): Action[JsValue] = AuthAction.async(parse.json) { ctx =>
    import Feature._
    val key = Key(id)
    for {
      current <- featureStore.getById(key).one |> liftFOption[Result, Feature](NotFound)
      _       <- current.isAllowed(ctx.auth) |> liftBooleanTrue(Forbidden(AppErrors.error("error.forbidden").toJson))
      updated <- Patch.patch(ctx.request.body, current) |> liftJsResult(
                  err => BadRequest(AppErrors.fromJsError(err).toJson)
                )
      event <- featureStore
                .update(key, current.id, updated) |> mapLeft(err => BadRequest(err.toJson))
    } yield Ok(Json.toJson(updated))
  }

  def delete(id: String): Action[AnyContent] = AuthAction.async { ctx =>
    import Feature._
    val key = Key(id)
    for {
      feature <- featureStore.getById(key).one |> liftFOption[Result, Feature](NotFound)
      _       <- feature.isAllowed(ctx.auth) |> liftBooleanTrue[Result](Forbidden(AppErrors.error("error.forbidden").toJson))
      deleted <- featureStore.delete(key) |> mapLeft(err => BadRequest(err.toJson))
    } yield Ok(Json.toJson(feature))
  }

  def deleteAll(pattern: String): Action[AnyContent] = AuthAction.async { ctx =>
    val patternsSeq: Seq[String] = ctx.authorizedPatterns :+ pattern
    for {
      deletes <- featureStore.deleteAll(patternsSeq) |> mapLeft(
                  err => BadRequest(err.toJson)
                )
    } yield Ok
  }

  def count(): Action[Unit] = AuthAction.async(parse.empty) { ctx =>
    val patterns: Seq[String] = ctx.authorizedPatterns
    featureStore.count(patterns).map { count =>
      Ok(Json.obj("count" -> count))
    }
  }

  def download(): Action[AnyContent] = AuthAction { ctx =>
    val source = featureStore
      .getByIdLike(ctx.authorizedPatterns)
      .stream
      .map(data => Json.toJson(data))
      .map(Json.stringify _)
      .intersperse("", "\n", "\n")
      .map(ByteString.apply)
    Result(
      header = ResponseHeader(200, Map("Content-Disposition" -> "attachment", "filename" -> "features.dnjson")),
      body = HttpEntity.Streamed(source, None, Some("application/json"))
    )
  }

  def upload() = AuthAction.async(Import.ndJson) { ctx =>
    ctx.body
      .map { case (s, json) => (s, json.validate[Feature]) }
      .mapAsync(4) {
        case (_, JsSuccess(obj, _)) =>
          featureStore.create(obj.id, obj) map { ImportResult.fromResult _ }
        case (s, JsError(_)) =>
          FastFuture.successful(ImportResult.error(ErrorMessage("json.parse.error", s)))
      }
      .fold(ImportResult()) { _ |+| _ }
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
