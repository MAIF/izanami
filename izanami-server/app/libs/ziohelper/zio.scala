package libs.ziohelper
import cats.data.NonEmptyList
import play.api.libs.json.{JsError, JsPath, JsResult, JsSuccess, JsonValidationError}
import domains.errors.{IzanamiErrors, ValidationError}
import zio._
import libs.logs.ZLogger

object JsResults {

  def handleJsError[C <: ZLogger, T](err: Seq[(JsPath, Seq[JsonValidationError])]): ZIO[C, IzanamiErrors, T] =
    ZLogger.error(s"Error parsing json $err") *>
    IO.fail(NonEmptyList.of(ValidationError.error("error.json.parsing")))

  def jsResultToError[C <: ZLogger, T](jsResult: JsResult[T]): ZIO[C, IzanamiErrors, T] =
    fromJsResult(jsResult) { handleJsError }

  def jsResultToHttpResponse[T](jsResult: JsResult[T]) =
    liftJsResult(jsResult)(err => play.api.mvc.Results.BadRequest(ValidationError.fromJsError(err).toJson))

  def liftJsResult[T, E](jsResult: JsResult[T])(onError: Seq[(JsPath, Seq[JsonValidationError])] => E): IO[E, T] =
    jsResult match {
      case JsSuccess(value, _) => IO.succeed(value)
      case JsError(errors)     => IO.fail(onError(errors.toSeq.map(t => t.copy(_2 = t._2.toSeq))))
    }

  def fromJsResult[C <: ZLogger, T, E](
      jsResult: JsResult[T]
  )(onError: Seq[(JsPath, Seq[JsonValidationError])] => ZIO[C, E, T]): ZIO[C, E, T] =
    jsResult match {
      case JsSuccess(value, _) => ZIO.succeed(value)
      case JsError(errors)     => onError(errors.toSeq.map(t => t.copy(_2 = t._2.toSeq)))
    }

}
