package fr.maif.izanami.datastores

import com.zaxxer.hikari.HikariConfig
import com.zaxxer.hikari.HikariDataSource
import fr.maif.izanami.datastores.tenantImplicits.TenantRow
import fr.maif.izanami.env.Env
import fr.maif.izanami.env.PostgresqlErrors.UNIQUE_VIOLATION
import fr.maif.izanami.env.pgimplicits.EnhancedRow
import fr.maif.izanami.errors.InternalServerError
import fr.maif.izanami.errors.IzanamiError
import fr.maif.izanami.errors.TenantAlreadyExists
import fr.maif.izanami.errors.TenantDoesNotExists
import fr.maif.izanami.events.EventOrigin.NormalOrigin
import fr.maif.izanami.events.EventService.IZANAMI_CHANNEL
import fr.maif.izanami.events.SourceTenantCreated
import fr.maif.izanami.events.SourceTenantDeleted
import fr.maif.izanami.models.RightLevels
import fr.maif.izanami.models.Tenant
import fr.maif.izanami.models.TenantCreationRequest
import fr.maif.izanami.utils.Datastore
import fr.maif.izanami.utils.syntax.implicits.BetterJsValue
import fr.maif.izanami.utils.syntax.implicits.BetterSyntax
import fr.maif.izanami.web.ImportFailure
import fr.maif.izanami.web.ImportPending
import fr.maif.izanami.web.ImportState
import fr.maif.izanami.web.ImportState.importFailureWrites
import fr.maif.izanami.web.ImportState.importResultReads
import fr.maif.izanami.web.ImportState.importSuccessWrites
import fr.maif.izanami.web.ImportSuccess
import fr.maif.izanami.web.UserInformation
import io.vertx.pgclient.PgException
import io.vertx.sqlclient.Row
import org.flywaydb.core.Flyway
import play.api.libs.json.Json

import java.util.UUID
import scala.concurrent.Future

class TenantsDatastore(val env: Env) extends Datastore {
  def deleteImportStatus(id: UUID): Future[Unit] = {
    env.postgresql.queryOne(
      s"""
         |DELETE FROM izanami.pending_imports WHERE id=$$1
         |""".stripMargin,
      List(id)
    ){_ => Some(())}.map(_ => ())
  }

  def readImportStatus(id: UUID): Future[Option[ImportState]] = {
    env.postgresql.queryOne(
      s"""
         |SELECT status, result FROM izanami.pending_imports WHERE id=$$1
         |""".stripMargin,
      List(id)
    ){r => {
      val maybeStatus = r.optJsObject("result")
        .flatMap(obj => obj.asOpt[ImportState](importResultReads))

      maybeStatus.orElse(r.optString("status").filter(_ == "PENDING").map(_ => ImportPending(id)))
    }}
  }

  def markImportAsSucceded(id: UUID, importSuccess: ImportSuccess): Future[Unit] = {
    env.postgresql.queryOne(
      s"""
         |UPDATE izanami.pending_imports SET status='FINISHED', result=$$2 WHERE id=$$1
         |""".stripMargin,
      List(id, Json.toJson(importSuccess)(importSuccessWrites).vertxJsValue)
    ){r => Some(())}
      .map(_ => ())
  }

  def markImportAsFailed(id: UUID, importFailure: ImportFailure): Future[Unit] = {
    env.postgresql.queryOne(
      s"""
         |UPDATE izanami.pending_imports SET status='FAILED', result=$$2 WHERE id=$$1
         |""".stripMargin,
      List(id, Json.toJson(importFailure)(importFailureWrites).vertxJsValue)
    ) { r => Some(()) }
      .map(_ => ())
  }


  def markImportAsStarted(): Future[Either[IzanamiError, UUID]] = {
    env.postgresql.queryOne(
      s"""INSERT INTO izanami.pending_imports DEFAULT VALUES RETURNING id""",
      List()
    ){r => r.optUUID("id")}
      .map(_.toRight(InternalServerError()))
  }

