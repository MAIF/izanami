package fr.maif.izanami.models

import fr.maif.izanami.models.PersonnalAccessToken.TokenRights
import play.api.libs.json.JsError
import play.api.libs.json.JsFalse
import play.api.libs.json.JsObject
import play.api.libs.json.JsSuccess
import play.api.libs.json.JsTrue
import play.api.libs.json.Json
import play.api.libs.json.Reads
import play.api.libs.json.Writes

import java.time.Instant
import java.time.LocalDateTime
import java.time.ZoneId
import java.util.UUID

sealed trait TenantTokenRights {
  def name: String
}

case object Export extends TenantTokenRights {
  val name = "EXPORT"
}
case object Import extends TenantTokenRights {
  val name = "IMPORT"
}

sealed trait PersonnalAccessTokenRights
case object AllRights                         extends PersonnalAccessTokenRights
case class LimitedRights(rights: TokenRights) extends PersonnalAccessTokenRights

sealed trait PersonnalAccessTokenExpiration
case object NoExpiration                                                    extends PersonnalAccessTokenExpiration
case class Expiration(expiresAt: LocalDateTime, expirationTimezone: ZoneId) extends PersonnalAccessTokenExpiration

case class PersonnalAccessTokenCreationRequest(
    name: String,
    username: String,
    rights: PersonnalAccessTokenRights,
    expiration: PersonnalAccessTokenExpiration
)

case class ReadPersonnalAccessToken(id: UUID, createdAt: Instant, underlying: PersonnalAccessTokenCreationRequest) {
  def rights: PersonnalAccessTokenRights         = underlying.rights
  def expiration: PersonnalAccessTokenExpiration = underlying.expiration
  def name: String                               = underlying.name
  def username: String                           = underlying.username

}
case class CompletePersonnalAccessToken(token: String, underlying: ReadPersonnalAccessToken)                       {
  def rights: PersonnalAccessTokenRights         = underlying.rights
  def expiration: PersonnalAccessTokenExpiration = underlying.expiration
  def name: String                               = underlying.name
  def username: String                           = underlying.username
  def id: UUID                                   = underlying.id
  def createdAt: Instant                         = underlying.createdAt
}

object PersonnalAccessToken {
  type TokenRights = Map[String, Set[TenantTokenRights]]

  def parseRight(rawRight: String): TenantTokenRights = {
    rawRight.toUpperCase match {
      case "EXPORT" => Export
      case "IMPORT" => Import
    }
  }

  def personnalAccessTokenExpirationReads: Reads[PersonnalAccessTokenExpiration] = json => {
    (for (
      expiration <- (json \ "expiresAt").asOpt[LocalDateTime];
      timezone   <- (json \ "expirationTimezone").asOpt[String]
    ) yield {
      JsSuccess(Expiration(expiresAt = expiration, expirationTimezone = ZoneId.of(timezone)))
    }).getOrElse {
      if ((json \ "expiresAt").isDefined || (json \ "expirationTimezone").isDefined) {
        JsError("Bad body format")
      } else {
        JsSuccess(NoExpiration)
      }
    }
  }

  def personnalAccessTokenExpirationWrites: Writes[PersonnalAccessTokenExpiration] = {
    case NoExpiration                              => Json.obj()
    case Expiration(expiresAt, expirationTimezone) =>
      Json.obj(
        "expiresAt"          -> expiresAt,
        "expirationTimezone" -> expirationTimezone.getId
      )
  }

  def personnalAccessTokenTenantRightsReads: Reads[TenantTokenRights] = json => {
    val res = json.asOpt[String].map(_.toUpperCase) match {
      case Some("EXPORT") => JsSuccess(Export)
      case Some("IMPORT") => JsSuccess(Import)
      case _              => JsError("Bad body format")
    }

    res
  }

  def personnalAccessTokenTenantRightsWrites: Writes[TenantTokenRights] = {
    case Import => Json.toJson("IMPORT")
    case Export => Json.toJson("EXPORT")
  }

  def personnalAccessTokenRightsReads: Reads[PersonnalAccessTokenRights] = json => {
    (json \ "allRights")
      .asOpt[Boolean]
      .flatMap {
        case true  => Some(JsSuccess(AllRights))
        case false =>
          (json \ "rights")
            .asOpt[TokenRights](Reads.map(Reads.set(personnalAccessTokenTenantRightsReads)))
            .map(rights => JsSuccess(LimitedRights(rights)))
      }
      .getOrElse(JsError("Bad body format"))
  }

  def personnalAccessTokenRightWrites: Writes[PersonnalAccessTokenRights] = {
    case AllRights        => Json.obj("allRights" -> JsTrue)
    case LimitedRights(r) =>
      Json.obj(
        "allRights" -> JsFalse,
        "rights"    -> Json.toJson(r)(Writes.map(Writes.set(personnalAccessTokenTenantRightsWrites)))
      )
  }

  def personnalAccessTokenCreationRequestRead(user: String): Reads[PersonnalAccessTokenCreationRequest] =
    json => {
      (for (
        name       <- (json \ "name").asOpt[String];
        rights     <- json.asOpt[PersonnalAccessTokenRights](personnalAccessTokenRightsReads);
        expiration <- json.asOpt[PersonnalAccessTokenExpiration](personnalAccessTokenExpirationReads)
      ) yield {
        JsSuccess(
          PersonnalAccessTokenCreationRequest(name = name, username = user, rights = rights, expiration = expiration)
        )
      }).getOrElse(JsError("Bad body format"))
    }

  def personnalAccessTokenCreationRequestWrites: Writes[PersonnalAccessTokenCreationRequest] = token => {
    Json.obj(
      "name"     -> token.name,
      "username" -> token.username
    ) ++ Json.toJson(token.rights)(personnalAccessTokenRightWrites).as[JsObject] ++ Json
      .toJson(token.expiration)(personnalAccessTokenExpirationWrites)
      .as[JsObject]
  }

  def consultationTokenWrites: Writes[ReadPersonnalAccessToken] = {
    case ReadPersonnalAccessToken(id, createdAt, underlying) =>
      Json.obj(
        "id"        -> id,
        "createdAt" -> createdAt
      ) ++ Json.toJson(underlying)(personnalAccessTokenCreationRequestWrites).as[JsObject]
  }

  def completePersonnalAccessTokenWrites: Writes[CompletePersonnalAccessToken] = {
    case CompletePersonnalAccessToken(token, underlying) =>
      Json.obj(
        "token" -> token
      ) ++ Json.toJson(underlying)(consultationTokenWrites).as[JsObject]
  }
}
