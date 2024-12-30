package fr.maif.izanami.datastores

import fr.maif.izanami.datastores.HashUtils.bcryptCheck
import fr.maif.izanami.datastores.HashUtils.bcryptHash
import fr.maif.izanami.datastores.PersonnalAccessTokenDatastore.TokenCheckFailure
import fr.maif.izanami.datastores.PersonnalAccessTokenDatastore.TokenCheckResult
import fr.maif.izanami.datastores.PersonnalAccessTokenDatastore.TokenCheckSuccess
import fr.maif.izanami.datastores.PersonnalAccessTokenDatastoreImplicits.PersonnalAccessTokenRow
import fr.maif.izanami.env.Env
import fr.maif.izanami.env.PostgresqlErrors.FOREIGN_KEY_VIOLATION
import fr.maif.izanami.env.PostgresqlErrors.UNIQUE_VIOLATION
import fr.maif.izanami.env.pgimplicits.EnhancedRow
import fr.maif.izanami.errors.InternalServerError
import fr.maif.izanami.errors.IzanamiError
import fr.maif.izanami.errors.TenantDoesNotExists
import fr.maif.izanami.errors.TokenDoesNotExist
import fr.maif.izanami.errors.TokenWithThisNameAlreadyExists
import fr.maif.izanami.models.AllRights
import fr.maif.izanami.models.CompletePersonnalAccessToken
import fr.maif.izanami.models.Expiration
import fr.maif.izanami.models.LimitedRights
import fr.maif.izanami.models.NoExpiration
import fr.maif.izanami.models.PersonnalAccessToken
import fr.maif.izanami.models.PersonnalAccessTokenCreationRequest
import fr.maif.izanami.models.PersonnalAccessTokenExpiration
import fr.maif.izanami.models.PersonnalAccessTokenRights
import fr.maif.izanami.models.ReadPersonnalAccessToken
import fr.maif.izanami.models.TenantTokenRights
import fr.maif.izanami.security.IdGenerator.token
import fr.maif.izanami.utils.Datastore
import io.vertx.pgclient.PgException
import io.vertx.sqlclient.Row

import java.time.Instant
import java.time.ZoneId
import java.util.UUID
import scala.concurrent.Future
import scala.util.Try

class PersonnalAccessTokenDatastore(val env: Env) extends Datastore {
  def findAccessTokenByIds(ids: Set[UUID]): Future[Map[UUID, String]] = {
    env.postgresql.queryAll(
      s"""
         |SELECT t.id, t.name
         |FROM izanami.personnal_access_tokens t
         |WHERE t.id=ANY($$1::UUID[])
         |""".stripMargin,
      List(ids.toArray)
    ){r => {
      for(
        id <- r.optUUID("id");
        name <- r.optString("name")
      ) yield (id, name)
    }}.map(l => l.toMap)
  }

  def checkAccessToken(
      username: String,
      token: String,
      tenant: String,
      operation: TenantTokenRights
  ): Future[TokenCheckResult] = {
    val parts = token.split("_")
    if (parts.length != 2) {
      Future.successful(TokenCheckFailure)
    } else {
      val id     = UUID.fromString(parts.head)
      val secret = parts.last
      env.postgresql
        .queryOne(
          s"""
           |SELECT
           |  t.token,
           |  t.expires_at,
           |  t.expiration_timezone
           |FROM izanami.personnal_access_tokens t
           |WHERE username = $$1 AND id = $$2 AND (
           |  all_rights = true OR EXISTS(
           |    SELECT value
           |    FROM izanami.personnal_access_token_rights
           |    WHERE token=t.id  AND tenant=$$3 AND value = $$4
           |  )
           |)
           |""".stripMargin,
          List(username, id, tenant, operation.name)
        ) { r =>
          {
            val maybeExpiresAt          = r.optLocalDateTime("expires_at")
            val maybeExpirationTimezone = r.optString("expiration_timezone")
            if (maybeExpiresAt.isDefined && maybeExpirationTimezone.isDefined) {
              (for (
                expiresAt   <- maybeExpiresAt;
                timezoneStr <- maybeExpirationTimezone;
                timezone    <- Try(ZoneId.of(timezoneStr)).toOption;
                date         = expiresAt.atZone(timezone)
              ) yield {
                if (date.toInstant.isAfter(Instant.now())) {
                  r.optString("token")
                } else {
                  None
                }
              }).flatten
            } else {
              r.optString("token")
            }
          }
        }
        .map {
          case Some(t) if bcryptCheck(secret, t) => {
            TokenCheckSuccess(id)
          }
          case _    => TokenCheckFailure
        }
    }
  }

