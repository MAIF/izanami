package fr.maif.izanami.models

import fr.maif.izanami.models.PersonnalAccessToken.TokenRights
import play.api.libs.json.{
  JsError,
  JsFalse,
  JsObject,
  JsResult,
  JsSuccess,
  JsTrue,
  Json,
  Reads,
  Writes
}

import java.time.{Instant, LocalDate, LocalDateTime, ZoneId}
import java.util.UUID

sealed trait TenantTokenRights {
  def name: String
  def requiredCreationRight: Option[RequiredTenantRights]
}

case object Export extends TenantTokenRights {
  val name = "EXPORT"
  override def requiredCreationRight: Option[RequiredTenantRights] = Some(RequiredTenantRights(level = RightLevel.Admin))
}
case object Import extends TenantTokenRights {
  val name = "IMPORT"
  override def requiredCreationRight: Option[RequiredTenantRights] = Some(RequiredTenantRights(level = RightLevel.Admin))
}

case object DeleteFeature extends TenantTokenRights {
  val name = "DELETE FEATURE"
  override def requiredCreationRight: Option[RequiredTenantRights] = None
}

case object DeleteProject extends TenantTokenRights {
  val name = "DELETE PROJECT"
  override def requiredCreationRight: Option[RequiredTenantRights] = None
}

case object DeleteKey extends TenantTokenRights {
  val name = "DELETE KEY"
  override def requiredCreationRight: Option[RequiredTenantRights] = None
}

sealed trait GlobalTokenRight {
  def name: String
  def requiredCreationRight: Option[RequiredRights]
}
case object CreateTenant extends GlobalTokenRight {
  val name = "CREATE TENANT"
  override def requiredCreationRight: Option[RequiredRights] = Some(RequiredRights(admin = true))
}

sealed trait PersonnalAccessTokenRights
case object AllRights extends PersonnalAccessTokenRights
case class LimitedRights(
    rights: TokenRights,
    globalRights: Set[GlobalTokenRight]
) extends PersonnalAccessTokenRights

sealed trait PersonnalAccessTokenExpiration
case object NoExpiration extends PersonnalAccessTokenExpiration
case class Expiration(expiresAt: LocalDateTime, expirationTimezone: ZoneId)
    extends PersonnalAccessTokenExpiration

case class PersonnalAccessTokenCreationRequest(
    name: String,
    username: String,
    rights: PersonnalAccessTokenRights,
    expiration: PersonnalAccessTokenExpiration
)

case class ReadPersonnalAccessToken(
    id: UUID,
    createdAt: Instant,
    underlying: PersonnalAccessTokenCreationRequest
) {
  def rights: PersonnalAccessTokenRights = underlying.rights
  def expiration: PersonnalAccessTokenExpiration = underlying.expiration
  def name: String = underlying.name
  def username: String = underlying.username

}
case class CompletePersonnalAccessToken(
    token: String,
    underlying: ReadPersonnalAccessToken
) {
  def rights: PersonnalAccessTokenRights = underlying.rights
  def expiration: PersonnalAccessTokenExpiration = underlying.expiration
  def name: String = underlying.name
  def username: String = underlying.username
  def id: UUID = underlying.id
  def createdAt: Instant = underlying.createdAt
}

object PersonnalAccessToken {
  type TokenRights = Map[String, Set[TenantTokenRights]]

  def parseRight(rawRight: String): TenantTokenRights = {
    rawRight.toUpperCase match {
      case "EXPORT"         => Export
      case "IMPORT"         => Import
      case "DELETE FEATURE" => DeleteFeature
      case "DELETE PROJECT" => DeleteProject
      case "DELETE KEY"     => DeleteKey
    }
  }

  def personnalAccessTokenExpirationReads
      : Reads[PersonnalAccessTokenExpiration] = json => {
    (for (
      expiration <- (json \ "expiresAt").asOpt[LocalDateTime];
      timezone <- (json \ "expirationTimezone").asOpt[String]
    ) yield {
      JsSuccess(
        Expiration(
          expiresAt = expiration,
          expirationTimezone = ZoneId.of(timezone)
        )
      )
    }).getOrElse {
      if (
        (json \ "expiresAt").isDefined || (json \ "expirationTimezone").isDefined
      ) {
        JsError("Bad body format")
      } else {
        JsSuccess(NoExpiration)
      }
    }
  }

  def personnalAccessTokenExpirationWrites
      : Writes[PersonnalAccessTokenExpiration] = {
    case NoExpiration                              => Json.obj()
    case Expiration(expiresAt, expirationTimezone) =>
      Json.obj(
        "expiresAt" -> expiresAt,
        "expirationTimezone" -> expirationTimezone.getId
      )
  }

