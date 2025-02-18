package fr.maif.izanami.datastores

import akka.actor.Cancellable
import fr.maif.izanami.datastores.userImplicits.{UserRow, dbUserTypeToUserType, rightRead}
import fr.maif.izanami.env.Env
import fr.maif.izanami.env.PostgresqlErrors.{RELATION_DOES_NOT_EXISTS, UNIQUE_VIOLATION}
import fr.maif.izanami.env.pgimplicits.EnhancedRow
import fr.maif.izanami.errors._
import fr.maif.izanami.models.RightLevels.{RightLevel, superiorOrEqualLevels}
import fr.maif.izanami.models.Rights.TenantRightDiff
import fr.maif.izanami.models.User.{rightLevelReads, tenantRightReads}
import fr.maif.izanami.models._
import fr.maif.izanami.utils.Datastore
import fr.maif.izanami.utils.syntax.implicits.BetterSyntax
import fr.maif.izanami.web.ImportController.{Fail, ImportConflictStrategy, MergeOverwrite, Skip}
import io.vertx.pgclient.PgException
import io.vertx.sqlclient.{Row, SqlConnection}
import play.api.libs.json.{JsError, JsSuccess, Reads}

import java.util.UUID
import scala.collection.mutable.ArrayBuffer
import scala.concurrent.Future
import scala.concurrent.duration.{DurationInt, DurationLong}

class UsersDatastore(val env: Env) extends Datastore {
  var sessionExpirationCancellation: Cancellable    = Cancellable.alreadyCancelled
  var invitationExpirationCancellation: Cancellable = Cancellable.alreadyCancelled
  var passwordResetRequestCancellation: Cancellable = Cancellable.alreadyCancelled

  override def onStart(): Future[Unit] = {
    sessionExpirationCancellation = env.actorSystem.scheduler.scheduleAtFixedRate(env.houseKeepingStartDelayInSeconds.seconds, env.houseKeepingIntervalInSeconds.seconds)(() =>
      deleteExpiredSessions(env.configuration.get[Int]("app.sessions.ttl"))
    )
    invitationExpirationCancellation = env.actorSystem.scheduler.scheduleAtFixedRate(env.houseKeepingStartDelayInSeconds.seconds, env.houseKeepingIntervalInSeconds.seconds)(() =>
      deleteExpiredInvitations(env.configuration.get[Int]("app.invitations.ttl"))
    )
    passwordResetRequestCancellation = env.actorSystem.scheduler.scheduleAtFixedRate(env.houseKeepingStartDelayInSeconds.seconds, env.houseKeepingIntervalInSeconds.seconds)(() =>
      deleteExpiredPasswordResetRequests(env.configuration.get[Int]("app.password-reset-requests.ttl"))
    )
    Future.successful(())
  }

  override def onStop(): Future[Unit] = {
    sessionExpirationCancellation.cancel()
    invitationExpirationCancellation.cancel()
    passwordResetRequestCancellation.cancel()
    Future.successful(())
  }

  def createSession(username: String): Future[String] = {
    env.postgresql
      .queryOne(s"INSERT INTO izanami.sessions(username) VALUES ($$1) RETURNING id", List(username)) { row =>
        row.optUUID("id")
      }
      .map(maybeUUID => maybeUUID.getOrElse(throw new RuntimeException("Failed to create session")).toString)
  }

  def deleteSession(sessionId: String): Future[Option[String]] = {
    env.postgresql
      .queryOne(s"DELETE FROM izanami.sessions WHERE id=$$1 RETURNING id", List(sessionId)) { row =>
        row.optUUID("id")
      }
      .map(maybeUUID => maybeUUID.map(_.toString))
  }

  def deleteExpiredSessions(sessiontTtlInSeconds: Integer): Future[Integer] = {
    env.postgresql
      .queryAll(
        s"DELETE FROM izanami.sessions WHERE EXTRACT(EPOCH FROM (NOW() - creation)) > $$1 returning id",
        List(sessiontTtlInSeconds)
      ) { _ =>
        Some(())
      }
      .map(_.size)
  }

  def deleteExpiredInvitations(invitationsTtlInSeconds: Integer): Future[Integer] = {
    env.postgresql
      .queryAll(
        s"DELETE FROM izanami.invitations WHERE EXTRACT(EPOCH FROM (NOW() - creation)) > $$1 returning id",
        List(invitationsTtlInSeconds)
      ) { _ =>
        Some(())
      }
      .map(_.size)
  }

  def deleteExpiredPasswordResetRequests(ttlInSeconds: Integer): Future[Integer] = {
    env.postgresql
      .queryAll(
        s"DELETE FROM izanami.password_reset WHERE EXTRACT(EPOCH FROM (NOW() - creation)) > $$1 returning id",
        List(ttlInSeconds)
      ) { _ =>
        Some(())
      }
      .map(_.size)
  }

  def updateUserInformation(
      name: String,
      updateRequest: UserInformationUpdateRequest
  ): Future[Either[IzanamiError, Unit]] = {
    env.postgresql
      .queryOne(
        s"""UPDATE izanami.users SET username=$$1, email=$$2, default_tenant=$$4 WHERE username=$$3 RETURNING username""",
        List(updateRequest.name, updateRequest.email, name, updateRequest.defaultTenant.orNull)
      ) { _ => Some(()) }
      .map(_.toRight(InternalServerError()))
      .recover {
        case f: PgException if f.getSqlState == UNIQUE_VIOLATION =>
          Left(UserAlreadyExist(updateRequest.name, updateRequest.email))
      }
      .recover(env.postgresql.pgErrorPartialFunction.andThen(err => Left(err)))
  }

  def updateLegacyUser(
      name: String,
      password: String
  ): Future[Either[IzanamiError, Unit]] = {
    env.postgresql
      .queryOne(
        s"""UPDATE izanami.users SET password=$$1, legacy=false WHERE username=$$2 RETURNING username""",
        List(HashUtils.bcryptHash(password), name)
      ) { _ => Some(()) }
      .map(_.toRight(InvalidCredentials()))
  }

  def updateUserPassword(
      name: String,
      password: String
  ): Future[Either[IzanamiError, Unit]] = {
    env.postgresql
      .queryOne(
        s"""UPDATE izanami.users SET password=$$1 WHERE username=$$2 RETURNING username""",
        List(HashUtils.bcryptHash(password), name)
      ) { _ => Some(()) }
      .map(_.toRight(InvalidCredentials()))
  }

  def deleteRightsForProject(username: String, tenant: String, project: String): Future[Unit] = {
    env.postgresql
      .queryOne(
        s"""
         |DELETE FROM users_projects_rights WHERE username=$$1
         |""".stripMargin,
        List(username),
        schemas = Seq(tenant)
      ) { _ => Some(()) }
      .map(_ => ())
  }

  def deleteRightsForTenant(
      name: String,
      tenant: String,
      loggedInUser: UserWithRights
  ): Future[Either[IzanamiError, Unit]] = {
    val authorized = loggedInUser.hasAdminRightForTenant(tenant)

    if (!authorized) {
      Left(NotEnoughRights()).future
    } else {
      env.postgresql.executeInTransaction(
        conn =>
          {
            env.postgresql
              .queryOne(
                s"""
               |DELETE FROM izanami.users_tenants_rights WHERE username=$$1 AND tenant=$$2
               |""".stripMargin,
                List(name, tenant),
                conn = Some(conn)
              ) { _ => Some(()) }
              .flatMap(_ => {
                env.postgresql.queryOne(
                  s"""
                       |DELETE FROM users_projects_rights WHERE username=$$1
                       |""".stripMargin,
                  List(name),
                  conn = Some(conn)
                ) { r => Some(()) }
              })
              .flatMap(_ => {
                env.postgresql.queryOne(
                  s"""
                       |DELETE FROM users_keys_rights WHERE username=$$1
                       |""".stripMargin,
                  List(name),
                  conn = Some(conn)
                ) { r => Some(()) }
              })
          }.map(_ => Right(())),
        schemas = Seq(tenant)
      )
    }
  }