  def createTenant(tenantCreationRequest: TenantCreationRequest, user: UserInformation): Future[Either[IzanamiError, Tenant]] = {
    val connectOptions = env.postgresql.connectOptions
    val config         = new HikariConfig()
    config.setDriverClassName(classOf[org.postgresql.Driver].getName)
    config.setJdbcUrl(
      s"jdbc:postgresql://${connectOptions.getHost}:${connectOptions.getPort}/${connectOptions.getDatabase}"
    )
    config.setUsername(connectOptions.getUser)
    config.setPassword(connectOptions.getPassword)
    config.setMaximumPoolSize(10)
    val dataSource     = new HikariDataSource(config)
    val flyway         =
      Flyway.configure
        .dataSource(dataSource)
        .locations("filesystem:conf/sql/tenants", "filesystem:sql/tenants", "sql/tenants", "conf/sql/tenants")
        .baselineOnMigrate(true)
        .schemas(tenantCreationRequest.name)
        .load()
    flyway.migrate()
    dataSource.close()

    env.postgresql.executeInTransaction(conn => {
      env.postgresql
        .queryOne(
          s"insert into izanami.tenants (name, description) values ($$1, $$2) returning *",
          List(tenantCreationRequest.name, tenantCreationRequest.description),
          conn=Some(conn)
        ) { row => row.optTenant() }
        .map(maybeFeature => maybeFeature.toRight(InternalServerError()))
        .recover {
          case f: PgException if f.getSqlState == UNIQUE_VIOLATION => Left(TenantAlreadyExists(tenantCreationRequest.name))
          case _ => Left(InternalServerError())
        }.flatMap {
          case Left(value) => Left(value).future
          case Right(value) => env.postgresql.queryOne(
            s"""
               | INSERT INTO izanami.users_tenants_rights(username, tenant, level) VALUES ($$1, $$2, $$3)
               | RETURNING username
               |""".stripMargin,
            List(user.username, value.name, RightLevels.Admin.toString.toUpperCase),
            conn=Some(conn)
          ){_ => Some(value)}
          .map(maybeFeature => maybeFeature.toRight(InternalServerError()))
        }.flatMap {
          case Left(value) => Left(value).future
          case r@Right(tenant) => {
            env.eventService.emitEvent(channel = IZANAMI_CHANNEL, event = SourceTenantCreated(tenant.name, user = user.username, authentication = user.authentication, origin = NormalOrigin))(conn)
              .map(_ => r)
          }
        }
    })

  }

  def updateTenant(name: String, updateRequest: TenantCreationRequest): Future[Either[IzanamiError, Unit]] = {
    env.postgresql.executeInTransaction(conn => {
      env.postgresql.queryOne(
        s"""
           |UPDATE izanami.tenants SET description=$$1 WHERE name=$$2 RETURNING name
           |""".stripMargin,
        List(updateRequest.description, name),
        conn=Some(conn)
      ){r => r.optString("name")}
        .map(o => o.toRight(TenantDoesNotExists(name)).map(_ => ()))
    })
  }

  def readTenants(): Future[List[Tenant]] = {
    env.postgresql.queryAll(
      "SELECT name, description FROM izanami.tenants"
    ) { row => row.optTenant() }
  }

  def readTenantsFiltered(names: Set[String]): Future[List[Tenant]] = {
    if(names.isEmpty) {
      Future.successful(List())
    } else {
      env.postgresql.queryAll(
        s"""
           |SELECT name, description
           |FROM izanami.tenants
           |WHERE name=ANY($$1)""".stripMargin,
        List(names.toArray)
      ) { row => row.optTenant() }
    }

  }

  def readTenantByName(name: String): Future[Either[IzanamiError, Tenant]] = {
    env.postgresql
      .queryOne(
        s"""SELECT t.name, t.description
         |FROM izanami.tenants t
         |WHERE t.name=$$1
         |""".stripMargin,
        List(name)
      ) { row => row.optTenant() }
      .map { _.toRight(TenantDoesNotExists(name)) }
  }

  def deleteTenant(name: String, user: UserInformation): Future[Either[IzanamiError, Unit]] = {

    env.postgresql.executeInTransaction(conn => {
      env.postgresql
        .queryOne(
          s"""DELETE FROM izanami.tenants WHERE name=$$1 RETURNING name""".stripMargin,
          List(name),
          conn=Some(conn)
        ) { r => r.optString("name") }
        .map {
          _.toRight(TenantDoesNotExists(name))
        }.flatMap {
        case l@Left(value) => Left(value).future
        case r@Right(deletedName) => env.postgresql.queryRaw(
          s"""DROP SCHEMA "${deletedName}" CASCADE""",
          conn=Some(conn)
        ){_ => Some(())}
          .map(_ => Right(()))
      }.flatMap(r => {
          env.eventService.emitEvent(channel=IZANAMI_CHANNEL, event=SourceTenantDeleted(name, user = user.username, authentication = user.authentication, origin = NormalOrigin))(conn)
            .map(_ => r)
        })
    })

  }
}

object tenantImplicits {
  implicit class TenantRow(val row: Row) extends AnyVal {
    def optTenant(): Option[Tenant] = {
      for (
        name        <- row.optString("name");
        description <- row.optString("description")
      ) yield Tenant(name = name, projects = List(), description = description)
    }
  }
}
