package fr.maif.izanami.datastores

import fr.maif.izanami.datastores.projectImplicits.ProjectRow
import fr.maif.izanami.env.Env
import fr.maif.izanami.env.PostgresqlErrors.{RELATION_DOES_NOT_EXISTS, UNIQUE_VIOLATION}
import fr.maif.izanami.env.pgimplicits.EnhancedRow
import fr.maif.izanami.errors._
import fr.maif.izanami.events.SourceFeatureDeleted
import fr.maif.izanami.models.{Feature, Project, ProjectCreationRequest, RightLevels}
import fr.maif.izanami.utils.Datastore
import fr.maif.izanami.utils.syntax.implicits.BetterSyntax
import fr.maif.izanami.web.ImportController
import fr.maif.izanami.web.ImportController.{Fail, ImportConflictStrategy, MergeOverwrite, Skip}
import io.vertx.pgclient.PgException
import io.vertx.sqlclient.{Row, SqlConnection}

import java.util.UUID
import java.util.regex.Pattern
import scala.concurrent.Future

class ProjectsDatastore(val env: Env) extends Datastore {

  def createProjects(
      tenant: String,
      names: Set[String],
      conflictStrategy: ImportConflictStrategy,
      user: String,
      conn: Option[SqlConnection] = None
  ): Future[Either[IzanamiError, Unit]] = {
    env.postgresql
      .queryRaw(
        s"""
           |INSERT INTO projects(name, description) VALUES(unnest($$1::text[]), '')
           |${conflictStrategy match {
          case Fail           => ""
          case MergeOverwrite => """
              | ON CONFLICT(name) DO UPDATE SET description = excluded.description
              |""".stripMargin
          case Skip           => " ON CONFLICT(name) DO NOTHING "
        }}
           |RETURNING name
           |""".stripMargin,
        List(names.toArray),
        schemas = Set(tenant),
        conn = conn
      ) { _ => Some(()) }
      .map(o => {
        o.toRight(InternalServerError())
      })
      .flatMap {
        case Left(err)    => Left(err).future
        case Right(value) =>
          env.postgresql
            .queryRaw(
              s"""INSERT INTO users_projects_rights (username, project, level)
                 |VALUES ($$1, unnest($$2::TEXT[]), $$3)
                 |ON CONFLICT(username, project) DO NOTHING
                 |RETURNING 1
                 |""".stripMargin,
              List(user, names.toArray, RightLevels.Admin.toString.toUpperCase),
              conn = conn
            ) { _ => Some(value) }
            .map(o => {
              o.toRight(InternalServerError())
            })
      }
      .recover {
        case f: PgException if f.getSqlState == UNIQUE_VIOLATION => {
          val regexp  = Pattern.compile("Key \\(name\\)=\\((?<name>.*)\\) already exists\\.")
          val matcher = regexp.matcher(f.getDetail)
          matcher.matches()
          val name    = matcher.group("name")
          Left(ProjectAlreadyExists(if (name.isEmpty) "" else name, tenant))
        }
        case ex                                                  =>
          logger.error("Failed to update project mapping table", ex)
          Left(InternalServerError())
      }
  }

  def createProject(
      tenant: String,
      projectCreationRequest: ProjectCreationRequest,
      user: String
  ): Future[Either[IzanamiError, Project]] = {
    env.postgresql.executeInTransaction(
      conn => {
        env.postgresql
          .queryOne(
            s"""insert into projects (name, description) values ($$1, $$2) returning *""",
            List(projectCreationRequest.name, projectCreationRequest.description),
            conn = Some(conn)
          ) { row => row.optProject() }
          .map {
            _.toRight(InternalServerError())
          }
          .recover {
            case f: PgException if f.getSqlState == UNIQUE_VIOLATION         =>
              Left(ProjectAlreadyExists(projectCreationRequest.name, tenant))
            case f: PgException if f.getSqlState == RELATION_DOES_NOT_EXISTS => Left(TenantDoesNotExists(tenant))
            case ex                                                          =>
              logger.error("Failed to insert project", ex)
              Left(InternalServerError())
          }
          .flatMap {
            case Left(value)  => Left(value).future
            case Right(value) =>
              env.postgresql
                .queryOne(
                  s"""INSERT INTO users_projects_rights (username, project, level) VALUES ($$1, $$2, $$3) RETURNING project""",
                  List(user, projectCreationRequest.name, RightLevels.Admin.toString.toUpperCase),
                  conn = Some(conn)
                ) { _ => Some(value) }
                .map(_.toRight(InternalServerError()))
          }
      },
      schemas = Set(tenant)
    )
  }

  def updateProject(tenant: String, oldName: String, newProject: ProjectCreationRequest): Future[Unit] = {
    env.postgresql
      .queryOne(
        s"""
         |UPDATE projects SET name=$$1, description=$$2 WHERE name=$$3 RETURNING *
         |""".stripMargin,
        List(newProject.name, newProject.description, oldName),
        schemas = Set(tenant)
      ) { _ => Some(()) }
      .map(_ => ())
  }

  def readTenantProjectForUser(tenant: String, user: String): Future[List[Project]] = {
    // TODO ensure performance of this query
    env.postgresql.queryAll(
      s"""
      SELECT p.*
      FROM projects p
      WHERE EXISTS (SELECT u.username FROM izanami.users u WHERE u.username=$$1 AND u.admin=TRUE)
      OR EXISTS(SELECT * FROM izanami.users_tenants_rights utr WHERE utr.username=$$1 AND (utr.level='ADMIN'))
      OR p.name=ANY(SELECT upr.project FROM users_projects_rights upr WHERE upr.username=$$1)
      """,
      List(user),
      schemas = Set(tenant)
    ) { row => row.optProject() }
  }