  def updateUserRightsForProject(
      username: String,
      tenant: String,
      project: String,
      right: RightLevel
  ): Future[Unit] = {
    env.postgresql
      .queryOne(
        s"""
         |INSERT INTO users_projects_rights (username, project, level) VALUES ($$1, $$2, $$3)
         |ON CONFLICT(username, project) DO UPDATE
         |SET username=EXCLUDED.username, project=EXCLUDED.project, level=EXCLUDED.level
         |RETURNING 1
         |""".stripMargin,
        List(username, project, right.toString.toUpperCase),
        schemas = Seq(tenant)
      ) { _ => Some(()) }
      .map(_ => ())
  }

  def updateUserRightsForTenant(
      name: String,
      tenant: String,
      diff: TenantRightDiff
  ): Future[Unit] = {
    env.postgresql
      .executeInTransaction(
        conn => {
          val tenantQuery = diff.removedTenantRight
            .map(r => {
              env.postgresql.queryOne(
                s"""
                           |DELETE FROM izanami.users_tenants_rights
                           |WHERE username=$$1
                           |AND tenant=$$2
                           |RETURNING username
                           |""".stripMargin,
                List(name, r.name),
                conn = Some(conn)
              ) { _ => Some(()) }
            })
            .toSeq
          Future
            .sequence(
              tenantQuery.concat(
                Seq(
                  env.postgresql.queryOne(
                    s"""
                               |DELETE FROM users_projects_rights
                               |WHERE username=$$1
                               |AND project=ANY($$2)
                               |RETURNING username
                               |""".stripMargin,
                    List(name, diff.removedProjectRights.map(_.name).toArray),
                    conn = Some(conn)
                  ) { _ => Some(()) },
                  env.postgresql.queryOne(
                    s"""
                               |DELETE FROM users_keys_rights
                               |WHERE username=$$1
                               |AND apikey=ANY($$2)
                               |RETURNING username
                               |""".stripMargin,
                    List(name, diff.removedKeyRights.map(_.name).toArray),
                    conn = Some(conn)
                  ) { _ => Some(()) },
                  env.postgresql.queryOne(
                    s"""
                       |DELETE FROM users_webhooks_rights
                       |WHERE username=$$1
                       |AND webhook=ANY($$2)
                       |RETURNING username
                       |""".stripMargin,
                    List(name, diff.removedWebhookRights.map(_.name).toArray),
                    conn = Some(conn)
                  ) { _ => Some(()) }
                )
              )
            )
            .flatMap(_ => {
              Future.sequence(
                (
                  diff.addedTenantRight
                    .map(r => {
                      env.postgresql.queryOne(
                        s"""
                               |INSERT INTO izanami.users_tenants_rights(username, tenant, level)
                               |VALUES($$1, $$2, $$3)
                               |RETURNING username
                               |""".stripMargin,
                        List(
                          name,
                          tenant,
                          r.level.toString.toUpperCase
                        ),
                        conn = Some(conn)
                      ) { _ => Some(()) }
                    })
                  )
                  .toSeq
                  .concat(
                    Seq(
                      env.postgresql.queryOne(
                        s"""
                               |INSERT INTO users_projects_rights(username, project, level)
                               |VALUES($$1, unnest($$2::TEXT[]), unnest($$3::izanami.right_level[]))
                               |RETURNING username
                               |""".stripMargin,
                        List(
                          name,
                          diff.addedProjectRights.map { flatten => flatten.name }.toArray,
                          diff.addedProjectRights.map { flatten => flatten.level.toString.toUpperCase }.toArray
                        ),
                        conn = Some(conn)
                      ) { _ => Some(()) },
                      env.postgresql.queryOne(
                        s"""
                               |INSERT INTO users_keys_rights(username,apikey, level)
                               |VALUES($$1, unnest($$2::TEXT[]), unnest($$3::izanami.right_level[]))
                               |RETURNING username
                               |""".stripMargin,
                        List(
                          name,
                          diff.addedKeyRights.map(flatten => flatten.name).toArray,
                          diff.addedKeyRights.map(flatten => flatten.level.toString.toUpperCase).toArray
                        ),
                        conn = Some(conn)
                      ) { _ => Some(()) },
                      env.postgresql.queryOne(
                        s"""
                           |INSERT INTO users_webhooks_rights(username, webhook, level)
                           |VALUES($$1, unnest($$2::TEXT[]), unnest($$3::izanami.right_level[]))
                           |RETURNING username
                           |""".stripMargin,
                        List(
                          name,
                          diff.addedWebhookRights.map(flatten => flatten.name).toArray,
                          diff.addedWebhookRights.map(flatten => flatten.level.toString.toUpperCase).toArray
                        ),
                        conn = Some(conn)
                      ) { _ => Some(()) }
                    )
                  )
              )
            })
        },
        schemas = Seq(tenant)
      )
      .map(_ => ())
  }

