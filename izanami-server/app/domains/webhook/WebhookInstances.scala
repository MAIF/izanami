package domains.webhook
import java.time.LocalDateTime

import domains.webhook.Webhook.WebhookKey
import domains.{AuthInfo, Domain, IsAllowed, Key}

object WebhookInstances {

  import play.api.libs.json._
  import play.api.libs.functional.syntax._

  private val reads: Reads[Webhook] = (
    (__ \ "clientId").read[WebhookKey] and
    (__ \ "callbackUrl").read[String] and
    (__ \ "domains").read[Seq[Domain.Domain]].orElse(Reads.pure(Seq.empty[Domain.Domain])) and
    (__ \ "patterns").read[Seq[String]].orElse(Reads.pure(Seq.empty[String])) and
    (__ \ "types").read[Seq[String]].orElse(Reads.pure(Seq.empty[String])) and
    (__ \ "headers").read[JsObject].orElse(Reads.pure(Json.obj())) and
    (__ \ "created").read[LocalDateTime].orElse(Reads.pure(LocalDateTime.now())) and
    (__ \ "isBanned").read[Boolean].orElse(Reads.pure(false))
  )(Webhook.apply _)

  private val writes = Json.writes[Webhook]

  implicit val format = Format(reads, writes)

}
