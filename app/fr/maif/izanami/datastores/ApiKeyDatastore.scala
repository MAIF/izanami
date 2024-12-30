package fr.maif.izanami.datastores

import fr.maif.izanami.datastores.apiKeyImplicites.ApiKeyRow
import fr.maif.izanami.env.Env
import fr.maif.izanami.env.PostgresqlErrors.FOREIGN_KEY_VIOLATION
import fr.maif.izanami.env.PostgresqlErrors.RELATION_DOES_NOT_EXISTS
import fr.maif.izanami.env.pgimplicits.EnhancedRow
import fr.maif.izanami.errors._
import fr.maif.izanami.models.ApiKey
import fr.maif.izanami.models.ApiKeyProject
import fr.maif.izanami.models.ApiKeyWithCompleteRights
import fr.maif.izanami.utils.Datastore
import fr.maif.izanami.utils.syntax.implicits.BetterSyntax
import fr.maif.izanami.web.ImportController.Fail
import fr.maif.izanami.web.ImportController.ImportConflictStrategy
import fr.maif.izanami.web.UserInformation
import io.vertx.pgclient.PgException
import io.vertx.sqlclient.Row
import io.vertx.sqlclient.SqlConnection

import java.util.UUID
import scala.List
import scala.concurrent.Future

class ApiKeyDatastore(val env: Env) extends Datastore {
  def createApiKey(
      apiKey: ApiKey,
      user: UserInformation
 ): Future[Either[IzanamiError, ApiKey]] = {
    createApiKeys(apiKey.tenant, apiKeys = Seq(apiKey), user=user, conflictStrategy = Fail, conn= None)
      .map(e => e.map(_.head).left.map(_.head))
  }

  def findLegacyKeyTenant(clientId: String): Future[Option[String]] = {
    env.postgresql.queryOne(
    s"""SELECT tenant FROM izanami.key_tenant WHERE client_id = $$1""",
      List(clientId)
    ){r => r.optString("tenant")}
  }

  def createApiKeys(
                    tenant: String,
                    apiKeys: Seq[ApiKey],
                    user: UserInformation,
                    conflictStrategy: ImportConflictStrategy,
                    conn: Option[SqlConnection]
                  ): Future[Either[Seq[IzanamiError], Seq[ApiKey]]] = {
    // TODO handle conflict strategy

    def callback(connection: SqlConnection): Future[Either[Seq[IzanamiError], Seq[ApiKey]]] = {
      env.postgresql
        .queryAll(
          s"""insert into apikeys (name, clientid, clientsecret, description, enabled, legacy, admin)
             |values (unnest($$1::text[]), unnest($$2::text[]), unnest($$3::text[]), unnest($$4::text[]), unnest($$5::boolean[]), unnest($$6::boolean[]), unnest($$7::boolean[])) returning *""".stripMargin,
          List(
            apiKeys.map(_.name).toArray,
            apiKeys.map(_.clientId).toArray,
            apiKeys.map(key => key.clientSecret).toArray,
            apiKeys.map(_.description).toArray,
            apiKeys.map(k => java.lang.Boolean.valueOf(k.enabled)).toArray,
            apiKeys.map(k => java.lang.Boolean.valueOf(k.legacy)).toArray,
            apiKeys.map(k => java.lang.Boolean.valueOf(k.admin)).toArray
          ),
          conn = Some(connection),
          schemas=Set(tenant)
        ) { row => {
          val requestKey = apiKeys.find(k => k.name == row.getString("name")).get
          row.optApiKey(requestKey.tenant).map(key => key.copy(clientSecret = requestKey.clientSecret))
        }
        }
        .flatMap(_ => {
          val futures: Seq[Future[Either[IzanamiError, ApiKey]]] = apiKeys.filter(key => key.projects.nonEmpty)
            .map(apiKey =>
              env.postgresql
                .queryOne(
                  s"""
                     |INSERT INTO apikeys_projects (apikey, project)
                     |SELECT $$1, unnest($$2::TEXT[])
                     |RETURNING *
                     |""".stripMargin,
                  List(apiKey.name, apiKey.projects.toArray),
                  conn = Some(connection),
                  schemas=Set(tenant)
                ) { _ => Some(apiKey) }
                .map {
                  _.toRight(InternalServerError())
                }
                .recover {
                  case f: PgException if f.getSqlState == FOREIGN_KEY_VIOLATION =>
                    Left(OneProjectDoesNotExists(apiKey.projects))
                  case ex =>
                    logger.error("Failed to update project mapping table", ex)
                    Left(InternalServerError())
                }
            )
          Future.sequence(futures)
        })
        .flatMap(eitherKey => {
          val errors = eitherKey.toList.filter(_.isLeft)
            .map(_.swap.toOption).flatMap(_.toList)
          errors match {
            case Nil => {
              env.postgresql
                .queryAll(
                  s"""
                     |INSERT INTO users_keys_rights(username, apikey, level)
                     |VALUES (unnest($$1::text[]), unnest($$2::text[]), 'ADMIN')
                     |RETURNING apikey
                     |""".stripMargin,
                  List(Array.fill(apiKeys.size)(user.username), apiKeys.map(_.name).toArray),
                  conn = Some(connection),
                  schemas = Set(tenant)
                ) { r => apiKeys.find(k => k.name == r.getString("apikey")) }
                .map(l => Right(l))
            }
            case _ => Left(errors).future
          }
        }).flatMap(either => {
        // FIXME remove this filter to add a legacy flag
        val clientIds = either.getOrElse(List()).map(_.clientId).filter(clientId => !clientId.contains("_"))
        if(clientIds.isEmpty) {
          either.future
        } else {
          env.postgresql.queryOne(
            s"""
               |INSERT INTO izanami.key_tenant (client_id, tenant) VALUES (unnest($$1::TEXT[]), $$2)
               |RETURNING tenant
               |""".stripMargin,
            List(clientIds.toArray, tenant),
            conn=Some(connection)
          ){_ => Some(())}
            .map(o => o.toRight(Seq(InternalServerError())))
            .map(e => e.flatMap(_ => either))
        }
      })
    }

    if (apiKeys.isEmpty) {
      Future.successful(Right(Seq()))
    } else {
      conn.map(c => callback(c)).getOrElse(env.postgresql.executeInTransaction(c => callback(c)))
    }
  }

