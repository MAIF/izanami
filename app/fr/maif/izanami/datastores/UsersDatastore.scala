package fr.maif.izanami.datastores

import akka.actor.Cancellable
import fr.maif.izanami.datastores.userImplicits.{dbUserTypeToUserType, projectRightRead, rightRead, UserRow}
import fr.maif.izanami.env.Env
import fr.maif.izanami.env.PostgresqlErrors.{RELATION_DOES_NOT_EXISTS, UNIQUE_VIOLATION}
import fr.maif.izanami.env.pgimplicits.EnhancedRow
import fr.maif.izanami.errors._
import fr.maif.izanami.models.RightLevel.{superiorOrEqualLevels, Read}
import fr.maif.izanami.models.Rights.{
  RightDiff,
  TenantRightDiff,
  UnscopedFlattenKeyRight,
  UnscopedFlattenProjectRight,
  UnscopedFlattenTenantRight,
  UnscopedFlattenWebhookRight,
  UpsertTenantRights
}
import fr.maif.izanami.models.User.tenantRightReads
import fr.maif.izanami.models._
import fr.maif.izanami.utils.syntax.implicits.BetterSyntax
import fr.maif.izanami.utils.{Datastore, FutureEither}
import fr.maif.izanami.web.ImportController._
import io.vertx.pgclient.PgException
import io.vertx.sqlclient.{Row, SqlConnection}
import play.api.libs.json.{JsError, JsSuccess, Reads}

import java.util.UUID
import scala.collection.mutable.ArrayBuffer
import scala.concurrent.Future
import scala.concurrent.duration.DurationLong

class UsersDatastore(val env: Env) extends Datastore {
  var sessionExpirationCancellation: Cancellable    = Cancellable.alreadyCancelled
  var invitationExpirationCancellation: Cancellable = Cancellable.alreadyCancelled
  var passwordResetRequestCancellation: Cancellable = Cancellable.alreadyCancelled

