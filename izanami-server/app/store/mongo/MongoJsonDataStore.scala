package store.mongo

import akka.{Done, NotUsed}
import akka.actor.ActorSystem
import akka.http.scaladsl.util.FastFuture
import akka.stream.scaladsl.Source
import akka.stream.{ActorMaterializer, Materializer}
import domains.Key
import env.DbDomainConfig
import libs.mongo.MongoUtils
import play.api.Logger
import play.api.libs.json.{JsObject, JsValue, Json}
import play.modules.reactivemongo.ReactiveMongoApi
import reactivemongo.akkastream._
import reactivemongo.api.indexes.{Index, IndexType}
import reactivemongo.api.{Cursor, QueryOpts, ReadPreference}
import reactivemongo.play.json._
import reactivemongo.play.json.collection.JSONCollection
import store.Result.{AppErrors, Result}
import store._

import scala.concurrent.duration.DurationLong
import scala.concurrent.{Await, ExecutionContext, Future}

case class MongoDoc(id: Key, data: JsValue)

object MongoDoc {
  implicit val format = Json.format[MongoDoc]
}

object MongoJsonDataStore {
  def apply(mongoApi: ReactiveMongoApi, config: DbDomainConfig, system: ActorSystem): MongoJsonDataStore =
    new MongoJsonDataStore(config.conf.namespace, mongoApi)(system, system.dispatcher)
}

class MongoJsonDataStore(namespace: String, mongoApi: ReactiveMongoApi)(implicit actorSystem: ActorSystem,
                                                                        ec: ExecutionContext)
    extends JsonDataStore[Future] {

  import cats.implicits._
  import libs.functional.EitherTOps._
  import libs.functional.syntax._

  private val collectionName = namespace.replaceAll(":", "_")

  Logger.debug(s"Initializing mongo collection $collectionName")

  private implicit val mapi: ReactiveMongoApi = mongoApi
  private implicit val mat: Materializer      = ActorMaterializer()

  private val indexesDefinition: Seq[Index] = Seq(Index(Seq("id" -> IndexType.Ascending), unique = true))

  private def initIndexes(): Future[Unit] =
    MongoUtils.initIndexes(collectionName, indexesDefinition)

  Await.result(initIndexes(), 5.second)

  private def storeCollection = mongoApi.database.map(_.collection[JSONCollection](collectionName))

  override def create(id: Key, data: JsValue): Future[Result[JsValue]] = {
    Logger.debug(s"Creating $id => $data")
    storeCollection
      .map(_.insert(MongoDoc(id, data)))
      .map { _ =>
        Result.ok(data)
      }
  }

  override def update(oldId: Key, id: Key, data: JsValue): Future[Result[JsValue]] =
    storeCollection.flatMap { implicit collection =>
      if (oldId == id) {
        val res = for {
          _ <- getByIdRaw(oldId: Key) |> liftFOption[AppErrors, JsValue] { AppErrors.error(s"error.data.missing") }
          _ <- updateRaw(id, data) |> liftFEither[AppErrors, JsValue]
        } yield data
        res.value
      } else {
        val res = for {
          _ <- getByIdRaw(oldId: Key) |> liftFOption[AppErrors, JsValue] { AppErrors.error(s"error.data.missing") }
          _ <- deleteRaw(id) |> liftFEither[AppErrors, Unit]
          _ <- createRaw(id, data) |> liftFEither[AppErrors, JsValue]
        } yield data
        res.value
      }
    }

  override def delete(id: Key): Future[Result[JsValue]] =
    storeCollection.flatMap { implicit collection: JSONCollection =>
      getByIdRaw(id).flatMap {
        case Some(data) => deleteRaw(id).map(_.map(_ => data))
        case None       => FastFuture.successful(Result.error(s"error.data.missing"))
      }
    }

  private def deleteRaw(id: Key)(implicit collection: JSONCollection): Future[Result[Unit]] =
    collection.remove(Json.obj("id" -> id.key)).map(_ => Result.ok(()))

  override def deleteAll(patterns: Seq[String]): Future[Result[Done]] =
    storeCollection.map(_.remove(Json.obj())).map(_ => Result.ok(Done))

  private def updateRaw(id: Key, data: JsValue)(implicit collection: JSONCollection): Future[Result[JsValue]] =
    collection.update(Json.obj("id" -> id.key), MongoDoc(id, data), upsert = true).map(_ => Result.ok(data))

  private def createRaw(id: Key, data: JsValue)(implicit collection: JSONCollection): Future[Result[JsValue]] =
    collection.insert(MongoDoc(id, data)).map(_ => Result.ok(data))

  private def getByIdRaw(id: Key)(implicit collection: JSONCollection): Future[Option[JsValue]] = {
    Logger.debug(s"Mongo query $collectionName findById ${id.key}")
    collection
      .find(Json.obj("id" -> id.key))
      .one[MongoDoc]
      .map(_.map(_.data))
  }

  override def getById(id: Key): Future[Option[JsValue]] =
    storeCollection
      .flatMap(getByIdRaw(id)(_))

  override def getByIdLike(patterns: Seq[String], page: Int, nbElementPerPage: Int): Future[PagingResult[JsValue]] = {
    val from    = (page - 1) * nbElementPerPage
    val options = QueryOpts(skipN = from, batchSizeN = nbElementPerPage, flagsN = 0)
    val query   = buildPatternLikeRequest(patterns)
    Logger.debug(
      s"Mongo query $collectionName find ${Json.stringify(query)}, page = $page, pageSize = $nbElementPerPage"
    )
    storeCollection.flatMap { implicit collection =>
      val findResult: Future[Seq[MongoDoc]] = collection
        .find(query)
        .options(options)
        .cursor[MongoDoc](ReadPreference.primary)
        .collect[Seq](maxDocs = nbElementPerPage, Cursor.FailOnError[Seq[MongoDoc]]())

      val countResult: Future[Int] = countRaw(patterns)

      (countResult, findResult).mapN { (count, res) =>
        DefaultPagingResult(res.map(_.data), page, nbElementPerPage, count)
      }
    }
  }

  override def getByIdLike(patterns: Seq[String]): Source[(Key, JsValue), NotUsed] =
    Source.fromFuture(storeCollection).flatMapConcat {
      val query = buildPatternLikeRequest(patterns)
      Logger.debug(s"Mongo query $collectionName find ${Json.stringify(query)} as stream")
      _.find(query)
        .cursor[MongoDoc](ReadPreference.primary)
        .documentSource()
        .map(mongoDoc => (mongoDoc.id, mongoDoc.data))
    }

  private def countRaw(patterns: Seq[String])(implicit collection: JSONCollection): Future[Int] = {
    val query = buildPatternLikeRequest(patterns)
    Logger.debug(s"Mongo query $collectionName count ${Json.stringify(query)}")
    collection.count(Some(query))
  }

  override def count(patterns: Seq[String]): Future[Long] =
    storeCollection.flatMap(countRaw(patterns)(_)).map(_.longValue())

  private def buildPatternLikeRequest(patterns: Seq[String]): JsObject = {
    val searchs = patterns.map {
      case p if p.contains("*") =>
        val regex = Json.obj("$regex" -> p.replaceAll("\\*", ".*"), "$options" -> "i")
        Json.obj("id" -> regex)
      case p =>
        Json.obj("id" -> p)
    }
    Json.obj("$and" -> searchs)
  }

}
