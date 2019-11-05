package domains.user
import domains.{AuthInfo, AuthorizedPattern, IsAllowed, Key}
import play.api.libs.json._

object UserNoPasswordInstances {
  import play.api.libs.json.{Format, Json}

  private val writes = {
    import domains.AuthorizedPattern._
    Json.writes[User].transform { o: JsObject =>
      o - "password"
    }
  }

  implicit val format: Format[User] = Format(UserInstances.reads, writes)
}

object UserInstances {
  import play.api.libs.functional.syntax._
  import play.api.libs.json._
  import play.api.libs.json.Reads.{email, pattern}

  implicit val isAllowed: IsAllowed[User] = new IsAllowed[User] {
    override def isAllowed(value: User)(auth: Option[AuthInfo]): Boolean = Key.isAllowed(value.authorizedPattern)(auth)
  }

  private[user] val reads: Reads[User] = {
    import domains.AuthorizedPattern._
    (
      (__ \ "id").read[String] and
      (__ \ "name").read[String](pattern("^[\\p{L} .'-]+$".r)) and
      (__ \ "email").read[String](email) and
      (__ \ "password").readNullable[String] and
      (__ \ "admin").read[Boolean] and
      (__ \ "authorizedPattern").read[AuthorizedPattern](AuthorizedPattern.reads)
    )(User.apply _)
  }

  private val writes = {
    import domains.AuthorizedPattern._
    Json.writes[User]
  }

  implicit val format = Format[User](reads, writes)

}
