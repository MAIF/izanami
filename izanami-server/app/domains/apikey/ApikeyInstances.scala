package domains.apikey
import domains._

object ApikeyInstances {

  import play.api.libs.functional.syntax._
  import play.api.libs.json._

  import play.api.libs.json.Reads._

  private val reads: Reads[Apikey] = {
    import domains.AuthorizedPatterns._
    (
      (__ \ "clientId").read[String](pattern("^[@0-9\\p{L} .'-]+$".r)) and
      (__ \ "name").read[String](pattern("^[@0-9\\p{L} .'-]+$".r)) and
      (__ \ "clientSecret").read[String](pattern("^[@0-9\\p{L} .'-]+$".r)) and
      (__ \ "authorizedPattern")
        .read[AuthorizedPatterns](AuthorizedPatterns.reads)
        .orElse((__ \ "authorizedPatterns").read[AuthorizedPatterns](AuthorizedPatterns.reads)) and
      (__ \ "admin").read[Boolean].orElse(Reads.pure(false))
    )(Apikey.apply _)
  }

  private val writes = {
    import domains.AuthorizedPatterns._
    Json.writes[Apikey]
  }

  implicit val format = Format[Apikey](reads, writes)

}