  def personnalAccessTokenGlobalRightRead: Reads[GlobalTokenRight] = json => {
    json.asOpt[String].map(str => str.toUpperCase) match {
      case Some("CREATE TENANT") => JsSuccess(CreateTenant)
      case _                     => JsError("Bad body format")
    }
  }

  def personnalAccessTokenTenantRightsReads: Reads[TenantTokenRights] = json =>
    {
      val res = json.asOpt[String].map(_.toUpperCase) match {
        case Some("EXPORT")         => JsSuccess(Export)
        case Some("IMPORT")         => JsSuccess(Import)
        case Some("DELETE FEATURE") => JsSuccess(DeleteFeature)
        case Some("DELETE PROJECT") => JsSuccess(DeleteProject)
        case Some("DELETE KEY")     => JsSuccess(DeleteKey)
        case _                      => JsError("Bad body format")
      }

      res
    }

  def personnalAccessTokenTenantRightsWrites: Writes[TenantTokenRights] = {
    case Import        => Json.toJson("IMPORT")
    case Export        => Json.toJson("EXPORT")
    case DeleteFeature => Json.toJson("DELETE FEATURE")
    case DeleteProject => Json.toJson("DELETE PROJECT")
    case DeleteKey     => Json.toJson("DELETE KEY")
  }

  def personnalAccessTokenGlobalRightsWrites: Writes[GlobalTokenRight] = {
    case CreateTenant => Json.toJson("CREATE TENANT")
  }

  def personnalAccessTokenRightsReads: Reads[PersonnalAccessTokenRights] =
    json => {
      (json \ "allRights")
        .asOpt[Boolean]
        .flatMap {
          case true  => Some(JsSuccess(AllRights))
          case false => {
            val rights = (json \ "rights")
              .asOpt[TokenRights](
                Reads.map(Reads.set(personnalAccessTokenTenantRightsReads))
              )
              .getOrElse(Map())
            val globalRights = (json \ "globalRights")
              .asOpt[Set[GlobalTokenRight]](
                Reads.set(personnalAccessTokenGlobalRightRead)
              )
              .getOrElse(Set())

            Some(JsSuccess(LimitedRights(rights, globalRights)))
          }
        }
        .getOrElse(JsError("Bad body format"))
    }

  def personnalAccessTokenRightWrites: Writes[PersonnalAccessTokenRights] = {
    case AllRights                           => Json.obj("allRights" -> JsTrue)
    case LimitedRights(rights, globalRights) =>
      Json.obj(
        "allRights" -> JsFalse,
        "rights" -> Json.toJson(rights)(
          Writes.map(Writes.set(personnalAccessTokenTenantRightsWrites))
        ),
        "globalRights" -> Json.toJson(globalRights)(
          Writes.set(personnalAccessTokenGlobalRightsWrites)
        )
      )
  }

  def personnalAccessTokenCreationRequestRead(
      user: String
  ): Reads[PersonnalAccessTokenCreationRequest] =
    json => {
      (for (
        name <- (json \ "name").asOpt[String];
        rights <- json.asOpt[PersonnalAccessTokenRights](
          personnalAccessTokenRightsReads
        );
        expiration <- json.asOpt[PersonnalAccessTokenExpiration](
          personnalAccessTokenExpirationReads
        )
      ) yield {
        JsSuccess(
          PersonnalAccessTokenCreationRequest(
            name = name,
            username = user,
            rights = rights,
            expiration = expiration
          )
        )
      }).getOrElse(JsError("Bad body format"))
    }

  def personnalAccessTokenCreationRequestWrites
      : Writes[PersonnalAccessTokenCreationRequest] = token => {
    Json.obj(
      "name" -> token.name,
      "username" -> token.username
    ) ++ Json
      .toJson(token.rights)(personnalAccessTokenRightWrites)
      .as[JsObject] ++ Json
      .toJson(token.expiration)(personnalAccessTokenExpirationWrites)
      .as[JsObject]
  }

  def consultationTokenWrites: Writes[ReadPersonnalAccessToken] = {
    case ReadPersonnalAccessToken(id, createdAt, underlying) =>
      Json.obj(
        "id" -> id,
        "createdAt" -> createdAt
      ) ++ Json
        .toJson(underlying)(personnalAccessTokenCreationRequestWrites)
        .as[JsObject]
  }

  def completePersonnalAccessTokenWrites
      : Writes[CompletePersonnalAccessToken] = {
    case CompletePersonnalAccessToken(token, underlying) =>
      Json.obj(
        "token" -> token
      ) ++ Json.toJson(underlying)(consultationTokenWrites).as[JsObject]
  }
}
