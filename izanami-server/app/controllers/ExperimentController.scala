package controllers

import akka.actor.ActorSystem
import akka.stream.ActorMaterializer
import akka.stream.scaladsl.{Sink, Source}
import akka.util.ByteString
import controllers.actions.SecuredAuthContext
import controllers.dto.{ExperimentListResult, Metadata}
import domains.abtesting.Experiment.ExperimentKey
import domains.{Import, ImportData, IsAllowed, Key, Node}
import domains.abtesting._
import libs.patch.Patch
import libs.logs.IzanamiLogger
import libs.ziohelper.JsResults.jsResultToHttpResponse
import play.api.http.HttpEntity
import play.api.libs.json.{JsValue, Json}
import play.api.mvc._
import store.Query
import store.Result.{IzanamiErrors, ValidationError}
import zio.{Runtime, Task, ZIO}

class ExperimentController(system: ActorSystem,
                           AuthAction: ActionBuilder[SecuredAuthContext, AnyContent],
                           cc: ControllerComponents)(implicit runtime: Runtime[ExperimentContext])
    extends AbstractController(cc) {

  import system.dispatcher
  import libs.http._

  implicit val materializer = ActorMaterializer()(system)

  def list(pattern: String, page: Int = 1, nbElementPerPage: Int = 15, render: String): Action[Unit] =
    AuthAction.asyncTask[ExperimentContext](parse.empty) { ctx =>
      import ExperimentInstances._
      val query: Query = Query.oneOf(ctx.authorizedPatterns).and(pattern.split(",").toList)

      render match {
        case "flat" =>
          ExperimentService
            .findByQuery(query, page, nbElementPerPage)
            .map { r =>
              Ok(Json.toJson(ExperimentListResult(r.results, Metadata(page, nbElementPerPage, r.count, r.nbPages))))
            }
        case "tree" =>
          import Node._
          ExperimentService
            .findByQuery(query)
            .flatMap { s =>
              ZIO.fromFuture { _ =>
                s.fold(List.empty[(ExperimentKey, Experiment)])(_ :+ _)
                  .map { v =>
                    Node.valuesToNodes[Experiment](v)(ExperimentInstances.format)
                  }
                  .map { v =>
                    Json.toJson(v)
                  }
                  .map(json => Ok(json))
                  .runWith(Sink.head)
              }
            }
        case _ =>
          Task.succeed(BadRequest(Json.toJson(ValidationError.error("unknown.render.option"))))
      }

    }

  def tree(patterns: String, clientId: String): Action[Unit] =
    AuthAction.asyncTask[ExperimentContext](parse.empty) { ctx =>
      val query: Query = Query.oneOf(ctx.authorizedPatterns).and(patterns.split(",").toList)
      for {
        s <- ExperimentService.findByQuery(query)
        r <- ZIO.fromFuture(
              _ =>
                s.map(_._2)
                  .via(ExperimentService.toGraph(clientId))
                  .map { graph =>
                    Ok(graph)
                  }
                  .orElse(Source.single(Ok(Json.obj())))
                  .runWith(Sink.head)
            )
      } yield r
    }

  def create(): Action[JsValue] = AuthAction.asyncZio[ExperimentContext](parse.json) { ctx =>
    import ExperimentInstances._
    val body = ctx.request.body
    for {
      experiment <- jsResultToHttpResponse(body.validate[Experiment])
      _          <- isExperimentAllowed(experiment, ctx)
      _          <- ExperimentService.create(experiment.id, experiment).mapError { IzanamiErrors.toHttpResult }
    } yield Created(Json.toJson(experiment))
  }

  def get(id: String, clientId: Option[String]): Action[Unit] =
    AuthAction.asyncZio[ExperimentContext](parse.empty) { ctx =>
      import ExperimentInstances._
      val key = Key(id)
      for {
        _          <- Key.isAllowed(key, ctx.auth)(Forbidden(ValidationError.error("error.forbidden").toJson))
        mayBe      <- ExperimentService.getById(key).mapError(_ => InternalServerError)
        experiment <- ZIO.fromOption(mayBe).mapError(_ => NotFound)
      } yield Ok(Json.toJson(experiment))
    }

  def update(id: String): Action[JsValue] = AuthAction.asyncZio[ExperimentContext](parse.json) { ctx =>
    import ExperimentInstances._
    val body = ctx.request.body
    for {
      experiment <- jsResultToHttpResponse(body.validate[Experiment])
      _          <- isExperimentAllowed(experiment, ctx)
      _          <- ExperimentService.update(Key(id), experiment.id, experiment).mapError { IzanamiErrors.toHttpResult }
    } yield Ok(Json.toJson(experiment))
  }

  def patch(id: String): Action[JsValue] = AuthAction.asyncZio[ExperimentContext](parse.json) { ctx =>
    import ExperimentInstances._
    val key = Key(id)
    for {
      mayBe   <- ExperimentService.getById(key).mapError(_ => InternalServerError)
      current <- ZIO.fromOption(mayBe).mapError(_ => NotFound)
      _       <- isExperimentAllowed(current, ctx)
      body    = ctx.request.body
      updated <- jsResultToHttpResponse(Patch.patch(body, current))
      _       <- ExperimentService.update(key, current.id, updated).mapError { IzanamiErrors.toHttpResult }
    } yield Ok(Json.toJson(updated))
  }

  def delete(id: String): Action[AnyContent] = AuthAction.asyncZio[ExperimentContext] { ctx =>
    import ExperimentInstances._
    val key = Key(id)
    for {
      mayBe      <- ExperimentService.getById(key).mapError(_ => InternalServerError)
      experiment <- ZIO.fromOption(mayBe).mapError(_ => NotFound)
      _          <- isExperimentAllowed(experiment, ctx)
      _          <- ExperimentService.delete(key).mapError { IzanamiErrors.toHttpResult }
      _          <- ExperimentVariantEventService.deleteEventsForExperiment(experiment).mapError { IzanamiErrors.toHttpResult }
    } yield Ok(Json.toJson(experiment))
  }

  def deleteAll(pattern: String): Action[AnyContent] = AuthAction.asyncZio[ExperimentContext] { req =>
    val query: Query = Query.oneOf(req.authorizedPatterns).and(pattern.split(",").toList)

    for {
      runtime <- ZIO.runtime[ExperimentContext]
      _ <- ExperimentService
            .findByQuery(query)
            .map { s =>
              ZIO.fromFuture { _ =>
                s.map(_._2)
                  .flatMapMerge(
                    4, { experiment =>
                      val value = ExperimentVariantEventService.deleteEventsForExperiment(experiment).either
                      Source.fromFuture(
                        runtime.unsafeRunToFuture(value)
                      )
                    }
                  )
                  .runWith(Sink.ignore)
              }
            }
            .mapError { _ =>
              InternalServerError("")
            }

      _ <- ExperimentService.deleteAll(query).mapError { IzanamiErrors.toHttpResult }
    } yield Ok
  }

  /* Campaign */

  def getVariantForClient(experimentId: String, clientId: String): Action[Unit] =
    AuthAction.asyncZio[ExperimentContext](parse.empty) { _ =>
      import ExperimentInstances._
      for {
        variant <- ExperimentService.variantFor(Key(experimentId), clientId).mapError { IzanamiErrors.toHttpResult }
      } yield Ok(Json.toJson(variant))
    }

  def variantDisplayed(experimentId: String, clientId: String): Action[AnyContent] =
    AuthAction.asyncZio[ExperimentContext] { _ =>
      import ExperimentVariantEventInstances._

      val experimentKey = Key(experimentId)
      for {
        variant <- ExperimentService.variantFor(experimentKey, clientId).mapError { IzanamiErrors.toHttpResult }
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
        eventCreated <- ExperimentVariantEventService.create(key, variantDisplayed).mapError {
                         IzanamiErrors.toHttpResult
                       }
      } yield Ok(Json.toJson(eventCreated))
    }

  def variantWon(experimentId: String, clientId: String): Action[AnyContent] =
    AuthAction.asyncZio[ExperimentContext] { _ =>
      import ExperimentVariantEventInstances._
      val experimentKey = Key(experimentId)

      for {
        variant <- ExperimentService.variantFor(experimentKey, clientId).mapError { IzanamiErrors.toHttpResult }
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
        eventCreated <- ExperimentVariantEventService.create(key, variantWon).mapError { IzanamiErrors.toHttpResult }
      } yield Ok(Json.toJson(Json.toJson(eventCreated)))
    }

  def results(experimentId: String): Action[Unit] =
    AuthAction.asyncZio[ExperimentContext](parse.empty) { _ =>
      import ExperimentInstances._
      val experimentKey = Key(experimentId)
      ExperimentService
        .experimentResult(experimentKey)
        .mapError { IzanamiErrors.toHttpResult }
        .map { r =>
          Ok(Json.toJson(r))
        }
    }

  def count(): Action[Unit] = AuthAction.asyncTask[ExperimentContext](parse.empty) { ctx =>
    val patterns: Seq[String] = ctx.authorizedPatterns
    ExperimentService.count(Query.oneOf(patterns)).map { count =>
      Ok(Json.obj("count" -> count))
    }
  }

  def downloadExperiments(): Action[AnyContent] = AuthAction.asyncTask[ExperimentContext] { ctx =>
    import ExperimentInstances._
    ExperimentService
      .findByQuery(Query.oneOf(ctx.authorizedPatterns))
      .map { s =>
        val source = s
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

  }

  def uploadExperiments(strStrategy: String) = AuthAction.asyncTask[ExperimentContext](Import.ndJson) { ctx =>
    ImportData.importHttp(strStrategy, ctx.body, ExperimentService.importData)
  }

  def downloadEvents(): Action[AnyContent] = AuthAction.asyncTask[ExperimentContext] { ctx =>
    import ExperimentVariantEventInstances._
    ExperimentVariantEventService
      .listAll(ctx.authorizedPatterns)
      .map { s =>
        val source = s
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
  }

  def uploadEvents() = AuthAction.asyncTask[ExperimentContext](Import.ndJson) { ctx =>
    for {
      flow <- ExperimentVariantEventService.importData
      res <- ZIO.fromFuture(
              _ =>
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
    } yield res
  }

  private def isExperimentAllowed(experiment: Experiment,
                                  ctx: SecuredAuthContext[_])(implicit A: IsAllowed[Experiment]): zio.IO[Result, Unit] =
    IsAllowed[Experiment].isAllowed(experiment, ctx.auth)(Forbidden(ValidationError.error("error.forbidden").toJson))

}
