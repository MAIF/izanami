package controllers

import akka.Done
import akka.actor.ActorSystem
import akka.http.scaladsl.util.FastFuture
import akka.stream.ActorMaterializer
import akka.stream.scaladsl.{Sink, Source}
import akka.util.ByteString
import controllers.actions.SecuredAuthContext
import domains.{Import, ImportResult, Key}
import domains.abtesting._
import domains.apikey.Apikey
import domains.config.Config
import env.Env
import libs.patch.Patch
import play.api.Logger
import play.api.http.HttpEntity
import play.api.libs.json.{JsError, JsSuccess, JsValue, Json}
import play.api.mvc._
import store.Result.{AppErrors, ErrorMessage}

class ExperimentController(env: Env,
                           experimentStore: ExperimentStore,
                           variantBindingStore: VariantBindingStore,
                           eVariantEventStore: ExperimentVariantEventStore,
                           system: ActorSystem,
                           AuthAction: ActionBuilder[SecuredAuthContext, AnyContent],
                           cc: ControllerComponents)
    extends AbstractController(cc) {

  import cats.implicits._
  import libs.functional.EitherTOps._
  import libs.functional.Implicits._
  import system.dispatcher
  import AppErrors._

  implicit val materializer = ActorMaterializer()(system)

  implicit val vbStore = variantBindingStore
  implicit val eStore  = experimentStore

  def list(pattern: String, page: Int = 1, nbElementPerPage: Int = 15): Action[Unit] =
    AuthAction.async(parse.empty) { ctx =>
      import Experiment._
      val patternsSeq: Seq[String] = ctx.authorizedPatterns :+ pattern
      experimentStore
        .getByIdLike(patternsSeq, page, nbElementPerPage)
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

  def tree(patterns: String, clientId: String): Action[Unit] =
    AuthAction.async(parse.empty) { ctx =>
      val patternsSeq: Seq[String] = ctx.authorizedPatterns ++ patterns.split(",")

      experimentStore
        .getByIdLike(patternsSeq)
        .map(_._2)
        .via(Experiment.toGraph(clientId))
        .map { graph =>
          Ok(graph)
        }
        .orElse(Source.single(Ok(Json.obj())))
        .runWith(Sink.head)
    }

  def create(): Action[JsValue] = AuthAction.async(parse.json) { ctx =>
    import Experiment._
    for {
      experiment <- ctx.request.body.validate[Experiment] |> liftJsResult(
                     err => BadRequest(AppErrors.fromJsError(err).toJson)
                   )
      _     <- experiment.isAllowed(ctx.auth) |> liftBooleanTrue(Forbidden(AppErrors.error("error.forbidden").toJson))
      event <- experimentStore.create(experiment.id, experiment) |> mapLeft(err => BadRequest(err.toJson))
    } yield Created(Json.toJson(experiment))
  }

  def get(id: String, clientId: Option[String]): Action[Unit] =
    AuthAction.async(parse.empty) { ctx =>
      import Experiment._
      val key = Key(id)
      for {
        _ <- Experiment.isAllowed(key)(ctx.auth) |> liftBooleanTrue(
              Forbidden(AppErrors.error("error.forbidden").toJson)
            )
        experiment <- experimentStore
                       .getById(key)
                       .one |> liftFOption[Result, Experiment](NotFound)
      } yield Ok(Json.toJson(experiment))
    }

  def update(id: String): Action[JsValue] = AuthAction.async(parse.json) { ctx =>
    import Experiment._
    for {
      experiment <- ctx.request.body.validate[Experiment] |> liftJsResult(
                     err => BadRequest(AppErrors.fromJsError(err).toJson)
                   )
      _ <- experiment.isAllowed(ctx.auth) |> liftBooleanTrue(Forbidden(AppErrors.error("error.forbidden").toJson))
      event <- experimentStore
                .update(Key(id), experiment.id, experiment) |> mapLeft(err => BadRequest(err.toJson))
    } yield Ok(Json.toJson(experiment))
  }

  def patch(id: String): Action[JsValue] = AuthAction.async(parse.json) { ctx =>
    import Experiment._
    val key = Key(id)
    for {
      current <- experimentStore.getById(key).one |> liftFOption[Result, Experiment](NotFound)
      _       <- current.isAllowed(ctx.auth) |> liftBooleanTrue(Forbidden(AppErrors.error("error.forbidden").toJson))
      updated <- Patch.patch(ctx.request.body, current) |> liftJsResult(
                  err => BadRequest(AppErrors.fromJsError(err).toJson)
                )
      event <- experimentStore
                .update(key, current.id, updated) |> mapLeft(err => BadRequest(err.toJson))
    } yield Ok(Json.toJson(updated))
  }

  def delete(id: String): Action[AnyContent] = AuthAction.async { ctx =>
    import Experiment._
    val key = Key(id)
    for {
      experiment <- experimentStore
                     .getById(key)
                     .one |> liftFOption[Result, Experiment](NotFound)
      _       <- experiment.isAllowed(ctx.auth) |> liftBooleanTrue(Forbidden(AppErrors.error("error.forbidden").toJson))
      deleted <- experimentStore.delete(key) |> mapLeft(err => BadRequest(err.toJson))
      _ <- variantBindingStore
            .deleteAll(Seq(s"${experiment.id.key}*")) |> liftFuture[Result, store.Result.Result[Done]]
      _ <- eVariantEventStore.deleteEventsForExperiment(experiment) |> mapLeft(err => BadRequest(err.toJson))
    } yield Ok(Json.toJson(experiment))
  }

  def deleteAll(pattern: String): Action[AnyContent] = AuthAction.async { ctx =>
    val patternsSeq: Seq[String] = ctx.authorizedPatterns :+ pattern

    val experimentsRelationsDeletes = experimentStore
      .getByIdLike(patternsSeq)
      .map(_._2)
      .flatMapMerge(
        4, { experiment =>
          Source
            .fromFuture(variantBindingStore.deleteAll(Seq(s"${experiment.id.key}*")))
            .merge(
              Source.fromFuture(eVariantEventStore.deleteEventsForExperiment(experiment))
            )
        }
      )
      .runWith(Sink.ignore)

    for {
      _       <- experimentsRelationsDeletes
      deletes <- experimentStore.deleteAll(patternsSeq) |> mapLeft(err => BadRequest(err.toJson))
    } yield Ok
  }

  /* Campaign */

  def getVariantForClient(experimentId: String, clientId: String): Action[Unit] =
    AuthAction.async(parse.empty) { ctx =>
      import VariantBinding._
      val query = VariantBindingKey(Key(experimentId), clientId)
      for {
        variantBinding <- variantBindingStore
                           .getById(query)
                           .one |> liftFOption[Result, VariantBinding](NotFound)
        variantId = variantBinding.variantId
        experiment <- experimentStore
                       .getById(Key(experimentId))
                       .one |> liftFOption[Result, Experiment](NotFound)
        variant <- experiment.variants.find(_.id == variantId) |> liftOption[Result, Variant](NotFound)
      } yield Ok(Json.toJson(variant))
    }

  def variantDisplayed(experimentId: String, clientId: String): Action[AnyContent] =
    AuthAction.async { ctx =>
      import ExperimentVariantEvent._

      val experimentKey     = Key(experimentId)
      val variantBindingKey = VariantBindingKey(experimentKey, clientId)

      for {
        variant <- VariantBinding.variantFor(experimentKey, clientId) |> mapLeft(err => BadRequest(err.toJson))
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
    AuthAction.async { ctx =>
      val experimentKey     = Key(experimentId)
      val variantBindingKey = VariantBindingKey(experimentKey, clientId)

      for {
        variant <- VariantBinding.variantFor(experimentKey, clientId) |> mapLeft(err => BadRequest(err.toJson))
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
    AuthAction.async(parse.empty) { ctx =>
      val experimentKey = Key(experimentId)

      Source
        .fromFuture(experimentStore.getById(experimentKey).one)
        .mapConcat {
          _.toList
        }
        .flatMapConcat { experiment =>
          Source
            .fromFuture(
              eVariantEventStore
                .findVariantResult(experiment)
                .list
                .map(variantsResult => ExperimentResult(experiment, variantsResult))
            )
            .orElse(Source.single(ExperimentResult(experiment, Seq.empty)))
        }
        .map { r =>
          Ok(Json.toJson(r))
        }
        .orElse(Source.single(NotFound))
        .runWith(Sink.head)
    }

  def count(): Action[Unit] = AuthAction.async(parse.empty) { ctx =>
    val patterns: Seq[String] = ctx.authorizedPatterns
    experimentStore.count(patterns).map { count =>
      Ok(Json.obj("count" -> count))
    }
  }

  def downloadExperiments(): Action[AnyContent] = AuthAction { ctx =>
    val source = experimentStore
      .getByIdLike(ctx.authorizedPatterns)
      .map(_._2)
      .map(data => Json.toJson(data))
      .map(Json.stringify _)
      .intersperse("", "\n", "\n")
      .map(ByteString.apply)
      .recover {
        case e =>
          Logger.error("Error during experiments download", e)
          ByteString("")
      }
    Result(
      header = ResponseHeader(200, Map("Content-Disposition" -> "attachment", "filename" -> "experiments.dnjson")),
      body = HttpEntity.Streamed(source, None, Some("application/json"))
    )
  }

  def uploadExperiments() = AuthAction.async(Import.ndJson) { ctx =>
    ctx.body
      .via(Experiment.importData(experimentStore))
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

  def downloadBindings(): Action[AnyContent] = AuthAction { ctx =>
    val source = variantBindingStore
      .getByIdLike(ctx.authorizedPatterns)
      .map { case (_, data) => Json.toJson(data) }
      .map(Json.stringify _)
      .intersperse("", "\n", "\n")
      .map(ByteString.apply)
    Result(
      header =
        ResponseHeader(200, Map("Content-Disposition" -> "attachment", "filename" -> "experiments_bindings.dnjson")),
      body = HttpEntity.Streamed(source, None, Some("application/json"))
    )
  }

  def uploadBindings() = AuthAction.async(Import.ndJson) { ctx =>
    ctx.body
      .via(VariantBinding.importData(variantBindingStore))
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

  def downloadEvents(): Action[AnyContent] = AuthAction { ctx =>
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
      .via(ExperimentVariantEvent.importData(eVariantEventStore))
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