  def updateUserRights(
      name: String,
      updateRequest: UserRightsUpdateRequest
  ): Future[Either[IzanamiError, Unit]] = {
    findUserWithCompleteRights(name)
      .flatMap {
        case Some(UserWithRights(_, _, _, _, _, rights, _, _)) => {
          val diff = Rights.compare(base = rights, modified = updateRequest.rights)
          // TODO externalize this
          env.postgresql.executeInTransaction(conn => {
            updateRequest.admin
              .map(admin =>
                env.postgresql
                  .queryOne(
                    s"""UPDATE izanami.users SET admin=$$1 WHERE username=$$2 RETURNING username""",
                    List(java.lang.Boolean.valueOf(admin), name),
                    conn = Some(conn)
                  ) { _ => Some(()) }
              )
              .getOrElse(Future(Some(())))
              .map(_.toRight(InternalServerError()))
              .flatMap {
                case Left(value) => Left(value).future
                case Right(_)    => {
                  env.postgresql
                    .queryOne(
                      s"""
                                   |DELETE FROM izanami.users_tenants_rights
                                   |WHERE username=$$1
                                   |AND tenant=ANY($$2)
                                   |RETURNING username
                                   |""".stripMargin,
                      List(name, diff.removedTenantRights.map(_.name).toArray),
                      conn = Some(conn)
                    ) { _ => Some(()) }
                    .flatMap(_ => {
                      diff.removedProjectRights.foldLeft(Future.successful(())) { case (f, (tenant, rights)) =>
                        f.flatMap(_ => env.postgresql.updateSearchPath(tenant, conn))
                          .flatMap(_ =>
                            env.postgresql
                              .queryOne(
                                s"""
                                   |DELETE FROM users_projects_rights
                                   |WHERE username=$$1
                                   |AND project=ANY($$2)
                                   |RETURNING username
                                   |""".stripMargin,
                                List(name, rights.map(_.name).toArray),
                                conn = Some(conn)
                              ) { _ => Some(()) }
                              .map(_ => ())
                          )
                      }
                    })
                    .flatMap(_ => {
                      diff.removedKeyRights.foldLeft(Future.successful(())) { case (f, (tenant, rights)) =>
                        f.flatMap(_ => env.postgresql.updateSearchPath(tenant, conn))
                          .flatMap(_ =>
                            env.postgresql
                              .queryOne(
                                s"""
                                   |DELETE FROM users_keys_rights
                                   |WHERE username=$$1
                                   |AND apikey=ANY($$2)
                                   |RETURNING username
                                   |""".stripMargin,
                                List(name, rights.map(_.name).toArray),
                                conn = Some(conn)
                              ) { _ => Some(()) }
                              .map(_ => ())
                          )
                      }
                    })
                    .flatMap(_ => {
                      diff.removedWebhookRights.foldLeft(Future.successful(())) { case (f, (tenant, rights)) =>
                        f.flatMap(_ => env.postgresql.updateSearchPath(tenant, conn))
                          .flatMap(_ =>
                            env.postgresql
                              .queryOne(
                                s"""
                                   |DELETE FROM users_webhooks_rights
                                   |WHERE username=$$1
                                   |AND webhook=ANY($$2)
                                   |RETURNING username
                                   |""".stripMargin,
                                List(name, rights.map(_.name).toArray),
                                conn = Some(conn)
                              ) { _ => Some(()) }
                              .map(_ => ())
                          )
                      }
                    })
                    .flatMap(_ => {
                      env.postgresql
                        .queryOne(
                          s"""
                                   |INSERT INTO izanami.users_tenants_rights(username, tenant, level)
                                   |VALUES($$1, unnest($$2::TEXT[]), unnest($$3::izanami.right_level[]))
                                   |RETURNING username
                                   |""".stripMargin,
                          List(
                            name,
                            diff.addedTenantRights.map(_.name).toArray,
                            diff.addedTenantRights.map(_.level.toString.toUpperCase).toArray
                          ),
                          conn = Some(conn)
                        ) { _ => Some(()) }
                        .flatMap(_ => {
                          diff.addedProjectRights.foldLeft(Future.successful(())) { case (f, (tenantName, rights)) =>
                            f.flatMap(_ => env.postgresql.updateSearchPath(tenantName, conn))
                              .flatMap(_ =>
                                env.postgresql
                                  .queryOne(
                                    s"""
                                  |INSERT INTO users_projects_rights(username, project, level)
                                  |VALUES($$1, unnest($$2::TEXT[]), unnest($$3::izanami.right_level[]))
                                  |RETURNING username
                                  |""".stripMargin,
                                    List(
                                      name,
                                      rights.map(_.name).toArray,
                                      rights.map(_.level.toString.toUpperCase).toArray
                                    ),
                                    conn = Some(conn)
                                  ) { _ => Some(()) }
                                  .map(_ => ())
                              )
                          }
                        })
                        .flatMap(_ => {
                          diff.addedKeyRights.foldLeft(Future.successful(())) { case (f, (tenantName, rights)) =>
                            f.flatMap(_ => env.postgresql.updateSearchPath(tenantName, conn))
                              .flatMap(_ =>
                                env.postgresql
                                  .queryOne(
                                    s"""
                                  |INSERT INTO users_keys_rights(username,apikey, level)
                                  |VALUES($$1, unnest($$2::TEXT[]), unnest($$3::izanami.right_level[]))
                                  |RETURNING username
                                  |""".stripMargin,
                                    List(
                                      name,
                                      rights.map(_.name).toArray,
                                      rights.map(_.level.toString.toUpperCase).toArray
                                    ),
                                    conn = Some(conn)
                                  ) { _ => Some(()) }
                                  .map(_ => ())
                              )
                          }
                        })
                        .flatMap(_ => {
                          diff.addedWebhookRights.foldLeft(Future.successful(())) { case (f, (tenantName, rights)) =>
                            f.flatMap(_ => env.postgresql.updateSearchPath(tenantName, conn))
                              .flatMap(_ =>
                                env.postgresql
                                  .queryOne(
                                    s"""
                                       |INSERT INTO users_webhooks_rights(username, webhook, level)
                                       |VALUES($$1, unnest($$2::TEXT[]), unnest($$3::izanami.right_level[]))
                                       |RETURNING username
                                       |""".stripMargin,
                                    List(
                                      name,
                                      rights.map(_.name).toArray,
                                      rights.map(_.level.toString.toUpperCase).toArray
                                    ),
                                    conn = Some(conn)
                                  ) { _ => Some(()) }
                                  .map(_ => ())
                              )
                          }
                        })
                    })
                    .map(_ => Right(()))
                }
              }
          })
        }
        case None                                              => Left(UserNotFound(name)).future
      }
  }

  def createUserWithConn(
      users: Seq[UserWithRights],
      conn: SqlConnection,
      importConflictStrategy: ImportConflictStrategy = Fail
  ): Future[Either[IzanamiError, Unit]] = {
    if (users.isEmpty) {
      Future.successful(Right(()))
    } else {
      val eventualErrorOrUnit: Future[Either[IzanamiError, Unit]] = env.postgresql
        .queryRaw(
          s"""insert into izanami.users (username, password, admin, email, user_type, legacy)
             |values (unnest($$1::TEXT[]), unnest($$2::TEXT[]), unnest($$3::BOOLEAN[]), unnest($$4::TEXT[]), unnest($$5::izanami.user_type[]), unnest($$6::BOOLEAN[])) ${importConflictStrategy match {
            case Fail           => ""
            case MergeOverwrite => s" ON CONFLICT(username) DO UPDATE SET admin=COALESCE(users.admin, excluded.admin)"
            case Skip           => " ON CONFLICT(username) DO NOTHING"
          }} returning *""".stripMargin,
          List(
            users.map(_.username).toArray,
            users.map(user => Option(user.password).map(pwd => HashUtils.bcryptHash(pwd)).orNull).toArray,
            users.map(user => java.lang.Boolean.valueOf(user.admin)).toArray,
            users.map(user => Option(user.email).orNull).toArray,
            users.map(user => user.userType.toString).toArray,
            users.map(user => java.lang.Boolean.valueOf(user.legacy)).toArray
          ),
          conn = Some(conn)
        ) { _ => Right(()) }
        .recover(env.postgresql.pgErrorPartialFunction.andThen(err => Left(err)))
        .flatMap {
          case Left(err) => Future.successful(Left(err))
          case Right(_) => {
            val base = users.flatMap(u => u.rights.tenants.map { case (tenant, right) => (tenant, u.username, right) })
            base
              .filter { case (_, _, r) => r.projects.nonEmpty }
              .flatMap {
                case (tenant, username, tenantRight) => {
                  tenantRight.projects.map { case (project, right) =>
                    (tenant, username, project, right.level)
                  }
                }
              }
              .groupBy(_._1)
              .view
              .mapValues(seq => seq.map { case (_, username, project, level) => (username, project, level) })
              .foldLeft(Future.successful(())) {
                case (future, (tenant, values)) => {
                  future.flatMap(_ => createProjectRights(tenant, values, conn, importConflictStrategy))
                }
              }
              .flatMap(_ => {
                base
                  .filter { case (_, _, r) => r.keys.nonEmpty }
                  .flatMap {
                    case (tenant, username, tenantRight) => {
                      tenantRight.keys.map { case (key, right) =>
                        (tenant, username, key, right.level)
                      }
                    }
                  }
                  .groupBy(_._1)
                  .view
                  .mapValues(seq => seq.map { case (_, username, key, level) => (username, key, level) })
                  .foldLeft(Future.successful(())) {
                    case (future, (tenant, values)) => {
                      future.flatMap(_ => createKeyRights(tenant, values, conn, importConflictStrategy))
                    }
                  }
              })
              .flatMap(_ => {
                base
                  .filter { case (_, _, r) => r.webhooks.nonEmpty }
                  .flatMap {
                    case (tenant, username, tenantRight) => {
                      tenantRight.webhooks.map { case (key, right) =>
                        (tenant, username, key, right.level)
                      }
                    }
                  }
                  .groupBy(_._1)
                  .view
                  .mapValues(seq => seq.map { case (_, username, key, level) => (username, key, level) })
                  .foldLeft(Future.successful(())) {
                    case (future, (tenant, values)) => {
                      future.flatMap(_ => createWebhookRights(tenant, values, conn))
                    }
                  }
              })
              .flatMap(_ => {
                val (usernames, tenants, levels) = users
                  .flatMap(u => {
                    u.rights.tenants.map { case (tenant, TenantRight(level, _, _, _)) => (u.username, tenant, level) }
                  })
                  .toArray
                  .unzip3

                env.postgresql.queryRaw(
                  s"""
                     |INSERT INTO izanami.users_tenants_rights (username, tenant, level)
                     |VALUES (unnest($$1::TEXT[]), unnest($$2::TEXT[]), unnest($$3::izanami.right_level[]))
                     |${importConflictStrategy match {
                    case Fail           => ""
                    case MergeOverwrite =>
                      """
                     | ON CONFLICT(username, tenant) DO UPDATE SET level = CASE
                     | WHEN users_keys_rights.level = 'READ' THEN excluded.level
                     | WHEN (users_keys_rights.level = 'WRITE' AND excluded.level = 'ADMIN') THEN 'ADMIN'
                     | WHEN users_keys_rights.level = 'ADMIN' THEN 'ADMIN'
                     | ELSE users_keys_rights.level
                     | END
                     |""".stripMargin
                    case Skip           => " ON CONFLICT(username, tenant) DO NOTHING "
                  }}
                     |returning username
                     |""".stripMargin,
                  List(usernames, tenants, levels.map(l => l.toString.toUpperCase())),
                  conn = Some(conn)
                ) { _ => Some(()) }
                .map(_ => Right(()))
              })
          }
        }
      eventualErrorOrUnit
    }
  }

