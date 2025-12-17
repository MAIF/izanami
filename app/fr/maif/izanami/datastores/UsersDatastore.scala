package fr.maif.izanami.datastores

import fr.maif.izanami.datastores.userImplicits.{
  UserRow,
  dbUserTypeToUserType,
  projectRightRead,
  rightRead,
  webhookRightRead
}
import fr.maif.izanami.env.Env
import fr.maif.izanami.env.PostgresqlErrors.{
  RELATION_DOES_NOT_EXISTS,
  UNIQUE_VIOLATION
}
import fr.maif.izanami.env.pgimplicits.EnhancedRow
import fr.maif.izanami.errors.*
import fr.maif.izanami.models.*
import fr.maif.izanami.models.RightLevel.superiorOrEqualLevels
import fr.maif.izanami.models.Rights.*
import fr.maif.izanami.models.User.tenantRightReads
import fr.maif.izanami.services.*
import fr.maif.izanami.utils.syntax.implicits.{
  BetterFuture,
  BetterJsValue,
  BetterSyntax
}
import fr.maif.izanami.utils.{Datastore, Done, FutureEither}
import fr.maif.izanami.web.ImportController.*
import io.vertx.pgclient.PgException
import io.vertx.sqlclient.{Row, SqlConnection}
import org.apache.pekko.actor.Cancellable
import play.api.libs.json.*

import java.util.UUID
import scala.collection.mutable.ArrayBuffer
import scala.concurrent.Future
import scala.concurrent.duration.DurationLong

class UsersDatastore(val env: Env) extends Datastore {
  var sessionExpirationCancellation: Cancellable = Cancellable.alreadyCancelled
  var invitationExpirationCancellation: Cancellable =
    Cancellable.alreadyCancelled
  var passwordResetRequestCancellation: Cancellable =
    Cancellable.alreadyCancelled

