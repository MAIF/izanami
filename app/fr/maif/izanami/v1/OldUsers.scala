package fr.maif.izanami.v1

import fr.maif.izanami.models._
import fr.maif.izanami.utils.syntax.implicits.BetterSyntax
import fr.maif.izanami.v1.OldCommons.authorizedPatternReads
import fr.maif.izanami.v1.OldCommons.toNewRights

trait OldUser {
  def email: String
  def admin: Boolean
  def authorizedPatterns: Seq[AuthorizedPattern]

}

case class OldIzanamiUser(
    id: String,
    name: String,
    email: String,
    password: Option[String],
    admin: Boolean,
    authorizedPatterns: Seq[AuthorizedPattern]
) extends OldUser

case class OldOauthUser(
    id: String,
    name: String,
    email: String,
    admin: Boolean,
    authorizedPatterns: Seq[AuthorizedPattern]
) extends OldUser {}

case class OldOtoroshiUser(
    id: String,
    name: String,
    email: String,
    admin: Boolean,
    authorizedPatterns: Seq[AuthorizedPattern]
) extends OldUser {}

object OldUsers {
  import play.api.libs.json._

  def toNewUser(tenant: String, user: OldUser, projects: Set[String], filterProject: Boolean): Either[String, UserWithRights] = {
    user match {
      case OldIzanamiUser(id, name, email, password, admin, authorizedPatterns) => UserWithRights(username=id, userType = INTERNAL,email=email, password=password.orNull, admin=admin, rights = Rights(tenants=(Map(tenant -> toNewRights(admin, authorizedPatterns, projects, filterProject)))), legacy=true).right
      case OldOauthUser(id, name, email, admin, authorizedPatterns) => UserWithRights(username=id, userType = OIDC,email=email, password=null, admin=admin, rights = Rights(tenants=(Map(tenant -> toNewRights(admin, authorizedPatterns, projects, filterProject)))), legacy=true).right
      case OldOtoroshiUser(id, name, email, admin, authorizedPatterns) => UserWithRights(username=id, userType = OTOROSHI,email=email, password=null, admin=admin, rights = Rights(tenants=(Map(tenant -> toNewRights(admin, authorizedPatterns, projects, filterProject)))), legacy=true).right
      case _ => Left(s"Incorrect user type for ${user}")
    }
  }


  // TODO handle pattern
  val oldUserReads: Reads[OldUser] = {
    import play.api.libs.functional.syntax._
    import play.api.libs.json._
    val commonReads =
      (__ \ "id").read[String] and
      (__ \ "name").read[String] and
      (__ \ "email").read[String] and
      (__ \ "admin").read[Boolean] and
      (__ \ "authorizedPatterns").read[Seq[AuthorizedPattern]].orElse((__ \ "authorizedPattern").read[Seq[AuthorizedPattern]])

    val readOtoroshiUser = commonReads(OldOtoroshiUser.apply _)
    val readOauthUser    = commonReads(OldOauthUser.apply _)

    val readIzanamiUser = (
      (__ \ "id").read[String] and
        (__ \ "name").read[String] and
        (__ \ "email").read[String] and
        (__ \ "password").readNullable[String] and
        (__ \ "admin").read[Boolean] and
        (__ \ "authorizedPatterns").read[Seq[AuthorizedPattern]].orElse((__ \ "authorizedPattern").read[Seq[AuthorizedPattern]])
    )(OldIzanamiUser.apply _)

    (__ \ "type").readNullable[String].flatMap {
      case Some(UserType.Otoroshi) => readOtoroshiUser.asInstanceOf[Reads[OldUser]]
      case Some(UserType.Oauth)    => readOauthUser.asInstanceOf[Reads[OldUser]]
      case Some(UserType.Izanami)  => readIzanamiUser.asInstanceOf[Reads[OldUser]]
      case _                       => readIzanamiUser.asInstanceOf[Reads[OldUser]]
    }
  }
}

object UserType {
  val Otoroshi = "Otoroshi"
  val Oauth    = "OAuth"
  val Izanami  = "Izanami"
}