package fr.maif.izanami.v1

import fr.maif.izanami.models.AbstractFeature
import fr.maif.izanami.security.IdGenerator
import fr.maif.izanami.v1.V1FeatureEvents.{baseJson, gen}
import play.api.libs.json.{JsNull, JsObject, Json}

import java.time.LocalDateTime

object V2FeatureEvents {
  private def baseJson(feature: JsObject): JsObject = {
    Json.obj(
      "_id"       -> V1FeatureEvents.gen.nextId(),
      "timestamp" -> LocalDateTime.now(),
      "feature"   -> feature
    )
  }

  def updateEventV2(feature: JsObject): JsObject = {
    baseJson(feature) ++ Json.obj(
      "type" -> "FEATURE_UPDATED"
    )
  }

  def createEventV2(feature: JsObject): JsObject = {
    baseJson(feature) ++ Json.obj(
      "type" -> "FEATURE_CREATED"
    )
  }

  def keepAliveEventV2(): JsObject = {
    Json.obj(
      "_id"       -> gen.nextId(),
      "type"      -> "KEEP_ALIVE",
      "payload"   -> Json.obj(),
      "timestamp" -> LocalDateTime.now()
    )
  }

  def deleteEventV2(id: String): JsObject = {
    Json.obj(
      "_id"       -> gen.nextId(),
      "timestamp" -> LocalDateTime.now(),
      "type"      -> "FEATURE_DELETED",
      "feature"   -> Json.obj("id" -> id, "enabled" -> false)
    )
  }
}

object V1FeatureEvents {
  val gen = IdGenerator(1024)
  private def baseJson(id: String, feature: JsObject): JsObject = {
    Json.obj(
      "_id"       -> gen.nextId(),
      "domain"    -> "Feature",
      "timestamp" -> LocalDateTime.now(),
      "payload"   -> feature
    )
  }

  def updateEvent(id: String, feature: JsObject): JsObject = {
    baseJson(id, feature) ++ Json.obj(
      "type"     -> "FEATURE_UPDATED",
      "oldValue" -> feature
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
