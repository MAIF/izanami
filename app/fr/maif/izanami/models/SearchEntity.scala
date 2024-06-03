package fr.maif.izanami.models

import play.api.libs.json.{Json, Writes}

case class SearchEntity(id: String, name: String, origin_table: String)

object SearchEntity{
  implicit val searchEntityWrites: Writes[SearchEntity] = { searchEntity =>
    Json.obj(
      "id" -> searchEntity.id,
      "name"        -> searchEntity.name,
      "origin_table" -> searchEntity.origin_table
    )
  }

}


