package store.memory

import akka.{Done, NotUsed}
import akka.actor.ActorSystem
import akka.stream.scaladsl.Source
import cats.Applicative
import cats.effect.{Async}
import domains.Key
import env.DbDomainConfig
import libs.logs.IzanamiLogger
import play.api.libs.json.{JsValue, _}
import store.Result.Result
import store._

import scala.collection.concurrent.TrieMap
import scala.concurrent.{ExecutionContext}
import scala.util.control.NonFatal

object InMemoryJsonDataStore {

  def apply[F[_]: Async](dbDomainConfig: DbDomainConfig)(implicit actorSystem: ActorSystem): JsonDataStore[F] = {
    val namespace   = dbDomainConfig.conf.namespace
    implicit val ec = InMemoryExecutionContext(actorSystem)
    IzanamiLogger.info(s"Load store InMemory for namespace $namespace")
    //new InMemoryJsonDataStoreAsync(namespace)(sys, ec)
    new InMemoryJsonDataStore(namespace)
  }
}

case class InMemoryExecutionContext(actorSystem: ActorSystem) extends ExecutionContext {
  private val _ec = actorSystem.dispatchers.lookup("izanami.inmemory-dispatcher")

  override def execute(runnable: Runnable): Unit = _ec.execute(runnable)

  override def reportFailure(cause: Throwable): Unit = _ec.reportFailure(cause)
}

class InMemoryJsonDataStoreAsync[F[_]: Async](
    name: String,
    inMemoryStore: TrieMap[Key, JsValue] = TrieMap.empty[Key, JsValue]
)(implicit system: ActorSystem, ec: InMemoryExecutionContext)
    extends BaseInMemoryJsonDataStore(name, inMemoryStore)
    with JsonDataStore[F] {
  import cats.effect._

  override def create(id: Key, data: JsValue): F[Result[JsValue]] =
    toAsync { createSync(id, data) }

  override def update(oldId: Key, id: Key, data: JsValue): F[Result[JsValue]] =
    toAsync { updateSync(oldId, id, data) }

  override def delete(id: Key): F[Result[JsValue]] =
    toAsync { deleteSync(id) }

  override def deleteAll(patterns: Seq[String]): F[Result[Done]] =
    toAsync { deleteAllSync(patterns) }

  override def getById(id: Key): F[Option[JsValue]] =
    toAsync(getByIdSync(id))

  override def getByIdLike(patterns: Seq[String], page: Int, nbElementPerPage: Int): F[PagingResult[JsValue]] =
    toAsync {
      getByIdLikeSync(patterns, page, nbElementPerPage)
    }

  override def getByIdLike(patterns: Seq[String]): Source[(Key, JsValue), NotUsed] =
    Source(getByIdLikeSync(patterns))

  override def count(patterns: Seq[String]): F[Long] =
    toAsync(countSync(patterns))

  private def toAsync[T](a: => T): F[T] =
    Async[F].async { cb =>
      ec.execute { () =>
        try {
          // Signal completion
          cb(Right(a))
        } catch {
          case NonFatal(e) =>
            cb(Left(e))
        }
      }
    }
}

class InMemoryJsonDataStore[F[_]: Applicative](name: String,
                                               inMemoryStore: TrieMap[Key, JsValue] = TrieMap.empty[Key, JsValue])
    extends BaseInMemoryJsonDataStore(name, inMemoryStore)
    with JsonDataStore[F] {

  import cats.implicits._

  override def create(id: Key, data: JsValue): F[Result[JsValue]] =
    createSync(id, data).pure[F]

  override def update(oldId: Key, id: Key, data: JsValue): F[Result[JsValue]] =
    updateSync(oldId, id, data).pure[F]

  override def delete(id: Key): F[Result[JsValue]] =
    deleteSync(id).pure[F]

  override def deleteAll(patterns: Seq[String]): F[Result[Done]] =
    deleteAllSync(patterns).pure[F]

  override def getById(id: Key): F[Option[JsValue]] =
    getByIdSync(id).pure[F]

  override def getByIdLike(patterns: Seq[String], page: Int, nbElementPerPage: Int): F[PagingResult[JsValue]] =
    getByIdLikeSync(patterns, page, nbElementPerPage).pure[F]

  override def getByIdLike(patterns: Seq[String]): Source[(Key, JsValue), NotUsed] =
    Source(getByIdLikeSync(patterns))

  override def count(patterns: Seq[String]): F[Long] =
    countSync(patterns).pure[F]
}

class BaseInMemoryJsonDataStore(name: String, val inMemoryStore: TrieMap[Key, JsValue] = TrieMap.empty[Key, JsValue]) {

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
      IzanamiLogger.error(s"Error data missing for $oldId")
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
