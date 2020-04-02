package store.leveldb

import java.io.File

import akka.actor.ActorSystem
import akka.stream.{ActorMaterializer, Materializer}
import akka.stream.scaladsl.{Flow, Sink, Source}
import akka.util.ByteString
import akka.NotUsed
import domains.Key
import env.{DbDomainConfig, LevelDbConfig}
import libs.streams.Flows
import org.iq80.leveldb._
import org.iq80.leveldb.impl.Iq80DBFactory._
import libs.logs.{IzanamiLogger, Logger}
import play.api.inject.ApplicationLifecycle
import play.api.libs.json.{JsValue, Json}
import domains.errors.IzanamiErrors
import store._
import zio.blocking.Blocking.Live

import scala.collection.concurrent.TrieMap
import scala.concurrent.{ExecutionContext, Future}
import scala.util.Try
import domains.errors.DataShouldExists
import domains.errors.DataShouldNotExists

object DbStores {
  val stores = TrieMap.empty[String, LevelDBJsonDataStore]
}

object LevelDBJsonDataStore {

  def apply(
      levelDbConfig: LevelDbConfig,
      config: DbDomainConfig,
      applicationLifecycle: ApplicationLifecycle
  )(implicit actorSystem: ActorSystem): LevelDBJsonDataStore = {
    val namespace      = config.conf.namespace
    val parentPath     = levelDbConfig.parentPath
    val dbPath: String = parentPath + "/" + namespace.replaceAll(":", "_")
    DbStores.stores.getOrElseUpdate(dbPath, {
      new LevelDBJsonDataStore(dbPath, applicationLifecycle)
    })
  }
}
private[leveldb] class LevelDBJsonDataStore(dbPath: String, applicationLifecycle: ApplicationLifecycle)(
    implicit system: ActorSystem
) extends JsonDataStore {

  import zio._
  import zio.interop.catz._
  import cats.implicits._
  import IzanamiErrors._

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

  override def start: RIO[DataStoreContext, Unit] =
    Logger.info(s"Load store LevelDB for path $dbPath")

  private implicit val ec: ExecutionContext =
    system.dispatchers.lookup("izanami.level-db-dispatcher")

  private def buildKey(key: Key) = Key.Empty / key

  private def toAsync[T](a: => T): Task[T] =
    ZIO.provide(Live)(blocking.blocking(ZIO(a)))

  private def getByStringId(key: String): Task[Option[JsValue]] = toAsync {
    val bytesValue          = client.get(bytes(key))
    val stringValue: String = asString(bytesValue)
    if (stringValue != null) {
      val jsValue: JsValue = Json.parse(stringValue)
      Try(jsValue).toOption
    } else Option.empty
  }

  private def mget(keys: String*): Task[Seq[Option[(String, ByteString)]]] =
    keys.toList.traverse(k => get(k).map(_.map(v => (k, v)))).map(_.toSeq)

  private def get(key: String): Task[Option[ByteString]] = toAsync {
    Try(client.get(bytes(key))).toOption
      .flatMap(s => Option(asString(s)))
      .map(ByteString.apply)
  }

  private def getByIds(keys: String*): Task[Seq[(String, JsValue)]] =
    mget(keys: _*).map(_.flatten).map {
      _.map {
        case (k, v) => (k, Json.parse(v.utf8String))
      }
    }

  private def getByKeys(keys: Key*): Task[Seq[(String, JsValue)]] =
    getByIds(keys.map(_.key): _*)

  private def keys(query: Query): Source[Key, NotUsed] =
    getAllKeys()
      .map { Key.apply }
      .filter { k =>
        Query.keyMatchQuery(k, query)
      }

  private def getAllKeys(): Source[String, NotUsed] = {
    val iterator = client.iterator()
    Source
      .future(Future {
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

  override def create(id: Key, data: JsValue): IO[IzanamiErrors, JsValue] =
    getById(id)
      .refineToOrDie[IzanamiErrors]
      .flatMap {
        case Some(_) =>
          IO.fail(DataShouldNotExists(id).toErrors)
        case None =>
          toAsync {
            client.put(bytes(id.key), bytes(Json.stringify(data)))
            data
          }.refineToOrDie[IzanamiErrors]
      }

  override def update(oldId: Key, id: Key, data: JsValue): IO[IzanamiErrors, JsValue] =
    toAsync { Try(client.get(bytes(oldId.key))).toOption.flatMap(s => Option(asString(s))) }
      .refineToOrDie[IzanamiErrors]
      .flatMap {
        case Some(_) =>
          client.delete(bytes(oldId.key))
          client.put(bytes(id.key), bytes(Json.stringify(data)))
          IO.succeed(data)
        case None =>
          IO.fail(DataShouldExists(id).toErrors)
      }

  override def delete(id: Key): IO[IzanamiErrors, JsValue] =
    getById(id)
      .refineToOrDie[IzanamiErrors]
      .flatMap {
        case Some(value) =>
          toAsync {
            client.delete(bytes(id.key))
            value
          }.refineToOrDie[IzanamiErrors]
        case None =>
          IO.fail(DataShouldExists(id).toErrors)
      }

  override def getById(id: Key): Task[Option[JsValue]] = {
    val effectiveKey = buildKey(id)
    getByStringId(effectiveKey.key)
  }

  override def findByQuery(query: Query, page: Int, nbElementPerPage: Int): Task[PagingResult[JsValue]] = {
    val position = (page - 1) * nbElementPerPage
    for {
      runtime <- ZIO.runtime[Any]
      res <- Task.fromFuture { implicit ec =>
              keys(query)
                .via(Flows.count {
                  Flow[Key]
                    .drop(position)
                    .take(nbElementPerPage)
                    .grouped(nbElementPerPage)
                    .mapAsync(4)(keys => runtime.unsafeRunToFuture(getByKeys(keys: _*)))
                    .map(_.map(_._2))
                    .fold(Seq.empty[JsValue])(_ ++ _)
                })
                .runWith(Sink.head)
                .map {
                  case (results, count) =>
                    DefaultPagingResult(results, page, nbElementPerPage, count)
                }
            }
    } yield res

  }

  override def findByQuery(query: Query): Task[Source[(Key, JsValue), NotUsed]] =
    for {
      runtime <- ZIO.runtime[Any]
      res <- Task(
              keys(query)
                .grouped(50)
                .mapAsync(4)(keys => runtime.unsafeRunToFuture(getByKeys(keys: _*)))
                .mapConcat(_.toList)
                .map {
                  case (k, v) => (Key(k), v)
                }
            )
    } yield res

  override def deleteAll(query: Query): IO[IzanamiErrors, Unit] =
    for {
      runtime <- ZIO.runtime[Any]
      res <- Task
              .fromFuture { implicit ec =>
                keys(query)
                  .mapAsync(4)(id => runtime.unsafeRunToFuture(delete(id).either))
                  .runWith(Sink.ignore)
                  .map { _ =>
                    ()
                  }
              }
              .refineToOrDie[IzanamiErrors]
    } yield res

  override def count(query: Query): Task[Long] =
    for {
      source <- findByQuery(query)
      res <- Task.fromFuture(
              _ =>
                source.runFold(0L) { (acc, _) =>
                  acc + 1
              }
            )
    } yield res

  def stop() =
    client.close()

}
