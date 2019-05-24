package store.leveldb

import java.io.File
import java.util.concurrent.ConcurrentHashMap

import akka.actor.ActorSystem
import akka.stream.ActorMaterializer
import akka.stream.scaladsl.{Flow, Sink, Source}
import akka.util.ByteString
import akka.{Done, NotUsed}
import cats.effect.{Async, Effect}
import domains.Key
import env.{DbDomainConfig, LevelDbConfig}
import libs.streams.Flows
import org.iq80.leveldb._
import org.iq80.leveldb.impl.Iq80DBFactory._
import libs.logs.IzanamiLogger
import play.api.inject.ApplicationLifecycle
import play.api.libs.json.{JsValue, Json}
import store.Result.{ErrorMessage, Result}
import store._

import scala.collection.concurrent.TrieMap
import scala.concurrent.{ExecutionContext, Future}
import scala.util.Try
import scala.util.control.NonFatal

class DbStores[F[_]] {
  val stores = TrieMap.empty[String, LevelDBJsonDataStore[F]]
}

object LevelDBJsonDataStore {

  def apply[F[_]: Effect](
      levelDbConfig: LevelDbConfig,
      config: DbDomainConfig,
      applicationLifecycle: ApplicationLifecycle
  )(implicit actorSystem: ActorSystem, stores: DbStores[F]): LevelDBJsonDataStore[F] = {
    val namespace      = config.conf.namespace
    val parentPath     = levelDbConfig.parentPath
    val dbPath: String = parentPath + "/" + namespace.replaceAll(":", "_")
    stores.stores.getOrElseUpdate(dbPath, {
      IzanamiLogger.info(s"Load store LevelDB for namespace $namespace")
      new LevelDBJsonDataStore[F](dbPath, applicationLifecycle)
    })
  }
}

private[leveldb] class LevelDBJsonDataStore[F[_]: Effect](dbPath: String, applicationLifecycle: ApplicationLifecycle)(
    implicit system: ActorSystem
) extends JsonDataStore[F] {

  import cats.implicits._
  import libs.effects._
  import libs.streams.syntax._

  private val client: DB = try {
    factory.open(new File(dbPath), new Options().createIfMissing(true))
  } catch {
    case e: Throwable =>
      IzanamiLogger.error(s"Error opening db for path $dbPath", e)
      throw new RuntimeException(s"Error opening db for path $dbPath", e)
  }

  applicationLifecycle.addStopHook { () =>
    IzanamiLogger.info(s"Closing leveldb for path $dbPath")
    Future(client.close())
  }

  implicit val mat = ActorMaterializer()
  private implicit val ec: ExecutionContext =
    system.dispatchers.lookup("izanami.level-db-dispatcher")

  private def buildKey(key: Key) = Key.Empty / key

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

  private def getByKeyId(id: Key): F[Option[JsValue]] = {
    val effectiveKey = buildKey(id)
    getByStringId(effectiveKey.key)
  }

  private def getByStringId(key: String): F[Option[JsValue]] = toAsync {
    val bytesValue          = client.get(bytes(key))
    val stringValue: String = asString(bytesValue)
    if (stringValue != null) {
      val jsValue: JsValue = Json.parse(stringValue)
      Try(jsValue).toOption
    } else Option.empty
  }

  private def mget(keys: String*): F[Seq[Option[(String, ByteString)]]] =
    keys.toList.traverse(k => get(k).map(_.map(v => (k, v)))).map(_.toSeq)

  private def get(key: String): F[Option[ByteString]] = toAsync {
    Try(client.get(bytes(key))).toOption
      .flatMap(s => Option(asString(s)))
      .map(ByteString.apply)
  }

  private def getByIds(keys: String*): F[Seq[(String, JsValue)]] =
    mget(keys: _*).map(_.flatten).map {
      _.map {
        case (k, v) => (k, Json.parse(v.utf8String))
      }
    }

  private def getByKeys(keys: Key*): F[Seq[(String, JsValue)]] =
    getByIds(keys.map(_.key): _*)

  private def patternsToKey(patterns: Seq[String]): Seq[Key] =
    patterns.map(Key.apply).map(buildKey)

  private def keys(query: Query): Source[Key, NotUsed] =
    getAllKeys()
      .map { Key.apply }
      .filter { k =>
        Query.keyMatchQuery(k, query)
      }

  private def getAllKeys(): Source[String, NotUsed] = {
    val iterator = client.iterator()
    Source
      .fromFuture(Future {
        iterator.seekToFirst()
      })
      .flatMapConcat { _ =>
        Source.unfoldAsync(true) { _ =>
          Future {
            if (iterator.hasNext) {
              val key = asString(iterator.peekNext.getKey)
              iterator.next
              Some(true, key)
            } else {
              None
            }
          }
        }
      }
  }

  override def create(id: Key, data: JsValue): F[Result[JsValue]] =
    getByKeyId(id)
      .flatMap {
        case Some(_) =>
          Result.errors[JsValue](ErrorMessage("error.data.exists", id.key)).pure[F]
        case None =>
          toAsync {
            client.put(bytes(id.key), bytes(Json.stringify(data)))
            Result.ok(data)
          }
      }

  override def update(oldId: Key, id: Key, data: JsValue): F[Result[JsValue]] =
    toAsync {
      client.delete(bytes(oldId.key))
      client.put(bytes(id.key), bytes(Json.stringify(data)))
      Result.ok(data)
    }

  override def delete(id: Key): F[Result[JsValue]] =
    getByKeyId(id)
      .flatMap {
        case Some(value) =>
          toAsync {
            client.delete(bytes(id.key))
            Result.ok(value)
          }
        case None =>
          Result.error[JsValue]("error.data.missing").pure[F]
      }

  override def getById(id: Key): F[Option[JsValue]] =
    getByKeyId(id)

  override def findByQuery(query: Query, page: Int, nbElementPerPage: Int): F[PagingResult[JsValue]] = {
    val position = (page - 1) * nbElementPerPage
    keys(query)
      .via(Flows.count {
        Flow[Key]
          .drop(position)
          .take(nbElementPerPage)
          .grouped(nbElementPerPage)
          .mapAsyncF(4)(getByKeys)
          .map(_.map(_._2))
          .fold(Seq.empty[JsValue])(_ ++ _)
      })
      .runWith(Sink.head)
      .toF
      .map {
        case (results, count) =>
          DefaultPagingResult(results, page, nbElementPerPage, count)
      }
  }

  override def findByQuery(query: Query): Source[(Key, JsValue), NotUsed] =
    keys(query)
      .grouped(50)
      .mapAsyncF(4)(getByKeys)
      .mapConcat(_.toList)
      .map {
        case (k, v) => (Key(k), v)
      }

  override def deleteAll(query: Query): F[Result[Done]] =
    keys(query)
      .mapAsyncF(4)(delete)
      .runWith(Sink.ignore)
      .toF
      .map { _ =>
        Result.ok(Done)
      }

  override def count(query: Query): F[Long] =
    findByQuery(query)
      .runFold(0L) { (acc, _) =>
        acc + 1
      }
      .toF

  def stop() =
    client.close()

}
