package controllers

import akka.actor.ActorSystem
import akka.http.scaladsl.util.FastFuture
import akka.stream.scaladsl.{Sink}
import akka.stream.{ActorMaterializer, Materializer}
import akka.util.ByteString
import cats.data.EitherT
import cats.effect.Effect
import controllers.actions.SecuredAuthContext
import domains.feature.{Feature, FeatureInstances, FeatureService}
import domains._
import env.Env
import libs.functional.EitherTSyntax
import libs.patch.Patch
import play.api.Logger
import play.api.http.HttpEntity
import play.api.libs.json._
import play.api.mvc._
import store.Result.{AppErrors}

class FeatureController[F[_]: Effect](env: Env,
                                      featureStore: FeatureService[F],
                                      system: ActorSystem,
                                      AuthAction: ActionBuilder[SecuredAuthContext, AnyContent],
                                      cc: ControllerComponents)
    extends AbstractController(cc)
    with EitherTSyntax[F] {

  import cats.implicits._
  import libs.functional.syntax._
  import system.dispatcher
  import AppErrors._
  import libs.http._
  import FeatureInstances._

  implicit lazy val mat: Materializer = ActorMaterializer()(system)

  def list(pattern: String, page: Int = 1, nbElementPerPage: Int = 15, active: Boolean): Action[Unit] =
    AuthAction.asyncF(parse.empty) { ctx =>
      import FeatureInstances._
      val patternsSeq: Seq[String] = ctx.authorizedPatterns :+ pattern

      if (active) {
        featureStore
          .getByIdLikeActive(env, Json.obj(), patternsSeq, page, nbElementPerPage)
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
      } else {
        featureStore
          .getByIdLike(patternsSeq, page, nbElementPerPage)
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
      }
    }

  def listWithContext(pattern: String, page: Int = 1, nbElementPerPage: Int = 15): Action[JsValue] =
    AuthAction.asyncF(parse.json) { ctx =>
      val patternsSeq: Seq[String] = ctx.authorizedPatterns :+ pattern
      ctx.body match {
        case context: JsObject =>
          featureStore
            .getByIdLikeActive(env, context, patternsSeq, page, nbElementPerPage)
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
        case _ =>
          Effect[F].pure(BadRequest(Json.toJson(AppErrors.error("error.json.invalid"))))
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
    body match {
      case context: JsObject =>
        featureStore
          .getFeatureTree(patternsSeq, flat, context, env)
          .map(graph => Ok(graph))
          .runWith(Sink.head)
      case _ =>
        FastFuture.successful(BadRequest(Json.toJson(AppErrors.error("error.json.invalid"))))
    }
  }

  def create(): Action[JsValue] = AuthAction.asyncEitherT(parse.json) { ctx =>
    import FeatureInstances._
    for {
      feature <- ctx.request.body.validate[Feature] |> liftJsResult(
                  err => BadRequest(AppErrors.fromJsError(err).toJson)
                )
      _ <- IsAllowed[Feature].isAllowed(feature)(ctx.auth) |> liftBooleanTrue(
            Forbidden(AppErrors.error("error.forbidden").toJson)
          )
      _ <- featureStore.create(feature.id, feature) |> mapLeft(err => BadRequest(err.toJson))
    } yield Created(Json.toJson(feature))
  }

  def get(id: String): Action[Unit] = AuthAction.asyncEitherT(parse.empty) { ctx =>
    import FeatureInstances._
    val key = Key(id)
    for {
      _ <- Key.isAllowed(key)(ctx.auth) |> liftBooleanTrue[Result](
            Forbidden(AppErrors.error("error.forbidden").toJson)
          )
      feature <- featureStore.getById(key) |> liftFOption[Result, Feature](NotFound)
    } yield Ok(Json.toJson(feature))
  }

  def check(id: String): Action[Unit] = AuthAction.asyncEitherT(parse.empty) { ctx =>
    checkFeatureWithcontext(id, ctx.auth, Json.obj())
  }

  def checkWithContext(id: String): Action[JsValue] =
    AuthAction.asyncEitherT(parse.json) { ctx =>
      checkFeatureWithcontext(id, ctx.auth, ctx.body)
    }

  private def checkFeatureWithcontext(id: String,
                                      user: Option[AuthInfo],
                                      contextJson: JsValue): EitherT[F, Result, Result] = {
    import FeatureInstances._
    val key = Key(id)
    for {
      context <- contextJson.validate[JsObject] |> liftJsResult(err => BadRequest(AppErrors.fromJsError(err).toJson))
      _       <- Key.isAllowed(key)(user) |> liftBooleanTrue[Result](Forbidden(AppErrors.error("error.forbidden").toJson))
      pair    <- featureStore.getByIdActive(env, context, key) |> liftFOption(NotFound(Json.obj()))
    } yield Ok(Json.obj("active" -> (pair._2 && pair._1.enabled)))
  }

  def update(id: String): Action[JsValue] = AuthAction.asyncEitherT(parse.json) { ctx =>
    import FeatureInstances._
    for {
      feature <- ctx.request.body.validate[Feature] |> liftJsResult(
                  err => BadRequest(AppErrors.fromJsError(err).toJson)
                )
      _ <- IsAllowed[Feature].isAllowed(feature)(ctx.auth) |> liftBooleanTrue(
            Forbidden(AppErrors.error("error.forbidden").toJson)
          )
      event <- featureStore.update(Key(id), feature.id, feature) |> mapLeft(err => BadRequest(err.toJson))
    } yield Ok(Json.toJson(feature))
  }

  def patch(id: String): Action[JsValue] = AuthAction.asyncEitherT(parse.json) { ctx =>
    import FeatureInstances._
    val key = Key(id)
    for {
      current <- featureStore.getById(key) |> liftFOption[Result, Feature](NotFound)
      _ <- IsAllowed[Feature].isAllowed(current)(ctx.auth) |> liftBooleanTrue(
            Forbidden(AppErrors.error("error.forbidden").toJson)
          )
      updated <- Patch.patch(ctx.request.body, current) |> liftJsResult(
                  err => BadRequest(AppErrors.fromJsError(err).toJson)
                )
      event <- featureStore
                .update(key, current.id, updated) |> mapLeft(err => BadRequest(err.toJson))
    } yield Ok(Json.toJson(updated))
  }

  def delete(id: String): Action[AnyContent] = AuthAction.asyncEitherT { ctx =>
    val key = Key(id)
    for {
      feature <- featureStore.getById(key) |> liftFOption[Result, Feature](NotFound)
      _ <- IsAllowed[Feature].isAllowed(feature)(ctx.auth) |> liftBooleanTrue[Result](
            Forbidden(AppErrors.error("error.forbidden").toJson)
          )
      deleted <- featureStore.delete(key) |> mapLeft(err => BadRequest(err.toJson))
    } yield Ok(FeatureInstances.format.writes(feature))
  }

  def deleteAll(pattern: String): Action[AnyContent] = AuthAction.asyncEitherT { ctx =>
    val patternsSeq: Seq[String] = ctx.authorizedPatterns :+ pattern
    for {
      deletes <- featureStore.deleteAll(patternsSeq) |> mapLeft(
                  err => BadRequest(err.toJson)
                )
    } yield Ok
  }

  def count(): Action[Unit] = AuthAction.asyncF(parse.empty) { ctx =>
    val patterns: Seq[String] = ctx.authorizedPatterns
    featureStore.count(patterns).map { count =>
      Ok(Json.obj("count" -> count))
    }
  }

  def download(): Action[AnyContent] = AuthAction { ctx =>
    val source = featureStore
      .getByIdLike(ctx.authorizedPatterns)
      .map { case (_, data) => FeatureInstances.format.writes(data) }
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
      .via(featureStore.importData)
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