  override def onStart(): Future[Unit] = {
    sessionExpirationCancellation = env.actorSystem.scheduler
      .scheduleAtFixedRate(
        env.houseKeepingStartDelayInSeconds.seconds,
        env.houseKeepingIntervalInSeconds.seconds
      )(() => deleteExpiredSessions(env.typedConfiguration.sessions.ttl))
    invitationExpirationCancellation = env.actorSystem.scheduler
      .scheduleAtFixedRate(
        env.houseKeepingStartDelayInSeconds.seconds,
        env.houseKeepingIntervalInSeconds.seconds
      )(() => deleteExpiredInvitations(env.typedConfiguration.invitations.ttl))
    passwordResetRequestCancellation = env.actorSystem.scheduler
      .scheduleAtFixedRate(
        env.houseKeepingStartDelayInSeconds.seconds,
        env.houseKeepingIntervalInSeconds.seconds
      )(() =>
        deleteExpiredPasswordResetRequests(
          env.typedConfiguration.passwordResetRequests.ttl
        )
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
      .queryOne(
        s"INSERT INTO izanami.sessions(username) VALUES ($$1) RETURNING id",
        List(username)
      ) { row =>
        row.optUUID("id")
      }
      .map(maybeUUID =>
        maybeUUID
          .getOrElse(throw new RuntimeException("Failed to create session"))
          .toString
      )
  }

  def deleteSession(sessionId: String): Future[Option[String]] = {
    env.postgresql
      .queryOne(
        s"DELETE FROM izanami.sessions WHERE id=$$1 RETURNING id",
        List(sessionId)
      ) { row =>
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

  def deleteExpiredInvitations(
      invitationsTtlInSeconds: Integer
  ): Future[Integer] = {
    env.postgresql
      .queryAll(
        s"DELETE FROM izanami.invitations WHERE EXTRACT(EPOCH FROM (NOW() - creation)) > $$1 returning id",
        List(invitationsTtlInSeconds)
      ) { _ =>
        Some(())
      }
      .map(_.size)
  }

  def deleteExpiredPasswordResetRequests(
      ttlInSeconds: Integer
  ): Future[Integer] = {
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
        List(
          updateRequest.name,
          updateRequest.email,
          name,
          updateRequest.defaultTenant.orNull
        )
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
    updateUsersRightsForTenant(
      Set(username),
      tenant,
      diff,
      conn,
      importConflictStrategy
    )
  }

  private def deleteAllRightsForTenant(
      usernames: Set[String],
      tenant: String,
      conn: SqlConnection
  ): FutureEither[Unit] = {
    require(Tenant.isTenantValid(tenant))
    for (
      _ <- FutureEither(
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
          .map(_ => Right(()))
          .recover(
            env.postgresql.pgErrorPartialFunction.andThen(err => Left(err))
          )
      );
      _ <- FutureEither(
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
          .map(_ => Right(()))
          .recover(
            env.postgresql.pgErrorPartialFunction.andThen(err => Left(err))
          )
      );
      _ <- FutureEither(
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
          .map(_ => Right(()))
          .recover(
            env.postgresql.pgErrorPartialFunction.andThen(err => Left(err))
          )
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
          .map(_ => Right(()))
          .recover(
            env.postgresql.pgErrorPartialFunction.andThen(err => Left(err))
          )
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
          .map(_ => Right(()))
          .recover(
            env.postgresql.pgErrorPartialFunction.andThen(err => Left(err))
          )
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
          .map(_ => Right(()))
          .recover(
            env.postgresql.pgErrorPartialFunction.andThen(err => Left(err))
          )
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
          .map(_ => Right(()))
          .recover(
            env.postgresql.pgErrorPartialFunction.andThen(err => Left(err))
          )
      )
    }
  }

  private def updateTenantDefaultRights(
      tenant: String,
      usernames: Set[String],
      addedDefaultProjectRight: Option[ProjectRightLevel],
      addedDefaultKeyRight: Option[RightLevel],
      addedDefaultWebhookRight: Option[RightLevel],
      removeProjectRight: Boolean,
      removeKeyRight: Boolean,
      removeWebhookRight: Boolean,
      conn: Option[SqlConnection]
  ): FutureEither[Done] = {
    var index = 2
    val args = ArrayBuffer[AnyRef](usernames.toArray, tenant)
    val parts = ArrayBuffer[String]()
    addedDefaultProjectRight
      .map(_.toString.toUpperCase)
      .orElse(if (removeProjectRight) Some(null) else Option.empty)
      .foreach(v => {
        index = index + 1
        args.addOne(v)
        parts.addOne(s"default_project_right=$$${index}")
      })

    addedDefaultKeyRight
      .map(_.toString.toUpperCase)
      .orElse(if (removeKeyRight) Some(null) else Option.empty)
      .foreach(v => {
        index = index + 1
        args.addOne(v)
        parts.addOne(s"default_key_right=$$${index}")
      })

    addedDefaultWebhookRight
      .map(_.toString.toUpperCase)
      .orElse(if (removeWebhookRight) Some(null) else Option.empty)
      .foreach(v => {
        index = index + 1
        args.addOne(v)
        parts.addOne(s"default_webhook_right=$$${index}")
      })

    if (parts.isEmpty) {
      FutureEither.success(Done.done())
    } else {
      FutureEither(
        env.postgresql
          .queryOne(
            s"""
               |UPDATE izanami.users_tenants_rights SET ${parts.mkString(",")}
               |WHERE username=ANY($$1::TEXT[]) AND tenant=$$2
               |RETURNING username
               |""".stripMargin,
            args.toList,
            conn = conn
          ) { _ => Some(()) }
          .map(_ => Right(Done.done()))
          .recover(
            env.postgresql.pgErrorPartialFunction.andThen(err => Left(err))
          )
      )
    }
  }

  private def upsertTenantRights(
      tenant: String,
      usernames: Set[String],
      right: UnscopedFlattenTenantRight,
      importConflictStrategy: ImportConflictStrategy,
      conn: Option[SqlConnection]
  ): FutureEither[Unit] = {
    val fields = ArrayBuffer[String]()
    val args = ArrayBuffer[Any](
      usernames.toArray,
      tenant,
      right.level.toString.toUpperCase
    )
    val conflictParts = ArrayBuffer[String]()
    val replaceParts = ArrayBuffer[String]()
    val mergeParts = ArrayBuffer[String]()
    right.defaultProjectRight.foreach(dr => {
      fields.addOne("default_project_right")
      args.addOne(dr.toString.toUpperCase())
      replaceParts.addOne(
        "default_project_right=EXCLUDED.default_project_right"
      )
      conflictParts.addOne("""default_project_right=CASE
          |    WHEN users_tenants_rights.default_project_right = 'READ' THEN excluded.default_project_right
          |    WHEN (users_projects_rights.default_project_right = 'UPDATE' AND (excluded.default_project_right = 'ADMIN' OR excluded.default_project_right = 'WRITE')) THEN excluded.default_project_right
          |    WHEN (users_tenants_rights.default_project_right = 'WRITE' AND excluded.default_project_right = 'ADMIN') THEN 'ADMIN'
          |    WHEN users_tenants_rights.default_project_right = 'ADMIN' THEN 'ADMIN'
          |    ELSE users_tenants_rights.default_project_right
          |  END""".stripMargin)
    })

    right.defaultKeyRight.foreach(dr => {
      replaceParts.addOne("default_key_right=EXCLUDED.default_key_right")
      fields.addOne("default_key_right")
      args.addOne(dr.toString.toUpperCase())
      conflictParts.addOne("""default_key_right=CASE
          |    WHEN users_tenants_rights.default_key_right = 'READ' THEN excluded.default_key_right
          |    WHEN (users_tenants_rights.default_key_right = 'WRITE' AND excluded.default_key_right = 'ADMIN') THEN 'ADMIN'
          |    WHEN users_tenants_rights.default_key_right = 'ADMIN' THEN 'ADMIN'
          |    ELSE users_tenants_rights.default_key_right
          |  END""".stripMargin)
    })
    right.defaultWebhookRight.foreach(dr => {
      replaceParts.addOne(
        "default_webhook_right=EXCLUDED.default_webhook_right"
      )
      fields.addOne("default_webhook_right")
      args.addOne(dr.toString.toUpperCase())
      conflictParts.addOne("""default_webhook_right=CASE
          |   WHEN users_tenants_rights.default_webhook_right = 'READ' THEN excluded.default_webhook_right
          |   WHEN (users_tenants_rights.default_webhook_right = 'WRITE' AND excluded.default_webhook_right = 'ADMIN') THEN 'ADMIN'
          |   WHEN users_tenants_rights.default_webhook_right = 'ADMIN' THEN 'ADMIN'
          |   ELSE users_tenants_rights.default_webhook_right
          | END""".stripMargin)
    })

    val fieldPart = if (fields.isEmpty) {
      ""
    } else {
      fields.mkString(",").prepended(',')
    }

    val fieldIndexPart = if (fields.isEmpty) {
      ""
    } else {
      fields.zipWithIndex
        .map { case (_, index) =>
          s"$$${index + 4}"
        }
        .mkString(",")
        .prepended(',')
    }

    val conflictPartStr = if (conflictParts.isEmpty) {
      ""
    } else {
      conflictParts.mkString(",").prepended(',')
    }

    val replacePartStr = if (replaceParts.isEmpty) {
      ""
    } else {
      replaceParts.mkString(",").prepended(',')
    }

    FutureEither(
      env.postgresql
        .queryOne(
          s"""
             |INSERT INTO izanami.users_tenants_rights(username, tenant, level $fieldPart)
             |VALUES(unnest($$1::TEXT[]), $$2, $$3 ${fieldIndexPart})
             |${importConflictStrategy match {
              case Skip    => " ON CONFLICT(username, tenant) DO NOTHING"
              case Fail    => ""
              case Replace =>
                s""" ON CONFLICT (username, tenant) DO UPDATE
             | SET level=EXCLUDED.level
             |   ${replacePartStr}
             |""".stripMargin
              case MergeOverwrite =>
                s"""
             | ON CONFLICT(username, tenant) DO UPDATE SET level = CASE
             |   WHEN users_tenants_rights.level = 'READ' THEN excluded.level
             |   WHEN (users_tenants_rights.level = 'WRITE' AND excluded.level = 'ADMIN') THEN 'ADMIN'
             |   WHEN users_tenants_rights.level = 'ADMIN' THEN 'ADMIN'
             |   ELSE users_tenants_rights.level
             | END
             | ${conflictPartStr}
             |""".stripMargin
            }}
             |RETURNING username
             |""".stripMargin,
          args.toList,
          conn = conn
        ) { _ => Some(()) }
        .map(_ => Right(()))
        .recover(
          env.postgresql.pgErrorPartialFunction.andThen(err => Left(err))
        )
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
        rights.toSeq.flatMap(right =>
          usernames.map(username => (username, right))
        )
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
                case Skip    => " ON CONFLICT(username, project) DO NOTHING"
                case Replace =>
                  " ON CONFLICT (username, project) DO UPDATE SET level=EXCLUDED.level"
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
          .map(_ => Right(()))
          .recover(
            env.postgresql.pgErrorPartialFunction.andThen(err => Left(err))
          )
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
        rights.toSeq.flatMap(right =>
          usernames.map(username => (username, right))
        )
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
                case Skip    => " ON CONFLICT(username, apikey) DO NOTHING"
                case Replace =>
                  " ON CONFLICT (username, apikey) DO UPDATE SET level=EXCLUDED.level"
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
          .map(_ => Right(()))
          .recover(
            env.postgresql.pgErrorPartialFunction.andThen(err => Left(err))
          )
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
        rights.toSeq.flatMap(right =>
          usernames.map(username => (username, right))
        )

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
                case Skip    => " ON CONFLICT(username, webhook) DO NOTHING"
                case Replace =>
                  " ON CONFLICT (username, webhook) DO UPDATE SET level=EXCLUDED.level"
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
          .map(_ => Right(()))
          .recover(
            env.postgresql.pgErrorPartialFunction.andThen(err => Left(err))
          )
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
      f <- deleteProjectRights(
        tenant = tenant,
        usernames = usernames,
        projects = rights.removedProjectRights,
        conn = Some(conn)
      );
      _ <- deleteKeyRights(
        tenant = tenant,
        usernames = usernames,
        keys = rights.removedKeyRights,
        conn = Some(conn)
      );
      _ <- deleteWebhookRights(
        tenant = tenant,
        usernames = usernames,
        webhooks = rights.removedWebhookRights,
        conn = Some(conn)
      );
      _ <- {
        rights.addedTenantRight.fold(
          updateTenantDefaultRights(
            tenant = tenant,
            usernames = usernames,
            addedDefaultProjectRight = rights.addedDefaultProjectRight,
            addedDefaultKeyRight = rights.addedDefaultKeyRight,
            addedDefaultWebhookRight = rights.addedDefaultWebhookRight,
            removeProjectRight = rights.removedDefaultProjectRight,
            removeKeyRight = rights.removedDefaultKeyRight,
            removeWebhookRight = rights.removedDefaultWebhookRight,
            conn = Some(conn)
          )
        )(level => {
          upsertTenantRights(
            tenant = tenant,
            usernames = usernames,
            right = UnscopedFlattenTenantRight(
              level = level,
              defaultProjectRight = rights.addedDefaultProjectRight,
              defaultKeyRight = rights.addedDefaultKeyRight,
              defaultWebhookRight = rights.addedDefaultWebhookRight
            ),
            importConflictStrategy = importConflictStrategy,
            conn = Some(conn)
          )
        })
      };
      _ <- createProjectRights(
        tenant = tenant,
        usernames = usernames,
        rights = rights.addedProjectRights,
        importConflictStrategy = importConflictStrategy,
        conn = Some(conn)
      );
      _ <- createKeyRights(
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
          case Rights.DeleteTenantRights => {
            deleteAllRightsForTenant(usernames, tenant, conn).value
          }
          case r: Rights.UpsertTenantRights => {
            applyRightUpdateForTenant(
              usernames,
              tenant,
              r,
              importConflictStrategy,
              conn
            ).value
          }
        }
    )
  }

  def logoutConnectedUsersWithRoleIn(roles: Set[String], conn: Option[SqlConnection] = Option.empty): FutureEither[Done] = {
    env.postgresql.executeInOptionalTransaction(conn, conn => {
      env.postgresql.queryAll(
        s"""
           |WITH users_to_logout AS (
           |  SELECT username
           |  FROM izanami.users u
           |  WHERE u.roles ?| ($$1::TEXT[])
           |)
           |DELETE FROM izanami.sessions s
           |WHERE s.username IN (SELECT username FROM users_to_logout)
           |""".stripMargin,
          List(roles.toArray)
        ){r => Some(())}
        .mapToFEither()
        .map(_ => Done.done())
    })
  }

  def updateUserRights(
      name: String,
      rightDiff: RightDiff,
      conn: Option[SqlConnection] = Option.empty
  ): Future[Either[IzanamiError, Unit]] = {
    env.postgresql.executeInOptionalTransaction(
      conn,
      conn => {
        rightDiff.admin
          .fold(Future.successful(Right(())))(admin => {
            env.datastores.users.updateUsersAdminStatus(
              Set(name),
              admin,
              conn = Some(conn)
            )
          })
          .flatMap(_ =>
            rightDiff.diff.foldLeft(
              Future.successful(Right(())): Future[Either[IzanamiError, Unit]]
            )((future, t) => {
              future.flatMap {
                case Left(err) => Left(err).future
                case Right(_)  =>
                  updateUserRightsForTenant(name, t._1, t._2, conn = Some(conn))
              }
            })
          )
      }
    )

  }

  def createUserWithConn(
      users: Seq[UserWithRights],
      conn: SqlConnection,
      importConflictStrategy: ImportConflictStrategy = Fail
  ): Future[Either[IzanamiError, Unit]] = {
    if (users.isEmpty) {
      Future.successful(Right(()))
    } else {
      val eventualErrorOrUnit: Future[Either[IzanamiError, Unit]] =
        env.postgresql
          .queryRaw(
            s"""INSERT INTO izanami.users (username, password, admin, email, user_type, legacy, roles)
             |values (unnest($$1::TEXT[]), unnest($$2::TEXT[]), unnest($$3::BOOLEAN[]), unnest($$4::TEXT[]), unnest($$5::izanami.user_type[]), unnest($$6::BOOLEAN[]), unnest($$7::JSONB[])) ${importConflictStrategy match {
                case Fail           => ""
                case MergeOverwrite =>
                  s" ON CONFLICT(username) DO UPDATE SET admin=COALESCE(users.admin, excluded.admin)"
                case Replace =>
                  s" ON CONFLICT(username) DO UPDATE SET admin=excluded.admin"
                case Skip => " ON CONFLICT(username) DO NOTHING"
              }} returning *""".stripMargin,
            List(
              users.map(_.username).toArray,
              users
                .map(user =>
                  Option(user.password)
                    .map(pwd => HashUtils.bcryptHash(pwd))
                    .orNull
                )
                .toArray,
              users.map(user => java.lang.Boolean.valueOf(user.admin)).toArray,
              users.map(user => Option(user.email).orNull).toArray,
              users.map(user => user.userType.toString).toArray,
              users.map(user => java.lang.Boolean.valueOf(user.legacy)).toArray,
              users
                .map(user => {
                  JsArray(user.roles.map(JsString(_)).toIndexedSeq).vertxJsValue
                })
                .toArray
            ),
            conn = Some(conn)
          ) { _ => Right(()) }
          .recover(
            env.postgresql.pgErrorPartialFunction.andThen(err => Left(err))
          )
          .flatMap {
            case Left(err) => Future.successful(Left(err))
            case Right(_)  => {
              users
                .foldLeft(
                  Future
                    .successful(Right(())): Future[Either[IzanamiError, Unit]]
                )((future, ur) => {
                  val rightToCreate = Rights.compare(
                    Rights.EMPTY,
                    ur.rights,
                    baseAdmin = ur.admin,
                    admin = Option.empty
                  )
                  future.flatMap(_ =>
                    updateUserRights(
                      ur.username,
                      rightToCreate,
                      conn = Some(conn)
                    )
                  )
                })
            }
          }
      eventualErrorOrUnit
    }
  }

  def createUser(
      user: UserWithRights,
      conn: Option[SqlConnection] = None
  ): Future[Either[IzanamiError, Unit]] = {
    env.postgresql.executeInOptionalTransaction(
      conn,
      conn => {
        createUserWithConn(Seq(user), conn)
      }
    )
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

  def hasRightFor(
                   tenant: String,
                   userIdentication: UserIdentification,
                   rights: Set[RightUnit],
                   tenantLevel: Option[RightLevel] = Option.empty
  ): Future[Option[RightCheckConfirmation]] = {
    require(Tenant.isTenantValid(tenant))
    val webhooks = ArrayBuffer[WebhookRightUnit]()
    val keys = ArrayBuffer[KeyRightUnit]()
    val projects = ArrayBuffer[ProjectRightUnit]()

    rights.foreach {
      case p@ProjectRightUnit(name, rightLevel) => {
        projects.addOne(p)
      }
      case k@KeyRightUnit(name, rightLevel) => {
        keys.addOne(k)
      }
      case w@WebhookRightUnit(name, rightLevel) => {
        webhooks.addOne(w)
      }
    }

    var index = 2
    val subQueries = ArrayBuffer[String]()
    val params = ArrayBuffer[Object](userIdentication.identification)

    if (projects.nonEmpty) {
      val projectNames = ArrayBuffer[String]()
      val projectIds = ArrayBuffer[UUID]()

      projects.map(_.project).foreach {
        case ProjectIdIdentification(id) => projectIds.addOne(id)
        case ProjectNameIdentification(name) => projectNames.addOne(name)
      }

      subQueries.addOne(
        s"""
           |'projectIds',
           |array(
           |  select json_build_object('name', p.name, 'id', p.id)
           |  from "${tenant}".projects p
           |  where p.name=ANY($$${index})
           |  or p.id=ANY($$${index+1}::UUID[])
           |)
           |""".stripMargin
      )

      subQueries.addOne(s"""
          |'projects',
          |array(
          |  select json_build_object('name', p.project, 'level', p.level, 'id', pr.id)
          |  from "${tenant}".users_projects_rights p
          |  left join "${tenant}".projects pr on p.project =pr.name
          |  where p.username=u.username
          |  and (p.project=ANY($$${index})
          |  or pr.id=ANY($$${index+1}::UUID[]))
          |)
          |""".stripMargin)
      index = index + 2
      params.addOne(projectNames.toArray)
      params.addOne(projectIds.toArray)
    }
    if (keys.nonEmpty) {
      subQueries.addOne(
        s"""
           |'keys',
           |array(
           |  select json_build_object('name', kr.apikey, 'level', kr.level)
           |  from "${tenant}".users_keys_rights kr
           |  where kr.username=u.username
           |  and kr.apikey=ANY($$${index})
           |)
           |""".stripMargin
      )
      index = index + 1

      params.addOne(keys.map(_.key).toArray)
    }
    if (webhooks.nonEmpty) {
      val webhookNames = ArrayBuffer[String]()
      val webhookIds = ArrayBuffer[UUID]()

      webhooks.map(_.webhook).foreach {
        case WebhookNameIdentification(name) => webhookNames.addOne(name)
        case WebhookIdIdentification(id) => webhookIds.addOne(id)
      }

      subQueries.addOne(
        s"""
           |'webhookIds',
           |array(
           |  select json_build_object('name', w.name, 'id', w.id)
           |  from "${tenant}".webhooks w
           |  where w.name=ANY($$${index})
           |  or w.id=ANY($$${index + 1}::UUID[])
           |)
           |""".stripMargin
      )

      subQueries.addOne(
        s"""
           |'webhooks',
           |array(
           |  select json_build_object('name', wr.webhook, 'level', wr.level, 'id', w.id)
           |  from "${tenant}".users_webhooks_rights wr
           |  left join "${tenant}".webhooks w on wr.webhook=w.name
           |  where wr.username=u.username
           |  and (
           |    w.username=ANY($$${index}) OR w.id = ANY($$${index+1}::UUID[])
           |  )
           |)
           |""".stripMargin
      )
      index = index + 2
      params.addOne(webhookNames.toArray)
      params.addOne(webhookIds.toArray)
    }

    params.addOne(tenant)

    val fromPart = userIdentication match {
      case UsernameIdentification(username) =>
        """
          |FROM izanami.users u
          |""".stripMargin
      case SessionIdentification(sessionId) =>
        """
          |FROM izanami.sessions s
          |LEFT JOIN izanami.users u ON u.username = s.username
          |""".stripMargin
    }

    val wherePart = userIdentication match {
      case UsernameIdentification(username) => "WHERE u.username=$1"
      case SessionIdentification(sessionId) => "WHERE s.id=$1::UUID"
    }

    env.postgresql
      .queryOne(
        s"""
           |SELECT utr.level, u.username, u.email, u.user_type, u.admin, u.default_tenant, u.roles, utr.default_project_right, utr.default_key_right, json_build_object(
           |${subQueries.mkString(",")}
           |)::jsonb as rights
           |${fromPart}
           |LEFT JOIN izanami.users_tenants_rights utr ON u.username = utr.username AND utr.tenant=$$${index}
           |${wherePart}
           |""".stripMargin,
        params.toList
      ) { r =>
        {
          val actualTenantRight = r.optRightLevel("level")
          val defaultProjectRight =
            r.optProjectRightLevel("default_project_right")
          val defaultKeyRight = r.optRightLevel("default_key_right")
          val defaultWebhookRight = r.optRightLevel("default_webhook_right")
          val jsonRights = r.optJsObject("rights")
          val userProjectRights = jsonRights
            .flatMap(obj => {
              (obj \ "projects")
                .asOpt[Set[ProjectRightValue]](Reads.set(projectRightRead))
            })
            .getOrElse(Set())

          def parseToNameById(json: JsArray): Option[Map[UUID, String]] = {
            json.asOpt[Seq[Map[String, String]]]
              .map(data => {
                val r = data.map(obj => {
                  for(
                    idStr <- obj.get("id");
                    id = UUID.fromString(idStr);
                    name <- obj.get("name")
                  ) yield (id, name)
                })

                r.collect {
                  case Some(value) => value
                }.toMap
              })
          }

          val projectNameById = jsonRights.flatMap(jsObject => {
            (jsObject \ "projectIds").asOpt[JsArray]
          }).flatMap(json => parseToNameById(json))
            .getOrElse(Map())

          val keyNameById = jsonRights.flatMap(jsObject => {
            (jsObject \ "keyIds").asOpt[JsArray]
          }).flatMap(json => parseToNameById(json))
            .getOrElse(Map())

          val webhookNameById = jsonRights.flatMap(jsObject => {
            (jsObject \ "webhookIds").asOpt[JsArray]
          }).flatMap(json => parseToNameById(json))
            .getOrElse(Map())

          val userKeyRights = jsonRights
            .flatMap(obj => {
              (obj \ "keys")
                .asOpt[Set[KeyRightValue]](Reads.set(rightRead))
            })
            .getOrElse(Set())

          val userWebhookRights = jsonRights
            .flatMap(obj => {
              (obj \ "webhooks")
                .asOpt[Set[WebhookRightValue]](Reads.set(webhookRightRead))
            })
            .getOrElse(Set())

          val hasRightForProjects = rights
            .collect { case p: ProjectRightUnit =>
              p
            }
            .forall(requestedRight => {
              userProjectRights.exists(actualRight => {
                (requestedRight.project match {
                  case ProjectIdIdentification(identification) => actualRight.id == identification
                  case ProjectNameIdentification(identification) => actualRight.name == identification
                }) &&
                ProjectRightLevel
                  .superiorOrEqualLevels(requestedRight.rightLevel)
                  .contains(actualRight.level)
              }) || defaultProjectRight
                .exists(dpr =>
                  ProjectRightLevel
                    .superiorOrEqualLevels(requestedRight.rightLevel)
                    .contains(dpr)
                )
            })

          val hasRightForKeys = rights
            .collect { case k: KeyRightUnit =>
              k
            }
            .forall(requestedRight => {
              userKeyRights.exists(actualRight => {
                actualRight.name == requestedRight.key &&
                RightLevel
                  .superiorOrEqualLevels(requestedRight.rightLevel)
                  .contains(actualRight.level)
              }) || defaultKeyRight
                .exists(dpr =>
                  RightLevel
                    .superiorOrEqualLevels(requestedRight.rightLevel)
                    .contains(dpr)
                )
            })

          val hasRightForWebhooks = rights
            .collect { case k: WebhookRightUnit =>
              k
            }
            .forall(requestedRight => {
              userWebhookRights.exists(actualRight => {
                (requestedRight.webhook match {
                  case WebhookNameIdentification(name) => name == actualRight.name
                  case WebhookIdIdentification(id) => id == actualRight.id
                }) &&
                  RightLevel
                    .superiorOrEqualLevels(requestedRight.rightLevel)
                    .contains(actualRight.level)
              }) || defaultWebhookRight
                .exists(dpr =>
                  RightLevel
                    .superiorOrEqualLevels(requestedRight.rightLevel)
                    .contains(dpr)
                )
            })

          r.optUser()
            .filter(u => {
              u.admin ||
                actualTenantRight.contains(RightLevel.Admin) ||
                tenantLevel
                  .map(tLevel => {
                    actualTenantRight.exists(extractedLevel =>
                      superiorOrEqualLevels(tLevel).contains(extractedLevel)
                    ) && hasRightForProjects && hasRightForKeys && hasRightForWebhooks
                  })
                  .getOrElse(hasRightForProjects && hasRightForKeys && hasRightForWebhooks)
            }).map(u => RightCheckConfirmation(user = u, projectNameById = projectNameById, webhookNameById = webhookNameById))
        }
      }
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
        List(
          email,
          java.lang.Boolean.valueOf(admin),
          User.rightWrite.writes(rights).toString(),
          inviter
        )
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
          id <- row.optUUID("id");
          admin <- row.optBoolean("admin");
          jsonRights <- row.optJsObject("rights");
          rights <- User.rightsReads.reads(jsonRights).asOpt
        )
          yield UserInvitation(
            email = row.optString("email").orNull,
            admin = admin,
            rights = rights,
            id = id.toString
          )
      }
    }
  }

  def isUserValid(username: String, password: String): Future[Option[User]] = {
    // TODO handle lecgacy users & test it !
    env.postgresql
      .queryOne(
        s"""SELECT username, password, admin, email, user_type, legacy, roles FROM izanami.users WHERE username=$$1""",
        List(username)
      ) { row =>
        row
          .optString("password")
          .filter(hashed => {
            row.optBoolean("legacy").exists {
              case true =>
                HashUtils.bcryptCheck(HashUtils.hexSha512(password), hashed)
              case false => HashUtils.bcryptCheck(password, hashed)
            }
          })
          .flatMap(_ =>
            row
              .optUser()
              .map(u =>
                u.copy(legacy = row.optBoolean("legacy").getOrElse(false))
              )
          )
      }
  }

  def findSessionWithTenantRights(
      session: String
  ): Future[Option[UserWithTenantRights]] = {
    env.postgresql.queryOne(
      s"""
         |SELECT u.username, u.admin, u.email, u.default_tenant, u.user_type, u.roles,
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
          admin <- row.optBoolean("admin");
          rights <- row.optJsObject("tenants");
          userType <- row.optString("user_type").map(dbUserTypeToUserType)
        ) yield {
          val tenantRights =
            rights
              .asOpt[Map[String, RightLevel]](
                Reads.map(RightLevel.rightLevelReads)
              )
              .getOrElse(Map())
          UserWithTenantRights(
            username = username,
            email = row.optString("email").orNull,
            password = null,
            admin = admin,
            tenantRights = tenantRights,
            userType = userType,
            roles = row
              .optJsArray("roles")
              .map(arr =>
                arr.value
                  .map(s => {
                    s.as[JsString].value
                  })
                  .toSet
              )
              .getOrElse(Set())
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
      .map(
        _.getOrElse(
          throw new RuntimeException("Failed to create password request")
        )
      )
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
         |SELECT username, email, user_type, admin, default_tenant, roles FROM izanami.users WHERE email=$$1
         |""".stripMargin,
      List(email)
    ) { r => r.optUser() }
  }

  def findUser(username: String): Future[Option[UserWithTenantRights]] = {
    findUsers(Set(username)).map(_.headOption)
  }

  def findUsers(usernames: Set[String], roles: Option[Set[String]] = None): Future[Seq[UserWithTenantRights]] = {
    env.postgresql.queryAll(
      s"""
         |SELECT u.username, u.admin, u.email, u.user_type, u.default_tenant, u.roles,
         |  coalesce((
         |    select json_object_agg(utr.tenant, utr.level)
         |    from izanami.users_tenants_rights utr
         |    where utr.username=u.username
         |  ), '{}'::json) as tenants
         |from izanami.users u
         |WHERE u.username=ANY($$1::TEXT[])
         |${roles.map(rs => s"AND u.roles=ANY($$2::TEXT[])").getOrElse("")}""".stripMargin,
      roles.map(rs => List(usernames.toArray, rs.toArray)).getOrElse(List(usernames.toArray))
    ) { row => row.optUserWithTenantRights() }
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
         |SELECT u.username, u.admin, u.email, u.user_type, u.default_tenant, u.roles,
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

  def findUsersForTenant(
      tenant: String
  ): Future[List[UserWithSingleLevelRight]] = {
    env.postgresql.queryAll(
      s"""
         |SELECT u.username, u.email, u.admin, u.user_type, u.default_tenant, r.level, u.roles
         |FROM izanami.users u
         |LEFT JOIN izanami.users_tenants_rights r ON r.username = u.username AND r.tenant=$$1
         |WHERE r.level IS NOT NULL
         |OR u.admin=true
         |""".stripMargin,
      List(tenant)
    ) { r =>
      r.optUser()
        .map(u => u.withSingleLevelRight(r.optRightLevel("level").orNull))
    }
  }

  def findUsersForWebhook(
      tenant: String,
      webhook: String
  ): Future[List[SingleItemScopedUser]] = {
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
         |    u.roles,
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

  def findUsersForProject(
      tenant: String,
      project: String
  ): Future[List[ProjectScopedUser]] = {
    require(Tenant.isTenantValid(tenant))
    env.postgresql.queryAll(
      s"""
         |SELECT u.username, u.email, u.admin, u.user_type, u.default_tenant, u.roles, r.level, tr.level as tenant_right, tr.default_project_right
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
          val maybeTenantRight = r.optRightLevel("tenant_right")
          val maybeDefaultProjectRight =
            r.optProjectRightLevel("default_project_right")
          u.withProjectScopedRight(
            r.optProjectRightLevel("level").orNull,
            maybeDefaultProjectRight,
            maybeTenantRight.contains(RightLevel.Admin)
          )
        })
    }
  }

  def findUsersForKey(
      tenant: String,
      key: String
  ): Future[List[SingleItemScopedUser]] = {
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
         |    u.roles,
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

  def findSessionWithCompleteRights(
      session: String
  ): Future[Option[UserWithRights]] = {
    findSessionWithTenantRights(session).flatMap {
      case Some(user) if user.tenantRights.nonEmpty => {
        val tenants = user.tenantRights.keys.toSet
        findCompleteRightsFromTenant(user.username, tenants)
      }
      case Some(user) => Some(user.withRights(Rights.EMPTY)).future
      case _          => Future.successful(None)
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

  def findCompleteRightsFromTenant(
      username: String,
      tenants: Set[String]
  ): Future[Option[UserWithRights]] =
    findCompleteRightsFromTenantForUsers(Set(username), tenants).map(m =>
      m.get(username)
    )

  def findCompleteRightsFromTenantForUsers(
      users: Set[String],
      tenants: Set[String]
  ): Future[Map[String, UserWithRights]] = {
    require(tenants.forall(Tenant.isTenantValid))
    Future
      .sequence(
        tenants.map(tenant => {
          env.postgresql
            .queryAll(
              s"""
                 |SELECT u.username, u.admin, u.email, u.user_type, u.default_tenant, u.roles, json_build_object(
                 |    'level', utr.level,
                 |    'defaultProjectRight', utr.default_project_right,
                 |    'defaultKeyRight', utr.default_key_right,
                 |    'defaultWebhookRight', utr.default_webhook_right,
                 |    'projects', COALESCE((select json_object_agg(p.project, json_build_object('level', p.level)) from "${tenant}".users_projects_rights p where p.username=u.username), '{}'),
                 |    'keys', COALESCE((select json_object_agg(k.apikey, json_build_object('level', k.level)) from "${tenant}".users_keys_rights k where k.username=u.username), '{}'),
                 |    'webhooks', COALESCE((select json_object_agg(w.webhook, json_build_object('level', w.level)) from "${tenant}".users_webhooks_rights w where w.username=u.username), '{}')
                 |)::jsonb as rights
                 |from izanami.users u
                 |left join izanami.users_tenants_rights utr on (utr.username = u.username AND utr.tenant=$$2)
                 |WHERE u.username=ANY($$1::TEXT[]);
                 |""".stripMargin,
              List(users.toArray, tenant)
            ) { row => row.optUserWithRights() }
            .map(fuser => fuser.map(u => (tenant, u.username, u)))
        })
      )
      .map(_.flatten)
      .map(users => {
        val rightsByUser = users.groupMap(_._2)(u => (u._1, u._3))

        rightsByUser
          .map { (user, tenantByRights) =>
            {
              val baseUser = tenantByRights.headOption
                .map(_._2)
                .map(u =>
                  UserWithRights(
                    username = u.username,
                    email = u.email,
                    password = u.password,
                    admin = u.admin,
                    userType = u.userType,
                    rights = Rights.EMPTY,
                    defaultTenant = u.defaultTenant,
                    legacy = u.legacy,
                    roles = u.roles
                  )
                )

              val userWithTenantRights = baseUser.map(u =>
                tenantByRights.foldLeft(u)((user, tenantRights) => {
                  user.applyOnWithOpt(tenantRights._2.tenantRight)((u, tr) =>
                    u.addTenantRights(tenantRights._1, tr)
                  )
                })
              )

              userWithTenantRights
            }
          }
          .collect { case Some(u) =>
            (u.username, u)
          }
          .toMap
      })
  }

  def findUserWithCompleteRights(
      username: String
  ): Future[Option[UserWithRights]] = {
    findUser(username).flatMap {
      case Some(user) if user.tenantRights.nonEmpty => {
        val tenants = user.tenantRights.keys.toSet
        findCompleteRightsFromTenant(username, tenants)
      }
      case Some(user) => Some(user.withRights(Rights.EMPTY)).future
      case _          => Future.successful(None)
    }
  }

  def addUserRightsToTenant(
      tenant: String,
      users: Seq[(String, RightLevel)]
  ): Future[Unit] = {
    env.postgresql
      .queryOne(
        s"""
         |INSERT INTO izanami.users_tenants_rights (tenant, username, level)
         |VALUES($$1, unnest($$2::TEXT[]), unnest($$3::izanami.right_level[]))
         |ON CONFLICT (username, tenant) DO NOTHING
         |""".stripMargin,
        List(
          tenant,
          users.map(_._1).toArray,
          users.map(_._2.toString.toUpperCase).toArray
        )
      ) { r => Some(()) }
      .map(_ => ())
  }

  def findUserWithRightForTenant(
      username: String,
      tenant: String
  ): Future[Either[IzanamiError, UserWithCompleteRightForOneTenant]] = {
    require(Tenant.isTenantValid(tenant))
    env.postgresql
      .queryOne(
        s"""
           |SELECT u.username, u.admin, u.email, u.user_type, u.default_tenant, u.roles,
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
           |from izanami.users u
           |WHERE u.username=$$1
           |""".stripMargin,
        List(username, tenant)
      ) { row =>
        {
          for (
            username <- row.optString("username");
            userType <- row.optString("user_type").map(dbUserTypeToUserType);
            admin <- row.optBoolean("admin");
            right <- row.optJsObject("rights")
          ) yield {
            val parsedRights = right.asOpt[TenantRight]
            UserWithCompleteRightForOneTenant(
              username = username,
              email = row.optString("email").orNull,
              password = null,
              admin = admin,
              tenantRight = parsedRights,
              userType = userType,
              roles = row
                .optJsArray("roles")
                .map(arr =>
                  arr.value
                    .map(s => {
                      s.as[JsString].value
                    })
                    .toSet
                )
                .getOrElse(Set())
            )
          }
        }
      }
      .map(o => o.toRight(UserNotFound(username)))
      .recover {
        case f: PgException if f.getSqlState == RELATION_DOES_NOT_EXISTS =>
          Left(TenantDoesNotExists(tenant))
        case _ => Left(InternalServerError())
      }
  }

  def findSessionWithRightForTenant(
      session: String,
      tenant: String
  ): Future[Either[IzanamiError, UserWithCompleteRightForOneTenant]] = {
    require(Tenant.isTenantValid(tenant))
    env.postgresql
      .queryOne(
        s"""
         |SELECT u.username, u.admin, u.email, u.user_type, u.default_tenant, u.roles,
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
            admin <- row.optBoolean("admin");
            right <- row.optJsObject("rights")
          ) yield {
            val parsedRights = right.asOpt[TenantRight]
            UserWithCompleteRightForOneTenant(
              username = username,
              email = row.optString("email").orNull,
              password = null,
              admin = admin,
              tenantRight = parsedRights,
              userType = userType,
              roles = row
                .optJsArray("roles")
                .map(arr =>
                  arr.value
                    .map(s => {
                      s.as[JsString].value
                    })
                    .toSet
                )
                .getOrElse(Set())
            )
          }
        }
      }
      .map(o => o.toRight(SessionNotFound(session)))
      .recover {
        case f: PgException if f.getSqlState == RELATION_DOES_NOT_EXISTS =>
          Left(TenantDoesNotExists(tenant))
        case _ => Left(InternalServerError())
      }
  }
}

sealed trait UserIdentification {
  val identification: String
}
case class UsernameIdentification(override val identification: String) extends UserIdentification
case class SessionIdentification(override val identification: String) extends UserIdentification

case class ProjectRightValue(name: String, id: UUID, level: ProjectRightLevel)
case class KeyRightValue(name: String, level: RightLevel)
case class WebhookRightValue(name: String, level: RightLevel, id: UUID)

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
        admin <- row.optBoolean("admin");
        rights <- row.optJsObject("tenants")
      ) yield {
        val tenantRights = rights
          .asOpt[Map[String, RightLevel]](Reads.map(RightLevel.rightLevelReads))
          .getOrElse(Map())
        UserWithTenantRights(
          username = username,
          email = row.optString("email").orNull,
          password = null,
          admin = admin,
          tenantRights = tenantRights,
          userType = userType,
          defaultTenant = row.optString("default_tenant"),
          roles = row
            .optJsArray("roles")
            .map(arr =>
              arr.value
                .map(s => {
                  s.as[JsString].value
                })
                .toSet
            )
            .getOrElse(Set())
        )
      }
    }

    def optUser(): Option[User] = {
      for (
        username <- row.optString("username");
        userType <- row.optString("user_type").map(dbUserTypeToUserType);
        admin <- row.optBoolean("admin")
      )
        yield User(
          username = username,
          email = row.optString("email").orNull,
          password = null,
          admin = admin,
          userType = userType,
          defaultTenant = row.optString("default_tenant"),
          roles = row
            .optJsArray("roles")
            .map(arr =>
              arr.value
                .map(s => {
                  s.as[JsString].value
                })
                .toSet
            )
            .getOrElse(Set())
        )
    }

    def optUserWithRights(): Option[UserWithCompleteRightForOneTenant] = {
      for (
        username <- row.optString("username");
        userType <- row.optString("user_type").map(dbUserTypeToUserType);
        admin <- row.optBoolean("admin")
      )
        yield {
          UserWithCompleteRightForOneTenant(
            username = username,
            email = row.optString("email").orNull,
            password = null,
            admin = admin,
            tenantRight = row.optRights(),
            userType = userType,
            defaultTenant = row.optString("default_tenant"),
            roles = row
              .optJsArray("roles")
              .map(arr =>
                arr.value
                  .map(s => {
                    s.as[JsString].value
                  })
                  .toSet
              )
              .getOrElse(Set())
          )
        }
    }
  }

  implicit val projectRightRead: Reads[ProjectRightValue] = { json =>
    {
      for (
        name <- (json \ "name").asOpt[String];
        level <- (json \ "level").asOpt[String];
        id <- (json \ "id").asOpt[UUID]
      ) yield {
        val right = dbProjectRightToProjectRight(level)
        JsSuccess(ProjectRightValue(name = name, level = right, id=id))
      }
    }.getOrElse(JsError("Failed to read rights"))
  }

  implicit val rightRead: Reads[KeyRightValue] = { json =>
    {
      for (
        name <- (json \ "name").asOpt[String];
        level <- (json \ "level").asOpt[String]
      ) yield {
        val right = dbRightToRight(level)
        JsSuccess(KeyRightValue(name = name, level = right))
      }
    }.getOrElse(JsError("Failed to read rights"))
  }

  implicit val webhookRightRead: Reads[WebhookRightValue] = { json => {
    for (
      name <- (json \ "name").asOpt[String];
      level <- (json \ "level").asOpt[String];
      id <- (json \ "id").asOpt[UUID]
    ) yield {
      val right = dbRightToRight(level)
      JsSuccess(WebhookRightValue(name = name, level = right, id = id))
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
