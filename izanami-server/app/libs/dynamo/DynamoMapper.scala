package libs.dynamo

import com.amazonaws.services.dynamodbv2.model.AttributeValue
import play.api.libs.json._
import scala.jdk.CollectionConverters._

object DynamoMapper {

  def fromJsValue(data: JsValue): AttributeValue = data match {
    case JsNull               => new AttributeValue().withNULL(true)
    case JsString(value)      => new AttributeValue().withS(value)
    case JsNumber(value)      => new AttributeValue().withN(value.toString)
    case JsBoolean(value)     => new AttributeValue().withBOOL(value)
    case JsArray(values)      => new AttributeValue().withL(values.toSeq.map(fromJsValue): _*)
    case JsObject(attributes) => new AttributeValue().withM(attributes.view.mapValues(fromJsValue).toMap.asJava)
  }

  def toJsValue(map: java.util.Map[String, AttributeValue]): JsValue =
    map.asScala.foldLeft(Json.obj()) { case (obj, (k, v)) => obj.+(k, toJsValue(v)) }

  def toJsValue(av: AttributeValue): JsValue =
    if (av.getBOOL != null) JsBoolean(av.getBOOL.booleanValue())
    else if (av.getN != null) JsNumber(BigDecimal(av.getN))
    else if (av.getS != null) JsString(av.getS)
    else if (av.getL != null) JsArray(av.getL.asScala.map(toJsValue))
    else if (av.getM != null) toJsValue(av.getM)
    else if (av.getNS != null) JsArray(av.getNS.asScala.map(n => JsNumber(BigDecimal(n))))
    else if (av.getSS != null) JsArray(av.getSS.asScala.map(s => JsString(s)))
    else if (av.getNULL.booleanValue()) JsNull
    else JsNull

}