  def readApiKeys(tenant: String, username: String): Future[List[ApiKey]] = {
    env.postgresql.queryAll(
      s"""
         |SELECT
         |a.clientid,
         |a.name,
         |a.description,
         |a.enabled,
         |a.legacy,
         |a.admin,
         |a.clientsecret,
         |COALESCE(json_agg(ap.project) FILTER (WHERE ap.project IS NOT NULL), '[]') AS projects
         |FROM apikeys a
         |LEFT JOIN apikeys_projects ap
         |ON ap.apikey = a.name
         |WHERE EXISTS (SELECT u.username FROM izanami.users u WHERE u.username=$$1 AND u.admin=TRUE)
         |OR EXISTS(SELECT * FROM izanami.users_tenants_rights utr WHERE utr.username=$$1 AND (utr.level='ADMIN'))
         |OR a.name=ANY(SELECT akr.apikey FROM users_keys_rights akr WHERE akr.username=$$1)
         |GROUP BY a.name
         |""".stripMargin,
      List(username),
      schemas = Set(tenant)
    ) { r => r.optApiKeyWithSubObjects(tenant) }
  }

  def readApiKey(tenant: String, name: String): Future[Option[ApiKey]] = {
    env.postgresql.queryOne(
      s"""
         |SELECT
         |a.clientid,
         |a.name,
         |a.description,
         |a.enabled,
         |a.legacy,
         |a.admin,
         |a.clientsecret,
         |COALESCE(json_agg(ap.project) FILTER (WHERE ap.project IS NOT NULL), '[]') AS projects
         |FROM apikeys a
         |LEFT JOIN apikeys_projects ap
         |ON ap.apikey = a.name
         |WHERE a.name = $$1
         |GROUP BY a.name
         |""".stripMargin,
      List(name),
      schemas = Set(tenant)
    ) { r => r.optApiKeyWithSubObjects(tenant) }
  }

  def deleteApiKey(tenant: String, name: String): Future[Either[IzanamiError, String]] = {
    env.postgresql.executeInTransaction(conn => {
      env.postgresql
        .queryOne(
          s"""
           DELETE FROM apikeys WHERE name=$$1 RETURNING clientid
             |""".stripMargin,
          List(name),
          schemas = Set(tenant),
          conn=Some(conn)
        ) { row => row.optString("clientid") }
        .map(o => o.toRight(KeyNotFound(name)))
        .flatMap {
          case Left(value) => Left(value).future
          case Right(clientId) =>
              env.postgresql.queryRaw(
                s"DELETE FROM izanami.key_tenant WHERE client_id=$$1",
                List(clientId),
                conn=Some(conn)
            ){_ => Right(clientId)}
        }
    })
  }