  def updateAccessToken(
      id: UUID,
      user: String,
      data: PersonnalAccessTokenCreationRequest
  ): Future[Either[IzanamiError, ReadPersonnalAccessToken]] = {
    env.postgresql.executeInTransaction(conn => {
      env.postgresql
        .queryRaw(
          s"""
           |DELETE FROM izanami.personnal_access_token_rights
           |WHERE token = $$1
           |RETURNING 1
           |""".stripMargin,
          List(id),
          conn = Some(conn)
        ) { _ => () }
        .flatMap(_ => {
          env.postgresql
            .queryOne(
              s"""
                 |UPDATE izanami.personnal_access_tokens SET name = $$1, all_rights=$$2, expires_at = $$5, expiration_timezone = $$6
                 |WHERE id = $$3 AND username = $$4
                 |RETURNING created_at
                 |""".stripMargin,
              List(
                data.name,
                data.rights match {
                  case AllRights        => java.lang.Boolean.valueOf(true)
                  case LimitedRights(_) => java.lang.Boolean.valueOf(false)
                },
                id,
                user
              ).concat(
                data.expiration match {
                  case NoExpiration                              => List(null, null)
                  case Expiration(expiresAt, expirationTimezone) => List(expiresAt, expirationTimezone.toString)
                }
              ),
              conn = Some(conn)
            ) { r => r.optOffsetDatetime("created_at") }
            .map(t => t.get)
        })
        .flatMap(t => {
          val res: Future[Either[IzanamiError, ReadPersonnalAccessToken]] = data.rights match {
            case AllRights             =>
              Future.successful(Right(ReadPersonnalAccessToken(id = id, createdAt = t.toInstant, underlying = data)))
            case LimitedRights(rights) => {
              val rightAsList = rights.toList
              if (rightAsList.isEmpty) {
                Future.successful(Right(ReadPersonnalAccessToken(id = id, createdAt = t.toInstant, underlying = data)))
              } else {
                env.postgresql
                  .queryRaw(
                    s"""
                   |INSERT INTO izanami.personnal_access_token_rights (token, tenant, value) VALUES($$1, UNNEST($$2::TEXT[]), UNNEST($$3::izanami.TOKEN_RIGHT[])) RETURNING *
                   |""".stripMargin,
                    List(
                      id,
                      rightAsList.map(_._1).toArray,
                      rightAsList.flatMap(_._2.map(_.name)).toArray
                    ),
                    conn = Some(conn)
                  ) { r => () }
                  .map(_ => Right(ReadPersonnalAccessToken(id = id, createdAt = t.toInstant, underlying = data)))
              }
            }
          }
          res
        })
        .recover {
          case f: PgException if f.getSqlState == UNIQUE_VIOLATION =>
            Left(TokenWithThisNameAlreadyExists(data.name))
          case ex                                                  =>
            Left(InternalServerError())
        }
    })
  }

  def createAcessToken(
      data: PersonnalAccessTokenCreationRequest
  ): Future[Either[IzanamiError, CompletePersonnalAccessToken]] = {
    val secret = token(64)
    env.postgresql.executeInTransaction(conn => {
      env.postgresql
        .queryOne(
          s"""
             |INSERT INTO izanami.personnal_access_tokens (name, username, token, expires_at, expiration_timezone, all_rights) VALUES($$1, $$2, $$3, $$4, $$5, $$6) RETURNING *
             |""".stripMargin,
          List(data.name, data.username, bcryptHash(secret))
            .concat(
              data.expiration match {
                case NoExpiration                              => List(null, null)
                case Expiration(expiresAt, expirationTimezone) => List(expiresAt, expirationTimezone.toString)
              }
            )
            .appended(data.rights match {
              case AllRights        => java.lang.Boolean.valueOf(true)
              case LimitedRights(_) => java.lang.Boolean.valueOf(false)
            }),
          conn = Some(conn)
        ) { r =>
          {
            r.optToken.map(t => {
              val exposedToken = s"${t.id}_$secret"
              CompletePersonnalAccessToken(
                token = exposedToken,
                underlying = t.copy(underlying = data)
              )
            })
          }
        }
        .flatMap {
          case Some(token) => {
            token.rights match {
              case AllRights             => Future.successful(Right(token))
              case LimitedRights(rights) => {
                val rightAsList = rights.toList
                if (rightAsList.isEmpty) {
                  Future.successful(Right(token))
                } else {
                  env.postgresql
                    .queryRaw(
                      s"""
                       |INSERT INTO izanami.personnal_access_token_rights (token, tenant, value) VALUES($$1, UNNEST($$2::TEXT[]), UNNEST($$3::izanami.TOKEN_RIGHT[])) RETURNING *
                       |""".stripMargin,
                      List(
                        token.id,
                        rightAsList.flatMap(r => r._2.toList.map(_ => r._1)).toArray,
                        rightAsList.flatMap(_._2.map(_.name)).toArray
                      ),
                      conn = Some(conn)
                    ) { r => () }
                    .map(_ => Right(token))
                }
              }
            }
          }
          case None        => {
            Future.successful(Left(InternalServerError()): Either[IzanamiError, CompletePersonnalAccessToken])
          }
        }
        .recover {
          case f: PgException if f.getSqlState == UNIQUE_VIOLATION      =>
            Left(TokenWithThisNameAlreadyExists(data.name))
          case f: PgException if f.getSqlState == FOREIGN_KEY_VIOLATION =>
            Left(TenantDoesNotExists(data.rights match {
              case LimitedRights(rights) => rights.keys.mkString(" or ")
              case _                     => "<unknown tenant>"
            }))
          case ex                                                       =>
            logger.error("Failed to update project mapping table", ex)
            Left(InternalServerError())
        }
    })
  }

