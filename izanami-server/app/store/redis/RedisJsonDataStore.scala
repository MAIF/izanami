package store.redis

import akka.actor.ActorSystem
import akka.http.scaladsl.util.FastFuture
import akka.stream.ActorMaterializer
import akka.stream.scaladsl.{Flow, Sink, Source}
import akka.{Done, NotUsed}
import cats.data.EitherT
import cats.effect.Effect
import domains.Key
import env.DbDomainConfig
import libs.streams.Flows
import libs.logs.IzanamiLogger
import play.api.libs.json.{JsValue, Json}
import store.Result.{AppErrors, ErrorMessage, Result}
import store._
import io.lettuce.core._
import io.lettuce.core.api.async.RedisAsyncCommands
import libs.functional.EitherTSyntax

import scala.compat.java8.FutureConverters._
import scala.collection.JavaConverters._

object RedisJsonDataStore {
  def apply[F[_]: Effect](client: RedisWrapper, name: String)(implicit system: ActorSystem): RedisJsonDataStore[F] =
    new RedisJsonDataStore(client, name)

  def apply[F[_]: Effect](client: RedisWrapper,
                          config: DbDomainConfig)(implicit system: ActorSystem): RedisJsonDataStore[F] = {
    val namespace = config.conf.namespace
    IzanamiLogger.info(s"Load store Redis for namespace $namespace")
    RedisJsonDataStore(client, namespace)
  }
}

class RedisJsonDataStore[F[_]: Effect](client: RedisWrapper, name: String)(implicit system: ActorSystem)
    extends JsonDataStore[F]
      with EitherTSyntax[F] {

  import system.dispatcher
  import cats.implicits._
  import cats.effect.implicits._
  import libs.effects._
  import libs.streams.syntax._
  import libs.functional.syntax._

  private implicit val mat = ActorMaterializer()(system)

  private def buildKey(key: Key) = Key.Empty / name / key

  private def fromRawKey(strKey: String): Key = Key(strKey.substring(name.length + 1))

  private def getByKeyId(id: Key): F[Option[JsValue]] = {
    val effectiveKey = buildKey(id)
    getByStringId(effectiveKey.key)
  }

  private def command(): RedisAsyncCommands[String, String] = client.connection.async()

  private def getByStringId(key: String): F[Option[JsValue]] =
    command()
      .get(key)
      .toF
      .map { Option(_).map { Json.parse } }

  private def getByIds(keys: Key*): F[Seq[(String, JsValue)]] =
    command()
      .mget(keys.map(buildKey).map(_.key): _*)
      .toF
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

  override def create(id: Key, data: JsValue): F[Result[JsValue]] = getByKeyId(id).flatMap {
    case Some(_) =>
      Result.errors[JsValue](ErrorMessage("error.data.exists", id.key)).pure[F]

    case None =>
      command()
        .set(buildKey(id).key, Json.stringify(data))
        .toF
        .map(_ => Result.ok(data))
  }

  override def update(oldId: Key, id: Key, data: JsValue): F[Result[JsValue]] = {
      if (oldId == id) {
        val res: EitherT[F, AppErrors, JsValue] = for {
          _ <- getByKeyId(oldId: Key) |> liftFOption[AppErrors, JsValue] { AppErrors.error(s"error.data.missing", id.key) }
          _ <- command().set(buildKey(id).key, Json.stringify(data)).toF.map(_ => data) |> liftF[AppErrors, JsValue]
        } yield data
        res.value
      } else {
        val res: EitherT[F, AppErrors, JsValue] = for {
          _ <- getByKeyId(oldId: Key) |> liftFOption[AppErrors, JsValue] { AppErrors.error(s"error.data.missing", id.key) }
          _ <- command().del(buildKey(oldId).key).toF |> liftF
          _ <- create(id, data) |> liftFEither[AppErrors, JsValue]
        } yield data
        res.value
      }
  }

  override def delete(id: Key): F[Result[JsValue]] =
    getByKeyId(id).flatMap {
      case Some(value) =>
        command()
          .del(buildKey(id).key)
          .toF
          .map(_ => Result.ok(value))

      case None =>
        Result.error[JsValue](s"error.data.missing").pure[F]
    }

  override def deleteAll(query: Query): F[Result[Done]] = {
    findByQuery(query)
      .map { case (k, _) => k.key }
      .grouped(20)
      .mapAsync(10) { keys =>
        val toDelete: Seq[String] = keys.map { k => buildKey(Key(k)).key }
        command().del( toDelete: _* ).toScala
      }
      .runWith(Sink.ignore)
      .toF
      .map(_ => Result.ok(Done))
  }

  override def getById(id: Key): F[Option[JsValue]] =
    getByKeyId(id)

  override def findByQuery(query: Query): Source[(Key, JsValue), NotUsed] =
    findKeys(query)
      .grouped(50)
      .mapAsyncUnorderedF(50)(getByIds)
      .mapConcat(_.toList)
      .map {
        case (k, v) => (fromRawKey(k), v)
      }

  override def findByQuery(query: Query, page: Int, nbElementPerPage: Int): F[PagingResult[JsValue]] = {
    val position = (page - 1) * nbElementPerPage
    findKeys(query)
      .via(Flows.count {
        Flow[Key]
          .drop(position)
          .take(nbElementPerPage)
          .grouped(nbElementPerPage)
          .mapAsyncUnorderedF(nbElementPerPage)(getByIds)
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

  override def count(query: Query): F[Long] =
    findByQuery(query)
      .runFold(0L) { (acc, _) =>
        acc + 1
      }
      .toF

}
