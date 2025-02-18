package fr.maif.izanami.models

import fr.maif.izanami.models.FeatureCall.FeatureCallOrigin
import fr.maif.izanami.models.FeatureCallAggregator.FeatureCallRange
import fr.maif.izanami.web.FeatureContextPath
import play.api.libs.json.JsValue

import java.time.{Instant, ZoneId, ZoneOffset}
import java.util.concurrent.atomic.AtomicLong
import scala.collection.concurrent.TrieMap

case class FeatureCallAggregator (calls: TrieMap[FeatureCallRange, AtomicLong] = TrieMap()) {
  def addCall(call: FeatureCall) = {
    val key = FeatureCallRange.fromCall(call)
    calls.getOrElseUpdate(key, new AtomicLong()).incrementAndGet();
    this
  }

  def clearCalls(): Unit = {
    calls.clear()
  }
}


case object FeatureCallAggregator {
  case class FeatureCallRange(tenant: String,
                              feature: String,
                              key: String,
                              result: JsValue,
                              context: FeatureContextPath,
                              rangeStart: Instant,
                              origin: FeatureCallOrigin) {}

  case object FeatureCallRange {
    def fromCall(call: FeatureCall): FeatureCallRange = {
      val rangeStart = call.time.atZone(ZoneId.of("UTC")).withNano(0).withMinute(0).withSecond(0).toInstant;
      FeatureCallRange(
        tenant = call.tenant,
        key=call.key,
        feature = call.feature,
        result = call.result,
        context = call.context,
        rangeStart = rangeStart,
        origin = call.origin
      )
    }
  }
}
