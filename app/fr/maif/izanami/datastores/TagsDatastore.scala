package fr.maif.izanami.datastores

import fr.maif.izanami.datastores.tagImplicits.TagRow
import fr.maif.izanami.env.Env
import fr.maif.izanami.env.pgimplicits.EnhancedRow
import fr.maif.izanami.errors.InternalServerError
import fr.maif.izanami.errors.IzanamiError
import fr.maif.izanami.errors.TagDoesNotExists
import fr.maif.izanami.models.Tag
import fr.maif.izanami.models.TagCreationRequest
import fr.maif.izanami.utils.Datastore
import io.vertx.sqlclient.Row
import io.vertx.sqlclient.SqlConnection

import scala.concurrent.Future

class TagsDatastore(val env: Env) extends Datastore {
  def createTag(tagCreationRequest: TagCreationRequest, tenant: String): Future[Either[IzanamiError, Tag]] = {
    env.postgresql
      .queryOne(
        s"""insert into tags (name, description) values ($$1, $$2) returning *""",
        List(tagCreationRequest.name, tagCreationRequest.description),
        schemas=Set(tenant)
      ) { row => row.optTag() }
      .map {
        _.toRight(InternalServerError())
      }
      .recover { case ex =>
        logger.error("Failed to insert tag", ex)
        Left(InternalServerError())
      }
  }

  def createTags(tags: List[TagCreationRequest], tenant: String, conn: Option[SqlConnection] = None): Future[List[Tag]] = {
    env.postgresql
      .queryAll(
        s"""insert into tags (name, description) values (unnest($$1::text[]), unnest($$2::text[])) ON CONFLICT (name) DO NOTHING returning *""",
        List(tags.map(_.name).toArray, tags.map(_.description).toArray),
        schemas=Set(tenant),
        conn=conn
      ) { row => row.optTag() }
  }

  def readTag(tenant: String, name: String): Future[Either[IzanamiError, Tag]] = {
    env.postgresql
      .queryOne(
        s"""SELECT * FROM tags WHERE name=$$1""",
        List(name),
        schemas=Set(tenant)
      ) { row => row.optTag() }
      .map { _.toRight(TagDoesNotExists(name)) }
  }

  def deleteTag(tenant: String, name: String): Future[Either[IzanamiError, Unit]] = {
    env.postgresql
      .queryOne(
        s"""DELETE FROM tags WHERE name=$$1 returning name, id""",
        List(name),
        schemas=Set(tenant)
      ) { row => row.optTag() }
      .map { _.toRight(TagDoesNotExists(name)).map(_ => ()) }
  }

  def readTags(tenant: String, names: Set[String]): Future[List[Tag]] = {
    env.postgresql
      .queryAll(
        s"""SELECT * FROM tags WHERE name=ANY($$1)""",
        List(names.toArray),
        schemas=Set(tenant)
      ) { row => row.optTag() }
  }

  def readTags(tenant: String): Future[List[Tag]] = {
    env.postgresql.queryAll(
      s"""SELECT * FROM tags""",
      schemas=Set(tenant)
    ) { row => row.optTag() }
  }
  def updateTag(tag: Tag, tenant: String, currentName: String): Future[Either[IzanamiError, Tag]] = {
    env.postgresql
      .queryOne(
        s"""Update tags set name=$$1, description=$$2  where name = $$3 returning *""",
        List(tag.name, tag.description, currentName),
        schemas=Set(tenant)
      ) { row => row.optTag() }
      .map {
        _.toRight(TagDoesNotExists(currentName))
      }
      .recover { case ex =>
        logger.error("Failed to update tag", ex)
        Left(InternalServerError())
      }
  }
}

object tagImplicits {
  implicit class TagRow(val row: Row) extends AnyVal {
    def optTag(): Option[Tag] = {
      for (
        name <- row.optString("name");
        id <- row.optUUID("id")
      ) yield Tag(id=id, name = name, description = row.optString("description").orNull)
    }
  }
}
