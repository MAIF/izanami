package fr.maif.izanami.datastores

import fr.maif.izanami.datastores.searchEntityImplicits.SearchEntityRow
import fr.maif.izanami.env.Env
import fr.maif.izanami.env.pgimplicits.EnhancedRow
import fr.maif.izanami.models.{SearchEntity, Tag}
import fr.maif.izanami.utils.Datastore
import io.vertx.sqlclient.Row

import scala.concurrent.Future

class SearchDatastore(val env: Env) extends Datastore {

  def searchEntities(user: String, query: String) : Future[List[SearchEntity]] = {

    env.postgresql.queryAll(
      s"""
         |SELECT * FROM izanami.search_entities WHERE to_tsquery('english', $$1) @@ searchable_name;""".stripMargin,
      List(query.concat(":*")),
    ) { r => r.optSearchEntity()}

  }

  def searchEntitiesByTenant(tenant: String, query: String):  Future[List[SearchEntity]] = {

    env.postgresql.queryAll(
      s"""
         |SELECT * FROM search_entities WHERE to_tsquery('english', $$1) @@ searchable_name;""".stripMargin,
      List(query.concat(":*")),
      schemas = Set(tenant)
    ) { r => r.optSearchEntity()}
  }
}

object searchEntityImplicits {
  implicit class SearchEntityRow(val row: Row) extends AnyVal {
    def optSearchEntity(): Option[SearchEntity] = {
      for (
        name <- row.optString("name");
        origin_table <-row.optString("origin_table");
        id <- row.optString("id")
      ) yield SearchEntity(id=id , name=name, origin_table=origin_table)
    }
  }
}
