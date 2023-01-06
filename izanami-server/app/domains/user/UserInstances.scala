package domains.user

import domains.{AuthorizedPatterns, IsAllowed, Key}
import domains.auth.AuthInfo
import play.api.libs.json._

object UserNoPasswordInstances {
  import play.api.libs.json.{Format, Json}

  private val writes = UserInstances.writes.transform { o: JsObject =>
    o - "password"
  }

  implicit val format: Format[User] = Format(UserInstances.reads, writes)
}

object UserType {
  val Otoroshi = "Otoroshi"
  val Oauth    = "OAuth"
  val Izanami  = "Izanami"
}

object UserInstances {
  import play.api.libs.json._
  import play.api.libs.json.Reads.{email, pattern}

  private[user] val reads: Reads[User] = {
    import domains.AuthorizedPatterns._
    import play.api.libs.json._
    import play.api.libs.functional.syntax._
    val commonReads = (
      (__ \ "id").read[String] and
      (__ \ "name").read[String] and
      (__ \ "email").read[String] and
      (__ \ "admin").read[Boolean] and
      (__ \ "authorizedPatterns").read[AuthorizedPatterns].orElse((__ \ "authorizedPattern").read[AuthorizedPatterns])
    )

    val readOtoroshiUser = commonReads(OtoroshiUser.apply _)
    val readOauthUser    = commonReads(OauthUser.apply _)

    val readIzanamiUser = (
      (__ \ "id").read[String] and
      (__ \ "name").read[String] and
      (__ \ "email").read[String] and
      (__ \ "password").readNullable[String] and
      (__ \ "admin").read[Boolean] and
      (__ \ "temporary").read[Boolean].orElse(Reads.pure(false)) and
      (__ \ "authorizedPatterns").read[AuthorizedPatterns].orElse((__ \ "authorizedPattern").read[AuthorizedPatterns])
    )(IzanamiUser.apply _)

    (__ \ "type").readNullable[String].flatMap {
      case Some(UserType.Otoroshi) => readOtoroshiUser.asInstanceOf[Reads[User]]
      case Some(UserType.Oauth)    => readOauthUser.asInstanceOf[Reads[User]]
      case Some(UserType.Izanami)  => readIzanamiUser.asInstanceOf[Reads[User]]
      case _                       => readIzanamiUser.asInstanceOf[Reads[User]]
    }
  }

  private[user] val writes: OWrites[User] = {
    import domains.AuthorizedPatterns._
    val writeOtoroshiUser = Json.writes[OtoroshiUser]
    val writeIzanamiUser  = Json.writes[IzanamiUser]
    val writeOauthUser    = Json.writes[OauthUser]
    OWrites[User] {
      case u: OtoroshiUser => writeOtoroshiUser.writes(u) ++ Json.obj("type" -> UserType.Otoroshi)
      case u: IzanamiUser  => writeIzanamiUser.writes(u) ++ Json.obj("type"  -> UserType.Izanami)
      case u: OauthUser    => writeOauthUser.writes(u) ++ Json.obj("type"    -> UserType.Oauth)
    }
  }

  implicit val format = Format[User](reads, writes)

}
