package store.memory

import akka.{Done, NotUsed}
import akka.stream.scaladsl.Source
import domains.{errors, Key}
import env.DbDomainConfig
import play.api.libs.json.JsValue
import domains.errors.{IzanamiErrors, Result}
import store._
import store.datastore._

import scala.collection.concurrent.TrieMap
import libs.logs.ZLogger
import libs.logs.IzanamiLogger
import domains.errors.DataShouldExists
import domains.errors.DataShouldNotExists

object InMemoryJsonDataStore {

  def apply(dbDomainConfig: DbDomainConfig): InMemoryJsonDataStore = {
    val namespace = dbDomainConfig.conf.namespace
    new InMemoryJsonDataStore(namespace)
  }
}
class InMemoryJsonDataStore(name: String, inMemoryStore: TrieMap[Key, JsValue] = TrieMap.empty[Key, JsValue])
    extends BaseInMemoryJsonDataStore(inMemoryStore)
    with JsonDataStore.Service {

  import zio._

  override def start: RIO[DataStoreContext, Unit] = ZLogger.info(s"Load store InMemory for namespace $name")

  override def create(id: Key, data: JsValue): IO[IzanamiErrors, JsValue] =
    IO.fromEither(createSync(id, data))

  override def update(oldId: Key, id: Key, data: JsValue): IO[IzanamiErrors, JsValue] =
    IO.fromEither(updateSync(oldId, id, data))

  override def delete(id: Key): IO[IzanamiErrors, JsValue] =
    IO.fromEither(deleteSync(id))

  override def deleteAll(query: Query): IO[IzanamiErrors, Unit] =
    IO.fromEither(deleteAllSync(query))

  override def getById(id: Key): Task[Option[JsValue]] =
    IO(getByIdSync(id))

  override def findByQuery(query: Query, page: Int, nbElementPerPage: Int): Task[PagingResult[JsValue]] =
    Task(findByQuerySync(query, page, nbElementPerPage))

  override def findByQuery(query: Query): Task[Source[(Key, JsValue), NotUsed]] =
    Task(Source(findByQuerySync(query)))

  override def count(query: Query): Task[Long] =
    Task(countSync(query))
}

class BaseInMemoryJsonDataStore(val inMemoryStore: TrieMap[Key, JsValue] = TrieMap.empty[Key, JsValue]) {

  protected def createSync(id: Key, data: JsValue): Result[JsValue] =
    inMemoryStore.get(id) match {
      case Some(_) =>
        errors.Result.error[JsValue](DataShouldNotExists(id))
      case None =>
        inMemoryStore.put(id, data)
        errors.Result.ok(data)
    }

  protected def updateSync(oldId: Key, id: Key, data: JsValue): Result[JsValue] =
    if (inMemoryStore.contains(oldId)) {
      inMemoryStore.remove(oldId)
      inMemoryStore.put(id, data)
      errors.Result.ok(data)
    } else {
      IzanamiLogger.error(s"Error data missing for $oldId")
      errors.Result.error(DataShouldExists(id))
    }

  protected def deleteSync(id: Key): Result[JsValue] =
    inMemoryStore.remove(id) match {
      case Some(data: JsValue) =>
        val value: Result[JsValue] = errors.Result.ok(data)
        value
      case None =>
        errors.Result.error(DataShouldExists(id))
    }

  protected def deleteAllSync(query: Query): Result[Unit] = {
    val keys = find(query).map(_._1)
    keys.foreach { inMemoryStore.remove }
    errors.Result.ok(Done)
  }

  protected def getByIdSync(id: Key): Option[JsValue] =
    inMemoryStore.get(id)

  protected def findByQuerySync(query: Query, page: Int, nbElementPerPage: Int): PagingResult[JsValue] = {
    val position = (page - 1) * nbElementPerPage
    val values   = find(query)
    val r        = values.slice(position, position + nbElementPerPage).map(_._2)
    DefaultPagingResult(r, page, nbElementPerPage, values.size)
  }

  protected def findByQuerySync(query: Query): List[(Key, JsValue)] =
    find(query)

  protected def countSync(query: Query): Long =
    find(query).size.toLong

  protected def matchPatterns(patterns: Seq[String])(key: Key) =
    patterns.forall(r => key.matchPattern(r))

  protected def find(query: Query): List[(Key, JsValue)] = {
    val p = (k: Key) => Query.keyMatchQuery(k, query)
    inMemoryStore.collect { case (k, v) if p(k) => (k, v) }.toList
  }
}