  def deleteAcessToken(id: String, username: String): Future[Either[IzanamiError, Unit]] = {
    env.postgresql
      .queryOne(
        s"""
         |DELETE FROM izanami.personnal_access_tokens
         |WHERE id = $$1 AND username = $$2
         |RETURNING id
         |""".stripMargin,
        List(id, username)
      ) { r => Some(()) }
      .map(o => o.toRight(TokenDoesNotExist(id, username)))
  }

  def listUserTokens(user: String): Future[Seq[ReadPersonnalAccessToken]] = {
    env.postgresql.queryAll(
      s"""
         |SELECT
         |  t.id,
         |  t.name,
         |  t.username,
         |  t.created_at,
         |  t.expires_at,
         |  t.expiration_timezone,
         |  t.all_rights,
         |  COALESCE(json_agg(json_build_object('tenant', tr.tenant, 'right', tr.value)) FILTER (WHERE tr.token IS NOT NULL), '[]') AS rights
         |FROM izanami.personnal_access_tokens t
         |LEFT OUTER JOIN izanami.personnal_access_token_rights tr ON tr.token = t.id
         |WHERE username = $$1
         |GROUP BY t.id
         |""".stripMargin,
      List(user)
    ) { r => r.optToken }
  }

}

object PersonnalAccessTokenDatastore {
  sealed trait TokenCheckResult
  case class TokenCheckSuccess(tokenId: UUID) extends TokenCheckResult
  case object TokenCheckFailure extends TokenCheckResult
}

object PersonnalAccessTokenDatastoreImplicits {
  implicit class PersonnalAccessTokenRow(val row: Row) extends AnyVal {

    def optExpiration: Option[PersonnalAccessTokenExpiration] = {
      (for (
        expireAt <- row.optLocalDateTime("expires_at");
        timezone <- row.optString("expiration_timezone")
      )
        yield Expiration(expiresAt = expireAt, expirationTimezone = ZoneId.of(timezone))).orElse(Some(NoExpiration))
    }

    def optToken: Option[ReadPersonnalAccessToken] = {
      for (
        id         <- row.optUUID("id");
        name       <- row.optString("name");
        username   <- row.optString("username");
        createdAt  <- row.optOffsetDatetime("created_at");
        allRights  <- row.optBoolean("all_rights");
        expiration <- row.optExpiration
      ) yield {
        val rights: Option[PersonnalAccessTokenRights] = if (allRights) {
          Some(AllRights)
        } else if (row.getColumnIndex("rights") != -1) {
          row
            .optJsArray("rights")
            .map(rawRights => {
              rawRights.value
                .flatMap(json => {
                  for (
                    tenant   <- (json \ "tenant").asOpt[String];
                    rawRight <- (json \ "right").asOpt[String];
                    right     = PersonnalAccessToken.parseRight(rawRight)
                  ) yield {
                    tenant -> right
                  }
                })
                .groupBy(_._1)
                .view
                .mapValues(s => s.map(_._2).toSet)
                .toMap
            })
            .map(map => LimitedRights(map))
        } else {
          Some(LimitedRights(Map()))
        }

        rights.map(r => {
          ReadPersonnalAccessToken(
            id = id,
            createdAt = createdAt.toInstant,
            underlying = PersonnalAccessTokenCreationRequest(
              name,
              username,
              r,
              expiration
            )
          )
        })

      }
    }.flatten
  }
}