  override def onStart(): Future[Unit] = {
    sessionExpirationCancellation = env.actorSystem.scheduler
      .scheduleAtFixedRate(env.houseKeepingStartDelayInSeconds.seconds, env.houseKeepingIntervalInSeconds.seconds)(() =>
        deleteExpiredSessions(env.typedConfiguration.sessions.ttl)
      )
    invitationExpirationCancellation = env.actorSystem.scheduler
      .scheduleAtFixedRate(env.houseKeepingStartDelayInSeconds.seconds, env.houseKeepingIntervalInSeconds.seconds)(() =>
        deleteExpiredInvitations(env.typedConfiguration.invitations.ttl)
      )
    passwordResetRequestCancellation = env.actorSystem.scheduler
      .scheduleAtFixedRate(env.houseKeepingStartDelayInSeconds.seconds, env.houseKeepingIntervalInSeconds.seconds)(() =>
        deleteExpiredPasswordResetRequests(env.typedConfiguration.passwordResetRequests.ttl)
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

  def updateUsersAdminStatus(
      usernames: Set[String],
      admin: Boolean,
      conn: Option[SqlConnection] = None
  ): Future[Unit] = {
    env.postgresql
      .queryOne(
        s"""UPDATE izanami.users SET admin=$$1 WHERE username=any($$2::TEXT[]) RETURNING username""",
        List(java.lang.Boolean.valueOf(admin), usernames.toArray),
        conn = conn
      ) { _ => Some(()) }
      .map(_ => ())
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

  def updateUserRightsForTenant(
      username: String,
      tenant: String,
      diff: TenantRightDiff,
      conn: Option[SqlConnection] = None,
      importConflictStrategy: ImportConflictStrategy = Replace
  ): Future[Either[IzanamiError, Unit]] = {
    updateUsersRightsForTenant(Set(username), tenant, diff, conn, importConflictStrategy)
  }

  private def deleteAllRightsForTenant(
      usernames: Set[String],
      tenant: String,
      conn: SqlConnection
  ): FutureEither[Unit] = {
    require(Tenant.isTenantValid(tenant))
    for (
      _   <- FutureEither(
               env.postgresql
                 .queryOne(
                   s"""
           |DELETE FROM izanami.users_tenants_rights
           |WHERE username=any($$1::TEXT[])
           |AND tenant=$$2
           |RETURNING username
           |""".stripMargin,
                   List(usernames.toArray, tenant),
                   conn = Some(conn)
                 ) { _ => Some(()) }
                 .map(_ => Right())
                 .recover(env.postgresql.pgErrorPartialFunction.andThen(err => Left(err)))
             );
      _   <- FutureEither(
               env.postgresql
                 .queryOne(
                   s"""
             |DELETE FROM "${tenant}".users_projects_rights
             |WHERE username=any($$1::TEXT[])
             |RETURNING username
             |""".stripMargin,
                   List(usernames.toArray),
                   conn = Some(conn)
                 ) { _ => Some(()) }
                 .map(_ => Right())
                 .recover(env.postgresql.pgErrorPartialFunction.andThen(err => Left(err)))
             );
      _   <- FutureEither(
               env.postgresql
                 .queryOne(
                   s"""
             |DELETE FROM "${tenant}".users_keys_rights
             |WHERE username=any($$1::TEXT[])
             |RETURNING username
             |""".stripMargin,
                   List(usernames.toArray),
                   conn = Some(conn)
                 ) { _ => Some(()) }
                 .map(_ => Right())
                 .recover(env.postgresql.pgErrorPartialFunction.andThen(err => Left(err)))
             );
      res <- FutureEither(
               env.postgresql
                 .queryOne(
                   s"""
             |DELETE FROM "${tenant}".users_webhooks_rights
             |WHERE username=any($$1::TEXT[])
             |RETURNING username
             |""".stripMargin,
                   List(usernames.toArray),
                   conn = Some(conn)
                 ) { _ => Some(()) }
                 .map(_ => Right())
                 .recover(env.postgresql.pgErrorPartialFunction.andThen(err => Left(err)))
             )
    ) yield res
  }

  private def deleteProjectRights(
      tenant: String,
      usernames: Set[String],
      projects: Set[String],
      conn: Option[SqlConnection]
  ): FutureEither[Unit] = {
    if (usernames.isEmpty || projects.isEmpty) {
      FutureEither.success(())
    } else {
      FutureEither(
        env.postgresql
          .queryOne(
            s"""
             |DELETE FROM "${tenant}".users_projects_rights
             |WHERE username=any($$1::TEXT[])
             |AND project=ANY($$2)
             |RETURNING username
             |""".stripMargin,
            List(usernames.toArray, projects.toArray),
            conn = conn
          ) { _ => Some(()) }
          .map(_ => Right())
          .recover(env.postgresql.pgErrorPartialFunction.andThen(err => Left(err)))
      )
    }
  }

  private def deleteKeyRights(
      tenant: String,
      usernames: Set[String],
      keys: Set[String],
      conn: Option[SqlConnection]
  ): FutureEither[Unit] = {
    if (usernames.isEmpty || keys.isEmpty) {
      FutureEither.success(())
    } else {
      FutureEither(
        env.postgresql
          .queryOne(
            s"""
             |DELETE FROM "${tenant}".users_keys_rights
             |WHERE username=any($$1::TEXT[])
             |AND apikey=ANY($$2)
             |RETURNING username
             |""".stripMargin,
            List(usernames.toArray, keys.toArray),
            conn = conn
          ) { _ => Some(()) }
          .map(_ => Right())
          .recover(env.postgresql.pgErrorPartialFunction.andThen(err => Left(err)))
      )
    }
  }

  private def deleteWebhookRights(
      tenant: String,
      usernames: Set[String],
      webhooks: Set[String],
      conn: Option[SqlConnection]
  ): FutureEither[Unit] = {
    if (usernames.isEmpty || webhooks.isEmpty) {
      FutureEither.success(())
    } else {
      FutureEither(
        env.postgresql
          .queryOne(
            s"""
             |DELETE FROM "${tenant}".users_webhooks_rights
             |WHERE username=any($$1::TEXT[])
             |AND webhook=ANY($$2)
             |RETURNING username
             |""".stripMargin,
            List(usernames.toArray, webhooks.toArray),
            conn = conn
          ) { _ => Some(()) }
          .map(_ => Right())
          .recover(env.postgresql.pgErrorPartialFunction.andThen(err => Left(err)))
      )
    }
  }

  private def createTenantRight(
      tenant: String,
      usernames: Set[String],
      right: UnscopedFlattenTenantRight,
      importConflictStrategy: ImportConflictStrategy,
      conn: Option[SqlConnection]
  ): FutureEither[Unit] = {
    FutureEither(
      env.postgresql
        .queryOne(
          s"""
             |INSERT INTO izanami.users_tenants_rights(username, tenant, level, default_project_right, default_webhook_right, default_key_right)
             |VALUES(unnest($$1::TEXT[]), $$2, $$3, $$4, $$5, $$6)
             |${importConflictStrategy match {
            case Skip           => " ON CONFLICT(username, tenant) DO NOTHING"
            case Fail           => ""
            case Replace        =>
              """ ON CONFLICT (username, tenant) DO UPDATE
             | SET level=EXCLUDED.level,
             | default_project_right=EXCLUDED.default_project_right,
             | default_webhook_right=EXCLUDED.default_webhook_right,
             | default_key_right=EXCLUDED.default_key_right
             |""".stripMargin
            case MergeOverwrite =>
              """
             | ON CONFLICT(username, tenant) DO UPDATE SET level = CASE
             |   WHEN users_tenants_rights.level = 'READ' THEN excluded.level
             |   WHEN (users_tenants_rights.level = 'WRITE' AND excluded.level = 'ADMIN') THEN 'ADMIN'
             |   WHEN users_tenants_rights.level = 'ADMIN' THEN 'ADMIN'
             |   ELSE users_tenants_rights.level
             | END,
             | default_project_right=CASE
             |   WHEN users_tenants_rights.default_project_right = 'READ' THEN excluded.default_project_right
             |   WHEN (users_projects_rights.default_project_right = 'UPDATE' AND (excluded.default_project_right = 'ADMIN' OR excluded.default_project_right = 'WRITE')) THEN excluded.default_project_right
             |   WHEN (users_tenants_rights.default_project_right = 'WRITE' AND excluded.default_project_right = 'ADMIN') THEN 'ADMIN'
             |   WHEN users_tenants_rights.default_project_right = 'ADMIN' THEN 'ADMIN'
             |   ELSE users_tenants_rights.default_project_right
             | END,
             | default_key_right=CASE
             |   WHEN users_tenants_rights.default_key_right = 'READ' THEN excluded.default_key_right
             |   WHEN (users_tenants_rights.default_key_right = 'WRITE' AND excluded.default_key_right = 'ADMIN') THEN 'ADMIN'
             |   WHEN users_tenants_rights.default_key_right = 'ADMIN' THEN 'ADMIN'
             |   ELSE users_tenants_rights.default_key_right
             | END,
             | default_webhook_right=CASE
             |   WHEN users_tenants_rights.default_webhook_right = 'READ' THEN excluded.default_webhook_right
             |   WHEN (users_tenants_rights.default_webhook_right = 'WRITE' AND excluded.default_webhook_right = 'ADMIN') THEN 'ADMIN'
             |   WHEN users_tenants_rights.default_webhook_right = 'ADMIN' THEN 'ADMIN'
             |   ELSE users_tenants_rights.default_webhook_right
             | END
             |""".stripMargin
          }}
             |RETURNING username
             |""".stripMargin,
          List(
            usernames.toArray,
            tenant,
            right.level.toString.toUpperCase,
            right.defaultProjectRight.map(_.toString.toUpperCase).orNull,
            right.defaultWebhookRight.map(_.toString.toUpperCase).orNull,
            right.defaultKeyRight.map(_.toString.toUpperCase).orNull
          ),
          conn = conn
        ) { _ => Some(()) }
        .map(_ => Right())
        .recover(env.postgresql.pgErrorPartialFunction.andThen(err => Left(err)))
    )
  }

  private def createProjectRights(
      tenant: String,
      usernames: Set[String],
      rights: Set[UnscopedFlattenProjectRight],
      importConflictStrategy: ImportConflictStrategy,
      conn: Option[SqlConnection]
  ): FutureEither[Unit] = {
    if (rights.isEmpty || usernames.isEmpty) {
      FutureEither.success(())
    } else {
      val args =
        rights.toSeq.flatMap(right => usernames.map(username => (username, right)))
      FutureEither(
        env.postgresql
          .queryOne(
            s"""
               |INSERT INTO "${tenant}".users_projects_rights(username, project, level)
               |VALUES(unnest($$1::TEXT[]), unnest($$2::TEXT[]), unnest($$3::izanami.project_right_level[]))
               |${importConflictStrategy match {
              case Fail           => ""
              case MergeOverwrite =>
                s"""
                   | ON CONFLICT (username, project) DO UPDATE SET level=
                   | CASE
                   |  WHEN users_projects_rights.level = 'READ' THEN excluded.level
                   |  WHEN (users_projects_rights.level = 'UPDATE' AND (excluded.level = 'ADMIN' OR excluded.level = 'WRITE')) THEN excluded.level
                   |  WHEN (users_projects_rights.level = 'WRITE' AND excluded.level = 'ADMIN') THEN 'ADMIN'
                   |  WHEN users_projects_rights.level = 'ADMIN' THEN 'ADMIN'
                   |  ELSE users_projects_rights.level
                   | END
                   |""".stripMargin
              case Skip           => " ON CONFLICT(username, project) DO NOTHING"
              case Replace        => " ON CONFLICT (username, project) DO UPDATE SET level=EXCLUDED.level"
            }}
               |RETURNING username
               |""".stripMargin,
            List(
              args.map(_._1).toArray,
              args.map(t => t._2.name).toArray,
              args.map(t => t._2.level.toString.toUpperCase).toArray
            ),
            conn = conn
          ) { _ => Some(()) }
          .map(_ => Right())
          .recover(env.postgresql.pgErrorPartialFunction.andThen(err => Left(err)))
      )
    }
  }

  private def createKeyRights(
      tenant: String,
      usernames: Set[String],
      rights: Set[UnscopedFlattenKeyRight],
      importConflictStrategy: ImportConflictStrategy,
      conn: Option[SqlConnection]
  ): FutureEither[Unit] = {
    if (rights.isEmpty || usernames.isEmpty) {
      FutureEither.success(())
    } else {
      val localArgument =
        rights.toSeq.flatMap(right => usernames.map(username => (username, right)))
      FutureEither(
        env.postgresql
          .queryOne(
            s"""
               |INSERT INTO "${tenant}".users_keys_rights(username,apikey, level)
               |VALUES(unnest($$1::TEXT[]), unnest($$2::TEXT[]), unnest($$3::izanami.right_level[]))
               |${importConflictStrategy match {
              case Fail           => ""
              case MergeOverwrite =>
                s"""
                   | ON CONFLICT (username, apikey) DO UPDATE SET level=
                   | CASE
                   |  WHEN users_keys_rights.level = 'READ' THEN excluded.level
                   |  WHEN (users_keys_rights.level = 'WRITE' AND excluded.level = 'ADMIN') THEN 'ADMIN'
                   |  WHEN users_keys_rights.level = 'ADMIN' THEN 'ADMIN'
                   |  ELSE users_keys_rights.level
                   | END
                   |""".stripMargin
              case Skip           => " ON CONFLICT(username, apikey) DO NOTHING"
              case Replace        => " ON CONFLICT (username, apikey) DO UPDATE SET level=EXCLUDED.level"
            }}
               |RETURNING username
               |""".stripMargin,
            List(
              localArgument.map(_._1).toArray,
              localArgument.map(t => t._2.name).toArray,
              localArgument.map(t => t._2.level.toString.toUpperCase).toArray
            ),
            conn = conn
          ) { _ => Some(()) }
          .map(_ => Right())
          .recover(env.postgresql.pgErrorPartialFunction.andThen(err => Left(err)))
      )
    }
  }

  private def createWebhookRights(
      tenant: String,
      usernames: Set[String],
      rights: Set[UnscopedFlattenWebhookRight],
      importConflictStrategy: ImportConflictStrategy,
      conn: Option[SqlConnection]
  ): FutureEither[Unit] = {
    if (rights.isEmpty || usernames.isEmpty) {
      FutureEither.success(())
    } else {
      val localArgument =
        rights.toSeq.flatMap(right => usernames.map(username => (username, right)))
      FutureEither(
        env.postgresql
          .queryOne(
            s"""
               |INSERT INTO "${tenant}".users_webhooks_rights(username, webhook, level)
               |VALUES(unnest($$1::TEXT[]), unnest($$2::TEXT[]), unnest($$3::izanami.right_level[]))
               |${importConflictStrategy match {
              case Fail           => ""
              case MergeOverwrite =>
                s"""
                   | ON CONFLICT (username, webhook) DO UPDATE SET level=
                   | CASE
                   |  WHEN users_webhooks_rights.level = 'READ' THEN excluded.level
                   |  WHEN (users_webhooks_rights.level = 'WRITE' AND excluded.level = 'ADMIN') THEN 'ADMIN'
                   |  WHEN users_webhooks_rights.level = 'ADMIN' THEN 'ADMIN'
                   |  ELSE users_webhooks_rights.level
                   | END
                   |""".stripMargin
              case Skip           => " ON CONFLICT(username, webhook) DO NOTHING"
              case Replace        => " ON CONFLICT (username, webhook) DO UPDATE SET level=EXCLUDED.level"
            }}
               |RETURNING username
               |""".stripMargin,
            List(
              localArgument.map(_._1).toArray,
              localArgument.map(t => t._2.name).toArray,
              localArgument.map(t => t._2.level.toString.toUpperCase).toArray
            ),
            conn = conn
          ) { _ => Some(()) }
          .map(_ => Right())
          .recover(env.postgresql.pgErrorPartialFunction.andThen(err => Left(err)))
      )
    }
  }

  private def applyRightUpdateForTenant(
      usernames: Set[String],
      tenant: String,
      rights: UpsertTenantRights,
      importConflictStrategy: ImportConflictStrategy = Replace,
      conn: SqlConnection
  ): FutureEither[Unit] = {
    require(Tenant.isTenantValid(tenant))
    for (
      f   <- deleteProjectRights(
               tenant = tenant,
               usernames = usernames,
               projects = rights.removedProjectRights,
               conn = Some(conn)
             );
      _   <- deleteKeyRights(tenant = tenant, usernames = usernames, keys = rights.removedKeyRights, conn = Some(conn));
      _   <- deleteWebhookRights(
               tenant = tenant,
               usernames = usernames,
               webhooks = rights.removedWebhookRights,
               conn = Some(conn)
             );
      _   <- {
        val (default, r) = rights.addedTenantRight
          .map(r => (false, r))
          .getOrElse((true, UnscopedFlattenTenantRight(level = Read)))
        createTenantRight(
          tenant = tenant,
          usernames = usernames,
          right = r,
          importConflictStrategy = if (default) Skip else importConflictStrategy,
          conn = Some(conn)
        )
      };
      _   <- createProjectRights(
               tenant = tenant,
               usernames = usernames,
               rights = rights.addedProjectRights,
               importConflictStrategy = importConflictStrategy,
               conn = Some(conn)
             );
      _   <- createKeyRights(
               tenant = tenant,
               usernames = usernames,
               rights = rights.addedKeyRights,
               importConflictStrategy = importConflictStrategy,
               conn = Some(conn)
             );
      res <- createWebhookRights(
               tenant = tenant,
               usernames = usernames,
               rights = rights.addedWebhookRights,
               importConflictStrategy = importConflictStrategy,
               conn = Some(conn)
             )
    ) yield res
  }

  def updateUsersRightsForTenant(
      usernames: Set[String],
      tenant: String,
      diff: TenantRightDiff,
      conn: Option[SqlConnection] = None,
      importConflictStrategy: ImportConflictStrategy = Replace
  ): Future[Either[IzanamiError, Unit]] = {
    env.postgresql.executeInOptionalTransaction(
      conn,
      conn =>
        diff match {
          case Rights.DeleteTenantRights    => {
            deleteAllRightsForTenant(usernames, tenant, conn).value
          }
          case r: Rights.UpsertTenantRights => {
            applyRightUpdateForTenant(usernames, tenant, r, importConflictStrategy, conn).value
          }
        }
    )
  }

  def updateUserRights(
      name: String,
      rightDiff: RightDiff,
      conn: Option[SqlConnection] = None
  ): Future[Either[IzanamiError, Unit]] = {
    rightDiff.diff.foldLeft(Future.successful(Right()): Future[Either[IzanamiError, Unit]])((future, t) => {
      future.flatMap {
        case Left(err) => Left(err).future
        case Right(_)  => updateUserRightsForTenant(name, t._1, t._2, conn = conn)
      }
    })
  }

  def updateUsersRights(
      usernames: Set[String],
      rightDiff: RightDiff,
      conn: Option[SqlConnection] = None
  ): Future[Unit] = {
    rightDiff.diff.foldLeft(Future.successful())((future, t) => {
      future.map(_ => updateUsersRightsForTenant(usernames, t._1, t._2, conn = conn))
    })
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
            case Replace        => s" ON CONFLICT(username) DO UPDATE SET admin=excluded.admin"
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
          case Right(_)  => {
            users
              .foldLeft(Future.successful(Right()): Future[Either[IzanamiError, Unit]])((future, ur) => {
                val rightToCreate = Rights.compare(Rights.EMPTY, ur.rights)
                future.flatMap(_ => updateUserRights(ur.username, rightToCreate, conn = Some(conn)))
              })
          }
        }
      eventualErrorOrUnit
    }
  }

  def createUser(user: UserWithRights, conn: Option[SqlConnection] = None): Future[Either[IzanamiError, Unit]] = {
    env.postgresql.executeInOptionalTransaction(conn, conn => {
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
    require(Tenant.isTenantValid(tenant))
    env.postgresql
      .queryOne(
        s"""
           |SELECT u.username, w.name
           |FROM izanami.sessions s
           |LEFT JOIN izanami.users u ON u.username=s.username
           |LEFT JOIN izanami.users_tenants_rights utr ON u.username = utr.username AND utr.tenant=$$2
           |LEFT JOIN "${tenant}".webhooks w ON w.id=$$3
           |LEFT JOIN "${tenant}".users_webhooks_rights uwr ON u.username = uwr.username AND uwr.webhook=w.name
           |WHERE s.id=$$1
           |AND (
           |  u.admin=true
           |  OR utr.level='ADMIN'
           |  OR uwr.level=ANY($$4)
           |  OR utr.default_webhook_right=ANY($$4)
           |)
           |""".stripMargin,
        List(session, tenant, webhook, superiorOrEqualLevels(level).map(l => l.toString.toUpperCase).toArray)
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
    require(Tenant.isTenantValid(tenant))
    env.postgresql
      .queryOne(
        s"""
           |SELECT u.username
           |FROM izanami.sessions s
           |LEFT JOIN izanami.users u ON u.username=s.username
           |LEFT JOIN izanami.users_tenants_rights utr ON u.username = utr.username AND utr.tenant=$$2
           |LEFT JOIN "${tenant}".users_keys_rights ukr ON u.username = ukr.username AND ukr.apikey=$$3
           |WHERE s.id=$$1
           |AND (
           |  u.admin=true
           |  OR utr.level='ADMIN'
           |  OR ukr.level=ANY($$4)
           |  OR utr.default_key_right=ANY($$4)
           |)
           |""".stripMargin,
        List(session, tenant, key, superiorOrEqualLevels(level).map(l => l.toString.toUpperCase).toArray)
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
      level: ProjectRightLevel
  ): Future[Either[IzanamiError, Option[(String, UUID)]]] = {
    require(Tenant.isTenantValid(tenant))
    env.postgresql
      .queryOne(
        s"""
           |SELECT p.id, u.username
           |FROM "${tenant}".projects p
           |JOIN izanami.sessions s ON s.id=$$1
           |JOIN izanami.users u ON u.username=s.username
           |LEFT JOIN izanami.users_tenants_rights utr ON u.username = utr.username AND utr.tenant=$$2
           |LEFT JOIN "${tenant}".users_projects_rights upr ON u.username = upr.username AND upr.project=p.name
           |WHERE (
           |  u.admin=true
           |  OR utr.level='ADMIN'
           |  OR utr.default_project_right=ANY($$4)
           |  OR upr.level=ANY($$4)
           |)
           |AND ${projectIdOrName.fold(_ => s"p.id=$$3", _ => s"p.name=$$3")}
           |""".stripMargin,
        List(
          session,
          tenant,
          projectIdOrName.fold(identity, identity),
          ProjectRightLevel.superiorOrEqualLevels(level).map(l => l.toString.toUpperCase).toArray
        )
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
    require(Tenant.isTenantValid(tenant))
    val (keys, projects): (Set[String], Set[String]) = rights.partitionMap {
      case r: KeyRightUnit     => Left(r.name)
      case r: ProjectRightUnit => Right(r.name)
    }

    var index      = 2
    val subQueries = ArrayBuffer[String]()
    val params     = ArrayBuffer[Object](username)

    if (projects.nonEmpty) {
      subQueries.addOne(s"""
          |'projects',
          |array(
          |  select json_build_object('name', p.project, 'level', p.level)
          |  from "${tenant}".users_projects_rights p
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
           |  from "${tenant}".users_keys_rights k
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
           |SELECT utr.level, u.admin, utr.default_project_right, utr.default_key_right, json_build_object(
           |${subQueries.mkString(",")}
           |)::jsonb as rights
           |FROM izanami.users u
           |LEFT JOIN izanami.users_tenants_rights utr ON u.username = utr.username AND utr.tenant=$$${index}
           |WHERE u.username=$$1
           |""".stripMargin,
        params.toList
      ) { r =>
        {
          val admin               = r.getBoolean("admin")
          val actualTenantRight   = r.optRightLevel("level")
          val defaultProjectRight = r.optProjectRightLevel("default_project_right")
          val defaultKeyRight     = r.optRightLevel("default_key_right")
          val jsonRights          = r.optJsObject("rights")
          val userProjectRights   = jsonRights
            .flatMap(obj => {
              (obj \ "projects")
                .asOpt[Set[ProjectRightValue]](Reads.set(projectRightRead))
            })
            .getOrElse(Set())

          val userKeyRights = jsonRights
            .flatMap(obj => {
              (obj \ "keys")
                .asOpt[Set[KeyRightValue]](Reads.set(rightRead))
            })
            .getOrElse(Set())

          val hasRightForProjects = rights
            .collect { case p: ProjectRightUnit =>
              p
            }
            .forall(requestedRight => {
              userProjectRights.exists(actualRight => {
                actualRight.name == requestedRight.name &&
                ProjectRightLevel.superiorOrEqualLevels(requestedRight.rightLevel).contains(actualRight.level)
              }) || defaultProjectRight
                .exists(dpr => ProjectRightLevel.superiorOrEqualLevels(requestedRight.rightLevel).contains(dpr))
            })

          val hasRightForKeys = rights
            .collect { case k: KeyRightUnit =>
              k
            }
            .forall(requestedRight => {
              userKeyRights.exists(actualRight => {
                actualRight.name == requestedRight.name &&
                RightLevel.superiorOrEqualLevels(requestedRight.rightLevel).contains(actualRight.level)
              }) || defaultKeyRight
                .exists(dpr => RightLevel.superiorOrEqualLevels(requestedRight.rightLevel).contains(dpr))
            })

          Some(
            admin ||
            actualTenantRight.contains(RightLevel.Admin) ||
            tenantLevel
              .map(tLevel => {
                actualTenantRight.exists(extractedLevel =>
                  superiorOrEqualLevels(tLevel).contains(extractedLevel)
                ) && hasRightForProjects && hasRightForKeys
              })
              .getOrElse(hasRightForProjects && hasRightForKeys)
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
          val tenantRights =
            rights.asOpt[Map[String, RightLevel]](Reads.map(RightLevel.rightLevelReads)).getOrElse(Map())
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
    findUsers(Set(username)).map(_.headOption)
  }

  def findUsers(usernames: Set[String]): Future[Seq[UserWithTenantRights]] = {
    env.postgresql.queryAll(
      s"""
         |SELECT u.username, u.admin, u.email, u.user_type, u.default_tenant,
         |  coalesce((
         |    select json_object_agg(utr.tenant, utr.level)
         |    from izanami.users_tenants_rights utr
         |    where utr.username=u.username
         |  ), '{}'::json) as tenants
         |from izanami.users u
         |WHERE u.username=ANY($$1::TEXT[])""".stripMargin,
      List(usernames.toArray)
    ) { row =>
      {
        for (
          username <- row.optString("username");
          admin    <- row.optBoolean("admin");
          rights   <- row.optJsObject("tenants");
          userType <- row.optString("user_type").map(dbUserTypeToUserType)
        ) yield {
          val tenantRights =
            rights.asOpt[Map[String, RightLevel]](Reads.map(RightLevel.rightLevelReads)).getOrElse(Map())
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

  def findVisibleUsers(username: String): Future[Set[UserWithTenantRights]] = {
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

  def findUsersForWebhook(tenant: String, webhook: String): Future[List[SingleItemScopedUser]] = {
    require(Tenant.isTenantValid(tenant))
    env.postgresql.queryAll(
      s"""
         |SELECT
         |    wr.level as level,
         |    utr.level as tenant_right,
         |    u.admin,
         |    u.user_type,
         |    u.default_tenant,
         |    u.username,
         |    u.email,
         |    utr.default_webhook_right
         |FROM izanami.users u
         |LEFT JOIN "${tenant}".webhooks w ON w.id=$$2
         |LEFT JOIN "${tenant}".users_webhooks_rights wr ON wr.webhook=w.name AND wr.username=u.username
         |LEFT JOIN izanami.users_tenants_rights utr ON utr.username = u.username AND utr.tenant=$$1
         |WHERE wr.level IS NOT NULL
         |OR utr.level='ADMIN'
         |OR u.admin=true
         |OR utr.default_webhook_right IS NOT NULL
         |""".stripMargin,
      List(tenant, webhook)
    ) { r =>
      {
        r.optUser()
          .map(u => {
            val maybeTenantRight = r.optRightLevel("tenant_right")
            u.withWebhookOrKeyRight(
              r.optRightLevel("level").orNull,
              r.optRightLevel("default_webhook_right"),
              maybeTenantRight.contains(RightLevel.Admin)
            )
          })
      }
    }
  }

  def findUsersForProject(tenant: String, project: String): Future[List[ProjectScopedUser]] = {
    require(Tenant.isTenantValid(tenant))
    env.postgresql.queryAll(
      s"""
         |SELECT u.username, u.email, u.admin, u.user_type, u.default_tenant, r.level, tr.level as tenant_right, tr.default_project_right
         |FROM izanami.users u
         |LEFT JOIN "${tenant}".users_projects_rights r ON r.username = u.username AND r.project=$$1
         |LEFT JOIN izanami.users_tenants_rights tr ON tr.username = u.username AND tr.tenant=$$2
         |WHERE r.level IS NOT NULL
         |OR tr.level='ADMIN'
         |OR u.admin=true
         |OR tr.default_project_right IS NOT NULL
         |""".stripMargin,
      List(project, tenant)
    ) { r =>
      r.optUser()
        .map(u => {
          val maybeTenantRight         = r.optRightLevel("tenant_right")
          val maybeDefaultProjectRight = r.optProjectRightLevel("default_project_right")
          u.withProjectScopedRight(
            r.optProjectRightLevel("level").orNull,
            maybeDefaultProjectRight,
            maybeTenantRight.contains(RightLevel.Admin)
          )
        })
    }
  }

  def findUsersForKey(tenant: String, key: String): Future[List[SingleItemScopedUser]] = {
    require(Tenant.isTenantValid(tenant))
    env.postgresql.queryAll(
      s"""
         |SELECT
         |    kr.level as level,
         |    utr.level as tenant_right,
         |    u.admin,
         |    u.user_type,
         |    u.default_tenant,
         |    u.username,
         |    u.email,
         |    utr.default_key_right
         |FROM izanami.users u
         |LEFT JOIN "${tenant}".apikeys k ON k.name=$$2
         |LEFT JOIN "${tenant}".users_keys_rights kr ON kr.apikey=k.name AND kr.username=u.username
         |LEFT JOIN izanami.users_tenants_rights utr ON utr.username = u.username AND utr.tenant=$$1
         |WHERE kr.level IS NOT NULL
         |OR utr.level='ADMIN'
         |OR u.admin=true
         |OR utr.default_key_right IS NOT NULL
         |""".stripMargin,
      List(tenant, key)
    ) { r =>
      {
        r.optUser()
          .map(u => {
            val maybeTenantRight = r.optRightLevel("tenant_right")
            u.withWebhookOrKeyRight(
              r.optRightLevel("level").orNull,
              r.optRightLevel("default_key_right"),
              maybeTenantRight.contains(RightLevel.Admin)
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

  def findCompleteRights(user: String): Future[Option[UserWithRights]] = {
    findUser(user)
      .filter(_.isDefined)
      .flatMap(u => {
        val tenants = u.get.tenantRights.keys.toSet
        findCompleteRightsFromTenant(username = user, tenants = tenants)
      })
  }

  def findCompleteRightsFromTenant(username: String, tenants: Set[String]): Future[Option[UserWithRights]] = {
    require(tenants.forall(Tenant.isTenantValid))
    Future
      .sequence(
        tenants.map(tenant => {
          env.postgresql
            .queryOne(
              s"""
             |SELECT u.username, u.admin, u.email, u.user_type, u.default_tenant, json_build_object(
             |    'level', utr.level,
             |    'defaultProjectRight', utr.default_project_right,
             |    'defaultKeyRight', utr.default_key_right,
             |    'defaultWebhookRight', utr.default_webhook_right,
             |    'projects', COALESCE((select json_object_agg(p.project, json_build_object('level', p.level)) from "${tenant}".users_projects_rights p where p.username=$$1), '{}'),
             |    'keys', COALESCE((select json_object_agg(k.apikey, json_build_object('level', k.level)) from "${tenant}".users_keys_rights k where k.username=$$1), '{}'),
             |    'webhooks', COALESCE((select json_object_agg(w.webhook, json_build_object('level', w.level)) from "${tenant}".users_webhooks_rights w where w.username=$$1), '{}')
             |)::jsonb as rights
             |from izanami.users u
             |left join izanami.users_tenants_rights utr on (utr.username = u.username AND utr.tenant=$$2)
             |WHERE u.username=$$1;
             |""".stripMargin,
              List(username, tenant)
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

  def findSessionWithRightForTenant(
      session: String,
      tenant: String
  ): Future[Either[IzanamiError, UserWithCompleteRightForOneTenant]] = {
    require(Tenant.isTenantValid(tenant))
    env.postgresql
      .queryOne(
        s"""
         |SELECT u.username, u.admin, u.email, u.user_type, u.default_tenant,
         |    COALESCE((
         |      select (json_build_object('level', utr.level, 'defaultProjectRight', utr.default_project_right, 'projects', (
         |        select json_object_agg(p.project, json_build_object('level', p.level))
           |        from "${tenant}".users_projects_rights p
         |        where p.username=u.username
         |      )))
         |      from izanami.users_tenants_rights utr
         |      where utr.username=u.username
         |      and utr.tenant=$$2
         |    ), '{}'::json) as rights
         |from izanami.users u, izanami.sessions s
         |WHERE u.username=s.username
         |and s.id=$$1""".stripMargin,
        List(session, tenant)
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

case class ProjectRightValue(name: String, level: ProjectRightLevel)
case class KeyRightValue(name: String, level: RightLevel)

object userImplicits {
  implicit class UserRow(val row: Row) extends AnyVal {
    def optRights(): Option[TenantRight] = {
      row.optJsObject("rights").flatMap(js => js.asOpt[TenantRight])
    }

    def optRightLevel(field: String): Option[RightLevel] = {
      row.optString(field).map(dbRightToRight)
    }

    def optProjectRightLevel(field: String): Option[ProjectRightLevel] = {
      row.optString(field).map(dbProjectRightToProjectRight)
    }

    def optUserWithTenantRights(): Option[UserWithTenantRights] = {
      for (
        username <- row.optString("username");
        userType <- row.optString("user_type").map(dbUserTypeToUserType);
        admin    <- row.optBoolean("admin");
        rights   <- row.optJsObject("tenants")
      ) yield {
        val tenantRights = rights.asOpt[Map[String, RightLevel]](Reads.map(RightLevel.rightLevelReads)).getOrElse(Map())
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

  implicit val projectRightRead: Reads[ProjectRightValue] = { json =>
    {
      for (
        name  <- (json \ "name").asOpt[String];
        level <- (json \ "level").asOpt[String]
      ) yield {
        val right = dbProjectRightToProjectRight(level)
        JsSuccess(ProjectRightValue(name = name, level = right))
      }
    }.getOrElse(JsError("Failed to read rights"))
  }

  implicit val rightRead: Reads[KeyRightValue] = { json =>
    {
      for (
        name  <- (json \ "name").asOpt[String];
        level <- (json \ "level").asOpt[String]
      ) yield {
        val right = dbRightToRight(level)
        JsSuccess(KeyRightValue(name = name, level = right))
      }
    }.getOrElse(JsError("Failed to read rights"))
  }

  def dbRightToRight(dbRight: String): RightLevel = {
    dbRight match {
      case "ADMIN" => RightLevel.Admin
      case "READ"  => RightLevel.Read
      case "WRITE" => RightLevel.Write
    }
  }

  def dbProjectRightToProjectRight(dbRight: String): ProjectRightLevel = {
    dbRight match {
      case "ADMIN"  => ProjectRightLevel.Admin
      case "READ"   => ProjectRightLevel.Read
      case "UPDATE" => ProjectRightLevel.Update
      case "WRITE"  => ProjectRightLevel.Write
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