  def createUser(user: UserWithRights): Future[Either[IzanamiError, Unit]] = {
    env.postgresql.executeInTransaction(conn => {
      createUserWithConn(Seq(user), conn)
    })
  }

  def deleteUser(username: String): Future[Unit] = {
    env.postgresql
      .queryOne(
        s"""
         |DELETE FROM izanami.users
         |WHERE username=$$1
         |""".stripMargin,
        List(username)
      ) { row =>
        {
          Some(())
        }
      }
      .map(o => o.getOrElse(()))
  }

  def hasRightForWebhook(
      session: String,
      tenant: String,
      webhook: String,
      level: RightLevel
  ): Future[Either[IzanamiError, Option[(String, String)]]] = {
    env.postgresql
      .queryOne(
        s"""
           |SELECT u.username, w.name
           |FROM izanami.sessions s
           |LEFT JOIN izanami.users u ON u.username=s.username
           |LEFT JOIN izanami.users_tenants_rights utr ON u.username = utr.username AND utr.tenant=$$2
           |LEFT JOIN webhooks w ON w.id=$$3
           |LEFT JOIN users_webhooks_rights uwr ON u.username = uwr.username AND uwr.webhook=w.name
           |WHERE s.id=$$1
           |AND (
           |  u.admin=true
           |  OR utr.level='ADMIN'
           |  OR uwr.level=ANY($$4)
           |)
           |""".stripMargin,
        List(session, tenant, webhook, superiorOrEqualLevels(level).map(l => l.toString.toUpperCase).toArray),
        schemas = Seq(tenant)
      ) { r =>
        {
          for (
            username <- r.optString("username");
            hookname <- r.optString("name")
          ) yield (username, hookname)
        }
      }
      .map(Right(_))
      .recover {
        case f: PgException if f.getSqlState == RELATION_DOES_NOT_EXISTS => Left(TenantDoesNotExists(tenant))
        case _                                                           => Left(InternalServerError())
      }
  }

  def hasRightForKey(
      session: String,
      tenant: String,
      key: String,
      level: RightLevel
  ): Future[Either[IzanamiError, Option[String]]] = {
    env.postgresql
      .queryOne(
        s"""
           |SELECT u.username
           |FROM izanami.sessions s
           |LEFT JOIN izanami.users u ON u.username=s.username
           |LEFT JOIN izanami.users_tenants_rights utr ON u.username = utr.username AND utr.tenant=$$2
           |LEFT JOIN users_keys_rights ukr ON u.username = ukr.username AND ukr.apikey=$$3
           |WHERE s.id=$$1
           |AND (
           |  u.admin=true
           |  OR utr.level='ADMIN'
           |  OR ukr.level=ANY($$4)
           |)
           |""".stripMargin,
        List(session, tenant, key, superiorOrEqualLevels(level).map(l => l.toString.toUpperCase).toArray),
        schemas = Seq(tenant)
      ) { r => r.optString("username") }
      .map(Right(_))
      .recover {
        case f: PgException if f.getSqlState == RELATION_DOES_NOT_EXISTS => Left(TenantDoesNotExists(tenant))
        case _                                                           => Left(InternalServerError())
      }
  }

  // TODO merge with hasRightFor ?
  def hasRightForProject(
      session: String,
      tenant: String,
      projectIdOrName: Either[UUID, String],
      level: RightLevel
  ): Future[Either[IzanamiError, Option[(String, UUID)]]] = {
    env.postgresql
      .queryOne(
        s"""
           |SELECT p.id, u.username
           |FROM projects p
           |JOIN izanami.sessions s ON s.id=$$1
           |JOIN izanami.users u ON u.username=s.username
           |LEFT JOIN izanami.users_tenants_rights utr ON u.username = utr.username AND utr.tenant=$$2
           |LEFT JOIN users_projects_rights upr ON u.username = upr.username AND upr.project=p.name
           |WHERE (
           |  u.admin=true
           |  OR utr.level='ADMIN'
           |  OR upr.level=ANY($$4)
           |)
           |AND ${projectIdOrName.fold(_ => s"p.id=$$3", _ => s"p.name=$$3")}
           |""".stripMargin,
        List(session, tenant, projectIdOrName.fold(identity, identity), superiorOrEqualLevels(level).map(l => l.toString.toUpperCase).toArray),
        schemas = Seq(tenant)
      ) { r =>
        {
          for (
            username  <- r.optString("username");
            projectId <- r.optUUID("id")
          ) yield (username, projectId)
        }
      }
      .map(maybeUser => Right(maybeUser))
      .recover {
        case f: PgException if f.getSqlState == RELATION_DOES_NOT_EXISTS => Left(TenantDoesNotExists(tenant))
        case _                                                           => Left(InternalServerError())
      }
  }



