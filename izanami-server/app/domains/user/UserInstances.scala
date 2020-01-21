package domains.user

import domains.{AuthInfo, AuthorizedPattern, IsAllowed, Key}
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

  implicit val isAllowed: IsAllowed[User] = new IsAllowed[User] {
    override def isAllowed(value: User)(auth: Option[AuthInfo]): Boolean = Key.isAllowed(value._authorizedPattern)(auth)
  }
  private[user] val reads: Reads[User] = {
    import domains.AuthorizedPattern._
    val readOtoroshiUser = Json.reads[OtoroshiUser]
    val readIzanamiUser  = Json.reads[IzanamiUser]
    val readOauthUser    = Json.reads[OauthUser]
    (__ \ "type").readNullable[String].flatMap {
      case Some(UserType.Otoroshi) => readOtoroshiUser.asInstanceOf[Reads[User]]
      case Some(UserType.Oauth)    => readOauthUser.asInstanceOf[Reads[User]]
      case Some(UserType.Izanami)  => readIzanamiUser.asInstanceOf[Reads[User]]
      case _                       => readIzanamiUser.asInstanceOf[Reads[User]]
    }
  }

  private[user] val writes: OWrites[User] = {
    import domains.AuthorizedPattern._
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
