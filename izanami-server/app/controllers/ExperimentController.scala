package controllers

import akka.Done
import akka.actor.ActorSystem
import akka.stream.ActorMaterializer
import akka.stream.scaladsl.{Sink, Source}
import akka.util.ByteString
import cats.effect.Effect
import controllers.actions.SecuredAuthContext
import domains.{Import, IsAllowed, Key}
import domains.abtesting._
import libs.functional.EitherTSyntax
import libs.patch.Patch
import play.api.Logger
import play.api.http.HttpEntity
import play.api.libs.json.{JsValue, Json}
import play.api.mvc._
import store.Result.{AppErrors}

import scala.util.{Failure, Success}

class ExperimentController[F[_]: Effect](experimentStore: ExperimentStore[F],
                                         variantBindingStore: VariantBindingStore[F],
                                         eVariantEventStore: ExperimentVariantEventStore[F],
                                         system: ActorSystem,
                                         AuthAction: ActionBuilder[SecuredAuthContext, AnyContent],
                                         cc: ControllerComponents)
    extends AbstractController(cc)
    with EitherTSyntax[F] {

  import cats.implicits._
  import cats.effect.implicits._
  import libs.functional.syntax._
  import system.dispatcher
  import AppErrors._
  import libs.http._

  implicit val materializer = ActorMaterializer()(system)

  implicit val vbStore = variantBindingStore
  implicit val eStore  = experimentStore

  def list(pattern: String, page: Int = 1, nbElementPerPage: Int = 15): Action[Unit] =
    AuthAction.asyncF(parse.empty) { ctx =>
      import ExperimentInstances._
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
      _ <- variantBindingStore
            .deleteAll(Seq(s"${experiment.id.key}*")) |> liftF[Result, store.Result.Result[Done]]
      _ <- eVariantEventStore.deleteEventsForExperiment(experiment) |> mapLeft(err => BadRequest(err.toJson))
    } yield Ok(Json.toJson(experiment))
  }

  def deleteAll(pattern: String): Action[AnyContent] = AuthAction.asyncEitherT { ctx =>
    val patternsSeq: Seq[String] = ctx.authorizedPatterns :+ pattern

    val experimentsRelationsDeletes: F[Done] = Effect[F].async { cb =>
      experimentStore
        .getByIdLike(patternsSeq)
        .map(_._2)
        .flatMapMerge(
          4, { experiment =>
            Source
              .fromFuture(variantBindingStore.deleteAll(Seq(s"${experiment.id.key}*")).toIO.unsafeToFuture())
              .merge(
                Source.fromFuture(eVariantEventStore.deleteEventsForExperiment(experiment).toIO.unsafeToFuture())
              )
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
      deletes <- experimentStore.deleteAll(patternsSeq) |> mapLeft(err => BadRequest(err.toJson))
    } yield Ok
  }

  /* Campaign */

  def getVariantForClient(experimentId: String, clientId: String): Action[Unit] =
    AuthAction.asyncEitherT(parse.empty) { ctx =>
      import ExperimentInstances._
      val query = VariantBindingKey(Key(experimentId), clientId)
      for {
        variantBinding <- variantBindingStore
                           .getById(query) |> liftFOption[Result, VariantBinding](NotFound)
        variantId = variantBinding.variantId
        experiment <- experimentStore
                       .getById(Key(experimentId)) |> liftFOption[Result, Experiment](NotFound)
        variant <- experiment.variants.find(_.id == variantId) |> liftOption[Result, Variant](NotFound)
      } yield Ok(Json.toJson(variant))
    }

  def variantDisplayed(experimentId: String, clientId: String): Action[AnyContent] =
    AuthAction.asyncEitherT { ctx =>
      import ExperimentVariantEventInstances._

      val experimentKey     = Key(experimentId)
      val variantBindingKey = VariantBindingKey(experimentKey, clientId)

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
      val experimentKey     = Key(experimentId)
      val variantBindingKey = VariantBindingKey(experimentKey, clientId)

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
    AuthAction.async(parse.empty) { ctx =>
      val experimentKey = Key(experimentId)

      Source
        .fromFuture(experimentStore.getById(experimentKey).toIO.unsafeToFuture())
        .mapConcat {
          _.toList
        }
        .flatMapConcat { experiment =>
          eVariantEventStore
            .findVariantResult(experiment)
            .fold(Seq.empty[VariantResult])(_ :+ _)
            .map(variantsResult => ExperimentResult(experiment, variantsResult))
            .orElse(Source.single(ExperimentResult(experiment, Seq.empty)))
        }
        .map { r =>
          Ok(Json.toJson(r))
        }
        .orElse(Source.single(NotFound))
        .runWith(Sink.head)
    }

  def count(): Action[Unit] = AuthAction.asyncF(parse.empty) { ctx =>
    val patterns: Seq[String] = ctx.authorizedPatterns
    experimentStore.count(patterns).map { count =>
      Ok(Json.obj("count" -> count))
    }
  }

  def downloadExperiments(): Action[AnyContent] = AuthAction { ctx =>
    import ExperimentInstances._
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
      .via(experimentStore.importData)
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
    import VariantBindingInstances._
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
      .via(variantBindingStore.importData)
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
          Logger.error("Error importing file", e)
          InternalServerError
      }
      .runWith(Sink.head)
  }

}