  // TODO merge with hasRight
  def hasRightForTenant(session: String, tenant: String, level: RightLevel): Future[Option[String]] = {
    env.postgresql
      .queryOne(
        s"""
           |SELECT u.username
           |FROM izanami.sessions s
           |LEFT JOIN izanami.users u ON u.username = s.username
           |LEFT JOIN izanami.users_tenants_rights utr ON u.username = utr.username AND utr.tenant=$$2
           |WHERE s.id=$$1
           |AND (
           |  u.admin=true
           |  OR utr.level=ANY($$3)
           |)
           |""".stripMargin,
        List(session, tenant, superiorOrEqualLevels(level).map(l => l.toString.toUpperCase).toArray)
      ) { r => r.optString("username") }
  }

  def hasRightFor(
      tenant: String,
      username: String,
      rights: Set[RightUnit],
      tenantLevel: Option[RightLevel] = Option.empty
  ) = {
    val (keys, projects): (Set[String], Set[String]) = rights.partitionMap(r => {
      r.rightType match {
        case RightTypes.Key     => Left(r.name)
        case RightTypes.Project => Right(r.name)
      }
    })

    var index      = 2
    val subQueries = ArrayBuffer[String]()
    val params     = ArrayBuffer[Object](username)

    if (projects.nonEmpty) {
      subQueries.addOne(s"""
          |'projects',
          |array(
          |  select json_build_object('name', p.project, 'level', p.level)
          |  from users_projects_rights p
          |  where p.username=$$1
          |  and p.project=ANY($$${index})
          |)
          |""".stripMargin)
      index = index + 1
      params.addOne(projects.toArray)
    }
    if (keys.nonEmpty) {
      subQueries.addOne(
        s"""
           |'keys',
           |array(
           |  select json_build_object('name', k.apikey, 'level', k.level)
           |  from users_keys_rights k
           |  where k.username=$$1
           |  and k.apikey=ANY($$${index})
           |)
           |""".stripMargin
      )
      index = index + 1
      params.addOne(keys.toArray)
    }

    params.addOne(tenant)

    env.postgresql
      .queryOne(
        s"""
           |SELECT utr.level, u.admin, json_build_object(
           |${subQueries.mkString(",")}
           |)::jsonb as rights
           |FROM izanami.users u
           |LEFT JOIN izanami.users_tenants_rights utr ON u.username = utr.username AND utr.tenant=$$${index}
           |WHERE u.username=$$1
           |""".stripMargin,
        params.toList,
        schemas = Seq(tenant)
      ) { r =>
        {
          val admin            = r.getBoolean("admin")
          val tenantRightLevel = r.optRightLevel("level")
          val extractedRights  = r
            .optJsObject("rights")
            .map(obj => {
              (obj \ "projects")
                .asOpt[Set[RightValue]]
                .getOrElse(Set())
                .map(r => (RightTypes.Project, r.level, r.name))
                .concat(
                  (obj \ "keys").asOpt[Set[RightValue]].getOrElse(Set()).map(r => (RightTypes.Key, r.level, r.name))
                )
            })
            .getOrElse(Set())
            .groupBy(t => (t._1, t._3))
            .view
            .mapValues(s => s.map(t => t._2))

          val projectKeyRightMatches = rights
            .map(r => (r.rightType, r.rightLevel, r.name))
            .forall(t => {
              val maybeExtractedLevels = extractedRights.get((t._1, t._3))
              val acceptableLevels     = RightLevels.superiorOrEqualLevels(t._2)
              maybeExtractedLevels
                .exists(levels => levels.intersect(acceptableLevels).nonEmpty)
            })
          Some(
            admin ||
            tenantRightLevel.contains(RightLevels.Admin) ||
            tenantLevel
              .map(tLevel => {
                tenantRightLevel.exists(extractedLevel =>
                  superiorOrEqualLevels(tLevel).contains(extractedLevel)
                ) && projectKeyRightMatches
              })
              .getOrElse(projectKeyRightMatches)
          )
        }
      }
      .map(o => o.getOrElse(false))
  }

  def findAdminSession(session: String): Future[Option[String]] = {
    env.postgresql
      .queryOne(
        s"""
           |SELECT u.username
           |FROM izanami.users u, izanami.sessions s
           |WHERE u.username = s.username
           |AND s.id = $$1
           |AND u.admin = true
           |""".stripMargin,
        List(session)
      ) { row => row.optString("username") }
  }

  def findSession(session: String): Future[Option[String]] = {
    env.postgresql
      .queryOne(
        s"""
           |SELECT u.username
           |FROM izanami.users u, izanami.sessions s
           |WHERE u.username = s.username
           |AND s.id = $$1
           |""".stripMargin,
        List(session)
      ) { row => row.optString("username") }
  }

  def isAdmin(username: String): Future[Boolean] = {
    env.postgresql
      .queryOne(
        s"""
           |SELECT u.username
           |FROM izanami.users u
           |WHERE u.username = $$1
           |AND u.admin = true
           |""".stripMargin,
        List(username)
      ) { _ => Some(true) }
      .map(maybeBoolean => maybeBoolean.getOrElse(false))
  }

  def createInvitation(
      email: String,
      admin: Boolean,
      rights: Rights,
      inviter: String
  ): Future[Either[IzanamiError, String]] = {
    env.postgresql
      .queryOne(
        s"""
           |INSERT INTO izanami.invitations(email, admin, rights, inviter) values ($$1, $$2, $$3::jsonb, $$4)
           |ON CONFLICT(email)
           |DO UPDATE
           |SET admin=EXCLUDED.admin, rights=EXCLUDED.rights, creation=EXCLUDED.creation, id=EXCLUDED.id, inviter=EXCLUDED.inviter
           |returning id
           |""".stripMargin,
        List(email, java.lang.Boolean.valueOf(admin), User.rightWrite.writes(rights).toString(), inviter)
      ) { row =>
        Some(row.getUUID("id").toString)
      }
      .map(o => o.toRight(InternalServerError()))
      .recover(env.postgresql.pgErrorPartialFunction.andThen(err => Left(err)))
  }

  def deleteInvitation(id: String): Future[Option[Unit]] = {
    env.postgresql.queryOne(
      s"""
         |DELETE FROM izanami.invitations WHERE id=$$1::UUID RETURNING id
         |""".stripMargin,
      List(id)
    ) { _ =>
      Some(())
    }
  }

  def readInvitation(id: String): Future[Option[UserInvitation]] = {
    env.postgresql.queryOne(
      s"""
         |SELECT id, email, admin, rights from izanami.invitations where id=$$1
         |""".stripMargin,
      List(id)
    ) { row =>
      {
        for (
          id         <- row.optUUID("id");
          admin      <- row.optBoolean("admin");
          jsonRights <- row.optJsObject("rights");
          rights     <- User.rightsReads.reads(jsonRights).asOpt
        ) yield UserInvitation(email = row.optString("email").orNull, admin = admin, rights = rights, id = id.toString)
      }
    }
  }

  def createProjectRights(
      tenant: String,
      rights: Seq[(String, String, RightLevel)],
      conn: SqlConnection,
      conflictStrategy: ImportConflictStrategy = Fail
  ): Future[Unit] = {
    val (usernames, projects, levels) = rights.toArray.map { case (username, project, right) =>
      (username, project, right.toString.toUpperCase)
    }.unzip3

    env.postgresql.queryRaw(
      s"""
           |INSERT INTO users_projects_rights(username, project, level)
           |VALUES (unnest($$1::TEXT[]), unnest($$2::TEXT[]), unnest($$3::izanami.right_level[]))
           |${conflictStrategy match {
        case Fail           => ""
        case MergeOverwrite => """
               | ON CONFLICT(username, project) DO UPDATE SET level=CASE
               |  WHEN users_keys_rights.level='READ' THEN excluded.level
               |  WHEN (users_keys_rights.level='WRITE' AND excluded.level = 'ADMIN') THEN 'ADMIN'
               |  WHEN users_keys_rights.level='ADMIN' THEN 'ADMIN'
               |  ELSE users_keys_rights.level
               |END
               |""".stripMargin
        case Skip           => " ON CONFLICT(username, project) DO NOTHING "
      }}
           |RETURNING username
           |""".stripMargin,
      List(usernames, projects, levels),
      schemas = Seq(tenant),
      conn = Some(conn)
    ) { _ => Some(()) }
  }

