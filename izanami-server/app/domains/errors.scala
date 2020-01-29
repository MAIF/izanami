package domains

import cats.Semigroup
import cats.data.{NonEmptyList, Validated}
import cats.kernel.Monoid
import play.api.libs.json.{JsPath, JsValue, Json, JsonValidationError}

object errors {

  case class ErrorMessage(message: String, args: String*)

  object ErrorMessage {
    implicit val format = Json.format[ErrorMessage]
  }

  type IzanamiErrors = NonEmptyList[IzanamiError]

  object IzanamiErrors {

    def apply(error: IzanamiError, rest: IzanamiError*): IzanamiErrors = NonEmptyList.of[IzanamiError](error, rest: _*)

    def error(message: String) = apply(ValidationError.error(message))

    def fromNel(nel: NonEmptyList[IzanamiError]): IzanamiErrors = nel

    implicit class ToErrorsOps(err: IzanamiError) {
      def toErrors: IzanamiErrors = apply(err)
    }

    implicit def semigroup(implicit SG: Semigroup[NonEmptyList[IzanamiError]]): Semigroup[IzanamiErrors] =
      new Semigroup[IzanamiErrors] {
        override def combine(x: IzanamiErrors, y: IzanamiErrors): IzanamiErrors =
          fromNel(x ++ y.toList)
      }
  }

  sealed trait IzanamiError

  case class Unauthorized(id: Option[Key])                  extends IzanamiError
  case class InvalidCopyKey(id: Key)                        extends IzanamiError
  case class IdMustBeTheSame(fromObject: Key, inParam: Key) extends IzanamiError
  case class DataShouldExists(id: Key)                      extends IzanamiError
  case class DataShouldNotExists(id: Key)                   extends IzanamiError
  case class ValidationError(errors: Seq[ErrorMessage] = Seq.empty,
                             fieldErrors: Map[String, List[ErrorMessage]] = Map.empty)
      extends IzanamiError {
    def ++(s: ValidationError): ValidationError =
      this.copy(errors = errors ++ s.errors, fieldErrors = fieldErrors ++ s.fieldErrors)
    def addFieldError(field: String, errors: List[ErrorMessage]): ValidationError =
      fieldErrors.get(field) match {
        case Some(err) =>
          ValidationError(errors, fieldErrors + (field -> (err ++ errors)))
        case None => ValidationError(errors, fieldErrors + (field -> errors))
      }

    def toJson: JsValue =
      ValidationError.format.writes(this)

    def isEmpty: Boolean = errors.isEmpty && fieldErrors.isEmpty
  }

  object ValidationError {
    import cats.instances.all._
    import cats.syntax.semigroup._

    implicit val format = Json.format[ValidationError]

    def fromJsError(jsError: Seq[(JsPath, Seq[JsonValidationError])]): ValidationError = {
      val fieldErrors = jsError.map {
        case (k, v) =>
          (k.toJsonString, v.map(err => ErrorMessage(err.message, err.args.map(_.toString): _*)).toList)
      }.toMap
      ValidationError(fieldErrors = fieldErrors)
    }

    def error(message: String): ValidationError =
      ValidationError(Seq(ErrorMessage(message)))

    def error(message: String, args: String*): ValidationError =
      ValidationError(Seq(ErrorMessage(message, args: _*)))

    private def optionCombine[A: Semigroup](a: A, opt: Option[A]): A =
      opt.map(a |+| _).getOrElse(a)

    private def mergeMap[K, V: Semigroup](lhs: Map[K, V], rhs: Map[K, V]): Map[K, V] =
      lhs.foldLeft(rhs) {
        case (acc, (k, v)) => acc.updated(k, optionCombine(v, acc.get(k)))
      }

    implicit val monoid: Monoid[ValidationError] = new Monoid[ValidationError] {
      override def empty = ValidationError()
      override def combine(x: ValidationError, y: ValidationError) = {
        val errors      = x.errors ++ y.errors
        val fieldErrors = mergeMap(x.fieldErrors, y.fieldErrors)
        ValidationError(errors, fieldErrors)
      }
    }
  }

  type ValidatedResult[+E] = Validated[ValidationError, E]
  type Result[+E]          = Either[IzanamiErrors, E]
  object Result {
    def ok[E](event: E): Result[E]                = Right(event)
    def error[E](error: IzanamiErrors): Result[E] = Left(error)
    def error[E](error: IzanamiError): Result[E]  = Left(IzanamiErrors(error))
    def error[E](messages: String*): Result[E] =
      Left(IzanamiErrors(ValidationError(messages.map(m => ErrorMessage(m)))))
    def errors[E](errs: ErrorMessage*): Result[E] = Left(IzanamiErrors(ValidationError(errs)))
    def fieldError[E](field: String, errs: ErrorMessage*): Result[E] =
      Left(IzanamiErrors.apply(ValidationError(fieldErrors = Map(field -> errs.toList))))

    implicit class ResultOps[E](r: Result[E]) {
      def collect[E2](p: PartialFunction[E, E2]): Result[E2] =
        r match {
          case Right(elt) if p.isDefinedAt(elt) => ok(p(elt))
          case Right(_)                         => error("error.result.filtered")
          case Left(e)                          => error(e)
        }
    }
  }
}
