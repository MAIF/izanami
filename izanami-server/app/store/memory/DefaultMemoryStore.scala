package store.memory

import akka.Done
import akka.actor.ActorSystem
import akka.http.scaladsl.util.FastFuture
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
    extends JsonDataStore {
  private val inMemoryStore = TrieMap.empty[Key, JsValue]

  override def create(id: Key, data: JsValue): Future[Result[JsValue]] =
    Future {
      inMemoryStore.get(id) match {
        case Some(_) =>
          Result.error("error.data.exists")
        case None =>
          inMemoryStore + (id -> data)
          Result.ok(data)
      }
    }

  override def update(oldId: Key, id: Key, data: JsValue): Future[Result[JsValue]] =
    Future {
      if (inMemoryStore.contains(oldId)) {
        inMemoryStore - oldId
        inMemoryStore + (id -> data)
        Result.ok(data)
      } else {
        Result.error("error.data.missing")
      }
    }

  override def delete(id: Key): Future[Result[JsValue]] =
    Future {
      inMemoryStore.remove(id) match {
        case Some(data: JsValue) =>
          val value: Result[JsValue] = Result.ok(data)
          value
        case None =>
          Result.error("error.data.missing")
      }
    }

  override def deleteAll(patterns: Seq[String]): Future[Result[Done]] =
    Future {
      inMemoryStore.clear()
      Result.ok(Done)
    }

  override def getById(id: Key): FindResult[JsValue] =
    SimpleFindResult(Future(inMemoryStore.get(id).toList))

  override def getByIdLike(patterns: Seq[String], page: Int, nbElementPerPage: Int): Future[PagingResult[JsValue]] =
    Future {
      val position = (page - 1) * nbElementPerPage
      val values   = find(patterns)
      val r        = values.slice(position, position + nbElementPerPage)
      DefaultPagingResult(r, page, nbElementPerPage, values.size)
    }

  override def getByIdLike(patterns: Seq[String]): FindResult[JsValue] =
    SimpleFindResult(Future(find(patterns)))

  override def count(patterns: Seq[String]): Future[Long] =
    Future(find(patterns).size.toLong)

  private def matchPatterns(patterns: Seq[String])(key: Key) =
    patterns.forall(r => key.matchPattern(r))

  private def find(patterns: Seq[String]): List[JsValue] = {
    val p = matchPatterns(patterns)(_)
    inMemoryStore.collect { case (k, v) if p(k) => v }.toList
  }
}

class InMemoryJsonDataStore(name: String) extends JsonDataStore {

  private val inMemoryStore = TrieMap.empty[Key, JsValue]

  override def create(id: Key, data: JsValue): Future[Result[JsValue]] =
    inMemoryStore.get(id) match {
      case Some(_) =>
        FastFuture.successful(Result.error("error.data.exists"))
      case None =>
        inMemoryStore + (id -> data)
        FastFuture.successful(Result.ok(data))
    }

  override def update(oldId: Key, id: Key, data: JsValue): Future[Result[JsValue]] =
    if (inMemoryStore.contains(oldId)) {
      inMemoryStore - oldId
      inMemoryStore + (id -> data)
      FastFuture.successful(Result.ok(data))
    } else {
      FastFuture.successful(Result.error("error.data.missing"))
    }

  override def delete(id: Key): Future[Result[JsValue]] =
    inMemoryStore.remove(id) match {
      case Some(data: JsValue) =>
        val value: Result[JsValue] = Result.ok(data)
        FastFuture.successful(value)
      case None =>
        FastFuture.successful(Result.error("error.data.missing"))
    }

  override def deleteAll(patterns: Seq[String]): Future[Result[Done]] = {
    inMemoryStore.clear()
    FastFuture.successful(Result.ok(Done))
  }

  override def getById(id: Key): FindResult[JsValue] =
    SimpleFindResult(FastFuture.successful(inMemoryStore.get(id).toList))

  override def getByIdLike(patterns: Seq[String], page: Int, nbElementPerPage: Int): Future[PagingResult[JsValue]] = {
    val position = (page - 1) * nbElementPerPage
    val values   = find(patterns)
    val r        = values.slice(position, position + nbElementPerPage)
    FastFuture.successful(DefaultPagingResult(r, page, nbElementPerPage, values.size))
  }

  override def getByIdLike(patterns: Seq[String]): FindResult[JsValue] =
    SimpleFindResult(FastFuture.successful(find(patterns)))

  override def count(patterns: Seq[String]): Future[Long] =
    FastFuture.successful(find(patterns).size.toLong)

  private def matchPatterns(patterns: Seq[String])(key: Key) =
    patterns.forall(r => key.matchPattern(r))

  private def find(patterns: Seq[String]): List[JsValue] = {
    val p = matchPatterns(patterns)(_)
    inMemoryStore.collect { case (k, v) if p(k) => v }.toList
  }
}