  def createWebhookRights(
      tenant: String,
      rights: Seq[(String, String, RightLevel)],
      conn: SqlConnection
  ): Future[Unit] = {
    val (usernames, projects, levels) = rights.toArray.map { case (username, project, right) =>
      (username, project, right.toString.toUpperCase)
    }.unzip3

    env.postgresql.queryRaw(
      s"""
         |INSERT INTO users_webhooks_rights(username, webhook, level)
         |VALUES (unnest($$1::TEXT[]), unnest($$2::TEXT[]), unnest($$3::izanami.right_level[]))
         |RETURNING username
         |""".stripMargin,
      List(usernames, projects, levels),
      schemas = Seq(tenant),
      conn = Some(conn)
    ) { _ => () }
  }

  def createKeyRights(
      tenant: String,
      rights: Seq[(String, String, RightLevel)],
      conn: SqlConnection,
      conflictStrategy: ImportConflictStrategy = Fail
  ): Future[Unit] = {
    val (usernames, projects, levels) = rights.toArray.map { case (username, project, right) =>
      (username, project, right.toString.toUpperCase)
    }.unzip3

    env.postgresql.queryRaw(
      s"""
         |INSERT INTO users_keys_rights(username, apikey, level)
         |VALUES (unnest($$1::TEXT[]), unnest($$2::TEXT[]), unnest($$3::izanami.right_level[]))
         |${conflictStrategy match {
        case Fail           => ""
        case MergeOverwrite =>
          """
              | ON CONFLICT(username, project) DO UPDATE SET level = CASE
              | WHEN users_keys_rights.level = 'READ' THEN excluded.level
              | WHEN (users_keys_rights.level = 'WRITE' AND excluded.level = 'ADMIN') THEN 'ADMIN'
              | WHEN users_keys_rights.level = 'ADMIN' THEN 'ADMIN'
              | ELSE users_keys_rights.level
              | END
              |""".stripMargin
        case Skip           => " ON CONFLICT(username, project) DO NOTHING "
      }}
         |RETURNING username
         |""".stripMargin,
      List(usernames, projects, levels),
      schemas = Seq(tenant),
      conn = Some(conn)
    ) { _ => () }
  }

  def isUserValid(username: String, password: String): Future[Option[User]] = {
    // TODO handle lecgacy users & test it !
    env.postgresql
      .queryOne(
        s"""SELECT username, password, admin, email, user_type, legacy FROM izanami.users WHERE username=$$1""",
        List(username)
      ) { row =>
        row
          .optString("password")
          .filter(hashed => {
            row.optBoolean("legacy").exists {
              case true  => HashUtils.bcryptCheck(HashUtils.hexSha512(password), hashed)
              case false => HashUtils.bcryptCheck(password, hashed)
            }
          })
          .flatMap(_ => row.optUser().map(u => u.copy(legacy = row.optBoolean("legacy").getOrElse(false))))
      }
  }

  def findSessionWithTenantRights(session: String): Future[Option[UserWithTenantRights]] = {
    env.postgresql.queryOne(
      s"""
         |SELECT u.username, u.admin, u.email, u.default_tenant, u.user_type,
         |  coalesce((
         |    select json_object_agg(utr.tenant, utr.level)
         |    from izanami.users_tenants_rights utr
         |    where utr.username=u.username
         |  ), '{}'::json) as tenants
         |from izanami.users u, izanami.sessions s
         |WHERE u.username=s.username
         |AND s.id=$$1""".stripMargin,
      List(session)
    ) { row =>
      {
        for (
          username <- row.optString("username");
          admin    <- row.optBoolean("admin");
          rights   <- row.optJsObject("tenants");
          userType <- row.optString("user_type").map(dbUserTypeToUserType)
        ) yield {
          val tenantRights = rights.asOpt[Map[String, RightLevel]].getOrElse(Map())
          UserWithTenantRights(
            username = username,
            email = row.optString("email").orNull,
            password = null,
            admin = admin,
            tenantRights = tenantRights,
            userType = userType
          )
        }
      }
    }
  }

  def savePasswordResetRequest(username: String): Future[String] = {
    env.postgresql
      .queryOne(
        s"""
         |INSERT into izanami.password_reset(username)
         |VALUES($$1)
         |ON CONFLICT(username)
         |DO UPDATE
         |SET creation=EXCLUDED.creation, id=EXCLUDED.id
         |RETURNING id
         |""".stripMargin,
        List(username)
      ) { row =>
        row.optUUID("id").map(_.toString)
      }
      .map(_.getOrElse(throw new RuntimeException("Failed to create password request")))
  }

  def findPasswordResetRequest(id: String): Future[Option[String]] = {
    env.postgresql.queryOne(
      s"""
        |SELECT username FROM izanami.password_reset WHERE id=$$1
        |""".stripMargin,
      List(id)
    ) { row => row.optString("username") }
  }

  def deletePasswordResetRequest(id: String): Future[Unit] = {
    env.postgresql
      .queryOne(
        s"""
        |DELETE FROM izanami.password_reset WHERE id=$$1
        |""".stripMargin,
        List(id)
      ) { _ => Some(()) }
      .map(_ => ())
  }

  def findUserByMail(email: String): Future[Option[User]] = {
    env.postgresql.queryOne(
      s"""
         |SELECT username, email, user_type, admin, default_tenant FROM izanami.users WHERE email=$$1
         |""".stripMargin,
      List(email)
    ) { r => r.optUser() }
  }

  def findUser(username: String): Future[Option[UserWithTenantRights]] = {
    env.postgresql.queryOne(
      s"""
         |SELECT username, admin, email, user_type, default_tenant,
         |  coalesce((
         |    select json_object_agg(utr.tenant, utr.level)
         |    from izanami.users_tenants_rights utr
         |    where utr.username=$$1
         |  ), '{}'::json) as tenants
         |from izanami.users
         |WHERE username=$$1""".stripMargin,
      List(username)
    ) { row =>
      {
        for (
          username <- row.optString("username");
          admin    <- row.optBoolean("admin");
          rights   <- row.optJsObject("tenants");
          userType <- row.optString("user_type").map(dbUserTypeToUserType)
        ) yield {
          val tenantRights = rights.asOpt[Map[String, RightLevel]].getOrElse(Map())
          UserWithTenantRights(
            username = username,
            email = row.optString("email").orNull,
            password = null,
            admin = admin,
            tenantRights = tenantRights,
            defaultTenant = row.optString("default_tenant"),
            userType = userType
          )
        }
      }
    }
  }

  def searchUsers(search: String, count: Integer): Future[Seq[String]] = {
    // TODO better matching algorithm
    env.postgresql.queryAll(
      s"""
         |SELECT username
         |FROM izanami.users
         |WHERE username ILIKE $$1::TEXT
         |ORDER BY LENGTH(username)
         |LIMIT $$2
         |""".stripMargin,
      List(s"%${search}%", count)
    ) { r => r.optString("username") }
  }

