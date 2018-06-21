package store.memory

import akka.{Done, NotUsed}
import akka.actor.ActorSystem
import akka.http.scaladsl.util.FastFuture
import akka.stream.scaladsl.Source
import domains.Key
import env.DbDomainConfig
import play.api.Logger
import play.api.libs.json.{JsValue, _}
import store.Result.Result
import store._

import scala.collection.concurrent.TrieMap
import scala.concurrent.{ExecutionContext, Future}

object InMemoryJsonDataStore {

  def apply(dbDomainConfig: DbDomainConfig, actorSystem: ActorSystem): JsonDataStore = {
    val namespace    = dbDomainConfig.conf.namespace
    implicit val ec  = InMemoryExecutionContext(actorSystem)
    implicit val sys = actorSystem
    Logger.info(s"Load store InMemory for namespace $namespace")
    //new InMemoryJsonDataStoreAsync(namespace)(sys, ec)
    new InMemoryJsonDataStore(namespace)
  }
}

case class InMemoryExecutionContext(actorSystem: ActorSystem) extends ExecutionContext {
  private val _ec = actorSystem.dispatchers.lookup("izanami.inmemory-dispatcher")

  override def execute(runnable: Runnable): Unit = _ec.execute(runnable)

  override def reportFailure(cause: Throwable): Unit = _ec.reportFailure(cause)
}

class InMemoryJsonDataStoreAsync(name: String)(implicit system: ActorSystem, ec: InMemoryExecutionContext)
    extends BaseInMemoryJsonDataStore(name)
    with JsonDataStore {

  override def create(id: Key, data: JsValue): Future[Result[JsValue]] =
    Future { createSync(id, data) }

  override def update(oldId: Key, id: Key, data: JsValue): Future[Result[JsValue]] =
    Future { updateSync(oldId, id, data) }

  override def delete(id: Key): Future[Result[JsValue]] =
    Future { deleteSync(id) }

  override def deleteAll(patterns: Seq[String]): Future[Result[Done]] =
    Future { deleteAllSync(patterns) }

  override def getById(id: Key): FindResult[JsValue] =
    SimpleFindResult(Future(getByIdSync(id).toList))

  override def getByIdLike(patterns: Seq[String], page: Int, nbElementPerPage: Int): Future[PagingResult[JsValue]] =
    Future {
      getByIdLikeSync(patterns, page, nbElementPerPage)
    }

  override def getByIdLike(patterns: Seq[String]): Source[(Key, JsValue), NotUsed] =
    Source(getByIdLikeSync(patterns))

  override def count(patterns: Seq[String]): Future[Long] =
    Future(countSync(patterns))
}

class InMemoryJsonDataStore(name: String) extends BaseInMemoryJsonDataStore(name) with JsonDataStore {

  override def create(id: Key, data: JsValue): Future[Result[JsValue]] =
    FastFuture.successful(createSync(id, data))

  override def update(oldId: Key, id: Key, data: JsValue): Future[Result[JsValue]] =
    FastFuture.successful(updateSync(oldId, id, data))

  override def delete(id: Key): Future[Result[JsValue]] =
    FastFuture.successful(deleteSync(id))

  override def deleteAll(patterns: Seq[String]): Future[Result[Done]] =
    FastFuture.successful(deleteAllSync(patterns))

  override def getById(id: Key): FindResult[JsValue] =
    SimpleFindResult(FastFuture.successful(getByIdSync(id).toList))

  override def getByIdLike(patterns: Seq[String], page: Int, nbElementPerPage: Int): Future[PagingResult[JsValue]] =
    FastFuture.successful(getByIdLikeSync(patterns, page, nbElementPerPage))

  override def getByIdLike(patterns: Seq[String]): Source[(Key, JsValue), NotUsed] =
    Source(getByIdLikeSync(patterns))

  override def count(patterns: Seq[String]): Future[Long] =
    FastFuture.successful(countSync(patterns))
}

class BaseInMemoryJsonDataStore(name: String) {

  protected val inMemoryStore = TrieMap.empty[Key, JsValue]

  protected def createSync(id: Key, data: JsValue): Result[JsValue] =
    inMemoryStore.get(id) match {
      case Some(_) =>
        Result.error("error.data.exists")
      case None =>
        inMemoryStore.put(id, data)
        Result.ok(data)
    }

  protected def updateSync(oldId: Key, id: Key, data: JsValue): Result[JsValue] =
    if (inMemoryStore.contains(oldId)) {
      inMemoryStore.remove(oldId)
      inMemoryStore.put(id, data)
      Result.ok(data)
    } else {
      Logger.error(s"Error data missing for $oldId")
      Result.error("error.data.missing")
    }

  protected def deleteSync(id: Key): Result[JsValue] =
    inMemoryStore.remove(id) match {
      case Some(data: JsValue) =>
        val value: Result[JsValue] = Result.ok(data)
        value
      case None =>
        Result.error("error.data.missing")
    }

  protected def deleteAllSync(patterns: Seq[String]): Result[Done] = {
    val keys = find(patterns).map(_._1)
    keys.foreach { inMemoryStore.remove }
    Result.ok(Done)
  }

  protected def getByIdSync(id: Key): Option[JsValue] =
    inMemoryStore.get(id)

  protected def getByIdLikeSync(patterns: Seq[String], page: Int, nbElementPerPage: Int): PagingResult[JsValue] = {
    val position = (page - 1) * nbElementPerPage
    val values   = find(patterns)
    val r        = values.slice(position, position + nbElementPerPage).map(_._2)
    DefaultPagingResult(r, page, nbElementPerPage, values.size)
  }

  protected def getByIdLikeSync(patterns: Seq[String]): List[(Key, JsValue)] =
    find(patterns)

  protected def countSync(patterns: Seq[String]): Long =
    find(patterns).size.toLong

  protected def matchPatterns(patterns: Seq[String])(key: Key) =
    patterns.forall(r => key.matchPattern(r))

  protected def find(patterns: Seq[String]): List[(Key, JsValue)] = {
    val p = matchPatterns(patterns)(_)
    inMemoryStore.collect { case (k, v) if p(k) => (k, v) }.toList
  }
}
