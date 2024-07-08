package fr.maif.izanami.models

import play.api.libs.json.{Json, Writes}

case class SearchEntity(
    id: String,
    name: String,
    origin_table: String,
    origin_tenant: String,
    project: Option[String],
    description: Option[String]
)

object SearchEntity {
  implicit val searchEntityWrites: Writes[SearchEntity] = { searchEntity =>
    Json.obj(
      "id"            -> searchEntity.id,
      "name"          -> searchEntity.name,
      "origin_table"  -> searchEntity.origin_table,
      "origin_tenant" -> searchEntity.origin_tenant,
      "project"       -> searchEntity.project,
      "description"   -> searchEntity.description
    )
  }

}
