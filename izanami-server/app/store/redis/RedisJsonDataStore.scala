package store.redis

import java.util.concurrent.CompletionStage

import akka.actor.ActorSystem
import akka.http.scaladsl.util.FastFuture
import akka.stream.ActorMaterializer
import akka.stream.scaladsl.{Flow, Sink, Source}
import akka.NotUsed
import domains.Key
import domains.configuration.PlayModule
import env.{DbDomainConfig, IzanamiConfig}
import libs.streams.Flows
import play.api.libs.json.{JsValue, Json}
import domains.errors.IzanamiErrors
import store._
import store.datastore._
import io.lettuce.core._
import io.lettuce.core.api.async.RedisAsyncCommands

import scala.compat.java8.FutureConverters._
import scala.jdk.CollectionConverters._
import libs.logs.ZLogger
import domains.errors.DataShouldExists
import domains.errors.DataShouldNotExists
import env.configuration.IzanamiConfigModule
import libs.database.Drivers
import libs.database.Drivers.{DriverLayerContext, RedisDriver}
import zio.{Has, ZLayer}

object RedisJsonDataStore {

  def live(conf: DbDomainConfig): ZLayer[RedisDriver with DriverLayerContext, Throwable, JsonDataStore] =
    ZLayer.fromFunction { mix =>
      val playModule: PlayModule.Service    = mix.get[PlayModule.Service]
      implicit val actorSystem: ActorSystem = playModule.system
      val namespace                         = conf.conf.namespace
      val Some(redisDriver)                 = mix.get[Option[RedisWrapper]]
      RedisJsonDataStore(redisDriver, namespace)
    }

  def apply(client: RedisWrapper, name: String)(implicit system: ActorSystem): RedisJsonDataStore =
    new RedisJsonDataStore(client, name)

  def apply(client: RedisWrapper, config: DbDomainConfig)(implicit system: ActorSystem): RedisJsonDataStore = {
    val namespace = config.conf.namespace
    RedisJsonDataStore(client, namespace)
  }
}

