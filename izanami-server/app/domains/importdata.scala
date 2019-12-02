package domains

import akka.NotUsed
import akka.stream.{scaladsl, Materializer}
import akka.stream.scaladsl.{Flow, Source}
import libs.logs.{IzanamiLogger, Logger, LoggerModule}
import play.api.libs.json.{JsError, JsSuccess, JsValue, Reads}
import play.api.mvc.Results
import store.Result.{IzanamiErrors, ValidationError}

sealed trait ImportStrategy extends Product with Serializable
object ImportStrategy {

  import IzanamiErrors._

  case object Replace extends ImportStrategy
  case object Keep    extends ImportStrategy

  def parse(str: String): Either[IzanamiErrors, ImportStrategy] =
    str match {
      case "Replace" => Right(Replace)
      case "Keep"    => Right(Keep)
      case _         => Left(ValidationError.error("error.strategy.expected", "Replace", "Keep").toErrors)
    }
}

object ImportData {

  import fs2._
  import streamz.converter._
  import zio._
  import zio.interop.catz._

  def importData[Ctx <: LoggerModule, Key, Data](
      strategy: ImportStrategy,
      key: Data => Key,
      get: Key => RIO[Ctx, Option[Data]],
      create: (Key, Data) => ZIO[Ctx, IzanamiErrors, Data],
      update: (Key, Data) => ZIO[Ctx, IzanamiErrors, Data]
  )(implicit reads: Reads[Data]): RIO[Ctx, Pipe[Task, (String, JsValue), ImportResult]] = {

    val mutation: (Key, Data) => RIO[Ctx, ImportResult] = strategy match {

      case ImportStrategy.Replace =>
        (key, data) =>
          for {
            _         <- Logger.debug(s"Replacing $key with $data")
            mayBeData <- get(key)
            result <- mayBeData match {
                       case Some(_) => update(key, data).either.map { ImportResult.fromResult }
                       case None    => create(key, data).either.map { ImportResult.fromResult }
                     }
          } yield result

      case ImportStrategy.Keep =>
        (key, data) =>
          for {
            _         <- Logger.debug(s"Inserting $key with $data")
            mayBeData <- get(key)
            result <- mayBeData match {
                       case Some(_) => ZIO(ImportResult())
                       case None    => create(key, data).either.map { ImportResult.fromResult }
                     }
          } yield result
    }

    ZIO.access[Ctx] { ctx =>
      { in: Stream[Task, (String, JsValue)] =>
        in.map { case (s, json) => (s, json.validate[Data]) }
          .evalMap[Task, ImportResult] {
            case (_, JsSuccess(obj, _)) => ZIO.provide(ctx)(mutation(key(obj), obj))
            case (s, JsError(_))        => Task(ImportResult.error("json.parse.error", s))
          }
          .foldMonoid
      }
    }
  }

  def importDataFlow[Ctx <: LoggerModule, Key, Data](
      strategy: ImportStrategy,
      key: Data => Key,
      get: Key => RIO[Ctx, Option[Data]],
      create: (Key, Data) => ZIO[Ctx, IzanamiErrors, Data],
      update: (Key, Data) => ZIO[Ctx, IzanamiErrors, Data]
  )(implicit reads: Reads[Data]): RIO[Ctx, Flow[(String, JsValue), ImportResult, NotUsed]] =
    ZIO.runtime[Ctx].flatMap { implicit r =>
      importData(strategy, key, get, create, update)
        .map { _.toFlow }
        .map { Flow.fromGraph }
    }

  def importHttp[Ctx <: LoggerModule](
      strStrategy: String,
      body: Source[(String, JsValue), _],
      importFunction: ImportStrategy => RIO[Ctx, Flow[(String, JsValue), ImportResult, NotUsed]]
  )(
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
                _ =>
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