  def findUsers(username: String): Future[Set[UserWithTenantRights]] = {
    env.postgresql
      .queryAll(
        s"""
         |WITH rights AS (
         |  SELECT utr.tenant, u.admin
         |  FROM izanami.users u
         |  LEFT JOIN izanami.users_tenants_rights utr ON utr.username=u.username
         |  WHERE u.username=$$1
         |)
         |SELECT u.username, u.admin, u.email, u.user_type, u.default_tenant,
         |  CASE WHEN (SELECT admin FROM rights LIMIT 1) THEN (
         |    SELECT coalesce((
         |        select json_object_agg(utr2.tenant, utr2.level)
         |        from izanami.users_tenants_rights utr2
         |        where utr2.username = u.username
         |      ), '{}'::json))
         |  ELSE (
         |      SELECT coalesce((
         |        select json_object_agg(utr2.tenant, utr2.level)
         |        from izanami.users_tenants_rights utr2
         |        where utr2.tenant=ANY(SELECT tenant FROM rights)
         |        and utr2.username=u.username
         |      ), '{}'::json)
         |  )
         |  END AS tenants
         |  FROM izanami.users u
         |  GROUP BY (u.username, u.admin)
         """.stripMargin,
        List(username)
      ) { row =>
        {
          row.optUserWithTenantRights()
        }
      }
      .map(users => users.toSet)
  }

  def findUsersForTenant(tenant: String): Future[List[UserWithSingleLevelRight]] = {
    env.postgresql.queryAll(
      s"""
         |SELECT u.username, u.email, u.admin, u.user_type, u.default_tenant, r.level
         |FROM izanami.users u
         |LEFT JOIN izanami.users_tenants_rights r ON r.username = u.username AND r.tenant=$$1
         |WHERE r.level IS NOT NULL
         |OR u.admin=true
         |""".stripMargin,
      List(tenant)
    ) { r =>
      r.optUser().map(u => u.withSingleLevelRight(r.optRightLevel("level").orNull))
    }
  }

  def findUsersForWebhook(tenant: String, webhook: String): Future[List[UserWithSingleScopedRight]] = {
    env.postgresql.queryAll(
      s"""
         |SELECT
         |    wr.level as level,
         |    utr.level as tenant_right,
         |    u.admin,
         |    u.user_type,
         |    u.default_tenant,
         |    u.username,
         |    u.email
         |FROM izanami.users u
         |LEFT JOIN webhooks w ON w.id=$$2
         |LEFT JOIN users_webhooks_rights wr ON wr.webhook=w.name AND wr.username=u.username
         |LEFT JOIN izanami.users_tenants_rights utr ON utr.username = u.username AND utr.tenant=$$1
         |WHERE wr.level IS NOT NULL
         |OR utr.level='ADMIN'
         |OR u.admin=true
         |""".stripMargin,
      List(tenant, webhook),
      schemas = Seq(tenant)
    ) { r =>
      {
        r.optUser()
          .map(u => {
            val maybeTenantRight = r.optRightLevel("tenant_right")
            u.withSingleTenantScopedRightLevel(
              r.optRightLevel("level").orNull,
              maybeTenantRight.contains(RightLevels.Admin)
            )
          })
      }
    }
  }

  def findUsersForProject(tenant: String, project: String): Future[List[UserWithSingleScopedRight]] = {
    env.postgresql.queryAll(
      s"""
         |SELECT u.username, u.email, u.admin, u.user_type, u.default_tenant, r.level, tr.level as tenant_right
         |FROM izanami.users u
         |LEFT JOIN users_projects_rights r ON r.username = u.username AND r.project=$$1
         |LEFT JOIN izanami.users_tenants_rights tr ON tr.username = u.username AND tr.tenant=$$2
         |WHERE r.level IS NOT NULL
         |OR tr.level='ADMIN'
         |OR u.admin=true
         |""".stripMargin,
      List(project, tenant),
      schemas = Seq(tenant)
    ) { r =>
      r.optUser()
        .map(u => {
          val maybeTenantRight = r.optRightLevel("tenant_right")
          u.withSingleTenantScopedRightLevel(
            r.optRightLevel("level").orNull,
            maybeTenantRight.contains(RightLevels.Admin)
          )
        })
    }
  }

  def findUsersForKey(tenant: String, key: String): Future[List[UserWithSingleScopedRight]] = {
    env.postgresql.queryAll(
      s"""
         |SELECT
         |    kr.level as level,
         |    utr.level as tenant_right,
         |    u.admin,
         |    u.user_type,
         |    u.default_tenant,
         |    u.username,
         |    u.email
         |FROM izanami.users u
         |LEFT JOIN apikeys k ON k.name=$$2
         |LEFT JOIN users_keys_rights kr ON kr.apikey=k.name AND kr.username=u.username
         |LEFT JOIN izanami.users_tenants_rights utr ON utr.username = u.username AND utr.tenant=$$1
         |WHERE kr.level IS NOT NULL
         |OR utr.level='ADMIN'
         |OR u.admin=true
         |""".stripMargin,
      List(tenant, key),
      schemas = Seq(tenant)
    ) { r =>
      {
        r.optUser()
          .map(u => {
            val maybeTenantRight = r.optRightLevel("tenant_right")
            u.withSingleTenantScopedRightLevel(
              r.optRightLevel("level").orNull,
              maybeTenantRight.contains(RightLevels.Admin)
            )
          })
      }
    }
  }

  def findSessionWithCompleteRights(session: String): Future[Option[UserWithRights]] = {
    findSessionWithTenantRights(session).flatMap {
      case Some(user) if user.tenantRights.nonEmpty => {
        val tenants = user.tenantRights.keys.toSet
        findCompleteRightsFromTenant(user.username, tenants)
      }
      case Some(user)                               => Some(user.withRights(Rights.EMPTY)).future
      case _                                        => Future.successful(None)
    }
  }

  def findCompleteRightsFromTenant(username: String, tenants: Set[String]): Future[Option[UserWithRights]] = {
    Future
      .sequence(
        tenants.map(tenant => {
          env.postgresql
            .queryOne(
              s"""
             |SELECT u.username, u.admin, u.email, u.user_type, u.default_tenant, json_build_object(
             |    'level', utr.level,
             |    'projects', COALESCE((select json_object_agg(p.project, json_build_object('level', p.level)) from users_projects_rights p where p.username=$$1), '{}'),
             |    'keys', COALESCE((select json_object_agg(k.apikey, json_build_object('level', k.level)) from users_keys_rights k where k.username=$$1), '{}'),
             |    'webhooks', COALESCE((select json_object_agg(w.webhook, json_build_object('level', w.level)) from users_webhooks_rights w where w.username=$$1), '{}')
             |)::jsonb as rights
             |from izanami.users u
             |left join izanami.users_tenants_rights utr on (utr.username = u.username AND utr.tenant=$$2)
             |WHERE u.username=$$1;
             |""".stripMargin,
              List(username, tenant),
              schemas = Seq(tenant)
            ) { row => row.optUserWithRights() }
            .map(fuser => fuser.map(u => (tenant, u)))
        })
      )
      .map(users => {
        val userParts = users.flatMap(o => o.toSeq)
        val rightMap  = userParts
          .map { case (t, u) => (t, u.tenantRight) }
          .filter { case (_, maybeRight) => maybeRight.isDefined }
          .map { case (t, o) => (t, o.get) }
          .toMap

        userParts.headOption
          .map { case (_, u) => u }
          .map(u =>
            UserWithRights(
              username = u.username,
              email = u.email,
              admin = u.admin,
              userType = u.userType,
              rights = Rights(rightMap),
              defaultTenant = u.defaultTenant
            )
          )
      })
  }

