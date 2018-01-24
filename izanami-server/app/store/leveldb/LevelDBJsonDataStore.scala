package store.leveldb

import java.io.File
import java.util.concurrent.ConcurrentHashMap

import akka.actor.ActorSystem
import akka.http.scaladsl.util.FastFuture
import akka.stream.ActorMaterializer
import akka.stream.scaladsl.{Flow, Sink, Source}
import akka.util.ByteString
import akka.{Done, NotUsed}
import domains.Key
import env.{DbDomainConfig, LevelDbConfig}
import libs.streams.Flows
import org.iq80.leveldb._
import org.iq80.leveldb.impl.Iq80DBFactory._
import play.api.Logger
import play.api.inject.ApplicationLifecycle
import play.api.libs.json.{JsValue, Json}
import store.Result.ErrorMessage
import store._

import scala.concurrent.{ExecutionContext, Future}
import scala.util.Try

object LevelDBJsonDataStore {
  def apply(system: ActorSystem, dbPath: String, applicationLifecycle: ApplicationLifecycle): LevelDBJsonDataStore =
    new LevelDBJsonDataStore(system, dbPath, applicationLifecycle)

  def apply(levelDbConfig: LevelDbConfig,
            config: DbDomainConfig,
            actorSystem: ActorSystem,
            applicationLifecycle: ApplicationLifecycle): LevelDBJsonDataStore = {
    val namespace = config.conf.namespace
    if (stores.get(namespace) == null) {
      Logger.info(s"Load store LevelDB for namespace $namespace")
      val parentPath     = levelDbConfig.parentPath
      val dbPath: String = parentPath + "/" + namespace.replaceAll(":", "_")
      val store          = LevelDBJsonDataStore(actorSystem, dbPath, applicationLifecycle)
      stores.put(namespace, store)
      store
    } else {
      stores.get(namespace)
    }
  }
  private val stores = new ConcurrentHashMap[String, LevelDBJsonDataStore]()
}

class LevelDBJsonDataStore(system: ActorSystem, dbPath: String, applicationLifecycle: ApplicationLifecycle)
    extends JsonDataStore {

  private val client: DB =
    try {
      factory.open(new File(dbPath), new Options().createIfMissing(true))
    } catch {
      case e: Throwable =>
        Logger.error(s"Error initializing level db at path ${new File(dbPath).getAbsolutePath}", e)
        throw new RuntimeException(s"Error initializing level db at path ${new File(dbPath).getAbsolutePath}", e)
    }
  applicationLifecycle.addStopHook { () =>
    Logger.info(s"Closing leveldb for path $dbPath")
    Future(client.close())
  }

  implicit val mat = ActorMaterializer()(system)
  private implicit val ec: ExecutionContext =
    system.dispatchers.lookup("izanami.level-db-dispatcher")

  private def buildKey(key: Key) = Key.Empty / key

  private def getByKeyId(id: Key): Future[Option[JsValue]] = {
    val effectiveKey = buildKey(id)
    getByStringId(effectiveKey.key)
  }

  private def getByStringId(key: String): Future[Option[JsValue]] = Future {
    val bytesValue          = client.get(bytes(key))
    val stringValue: String = asString(bytesValue)
    if (stringValue != null) {
      val jsValue: JsValue = Json.parse(stringValue)
      Try(jsValue).toOption
    } else Option.empty
  }

  private def mget(keys: String*): Future[Seq[Option[ByteString]]] =
    Future.sequence(keys.map(k => get(k)))

  private def get(key: String): Future[Option[ByteString]] = Future {
    Try(client.get(bytes(key))).toOption
      .flatMap(s => Option(asString(s)))
      .map(ByteString.apply)
  }

  private def getByIds(keys: String*): Future[Seq[JsValue]] =
    mget(keys: _*).map(_.flatten).map {
      _.map {
        _.utf8String
      }.map {
        Json.parse
      }
    }

  private def getByKeys(keys: Key*): Future[Seq[JsValue]] =
    getByIds(keys.map(_.key): _*)

  private def patternsToKey(patterns: Seq[String]): Seq[Key] =
    patterns.map(Key.apply).map(buildKey)

  private def keys(patterns: String*): Source[Key, NotUsed] =
    getAllKeys()
      .map { Key.apply }
      .filter { _.matchPatterns(patterns: _*) }

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

  override def create(id: Key, data: JsValue) =
    getByKeyId(id)
      .flatMap {
        case Some(_) =>
          FastFuture.successful(Result.errors(ErrorMessage("error.data.exists", id.key)))
        case None =>
          Future {
            client.put(bytes(id.key), bytes(Json.stringify(data)))
            Result.ok(data)
          }
      }

  override def update(oldId: Key, id: Key, data: JsValue) =
    Future {
      client.delete(bytes(oldId.key))
      client.put(bytes(id.key), bytes(Json.stringify(data)))
      Result.ok(data)
    }

  override def delete(id: Key) =
    getByKeyId(id)
      .flatMap {
        case Some(value) =>
          Future {
            client.delete(bytes(id.key))
            Result.ok(value)
          }
        case None =>
          FastFuture.successful(Result.error("error.data.missing"))
      }

  override def deleteAll(patterns: Seq[String]) =
    Future {
      val p = patternsToKey(patterns).map(_.key).map(_.replaceAll("\\*", ".*"))
      p.foreach(key => client.delete(bytes(key)))
      Result.ok(Done)
    }

  override def getById(id: Key) =
    SimpleFindResult(getByKeyId(id).map(_.toList))

  override def getByIdLike(patterns: Seq[String]) =
    SourceFindResult(
      keys(patterns: _*)
        .grouped(50)
        .mapAsync(4)(getByKeys)
        .mapConcat(_.toList)
    )

  override def getByIdLike(patterns: Seq[String], page: Int, nbElementPerPage: Int) = {
    val position = (page - 1) * nbElementPerPage
    keys(patterns: _*) via Flows.count {
      Flow[Key]
        .drop(position)
        .take(nbElementPerPage)
        .grouped(nbElementPerPage)
        .mapAsync(4)(getByKeys)
        .fold(Seq.empty[JsValue])(_ ++ _)
    } runWith Sink.head map {
      case (results, count) =>
        DefaultPagingResult(results, page, nbElementPerPage, count)
    }
  }

  override def count(patterns: Seq[String]): Future[Long] =
    getByIdLike(patterns).list.map(_.size)

  def stop() =
    client.close()

}
