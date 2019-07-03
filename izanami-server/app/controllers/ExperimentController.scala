package controllers

import akka.Done
import akka.actor.ActorSystem
import akka.http.scaladsl.util.FastFuture
import akka.stream.ActorMaterializer
import akka.stream.scaladsl.{Sink, Source}
import akka.util.ByteString
import cats.data.NonEmptyList
import cats.effect.Effect
import controllers.actions.SecuredAuthContext
import domains.abtesting.Experiment.ExperimentKey
import domains.{Import, IsAllowed, Key, Node}
import domains.abtesting._
import domains.config.Config.ConfigKey
import domains.config.{Config, ConfigInstances}
import libs.functional.EitherTSyntax
import libs.patch.Patch
import libs.logs.IzanamiLogger
import play.api.http.HttpEntity
import play.api.libs.json.{JsValue, Json}
import play.api.mvc._
import store.Query
import store.Result.AppErrors

import scala.util.{Failure, Success}

class ExperimentController[F[_]: Effect](experimentStore: ExperimentService[F],
                                         eVariantEventStore: ExperimentVariantEventService[F],
                                         system: ActorSystem,
                                         AuthAction: ActionBuilder[SecuredAuthContext, AnyContent],
                                         cc: ControllerComponents)
    extends AbstractController(cc)
    with EitherTSyntax[F] {

  import cats.implicits._
  import cats.effect.implicits._
  import libs.effects._
  import libs.functional.syntax._
  import system.dispatcher
  import AppErrors._
  import libs.http._

  implicit val materializer = ActorMaterializer()(system)

  implicit val eStore   = experimentStore
  implicit val eVeStore = eVariantEventStore

  def list(pattern: String, page: Int = 1, nbElementPerPage: Int = 15, render: String): Action[Unit] =
    AuthAction.asyncF(parse.empty) { ctx =>
      import ExperimentInstances._
      val query: Query = Query.oneOf(ctx.authorizedPatterns).and(pattern.split(",").toList)

      render match {
        case "flat" =>
          experimentStore
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
          experimentStore
            .findByQuery(query)
            .fold(List.empty[(ExperimentKey, Experiment)])(_ :+ _)
            .map { v =>
              Node.valuesToNodes[Experiment](v)(ExperimentInstances.format)
            }
            .map { v =>
              Json.toJson(v)
            }
            .map(json => Ok(json))
            .runWith(Sink.head)
            .toF[F]
        case _ =>
          BadRequest(Json.toJson(AppErrors.error("unknown.render.option"))).pure[F]
      }

    }

  def tree(patterns: String, clientId: String): Action[Unit] =
    AuthAction.async(parse.empty) { ctx =>
      val query: Query = Query.oneOf(ctx.authorizedPatterns).and(patterns.split(",").toList)
      experimentStore
        .findByQuery(query)
        .map(_._2)
        .via(experimentStore.toGraph(clientId))
        .map { graph =>
          Ok(graph)
        }
        .orElse(Source.single(Ok(Json.obj())))
        .runWith(Sink.head)
    }

  def create(): Action[JsValue] = AuthAction.asyncEitherT(parse.json) { ctx =>
    import ExperimentInstances._
    for {
      experiment <- ctx.request.body.validate[Experiment] |> liftJsResult(
                     err => BadRequest(AppErrors.fromJsError(err).toJson)
                   )
      _ <- IsAllowed[Experiment].isAllowed(experiment)(ctx.auth) |> liftBooleanTrue(
            Forbidden(AppErrors.error("error.forbidden").toJson)
          )
      event <- experimentStore.create(experiment.id, experiment) |> mapLeft(err => BadRequest(err.toJson))
    } yield Created(Json.toJson(experiment))
  }

  def get(id: String, clientId: Option[String]): Action[Unit] =
    AuthAction.asyncEitherT(parse.empty) { ctx =>
      import ExperimentInstances._
      val key = Key(id)
      for {
        _ <- Key.isAllowed(key)(ctx.auth) |> liftBooleanTrue(
              Forbidden(AppErrors.error("error.forbidden").toJson)
            )
        experiment <- experimentStore
                       .getById(key) |> liftFOption[Result, Experiment](NotFound)
      } yield Ok(Json.toJson(experiment))
    }

  def update(id: String): Action[JsValue] = AuthAction.asyncEitherT(parse.json) { ctx =>
    import ExperimentInstances._
    for {
      experiment <- ctx.request.body.validate[Experiment] |> liftJsResult(
                     err => BadRequest(AppErrors.fromJsError(err).toJson)
                   )
      _ <- IsAllowed[Experiment].isAllowed(experiment)(ctx.auth) |> liftBooleanTrue(
            Forbidden(AppErrors.error("error.forbidden").toJson)
          )
      event <- experimentStore
                .update(Key(id), experiment.id, experiment) |> mapLeft(err => BadRequest(err.toJson))
    } yield Ok(Json.toJson(experiment))
  }

  def patch(id: String): Action[JsValue] = AuthAction.asyncEitherT(parse.json) { ctx =>
    import ExperimentInstances._
    val key = Key(id)
    for {
      current <- experimentStore.getById(key) |> liftFOption[Result, Experiment](NotFound)
      _ <- IsAllowed[Experiment].isAllowed(current)(ctx.auth) |> liftBooleanTrue(
            Forbidden(AppErrors.error("error.forbidden").toJson)
          )
      updated <- Patch.patch(ctx.request.body, current) |> liftJsResult(
                  err => BadRequest(AppErrors.fromJsError(err).toJson)
                )
      event <- experimentStore
                .update(key, current.id, updated) |> mapLeft(err => BadRequest(err.toJson))
    } yield Ok(Json.toJson(updated))
  }

  def delete(id: String): Action[AnyContent] = AuthAction.asyncEitherT { ctx =>
    import ExperimentInstances._
    val key = Key(id)
    for {
      experiment <- experimentStore
                     .getById(key) |> liftFOption[Result, Experiment](NotFound)
      _ <- IsAllowed[Experiment].isAllowed(experiment)(ctx.auth) |> liftBooleanTrue(
            Forbidden(AppErrors.error("error.forbidden").toJson)
          )
      deleted <- experimentStore.delete(key) |> mapLeft(err => BadRequest(err.toJson))
      _       <- eVariantEventStore.deleteEventsForExperiment(experiment) |> mapLeft(err => BadRequest(err.toJson))
    } yield Ok(Json.toJson(experiment))
  }

  def deleteAll(pattern: String): Action[AnyContent] = AuthAction.asyncEitherT { ctx =>
    val query: Query = Query.oneOf(ctx.authorizedPatterns).and(pattern.split(",").toList)

    val experimentsRelationsDeletes: F[Done] = Effect[F].async { cb =>
      experimentStore
        .findByQuery(query)
        .map(_._2)
        .flatMapMerge(
          4, { experiment =>
            Source.fromFuture(eVariantEventStore.deleteEventsForExperiment(experiment).toIO.unsafeToFuture())
          }
        )
        .runWith(Sink.ignore)
        .onComplete {
          case Success(value) => cb(Right(value))
          case Failure(e)     => cb(Left(e))
        }
    }

    for {
      _       <- experimentsRelationsDeletes |> liftF
      deletes <- experimentStore.deleteAll(query) |> mapLeft(err => BadRequest(err.toJson))
    } yield Ok
  }

  /* Campaign */

  def getVariantForClient(experimentId: String, clientId: String): Action[Unit] =
    AuthAction.asyncEitherT(parse.empty) { ctx =>
      import ExperimentInstances._
      for {
        variant <- experimentStore.variantFor(Key(experimentId), clientId) |> mapLeft(err => BadRequest(err.toJson))
      } yield Ok(Json.toJson(variant))
    }

  def variantDisplayed(experimentId: String, clientId: String): Action[AnyContent] =
    AuthAction.asyncEitherT { ctx =>
      import ExperimentVariantEventInstances._

      val experimentKey = Key(experimentId)
      for {
        variant <- experimentStore.variantFor(experimentKey, clientId) |> mapLeft(err => BadRequest(err.toJson))
        key = ExperimentVariantEventKey(experimentKey,
                                        variant.id,
                                        clientId,
                                        "displayed",
                                        ExperimentVariantEventKey.generateId)
        variantDisplayed = ExperimentVariantDisplayed(key,
                                                      experimentKey,
                                                      clientId,
                                                      variant,
                                                      transformation = 0,
                                                      variantId = variant.id)
        eventCreated <- eVariantEventStore.create(key, variantDisplayed) |> mapLeft(err => BadRequest(err.toJson))
      } yield Ok(Json.toJson(eventCreated))
    }

  def variantWon(experimentId: String, clientId: String): Action[AnyContent] =
    AuthAction.asyncEitherT { ctx =>
      import ExperimentVariantEventInstances._
      val experimentKey = Key(experimentId)

      for {
        variant <- experimentStore.variantFor(experimentKey, clientId) |> mapLeft(err => BadRequest(err.toJson))
        key = ExperimentVariantEventKey(experimentKey,
                                        variant.id,
                                        clientId,
                                        "won",
                                        ExperimentVariantEventKey.generateId)
        variantWon = ExperimentVariantWon(key,
                                          experimentKey,
                                          clientId,
                                          variant,
                                          transformation = 0,
                                          variantId = variant.id)
        eventCreated <- eVariantEventStore.create(key, variantWon) |> mapLeft(err => BadRequest(err.toJson))
      } yield Ok(Json.toJson(Json.toJson(eventCreated)))
    }

  def results(experimentId: String): Action[Unit] =
    AuthAction.asyncF(parse.empty) { ctx =>
      import ExperimentInstances._
      val experimentKey = Key(experimentId)
      experimentStore
        .experimentResult(experimentKey)
        .map { r =>
          Ok(Json.toJson(r))
        }
    }

  def count(): Action[Unit] = AuthAction.asyncF(parse.empty) { ctx =>
    val patterns: Seq[String] = ctx.authorizedPatterns
    experimentStore.count(Query.oneOf(patterns)).map { count =>
      Ok(Json.obj("count" -> count))
    }
  }

  def downloadExperiments(): Action[AnyContent] = AuthAction { ctx =>
    import ExperimentInstances._
    val source = experimentStore
      .findByQuery(Query.oneOf(ctx.authorizedPatterns))
      .map(_._2)
      .map(data => Json.toJson(data))
      .map(Json.stringify _)
      .intersperse("", "\n", "\n")
      .map(ByteString.apply)
      .recover {
        case e =>
          IzanamiLogger.error("Error during experiments download", e)
          ByteString("")
      }
    Result(
      header = ResponseHeader(200, Map("Content-Disposition" -> "attachment", "filename" -> "experiments.dnjson")),
      body = HttpEntity.Streamed(source, None, Some("application/json"))
    )
  }

  def uploadExperiments() = AuthAction.async(Import.ndJson) { ctx =>
    ctx.body
      .via(experimentStore.importData)
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

  def downloadEvents(): Action[AnyContent] = AuthAction { ctx =>
    import ExperimentVariantEventInstances._
    val source = eVariantEventStore
      .listAll(ctx.authorizedPatterns)
      .map(data => Json.toJson(data))
      .map(Json.stringify _)
      .intersperse("", "\n", "\n")
      .map(ByteString.apply)
    Result(
      header =
        ResponseHeader(200, Map("Content-Disposition" -> "attachment", "filename" -> "experiments_events.dnjson")),
      body = HttpEntity.Streamed(source, None, Some("application/json"))
    )
  }

  def uploadEvents() = AuthAction.async(Import.ndJson) { ctx =>
    ctx.body
      .via(eVariantEventStore.importData)
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
