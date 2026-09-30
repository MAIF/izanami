package fr.maif.izanami.services

import fr.maif.izanami.datastores.TagsDatastore
import fr.maif.izanami.utils.FutureEither
import fr.maif.izanami.models.Tag
import fr.maif.izanami.models.TagCreationRequest
import fr.maif.izanami.utils.Done
import scala.concurrent.Future

class TagService(private val datastore: TagsDatastore) {
  def createTag(
      tagCreationRequest: TagCreationRequest,
      tenant: String
  ): FutureEither[Tag] = {
    datastore.createTag(
      tagCreationRequest = tagCreationRequest,
      tenant = tenant
    )
  }

  def deleteTag(
      tenant: String,
      name: String
  ): FutureEither[Done] = {
    datastore.deleteTag(tenant = tenant, name = name)
  }

  def readTag(
      tenant: String,
      name: String
  ): FutureEither[Tag] = {
    datastore.readTag(tenant = tenant, name = name)
  }

  def readTags(tenant: String): Future[List[Tag]] = {
    datastore.readTags(tenant)
  }

  def updateTag(
      tag: Tag,
      tenant: String,
      currentName: String
  ): FutureEither[Tag] = {
    datastore.updateTag(tag = tag, tenant = tenant, currentName = currentName)
  }
}
