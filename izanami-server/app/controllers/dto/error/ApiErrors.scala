package controllers.dto.error

import cats.kernel.Monoid
import play.api.libs.json.{JsValue, Json}
import play.api.mvc.{Result, Results}
import domains.errors.{
  DataShouldExists,
  DataShouldNotExists,
  ErrorMessage,
  IdMustBeTheSame,
  InvalidCopyKey,
  IzanamiError,
  IzanamiErrors,
  Unauthorized,
  ValidationError
}

case class ApiError(message: String, args: List[String])

object ApiError {
  implicit val format = Json.format[ApiError]
}

case class ApiErrors(errors: List[ApiError], fieldErrors: Map[String, List[ApiError]]) {
  def toJson: JsValue = Json.toJson(this)(ApiErrors.format)
}

object ApiErrors {
  implicit val format = Json.format[ApiErrors]

  import cats.implicits._

  implicit val monoid: Monoid[ApiErrors] = new Monoid[ApiErrors] {
    override def empty = ApiErrors(List.empty, Map.empty)
    override def combine(x: ApiErrors, y: ApiErrors): ApiErrors = {
      val errors      = x.errors ++ y.errors
      val fieldErrors = (x.fieldErrors, y.fieldErrors).combineAll
      ApiErrors(errors, fieldErrors)
    }
  }

  def fromErrors(errors: List[IzanamiError]): ApiErrors =
    errors.foldMap {
      case ValidationError(errors, fieldErrors) =>
        ApiErrors(
          errors.toList.map { case e: ErrorMessage => ApiError(e.message, e.args.toList) },
          fieldErrors.view
            .mapValues(_.map { case e: ErrorMessage => ApiError(e.message, e.args.toList) })
            .toMap
        )
      case InvalidCopyKey(id) => error("error.id.copy.invalid", id.key)
      case IdMustBeTheSame(fromObject, inParam) =>
        error("error.id.not.the.same", fromObject.key, inParam.key)
      case DataShouldExists(id)    => error("error.data.missing", id.key)
      case DataShouldNotExists(id) => error("error.data.exists", id.key)
      case Unauthorized(id)        => error("error.data.unauthorized", id.key)
    }

  def toHttpResult(errors: IzanamiErrors): Result =
    errors.toList match {
      case Unauthorized(id) :: _ => Results.Unauthorized(Json.toJson(error("error.data.unauthorized", id.key)))
      case _                     => Results.BadRequest(Json.toJson(fromErrors(errors.toList)))
    }

  def error(message: String, args: String*): ApiErrors =
    ApiErrors(List(ApiError(message, args.toList)), Map.empty)

}
