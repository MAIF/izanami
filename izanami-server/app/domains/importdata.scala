package domains

import akka.NotUsed
import akka.stream.{scaladsl, Materializer}
import akka.stream.scaladsl.{Flow, Source}
import domains.feature.FeatureContext
import libs.logs.IzanamiLogger
import play.api.libs.json.{JsError, JsSuccess, JsValue, Reads}
import play.api.mvc.Results
import store.Result.{AppErrors, IzanamiErrors}

sealed trait ImportStrategy extends Product with Serializable
object ImportStrategy {
  case object Replace extends ImportStrategy
  case object Keep    extends ImportStrategy

  def parse(str: String): Either[IzanamiErrors, ImportStrategy] =
    str match {
      case "Replace" => Right(Replace)
      case "Keep"    => Right(Keep)
      case _         => Left(AppErrors.error("error.strategy.expected", "Replace", "Keep"))
    }
}

object ImportData {

  import cats.implicits._
  import fs2._
  import streamz.converter._
  import zio._
  import zio.interop.catz._

  def importData[Ctx, Key, Data](
      strategy: ImportStrategy,
      key: Data => Key,
      get: Key => RIO[Ctx, Option[Data]],
      create: (Key, Data) => ZIO[Ctx, IzanamiErrors, Data],
      update: (Key, Data) => ZIO[Ctx, IzanamiErrors, Data]
  )(implicit reads: Reads[Data]): RIO[Ctx, Pipe[Task, (String, JsValue), ImportResult]] = {

    val mutation: (Key, Data) => RIO[Ctx, ImportResult] = strategy match {
      case ImportStrategy.Replace =>
        (key, data) =>
          val result: ZIO[Ctx, IzanamiErrors, Data] = for {
            mayBeData <- get(key).refineToOrDie[IzanamiErrors]
            result <- mayBeData match {
                       case Some(_) => update(key, data)
                       case None    => create(key, data)
                     }
          } yield result
          result.either.map { ImportResult.fromResult }
      case ImportStrategy.Keep =>
        (key, data) =>
          val result: ZIO[Ctx, IzanamiErrors, Data] = for {
            mayBeData <- get(key).refineToOrDie[IzanamiErrors]
            result <- mayBeData match {
                       case Some(_) => ZIO(data).refineToOrDie[IzanamiErrors]
                       case None    => create(key, data)
                     }
          } yield result
          result.either.map { ImportResult.fromResult }
    }

    ZIO.access[Ctx] { ctx =>
      { in: Stream[Task, (String, JsValue)] =>
        in.map { case (s, json) => (s, json.validate[Data]) }
          .mapAsync(4) {
            case (_, JsSuccess(obj, _)) =>
              val value: RIO[Ctx, ImportResult] = mutation(key(obj), obj)
              ZIO.provide(ctx)(value)
            case (s, JsError(_)) => Task(ImportResult.error("json.parse.error", s))
          }
          .fold(ImportResult()) { _ |+| _ }
      }
    }
  }

  def importDataFlow[Ctx, Key, Data](
      strategy: ImportStrategy,
      key: Data => Key,
      get: Key => RIO[Ctx, Option[Data]],
      create: (Key, Data) => ZIO[Ctx, IzanamiErrors, Data],
      update: (Key, Data) => ZIO[Ctx, IzanamiErrors, Data]
  )(implicit reads: Reads[Data]): RIO[Ctx, Flow[(String, JsValue), ImportResult, NotUsed]] =
    ZIO.runtime[Any].flatMap { implicit r =>
      importData(strategy, key, get, create, update)
        .map { _.toFlow }
        .map { Flow.fromGraph }
    }

  def importHttp[Ctx](strStrategy: String,
                      body: Source[(String, JsValue), _],
                      importFunction: ImportStrategy => RIO[Ctx, Flow[(String, JsValue), ImportResult, NotUsed]])(
      implicit m: Materializer
  ): RIO[Ctx, play.api.mvc.Result] = {
    import play.api.libs.json._
    ImportStrategy
      .parse(strStrategy)
      .fold(
        { err =>
          ZIO(IzanamiErrors.toHttpResult(err))
        }, { strategy =>
          importFunction(strategy)
            .flatMap { flow =>
              ZIO.fromFuture(
                implicit ec =>
                  body
                    .via(flow)
                    .map {
                      case r if r.isError => Results.BadRequest(Json.toJson(r))
                      case r              => Results.Ok(Json.toJson(r))
                    }
                    .recover {
                      case e: Throwable =>
                        IzanamiLogger.error("Error importing file", e)
                        Results.InternalServerError
                    }
                    .runWith(scaladsl.Sink.head)
              )
            }
        }
      )
  }

}
