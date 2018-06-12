package store.redis

import akka.actor.ActorSystem
import akka.http.scaladsl.util.FastFuture
import akka.stream.ActorMaterializer
import akka.stream.scaladsl.{Flow, Sink, Source}
import akka.{Done, NotUsed}
import domains.Key
import env.DbDomainConfig
import libs.streams.Flows
import play.api.Logger
import play.api.libs.json.{JsValue, Json}
import redis.RedisClientMasterSlaves
import store.Result.ErrorMessage
import store._

import scala.concurrent.Future
import scala.concurrent.duration.FiniteDuration

object RedisJsonDataStore {
  def apply(client: RedisClientMasterSlaves, system: ActorSystem, name: String): RedisJsonDataStore =
    new RedisJsonDataStore(client, system, name)

  def apply(client: RedisClientMasterSlaves, config: DbDomainConfig, actorSystem: ActorSystem): RedisJsonDataStore = {
    val namespace = config.conf.namespace
    Logger.info(s"Load store Redis for namespace $namespace")
    RedisJsonDataStore(client, actorSystem, namespace)
  }
}

class RedisJsonDataStore(client: RedisClientMasterSlaves, system: ActorSystem, name: String) extends JsonDataStore {

  import system.dispatcher

  private implicit val mat = ActorMaterializer()(system)

  private def buildKey(key: Key) = Key.Empty / name / key

  private def getByKeyId(id: Key): Future[Option[JsValue]] = {
    val effectiveKey = buildKey(id)
    getByStringId(effectiveKey.key)
  }

  private def getByStringId(key: String): Future[Option[JsValue]] =
    client.get(key).map {
      _.map {
        _.utf8String
      }.map {
        Json.parse
      }
    }

  private def getByIds(keys: Key*): Future[Seq[JsValue]] =
    client.mget(keys.map(buildKey).map(_.key): _*).map(_.flatten).map {
      _.map {
        _.utf8String
      }.map {
        Json.parse
      }
    }

  private def patternsToKey(patterns: Seq[String]): Seq[Key] =
    patterns.map(Key.apply).map(buildKey)

  private def findKeys(patterns: Seq[String]): Source[Key, NotUsed] = patterns match {
    case p if p.isEmpty || p.contains("") =>
      Source.empty
    case p =>
      Source
        .unfoldAsync(0) { cursor =>
          client
            .scan(cursor, matchGlob = Some(s"$name:*"))
            .map { curs =>
              if (cursor == -1) {
                None
              } else if (curs.index == 0) {
                Some(-1, curs.data)
              } else {
                Some(curs.index, curs.data)
              }
            }
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
      client
        .set(buildKey(id).key, Json.stringify(data))
        .map(_ => Result.ok(data))
  }

  override def update(oldId: Key, id: Key, data: JsValue) =
    if (oldId == id) {
      client
        .set(buildKey(id).key, Json.stringify(data))
        .map(_ => Result.ok(data))
    } else {
      client
        .del(buildKey(oldId).key)
        .flatMap { _ =>
          create(id, data)
        }
    }

  override def delete(id: Key) =
    getByKeyId(id).flatMap {
      case Some(value) =>
        client
          .del(buildKey(id).key)
          .map(_ => Result.ok(value))

      case None =>
        FastFuture.successful(Result.error(s"error.data.missing"))
    }

  override def deleteAll(patterns: Seq[String]) = {
    val patternKeys: Seq[Key] = patternsToKey(patterns)
    for {
      keys <- Future.sequence {
               patternKeys.map(key => client.keys(key.key))
             }
      _ <- Future.sequence {
            keys.flatten(key => key.map(k => client.del(k)))
          }
    } yield Result.ok(Done)
  }

  override def getById(id: Key) =
    SimpleFindResult(getByKeyId(id).map(_.toList))

  override def getByIdLike(patterns: Seq[String]) = SourceFindResult(
    findKeys(patterns)
      .grouped(50)
      .mapAsyncUnordered(50)(getByIds)
      .mapConcat(_.toList)
  )

  override def getByIdLike(patterns: Seq[String], page: Int, nbElementPerPage: Int) = {
    val position = (page - 1) * nbElementPerPage
    findKeys(patterns) via Flows.count {
      Flow[Key]
        .drop(position)
        .take(nbElementPerPage)
        .grouped(nbElementPerPage)
        .mapAsyncUnordered(nbElementPerPage)(getByIds)
        .fold(Seq.empty[JsValue])(_ ++ _)
    } runWith Sink.head map {
      case (results, count) =>
        DefaultPagingResult(results, page, nbElementPerPage, count)
    }
  }

  override def count(patterns: Seq[String]): Future[Long] =
    getByIdLike(patterns).list.map(_.size)

}
