package store.redis

import akka.actor.ActorSystem
import akka.http.scaladsl.util.FastFuture
import akka.stream.ActorMaterializer
import akka.stream.scaladsl.{Flow, Sink, Source}
import akka.{Done, NotUsed}
import cats.syntax.option._
import domains.Key
import env.DbDomainConfig
import libs.streams.Flows
import play.api.Logger
import play.api.libs.json.{JsValue, Json}
import store.Result.ErrorMessage
import store._
import io.lettuce.core._
import io.lettuce.core.api.async.RedisAsyncCommands

import scala.concurrent.Future
import scala.compat.java8.FutureConverters._
import scala.collection.JavaConverters._

object RedisJsonDataStore {
  def apply(client: RedisWrapper, system: ActorSystem, name: String): RedisJsonDataStore =
    new RedisJsonDataStore(client, system, name)

  def apply(client: RedisWrapper, config: DbDomainConfig, actorSystem: ActorSystem): RedisJsonDataStore = {
    val namespace = config.conf.namespace
    Logger.info(s"Load store Redis for namespace $namespace")
    RedisJsonDataStore(client, actorSystem, namespace)
  }
}

class RedisJsonDataStore(client: RedisWrapper, system: ActorSystem, name: String) extends JsonDataStore {

  import system.dispatcher

  private implicit val mat = ActorMaterializer()(system)

  private def buildKey(key: Key) = Key.Empty / name / key

  private def getByKeyId(id: Key): Future[Option[JsValue]] = {
    val effectiveKey = buildKey(id)
    getByStringId(effectiveKey.key)
  }

  private def command(): RedisAsyncCommands[String, String] = client.connection.async()

  private def getByStringId(key: String): Future[Option[JsValue]] =
    command()
      .get(key)
      .toScala
      .map { Option(_).map { Json.parse } }

  private def getByIds(keys: Key*): Future[Seq[(String, JsValue)]] =
    command()
      .mget(keys.map(buildKey).map(_.key): _*)
      .toScala
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

  private def findKeys(patterns: Seq[String]): Source[Key, NotUsed] = patterns match {
    case p if p.isEmpty || p.contains("") =>
      Source.empty
    case p =>
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
        .filter(k => k.matchPatterns(patterns: _*))
  }

  override def create(id: Key, data: JsValue) = getByKeyId(id).flatMap {
    case Some(_) =>
      FastFuture.successful(Result.errors(ErrorMessage("error.data.exists", id.key)))

    case None =>
      command()
        .set(buildKey(id).key, Json.stringify(data))
        .toScala
        .map(_ => Result.ok(data))
  }

  override def update(oldId: Key, id: Key, data: JsValue) =
    if (oldId == id) {
      command()
        .set(buildKey(id).key, Json.stringify(data))
        .toScala
        .map(_ => Result.ok(data))
    } else {
      command()
        .del(buildKey(oldId).key)
        .toScala
        .flatMap { _ =>
          create(id, data)
        }
    }

  override def delete(id: Key) =
    getByKeyId(id).flatMap {
      case Some(value) =>
        command()
          .del(buildKey(id).key)
          .toScala
          .map(_ => Result.ok(value))

      case None =>
        FastFuture.successful(Result.error(s"error.data.missing"))
    }

  override def deleteAll(patterns: Seq[String]) = {
    val patternKeys: Seq[Key] = patternsToKey(patterns)
    getByIdLike(patterns)
      .mapAsync(10) {
        case (k, _) => command().del(k.key).toScala
      }
      .runWith(Sink.ignore)
      .map(_ => Result.ok(Done))
  }

  override def getById(id: Key) =
    SimpleFindResult(getByKeyId(id).map(_.toList))

  override def getByIdLike(patterns: Seq[String]) =
    findKeys(patterns)
      .grouped(50)
      .mapAsyncUnordered(50)(getByIds)
      .mapConcat(_.toList)
      .map {
        case (k, v) => (Key(k), v)
      }

  override def getByIdLike(patterns: Seq[String], page: Int, nbElementPerPage: Int) = {
    val position = (page - 1) * nbElementPerPage
    findKeys(patterns) via Flows.count {
      Flow[Key]
        .drop(position)
        .take(nbElementPerPage)
        .grouped(nbElementPerPage)
        .mapAsyncUnordered(nbElementPerPage)(getByIds)
        .map(_.map(_._2))
        .fold(Seq.empty[JsValue])(_ ++ _)
    } runWith Sink.head map {
      case (results, count) =>
        DefaultPagingResult(results, page, nbElementPerPage, count)
    }
  }

  override def count(patterns: Seq[String]): Future[Long] =
    getByIdLike(patterns).runFold(0L) { (acc, _) =>
      acc + 1
    }

}