  def updateApiKey(tenant: String, oldName: String, newKey: ApiKey): Future[Either[IzanamiError, Unit]] = {
    env.postgresql.executeInTransaction(
      conn => {
        env.postgresql
          .queryRaw(
            s"""
           |DELETE FROM apikeys_projects WHERE apikey = $$1
           |""".stripMargin,
            List(oldName),
            conn = Some(conn)
          ) { _ => Right(()) }
          .flatMap(_ => {
            env.postgresql
              .queryOne(
                s"""
               UPDATE apikeys
               |SET name=$$1,
               |description=$$2,
               |enabled=$$4,
               |admin=$$5
               |WHERE name=$$3
               |RETURNING name
               |""".stripMargin,
                List(newKey.name, newKey.description, oldName, java.lang.Boolean.valueOf(newKey.enabled), java.lang.Boolean.valueOf(newKey.admin)),
                conn = Some(conn)
              ) { row => row.optString("name") }
              .map(o => o.toRight(KeyNotFound(oldName)))
          })
          .flatMap(_ => {
            if (newKey.projects.nonEmpty) {
              env.postgresql.queryRaw(
                s"""
               |INSERT INTO apikeys_projects (apikey, project)
               |SELECT $$1, unnest($$2::text[])
               |""".stripMargin,
                List(newKey.name, newKey.projects.toArray),
                conn = Some(conn)
              ) { _ => Right(()) }
            } else {
              Future.successful(Right(()))
            }
          })
      },
      schemas = Set(tenant)
    )
  }

  def readAndCheckApiKey(
                          clientId: String,
                          clientSecret: String
                        ): Future[Either[IzanamiError, ApiKeyWithCompleteRights]] = {
    val futureMaybeTenant = ApiKey.extractTenant(clientId) match {
      case s@Some(tenant) => Future.successful(s)
      case None => findLegacyKeyTenant(clientId)
    }

    futureMaybeTenant.flatMap {
      case None => Future.successful(Left(ApiKeyDoesNotExist(clientId)))
      case Some(tenant) => {
        env.postgresql
          .queryOne(
            s"""
               |SELECT
               |a.clientid,
               |a.clientsecret,
               |a.name,
               |a.description,
               |a.enabled,
               |a.legacy,
               |a.admin,
               |COALESCE(json_agg(json_build_object('id', p.id, 'name', p.name)) FILTER (WHERE p.id IS NOT NULL), '[]') AS projects
               |FROM apikeys a
               |LEFT JOIN apikeys_projects ap ON ap.apikey = a.name
               |LEFT JOIN projects p ON p.name=ap.project
               |WHERE a.clientid=$$1
               |AND a.enabled=true
               |GROUP BY a.name
               |""".stripMargin,
            List(clientId),
            schemas = Set(tenant)
          ) { r => {
            val projects = r
              .optJsArray("projects")
              .map(arr =>
                arr.value
                  .map(v => {
                    for (
                      name <- (v \ "name").asOpt[String];
                      id <- (v \ "id").asOpt[UUID]
                    ) yield ApiKeyProject(name, id)
                  })
                  .toSet
              )
              .getOrElse(Set())
              .filter(_.isDefined)
              .map(_.get)
            for (
              _ <- r.optBoolean("enabled").filter(_ == true);
              clientSecret <- r.optString("clientsecret");
              clientid <- r.optString("clientid");
              enabled <- r.optBoolean("enabled");
              name <- r.optString("name");
              legacy <- r.optBoolean("legacy");
              admin <- r.optBoolean("admin")
            )
            yield ApiKeyWithCompleteRights(
              clientId = clientid,
              clientSecret=clientSecret,
              tenant = tenant,
              name = name,
              projects = projects,
              enabled = enabled,
              legacy=legacy,
              admin=admin
            )
          }
          }
          .map(o => o.toRight(KeyNotFound(clientId)))
          .recover {
            case f: PgException if f.getSqlState == RELATION_DOES_NOT_EXISTS => Left(TenantDoesNotExists(tenant))
            case _ => Left(InternalServerError())
          }
      }
    }
  }
}

object apiKeyImplicites {
  implicit class ApiKeyRow(val row: Row) extends AnyVal {
    def optApiKey(tenant: String): Option[ApiKey] = {
      for (
        clientid     <- row.optString("clientid");
        clientsecret <- row.optString("clientsecret");
        name         <- row.optString("name");
        description  <- row.optString("description");
        enabled      <- row.optBoolean("enabled");
        legacy       <- row.optBoolean("legacy");
        admin       <- row.optBoolean("admin")
      )
        yield ApiKey(
          clientId = clientid,
          clientSecret = clientsecret,
          tenant = tenant,
          name = name,
          description = description,
          enabled = enabled,
          legacy = legacy,
          admin = admin
        )
    }

    def optApiKeyWithSubObjects(tenant: String): Option[ApiKey] = {
      val projects = row.optJsArray("projects").map(arr => arr.value.map(v => v.as[String]).toSet).getOrElse(Set())

      for (
        clientid    <- row.optString("clientid");
        name        <- row.optString("name");
        description <- row.optString("description");
        enabled     <- row.optBoolean("enabled");
        legacy     <- row.optBoolean("legacy");
        admin     <- row.optBoolean("admin");
        clientSecret     <- row.optString("clientsecret")
      )
        yield ApiKey(
          clientId = clientid,
          tenant = tenant,
          name = name,
          projects = projects,
          description = description,
          enabled = enabled,
          legacy = legacy,
          admin=admin,
          clientSecret=clientSecret
        )
    }
  }
}
