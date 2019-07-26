package store.redis

import java.util.concurrent.CompletionStage

import akka.actor.ActorSystem
import akka.http.scaladsl.util.FastFuture
import akka.stream.ActorMaterializer
import akka.stream.scaladsl.{Flow, Sink, Source}
import akka.NotUsed
import domains.Key
import env.DbDomainConfig
import libs.streams.Flows
import libs.logs.IzanamiLogger
import play.api.libs.json.{JsValue, Json}
import store.Result.{AppErrors, IzanamiErrors}
import store._
import io.lettuce.core._
import io.lettuce.core.api.async.RedisAsyncCommands

import scala.compat.java8.FutureConverters._
import scala.collection.JavaConverters._
import libs.logs.Logger
import store.Result.DataShouldExists
import store.Result.DataShouldNotExists

object RedisJsonDataStore {
  def apply(client: RedisWrapper, name: String)(implicit system: ActorSystem): RedisJsonDataStore =
    new RedisJsonDataStore(client, name)

  def apply(client: RedisWrapper, config: DbDomainConfig)(implicit system: ActorSystem): RedisJsonDataStore = {
    val namespace = config.conf.namespace
    RedisJsonDataStore(client, namespace)
  }
}

class RedisJsonDataStore(client: RedisWrapper, name: String)(implicit system: ActorSystem) extends JsonDataStore {

  import zio._
  import system.dispatcher
  import cats.implicits._

  private implicit val mat = ActorMaterializer()(system)

  override def start: RIO[DataStoreContext, Unit] = Logger.info(s"Load store Redis for namespace $name")

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
        entries.asScala
          .map { kv =>
            (kv.getKey, Option(kv.getValue))
          }
          .collect {
            case (k, Some(v)) => (k, Json.parse(v))
          }
      }

  private def patternsToKey(patterns: Seq[String]): Seq[Key] =
    patterns.map(Key.apply).map(buildKey)

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
    getByKeyId(id)
      .refineToOrDie[IzanamiErrors]
      .flatMap {
        case Some(_) =>
          IO.fail(DataShouldNotExists(id))

        case None =>
          zioFromCs(command().set(buildKey(id).key, Json.stringify(data)))
            .map(_ => data)
            .refineToOrDie[IzanamiErrors]
      }

  override def update(oldId: Key, id: Key, data: JsValue): IO[IzanamiErrors, JsValue] =
    // format:off
    if (oldId == id) {
      for {
        mayBe <- getByKeyId(oldId: Key).refineToOrDie[IzanamiErrors]
        _ <- IO.fromOption(mayBe).mapError { _ =>
              DataShouldExists(oldId)
            }
        _ <- zioFromCs(command().set(buildKey(id).key, Json.stringify(data))).refineToOrDie[IzanamiErrors]
      } yield data
    } else {
      for {
        mayBe <- getByKeyId(oldId: Key).refineToOrDie[IzanamiErrors]
        _ <- IO.fromOption(mayBe).mapError { _ =>
              DataShouldExists(oldId)
            }
        _ <- zioFromCs(command().del(buildKey(oldId).key)).refineToOrDie[IzanamiErrors]
        _ <- create(id, data)
      } yield data
    }
  // format:on

  override def delete(id: Key): IO[IzanamiErrors, JsValue] =
    getByKeyId(id)
      .refineToOrDie[IzanamiErrors]
      .flatMap {
        case Some(value) =>
          zioFromCs(command().del(buildKey(id).key))
            .refineToOrDie[IzanamiErrors]
            .map(_ => value)
        case None =>
          IO.fail(DataShouldExists(id))
      }

  override def deleteAll(query: Query): IO[IzanamiErrors, Unit] =
    findByQuery(query)
      .flatMap(
        s =>
          IO.fromFuture(
            implicit ec =>
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
      .refineToOrDie[IzanamiErrors]
      .map(_ => ())

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
              .fromFuture { implicit ec =>
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
        IO.fromFuture { implicit ec =>
          s.runFold(0L) { (acc, _) =>
            acc + 1
          }
        }
      }
}