  def findUserWithCompleteRights(username: String): Future[Option[UserWithRights]] = {
    findUser(username).flatMap {
      case Some(user) if user.tenantRights.nonEmpty => {
        val tenants = user.tenantRights.keys.toSet
        findCompleteRightsFromTenant(username, tenants)
      }
      case Some(user)                               => Some(user.withRights(Rights.EMPTY)).future
      case _                                        => Future.successful(None)
    }
  }

  def addUserRightsToTenant(tenant: String, users: Seq[(String, RightLevel)]): Future[Unit] = {
    env.postgresql
      .queryOne(
        s"""
         |INSERT INTO izanami.users_tenants_rights (tenant, username, level)
         |VALUES($$1, unnest($$2::TEXT[]), unnest($$3::izanami.right_level[]))
         |ON CONFLICT (username, tenant) DO NOTHING
         |""".stripMargin,
        List(tenant, users.map(_._1).toArray, users.map(_._2.toString.toUpperCase).toArray)
      ) { r => Some(()) }
      .map(_ => ())
  }

  def addUserRightsToProject(tenant: String, project: String, users: Seq[(String, RightLevel)]): Future[Unit] = {
    env.postgresql
      .queryAll(
        s"""
         |SELECT username FROM izanami.users_tenants_rights
         |WHERE username = ANY($$1)
         |""".stripMargin,
        List(users.map { case (username, _) => username }.toArray)
      ) { r => r.optString("username") }
      .map(userWithRights => {
        val userWithMissingRights = users.map { case (username, _) => username }.toSet.diff(userWithRights.toSet)
        if (userWithMissingRights.isEmpty) {
          Future.successful(())
        } else {
          env.postgresql
            .queryAll(
              s"""
                 |INSERT INTO izanami.users_tenants_rights(username, tenant, level)
                 |VALUES (unnest($$1::text[]), $$2, 'READ')
                 |RETURNING username
                 |""".stripMargin,
              List(userWithMissingRights.toArray, tenant)
            ) { r => { Some(()) } }
            .map(_ => ())
        }
      })
      .flatMap(_ =>
        env.postgresql
          .queryOne(
            s"""
             |INSERT INTO users_projects_rights (project, username, level)
             |VALUES($$1, unnest($$2::TEXT[]), unnest($$3::izanami.right_level[]))
             |ON CONFLICT (username, project) DO NOTHING
             |""".stripMargin,
            List(project, users.map(_._1).toArray, users.map(_._2.toString.toUpperCase).toArray),
            schemas = Seq(tenant)
          ) { r => Some(()) }
          .map(_ => ())
      )
  }

  def findSessionWithRightForTenant(
      session: String,
      tenant: String
  ): Future[Either[IzanamiError, UserWithCompleteRightForOneTenant]] = {
    env.postgresql
      .queryOne(
        s"""
         |SELECT u.username, u.admin, u.email, u.user_type, u.default_tenant,
         |    COALESCE((
         |      select (json_build_object('level', utr.level, 'projects', (
         |        select json_object_agg(p.project, json_build_object('level', p.level))
         |        from users_projects_rights p
         |        where p.username=u.username
         |      )))
         |      from izanami.users_tenants_rights utr
         |      where utr.username=u.username
         |      and utr.tenant=$$2
         |    ), '{}'::json) as rights
         |from izanami.users u, izanami.sessions s
         |WHERE u.username=s.username
         |and s.id=$$1""".stripMargin,
        List(session, tenant),
        schemas = Seq(tenant)
      ) { row =>
        {
          for (
            username <- row.optString("username");
            userType <- row.optString("user_type").map(dbUserTypeToUserType);
            admin    <- row.optBoolean("admin");
            right    <- row.optJsObject("rights")
          ) yield {
            val parsedRights = right.asOpt[TenantRight]
            UserWithCompleteRightForOneTenant(
              username = username,
              email = row.optString("email").orNull,
              password = null,
              admin = admin,
              tenantRight = parsedRights,
              userType = userType
            )
          }
        }
      }
      .map(o => o.toRight(SessionNotFound(session)))
      .recover {
        case f: PgException if f.getSqlState == RELATION_DOES_NOT_EXISTS => Left(TenantDoesNotExists(tenant))
        case _                                                           => Left(InternalServerError())
      }
  }
}

case class RightValue(name: String, level: RightLevel)

object userImplicits {
  implicit class UserRow(val row: Row) extends AnyVal {
    def optRights(): Option[TenantRight] = {
      row.optJsObject("rights").flatMap(js => js.asOpt[TenantRight])
    }

    def optRightLevel(field: String): Option[RightLevel] = {
      row.optString(field).map(dbRightToRight)
    }

    def optUserWithTenantRights(): Option[UserWithTenantRights] = {
      for (
        username <- row.optString("username");
        userType <- row.optString("user_type").map(dbUserTypeToUserType);
        admin    <- row.optBoolean("admin");
        rights   <- row.optJsObject("tenants")
      ) yield {
        val tenantRights = rights.asOpt[Map[String, RightLevel]].getOrElse(Map())
        UserWithTenantRights(
          username = username,
          email = row.optString("email").orNull,
          password = null,
          admin = admin,
          tenantRights = tenantRights,
          userType = userType,
          defaultTenant = row.optString("default_tenant")
        )
      }
    }

    def optUser(): Option[User] = {
      for (
        username <- row.optString("username");
        userType <- row.optString("user_type").map(dbUserTypeToUserType);
        admin    <- row.optBoolean("admin")
      )
        yield User(
          username = username,
          email = row.optString("email").orNull,
          password = null,
          admin = admin,
          userType = userType,
          defaultTenant = row.optString("default_tenant")
        )
    }

    def optUserWithRights(): Option[UserWithCompleteRightForOneTenant] = {
      for (
        username <- row.optString("username");
        userType <- row.optString("user_type").map(dbUserTypeToUserType);
        admin    <- row.optBoolean("admin")
      )
        yield UserWithCompleteRightForOneTenant(
          username = username,
          email = row.optString("email").orNull,
          password = null,
          admin = admin,
          tenantRight = row.optRights(),
          userType = userType,
          defaultTenant = row.optString("default_tenant")
        )
    }
  }

  implicit val rightRead: Reads[RightValue] = { json =>
    {
      for (
        name  <- (json \ "name").asOpt[String];
        level <- (json \ "level").asOpt[String]
      ) yield {
        val right = dbRightToRight(level)
        JsSuccess(RightValue(name = name, level = right))
      }
    }.getOrElse(JsError("Failed to read rights"))
  }

  def dbRightToRight(dbRight: String): RightLevel = {
    dbRight match {
      case "ADMIN" => RightLevels.Admin
      case "READ"  => RightLevels.Read
      case "WRITE" => RightLevels.Write
    }
  }

  def dbUserTypeToUserType(userType: String): UserType = {
    userType match {
      case "OTOROSHI" => OTOROSHI
      case "INTERNAL" => INTERNAL
      case "OIDC"     => OIDC
    }
  }
}
