package fr.maif.izanami.v1

import fr.maif.izanami.models.AbstractFeature
import fr.maif.izanami.security.IdGenerator
import play.api.libs.json.{JsNull, JsObject, Json}

import java.time.LocalDateTime


object V1FeatureEvents {
  private val gen = IdGenerator(1024)
  private def baseJson(id: String, feature: JsObject): JsObject = {
    Json.obj(
      "_id" -> gen.nextId(),
      "domain" -> "Feature",
      "timestamp" -> LocalDateTime.now(),
      "key" -> id,
      "payload" -> feature
    )
  }

  def updateEvent(id: String, feature: JsObject): JsObject = {
    baseJson(id, feature) ++ Json.obj(
      "type" -> "FEATURE_UPDATED",
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
      "_id" -> gen.nextId(),
      "type" -> "KEEP_ALIVE",
      "key" -> "na",
      "domain" ->  "Unknown",
      "payload" -> Json.obj(),
      "authInfo" -> JsNull,
      "timestamp" -> LocalDateTime.now()
    )
  }

  def deleteEvent(id: String): JsObject = {
    Json.obj(
      "_id" -> gen.nextId(),
      "domain" -> "Feature",
      "timestamp" -> LocalDateTime.now(),
      "key" -> id,
      "type" -> "FEATURE_DELETED",
      "payload" -> Json.obj("id" -> id, "enabled" -> false)
    )
  }

  private def writeFeatureForEvent(feature: AbstractFeature): JsObject = {
    Json.obj(
      "enabled" -> feature.enabled,
      "id" -> feature.id,
      "parameters" -> Json.obj(),
      "activationStrategy" -> "NO_STRATEGY"
    )
  }
}