class RedisJsonDataStore(client: RedisWrapper, name: String)(implicit system: ActorSystem)
    extends JsonDataStore.Service {

  import zio._
  import system.dispatcher
  import cats.implicits._
  import IzanamiErrors._

  override def start: RIO[DataStoreContext, Unit] = ZLogger.info(s"Load store Redis for namespace $name")

  private def buildKey(key: Key) = Key.Empty / name / key

  private def fromRawKey(strKey: String): Key = Key(strKey.substring(name.length + 1))

  private def getByKeyId(id: Key): Task[Option[JsValue]] = {
    val effectiveKey = buildKey(id)
    getByStringId(effectiveKey.key)
  }

  private def zioFromCs[T](cs: => CompletionStage[T]): Task[T] =
    Task.effectAsync { cb =>
      cs.whenComplete((ok, e) => {
        if (e != null) {
          cb(Task.fail(e))
        } else {
          cb(Task.succeed(ok))
        }
      })
    }

  private def command(): RedisAsyncCommands[String, String] = client.connection.async()

  private def getByStringId(key: String): Task[Option[JsValue]] =
    zioFromCs(command().get(key))
      .map { Option(_).map { Json.parse } }

  private def getByIds(keys: Key*): Task[Seq[(String, JsValue)]] =
    zioFromCs(command().mget(keys.map(buildKey).map(_.key): _*))
      .map { entries =>
        entries.asScala.toSeq
          .map { kv =>
            (kv.getKey, Option(kv.getValue))
          }
          .collect {
            case (k, Some(v)) => (k, Json.parse(v))
          }
      }

  private def findKeys(query: Query): Source[Key, NotUsed] = query match {
    case q if q.hasEmpty =>
      Source.empty
    case _ =>
      Source
        .unfoldAsync(ScanCursor.INITIAL.some) {
          case Some(c) =>
            command()
              .scan(c, ScanArgs.Builder.matches(s"$name:*").limit(500))
              .toScala
              .map { curs =>
                if (curs.isFinished) {
                  Some((None, curs.getKeys.asScala))
                } else {
                  Some(Some(curs), curs.getKeys.asScala)
                }
              }
          case None =>
            FastFuture.successful(None)
        }
        .mapConcat(_.toList)
        .map(Key.apply)
        .map(_.drop(name))
        .filter(k => Query.keyMatchQuery(k, query))
  }

  override def create(id: Key, data: JsValue): IO[IzanamiErrors, JsValue] =
    getByKeyId(id).orDie
      .flatMap {
        case Some(_) =>
          IO.fail(DataShouldNotExists(id).toErrors)

        case None =>
          zioFromCs(command().set(buildKey(id).key, Json.stringify(data)))
            .map(_ => data)
            .orDie
      }

  private def rawUpdate(id: Key, data: JsValue) =
    zioFromCs(command().set(buildKey(id).key, Json.stringify(data))).orDie

  override def update(oldId: Key, id: Key, data: JsValue): IO[IzanamiErrors, JsValue] =
    for {
      mayBe <- getByKeyId(oldId: Key).orDie
      _     <- IO.fromOption(mayBe).mapError(_ => DataShouldExists(oldId).toErrors)
      _     <- IO.when(oldId =!= id)(zioFromCs(command().del(buildKey(oldId).key)).orDie)
      _     <- if (oldId === id) rawUpdate(id, data) else create(id, data)
    } yield data

  override def delete(id: Key): IO[IzanamiErrors, JsValue] =
    getByKeyId(id).orDie
      .flatMap {
        case Some(value) =>
          zioFromCs(command().del(buildKey(id).key)).orDie
            .map(_ => value)
        case None =>
          IO.fail(DataShouldExists(id).toErrors)
      }

  override def deleteAll(query: Query): IO[IzanamiErrors, Unit] =
    findByQuery(query)
      .flatMap(
        s =>
          IO.fromFuture(
            _ =>
              s.map { case (k, _) => k.key }
                .grouped(20)
                .mapAsync(10) { keys =>
                  val toDelete: Seq[String] = keys.map { k =>
                    buildKey(Key(k)).key
                  }
                  command().del(toDelete: _*).toScala
                }
                .runWith(Sink.ignore)
        )
      )
      .orDie
      .unit

  override def getById(id: Key): Task[Option[JsValue]] =
    getByKeyId(id)

  override def findByQuery(query: Query): Task[Source[(Key, JsValue), NotUsed]] =
    for {
      runtime <- ZIO.runtime[Any]
      res <- IO(
              findKeys(query)
                .grouped(50)
                .mapAsyncUnordered(50)(ids => runtime.unsafeRunToFuture(getByIds(ids: _*)))
                .mapConcat(_.toList)
                .map {
                  case (k, v) => (fromRawKey(k), v)
                }
            )
    } yield res

  override def findByQuery(query: Query, page: Int, nbElementPerPage: Int): Task[PagingResult[JsValue]] = {
    val position = (page - 1) * nbElementPerPage
    for {
      runtime <- ZIO.runtime[Any]
      res <- IO
              .fromFuture { _ =>
                findKeys(query)
                  .via(Flows.count {
                    Flow[Key]
                      .drop(position)
                      .take(nbElementPerPage)
                      .grouped(nbElementPerPage)
                      .mapAsyncUnordered(nbElementPerPage)(ids => runtime.unsafeRunToFuture(getByIds(ids: _*)))
                      .map(_.map(_._2))
                      .fold(Seq.empty[JsValue])(_ ++ _)
                  })
                  .runWith(Sink.head)
              }
              .map {
                case (results, count) =>
                  DefaultPagingResult(results, page, nbElementPerPage, count)
              }
    } yield res

  }

  override def count(query: Query): Task[Long] =
    findByQuery(query)
      .flatMap { s =>
        IO.fromFuture { _ =>
          s.runFold(0L) { (acc, _) =>
            acc + 1
          }
        }
      }
}
