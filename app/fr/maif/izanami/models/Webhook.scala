package fr.maif.izanami.models

import play.api.libs.json.JsError
import play.api.libs.json.JsString
import play.api.libs.json.JsSuccess
import play.api.libs.json.Json
import play.api.libs.json.Reads
import play.api.libs.json.Writes

import java.net.URI
import java.net.URL
import java.util.UUID
import scala.util.Try

case class LightWebhook(
    name: String,
    description: String,
    url: URL,
    headers: Map[String, String],
    features: Set[String],
    projects: Set[String],
    context: String,
    user: String,
    enabled: Boolean,
    bodyTemplate: Option[String],
    id: Option[UUID] = None,
    global: Boolean
)

case class Webhook(
    id: UUID,
    name: String,
    description: String,
    url: URL,
    headers: Map[String, String],
    features: Set[WebhookFeature],
    projects: Set[WebhookProject],
    context: String,
    user: String,
    enabled: Boolean,
    bodyTemplate: Option[String],
    global: Boolean
)

case class WebhookFeature(id: String, name: String, project: String)
case class WebhookProject(id: String, name: String)

object Webhook {
  val webhookWrite: Writes[Webhook] = o => {
    Json.obj(
      "id"           -> o.id,
      "enabled"      -> o.enabled,
      "name"         -> o.name,
      "url"          -> o.url.toString,
      "description"  -> o.description,
      "headers"      -> o.headers,
      "context"      -> o.context,
      "user"         -> o.user,
      "global"       -> o.global,
      "features"     -> o.features.map(f =>
        Json.obj(
          "name"    -> f.name,
          "project" -> f.project,
          "id"      -> f.id
        )
      ),
      "projects"     -> o.projects.map(p =>
        Json.obj(
          "name" -> p.name,
          "id"   -> p.id
        )
      ),
      "bodyTemplate" -> o.bodyTemplate
    )
  }
}

object LightWebhook {
  val lightWebhookRead: Reads[LightWebhook] = json => {
    (for (
      name        <- (json \ "name").asOpt[String];
      enabled     <- (json \ "enabled").asOpt[Boolean];
      global      <- (json \ "global").asOpt[Boolean];
      urlStr      <- (json \ "url").asOpt[String];
      url         <- Try {
                       new URI(urlStr).toURL
                     }.toOption;
      headers      = (json \ "headers").asOpt[Map[String, String]].getOrElse(Map());
      description  = (json \ "description").asOpt[String].getOrElse("");
      user         = (json \ "user").asOpt[String].getOrElse("");
      context      = (json \ "context").asOpt[String].getOrElse("");
      features     = (json \ "features").asOpt[Set[String]].getOrElse(Set());
      projects     = (json \ "projects").asOpt[Set[String]].getOrElse(Set());
      bodyTemplate = (json \ "bodyTemplate").toOption.flatMap {
                       case JsString(str) => Some(str)
                       case _             => None
                     }
    ) yield {
      val hook = LightWebhook(
        name = name,
        description = description,
        url = url,
        headers = headers,
        features = features,
        context = context,
        user = user,
        projects = projects,
        enabled = enabled,
        bodyTemplate = bodyTemplate,
        global = global
      )
      JsSuccess(hook)
    }) getOrElse (JsError("Bad body format"))
  }
}
