package fr.maif.izanami.models.features

import play.api.libs.json.JsArray
import play.api.libs.json.JsBoolean
import play.api.libs.json.JsError
import play.api.libs.json.JsNumber
import play.api.libs.json.JsString
import play.api.libs.json.JsSuccess
import play.api.libs.json.JsValue
import play.api.libs.json.Reads
import play.api.libs.json.Writes


/**
 * Represents result type of flag evaluation.
 * Boolean flags are well known, however flags can also returns multiple other values, such as String or Numbers.
 */
sealed trait ResultType {
  def toDatabaseName: String
  def parse(value: JsValue): Option[JsValue]
}

sealed trait ValuedResultType extends ResultType {
  def toJson(v: String): JsValue
}

case object StringResult  extends ValuedResultType {
  override def toJson(v: String): JsValue             = JsString(v)
  override def toDatabaseName: String                 = "string"
  override def parse(value: JsValue): Option[JsValue] = value.asOpt[JsString]
}
case object NumberResult  extends ValuedResultType {
  override def toJson(v: String): JsValue             = JsNumber(BigDecimal(v))
  override def toDatabaseName: String                 = "number"
  override def parse(value: JsValue): Option[JsValue] = value.asOpt[JsNumber]
}
case object BooleanResult extends ResultType       {
  override def toDatabaseName: String                 = "boolean"
  override def parse(value: JsValue): Option[JsValue] = value.asOpt[JsBoolean]
}

object ResultType {
  def parseResultType(valueStr: String): Option[ResultType] = valueStr match {
    case "boolean" => Some(BooleanResult)
    case "number"  => Some(NumberResult)
    case "string"  => Some(StringResult)
    case _         => None
  }

  def resultTypeReads: Reads[ResultType] = Reads[ResultType] { json =>
    json
      .asOpt[String]
      .flatMap(str => parseResultType(str))
      .map(rt => JsSuccess(rt))
      .getOrElse(JsError(s"Unknown result type $json"))
  }

  def resultTypeWrites: Writes[ResultType] = Writes[ResultType] {
    case StringResult  => JsString("string")
    case NumberResult  => JsString("number")
    case BooleanResult => JsString("boolean")
  }
}

sealed trait ResultDescriptor {
  def resultType: ResultType
  def conditions: Seq[ActivationCondition]
}

sealed trait ValuedResultDescriptor extends ResultDescriptor {
  override def resultType: ValuedResultType
  def jsonValue: JsValue
  def stringValue: String
  def conditions: Seq[ValuedActivationCondition]
}

object ValuedResultDescriptor {
  def valuedDescriptorReads: Reads[ValuedResultDescriptor] = Reads[ValuedResultDescriptor] { json =>
    {
      (json \ "resultType")
        .asOpt[ResultType](ResultType.resultTypeReads)
        .orElse((json \ "result_type").asOpt[ResultType](ResultType.resultTypeReads))
        .map {
          case StringResult  => {
            (for (value <- (json \ "value").asOpt[String])
              yield {
                val jsonConditions = (json \ "conditions")
                  .asOpt[JsArray]
                  .getOrElse(JsArray())
                val conditions     = jsonConditions
                  .asOpt[Seq[StringActivationCondition]](Reads.seq(ActivationCondition.stringActivationConditionRead))
                  .getOrElse(Seq())
                if (jsonConditions.value.length > conditions.length) {
                  JsError("Failed to parse conditions")
                } else {
                  JsSuccess(
                    StringResultDescriptor(
                      value = value,
                      conditions = conditions
                    )
                  )
                }
              }).getOrElse(JsError("Failed to read StringResultDescriptor"))
          }
          case NumberResult  => {
            (for (value <- (json \ "value").asOpt[BigDecimal])
              yield {
                val jsonConditions = (json \ "conditions")
                  .asOpt[JsArray]
                  .getOrElse(JsArray())
                val conditions     = jsonConditions
                  .asOpt[Seq[NumberActivationCondition]](Reads.seq(ActivationCondition.numberActivationConditionRead))
                  .getOrElse(Seq())
                if (jsonConditions.value.length > conditions.length) {
                  JsError("Failed to parse conditions")
                } else {
                  JsSuccess(
                    NumberResultDescriptor(
                      value = value,
                      conditions = conditions
                    )
                  )
                }
              }).getOrElse(JsError("Failed to read NumberResultDescriptor"))
          }
          case BooleanResult => JsError("Readed boolean result type, expected valued result type")
        }
        .getOrElse(JsError("Incorrect/missing result type"))
    }
  }
}

case class BooleanResultDescriptor(conditions: Seq[BooleanActivationCondition]) extends ResultDescriptor {
  override def resultType: ResultType = BooleanResult
}

case class NumberResultDescriptor(
    value: BigDecimal,
    conditions: Seq[NumberActivationCondition]
) extends ValuedResultDescriptor {
  override def resultType: ValuedResultType = NumberResult
  override def jsonValue: JsValue           = JsNumber(value)
  override def stringValue: String          = value.toString()
}

case class StringResultDescriptor(
    value: String,
    conditions: Seq[StringActivationCondition]
) extends ValuedResultDescriptor {
  override def resultType: ValuedResultType = StringResult
  override def jsonValue: JsValue           = JsString(value)
  override def stringValue: String          = value
}
