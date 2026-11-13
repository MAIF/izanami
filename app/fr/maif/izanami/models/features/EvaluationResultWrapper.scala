package fr.maif.izanami.models.features

import play.api.libs.json.{JsBoolean, JsNull, JsNumber, JsString, Writes}

sealed trait EvaluationResultWrapper
case class StringResultWrapper(value: String) extends EvaluationResultWrapper
case class NumberResultWrapper(value: BigDecimal) extends EvaluationResultWrapper
case class BooleanResultWrapper(value: Boolean) extends EvaluationResultWrapper
case object NullResultWrapper extends EvaluationResultWrapper

object EvaluationResultWrapper {
  def writes: Writes[EvaluationResultWrapper] = {
    case StringResultWrapper(value) => JsString(value)
    case NumberResultWrapper(value) => JsNumber(value)
    case BooleanResultWrapper(value) => JsBoolean(value)
    case NullResultWrapper => JsNull
  }
}
