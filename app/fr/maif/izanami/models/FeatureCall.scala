package fr.maif.izanami.models

import fr.maif.izanami.models.FeatureCall.FeatureCallOrigin
import fr.maif.izanami.web.FeatureContextPath
import play.api.libs.json.JsValue

import java.time.Instant

case class FeatureCall (
    tenant: String,
    feature: String,
    result: JsValue,
    context: FeatureContextPath,
    time: Instant,
    origin: FeatureCallOrigin,
    key: String
 )


object FeatureCall {
  sealed trait FeatureCallOrigin
  case object Sse extends FeatureCallOrigin
  case object Http extends FeatureCallOrigin
}