  def deleteProject(tenant: String, project: String, user: String): Future[Either[IzanamiError, List[String]]] = {
    env.postgresql.executeInTransaction(conn => {
      env.postgresql
        .queryOne(
          s"""DELETE FROM projects p WHERE p.name=$$1 RETURNING (SELECT array_agg(f.id) AS ids FROM features f WHERE f.project=p.name);""",
          List(project),
          schemas = Set(tenant),
          conn = Some(conn)
        ) { row => row.optStringArray("ids").map(_.toList).orElse(Some(List())) }
        .map(o => o.toRight(ProjectDoesNotExists(project)))
        .flatMap {
          case Left(value) => Future.successful(Left(value))
          case Right(ids)  =>
            Future
              .sequence(
                ids.map(id =>
                  env.eventService.emitEvent(
                    channel = tenant,
                    event = SourceFeatureDeleted(id = id, project = project, tenant = tenant, user = user)
                  )(conn)
                )
              )
              .map(_ => Right(ids))
        }
    })

  }

  def readProject(tenant: String, project: String): Future[Either[IzanamiError, Project]] = {
    env.postgresql
      .queryOne(
        s"""
         |select p.id, p.name, p.description,
         |  COALESCE(
         |    json_agg(row_to_json(f.*)::jsonb
         |      || (json_build_object('tags', (
         |        array(
         |          SELECT ft.tag
         |          FROM features_tags ft
         |          WHERE ft.feature = f.id
         |          GROUP BY ft.tag
         |        )
         |      ), 'wasmConfig', f.script_config))::jsonb)
         |      FILTER (WHERE f.id IS NOT NULL), '[]'
         |  ) as "features"
         |from projects p
         |left join features f on p.name = f.project
         |WHERE p.name = $$1
         |group by p.id""".stripMargin,
        List(project),
        schemas = Set(tenant)
      ) { row => row.optProjectWithFeatures() }
      .map(o => o.toRight(ProjectDoesNotExists(project)))
  }

  def readProjects(tenant: String): Future[List[Project]] = {
    env.postgresql
      .queryAll(
        s"""select p.id, p.name, p.description,
           |  COALESCE(
           |    json_agg(row_to_json(f.*)::jsonb
           |      || (json_build_object('tags', (
           |        array(
           |          SELECT ft.tag
           |          FROM features_tags ft
           |          WHERE ft.feature = f.id
           |          GROUP BY ft.tag
           |        )
           |      ),'wasmConfig', (
           |        select w.config FROM wasm_script_configurations w where w.id = f.script_config
           |      )))::jsonb)
           |      FILTER (WHERE f.id IS NOT NULL), '[]'
           |  ) as "features"
           |from projects p
           |left join features f on p.name = f.project
           |group by p.id""".stripMargin,
        List(),
        schemas = Set(tenant)
      ) { row => row.optProjectWithFeatures() }
  }

  def readProjectsFiltered(tenant: String, projectFilter: Set[String]): Future[List[Project]] = {
    env.postgresql
      .queryAll(
        s"""select p.id, p.name, p.description,
           |  COALESCE(
           |    json_agg(row_to_json(f.*)::jsonb
           |      || (json_build_object('tags', (
           |        array(
           |          SELECT ft.tag
           |          FROM features_tags ft
           |          WHERE ft.feature = f.id
           |          GROUP BY ft.tag
           |        )
           |      ), 'wasmConfig', (
           |        select w.config FROM wasm_script_configurations w where w.id = f.script_config
           |      )))::jsonb)
           |      FILTER (WHERE f.id IS NOT NULL), '[]'
           |  ) as "features"
           |from projects p
           |left join features f on p.name = f.project
           |where p.name=ANY($$1)
           |group by p.id""".stripMargin,
        List(projectFilter.toArray),
        schemas = Set(tenant)
      ) { row => row.optProjectWithFeatures() }
  }

  def readProjectsById(tenant: String, ids: Set[UUID]): Future[Map[UUID, Project]] = {
    if (ids.isEmpty) {
      Future.successful(Map())
    }
    env.postgresql
      .queryAll(
        s"""
         |SELECT id, name, description
         |FROM projects
         |WHERE id=ANY($$1)
         |""".stripMargin,
        List(ids.toArray),
        schemas = Set(tenant)
      ) { r =>
        {
          r.optProject()
        }
      }
      .map(ps => ps.map(p => (p.id, p)).toMap)
  }
}

object projectImplicits {
  implicit class ProjectRow(val row: Row) extends AnyVal {
    def optProject(): Option[Project] = {
      for (
        id          <- row.optUUID("id");
        name        <- row.optString("name");
        description <- row.optString("description")
      )
        yield Project(id = id, name = name, features = List(), description = description)
    }

    def optProjectWithFeatures(): Option[Project] = {
      for (
        id          <- row.optUUID("id");
        name        <- row.optString("name");
        description <- row.optString("description")
      )
        yield {
          val maybeFeatures = row
            .optJsArray("features")
            .map(array => array.value.map(v => Feature.readLightWeightFeature(v, name).asOpt).flatMap(o => o.toList).toList)
          Project(id = id, name = name, features = maybeFeatures.getOrElse(List()), description = description)
        }
    }
  }
}
