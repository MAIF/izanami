package domains.webhook
import java.time.LocalDateTime

import domains.Domain.Domain
import domains.{AuthInfo, Domain, IsAllowed, Key}
import play.api.libs.json.{Format, Json, Reads}
import playjson.rules.{jsonRead, orElse}

object WebhookInstances {

  import Domain._
  import play.api.libs.json._
  import playjson.rules._
  import shapeless.syntax.singleton._

  implicit val isAllowed: IsAllowed[Webhook] = new IsAllowed[Webhook] {
    override def isAllowed(value: Webhook)(auth: Option[AuthInfo]): Boolean = Key.isAllowed(value.clientId)(auth)
  }

  private val reads: Reads[Webhook] = jsonRead[Webhook].withRules(
    'domains ->> orElse(Seq.empty[Domain]) and
    'patterns ->> orElse(Seq.empty[String]) and
    'types ->> orElse(Seq.empty[String]) and
    'headers ->> orElse(Json.obj()) and
    'created ->> orElse(LocalDateTime.now()) and
    'isBanned ->> orElse(false)
  )

  private val writes = Json.writes[Webhook]

  implicit val format = Format(reads, writes)

}
