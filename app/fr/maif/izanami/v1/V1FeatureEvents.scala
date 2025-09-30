package fr.maif.izanami.v1

import fr.maif.izanami.models.AbstractFeature
import fr.maif.izanami.security.IdGenerator
import fr.maif.izanami.v1.V1FeatureEvents.{baseJson, gen}
import play.api.libs.json.{JsNull, JsObject, JsValue, Json}

import java.time.{Instant, LocalDateTime}

object V2FeatureEvents {
  def initialEvent(json: JsValue): JsObject = {
    Json.obj(
      "_id"       -> V1FeatureEvents.gen.nextId(),
      "timestamp" -> Instant.now(),
      "payload"  -> json,
      "type"      -> "FEATURE_STATES"
    )
  }

  def errorEvent(message: String): JsObject = {
    Json.obj(
      "_id"       -> V1FeatureEvents.gen.nextId(),
      "timestamp" -> Instant.now(),
      "payload"     -> message,
      "type"      -> "ERROR"
    )
  }

  def updateEventV2(feature: JsObject, user: String): JsObject = {
    baseJson(feature, user) ++ Json.obj(
      "type" -> "FEATURE_UPDATED",
    )
  }

  private def baseJson(feature: JsObject, user: String): JsObject = {
    Json.obj(
      "_id"       -> V1FeatureEvents.gen.nextId(),
      "timestamp" -> Instant.now(),
      "payload"   -> feature,
      "metadata" -> Json.obj("user" -> user)
    )
  }

  def createEventV2(feature: JsObject, user: String): JsObject = {
    baseJson(feature, user) ++ Json.obj(
      "type" -> "FEATURE_CREATED"
    )
  }

  def keepAliveEventV2(): JsObject = {
    Json.obj(
      "_id"       -> gen.nextId(),
      "type"      -> "KEEP_ALIVE",
      "timestamp" -> Instant.now()
    )
  }

  def deleteEventV2(id: String, user: String): JsObject = {
    Json.obj(
      "_id"       -> gen.nextId(),
      "timestamp" -> Instant.now(),
      "type"      -> "FEATURE_DELETED",
      "payload"   -> id,
      "metadata" -> Json.obj("user" -> user)
    )
  }
}

object V1FeatureEvents {
  val gen: IdGenerator = IdGenerator(1024)

  def updateEvent(id: String, feature: JsObject): JsObject = {
    baseJson(id, feature) ++ Json.obj(
      "type"     -> "FEATURE_UPDATED",
      "oldValue" -> feature
    )
  }

  private def baseJson(id: String, feature: JsObject): JsObject = {
    Json.obj(
      "_id"       -> gen.nextId(),
      "domain"    -> "Feature",
      "timestamp" -> LocalDateTime.now(),
      "payload"   -> feature
    )
  }

  def createEvent(id: String, feature: JsObject): JsObject = {
    baseJson(id, feature) ++ Json.obj(
      "type" -> "FEATURE_CREATED"
    )
  }

  def keepAliveEvent(): JsObject = {
    Json.obj(
      "_id"       -> gen.nextId(),
      "type"      -> "KEEP_ALIVE",
      "key"       -> "na",
      "domain"    -> "Unknown",
      "payload"   -> Json.obj(),
      "authInfo"  -> JsNull,
      "timestamp" -> LocalDateTime.now()
    )
  }

  def deleteEvent(id: String): JsObject = {
    Json.obj(
      "_id"       -> gen.nextId(),
      "domain"    -> "Feature",
      "timestamp" -> LocalDateTime.now(),
      "key"       -> id,
      "type"      -> "FEATURE_DELETED",
      "payload"   -> Json.obj("id" -> id, "enabled" -> false)
    )
  }

  private def writeFeatureForEvent(feature: AbstractFeature): JsObject = {
    Json.obj(
      "enabled"            -> feature.enabled,
      "id"                 -> feature.id,
      "parameters"         -> Json.obj(),
      "activationStrategy" -> "NO_STRATEGY"
    )
  }
}
