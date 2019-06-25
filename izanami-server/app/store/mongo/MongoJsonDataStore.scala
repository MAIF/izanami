package store.mongo

import akka.{Done, NotUsed}
import akka.actor.ActorSystem
import akka.stream.scaladsl.Source
import akka.stream.{ActorMaterializer, Materializer}
import cats.effect.Effect
import domains.Key
import env.DbDomainConfig
import libs.mongo.MongoUtils
import libs.logs.IzanamiLogger
import play.api.libs.json.{JsBoolean, JsObject, JsValue, Json}
import play.modules.reactivemongo.ReactiveMongoApi
import reactivemongo.akkastream._
import reactivemongo.api.indexes.{Index, IndexType}
import reactivemongo.api.{Cursor, QueryOpts, ReadConcern, ReadPreference}
import reactivemongo.play.json._
import reactivemongo.play.json.collection.JSONCollection
import store.Result.{AppErrors, Result}
import store._
import libs.functional.EitherTSyntax

import scala.concurrent.duration.DurationLong
import scala.concurrent.{Await, Future}

case class MongoDoc(id: Key, data: JsValue)

object MongoDoc {
  implicit val format = Json.format[MongoDoc]
}

object MongoJsonDataStore {
  def apply[F[_]: Effect](mongoApi: ReactiveMongoApi,
                          config: DbDomainConfig)(implicit actorSystem: ActorSystem): MongoJsonDataStore[F] =
    new MongoJsonDataStore[F](config.conf.namespace, mongoApi)
}

class MongoJsonDataStore[F[_]: Effect](namespace: String, mongoApi: ReactiveMongoApi)(implicit actorSystem: ActorSystem)
    extends JsonDataStore[F]
    with EitherTSyntax[F] {

  import cats.implicits._
  import libs.effects._
  import libs.streams.syntax._
  import libs.functional.syntax._
  import actorSystem.dispatcher

  private val collectionName = namespace.replaceAll(":", "_")

  IzanamiLogger.debug(s"Initializing mongo collection $collectionName")

  private implicit val mapi: ReactiveMongoApi = mongoApi
  private implicit val mat: Materializer      = ActorMaterializer()

  private val indexesDefinition: Seq[Index] = Seq(Index(Seq("id" -> IndexType.Ascending), unique = true))

  private def initIndexes(): Future[Unit] =
    MongoUtils.initIndexes(collectionName, indexesDefinition)

  Await.result(initIndexes(), 20.second)

  private def storeCollection = mongoApi.database.map(_.collection[JSONCollection](collectionName))

  override def create(id: Key, data: JsValue): F[Result[JsValue]] = {
    IzanamiLogger.debug(s"Creating $id => $data")
    storeCollection
      .map(_.insert.one(MongoDoc(id, data)))
      .map { _ =>
        Result.ok(data)
      }
      .toF
  }

  override def update(oldId: Key, id: Key, data: JsValue): F[Result[JsValue]] =
    storeCollection.toF.flatMap { implicit collection =>
      if (oldId == id) {
        val res = for {
          _ <- getByIdRaw(oldId: Key) |> liftFOption[AppErrors, JsValue] { AppErrors.error(s"error.data.missing") }
          _ <- updateRaw(id, data) |> liftFEither[AppErrors, JsValue]
        } yield data
        res.value
      } else {
        val res = for {
          _ <- getByIdRaw(oldId: Key) |> liftFOption[AppErrors, JsValue] { AppErrors.error(s"error.data.missing") }
          _ <- deleteRaw(oldId) |> liftFEither[AppErrors, Unit]
          _ <- createRaw(id, data) |> liftFEither[AppErrors, JsValue]
        } yield data
        res.value
      }
    }

  override def delete(id: Key): F[Result[JsValue]] =
    storeCollection.toF.flatMap { implicit collection: JSONCollection =>
      getByIdRaw(id).flatMap {
        case Some(data) => deleteRaw(id).map(_.map(_ => data))
        case None       => Result.error[JsValue](s"error.data.missing").pure[F]
      }
    }

  private def deleteRaw(id: Key)(implicit collection: JSONCollection): F[Result[Unit]] =
    collection.delete.one(Json.obj("id" -> id.key)).toF.map(_ => Result.ok(()))

  private def updateRaw(id: Key, data: JsValue)(implicit collection: JSONCollection): F[Result[JsValue]] =
    collection.update.one(Json.obj("id" -> id.key), MongoDoc(id, data), upsert = true).toF.map(_ => Result.ok(data))

  private def createRaw(id: Key, data: JsValue)(implicit collection: JSONCollection): F[Result[JsValue]] =
    collection.insert.one(MongoDoc(id, data)).toF.map(_ => Result.ok(data))

  private def getByIdRaw(id: Key)(implicit collection: JSONCollection): F[Option[JsValue]] = {
    IzanamiLogger.debug(s"Mongo query $collectionName findById ${id.key}")
    collection
      .find(Json.obj("id" -> id.key), projection = Option.empty[JsObject])
      .one[MongoDoc]
      .map(_.map(_.data))
      .toF
  }

  override def getById(id: Key): F[Option[JsValue]] =
    storeCollection.toF
      .flatMap(getByIdRaw(id)(_))

  override def findByQuery(q: Query, page: Int, nbElementPerPage: Int): F[PagingResult[JsValue]] = {
    val from    = (page - 1) * nbElementPerPage
    val options = QueryOpts(skipN = from, batchSizeN = nbElementPerPage, flagsN = 0)
    val query   = buildMongoQuery(q)
    IzanamiLogger.debug(
      s"Mongo query $collectionName find ${Json.stringify(query)}, page = $page, pageSize = $nbElementPerPage"
    )
    storeCollection.toF.flatMap { implicit collection =>
      val findResult: F[Seq[MongoDoc]] = collection
        .find(query, projection = Option.empty[JsObject])
        .options(options)
        .cursor[MongoDoc](ReadPreference.primary)
        .collect[Seq](maxDocs = nbElementPerPage, Cursor.FailOnError[Seq[MongoDoc]]())
        .toF

      val countResult: F[Long] = countRaw(q)

      (countResult, findResult).mapN { (count, res) =>
        DefaultPagingResult(res.map(_.data), page, nbElementPerPage, count.toInt)
      }
    }
  }

  override def findByQuery(q: Query): Source[(Key, JsValue), NotUsed] = Source.fromFuture(storeCollection).flatMapConcat {
    val query = buildMongoQuery(q)
    IzanamiLogger.debug(s"Mongo query $collectionName find ${Json.stringify(query)} as stream")
    _.find(query, projection = Option.empty[JsObject])
      .cursor[MongoDoc](ReadPreference.primary)
      .documentSource()
      .map(mongoDoc => (mongoDoc.id, mongoDoc.data))
  }

  private def countRaw(query: Query)(implicit collection: JSONCollection): F[Long] = {
    val q = buildMongoQuery(query)
    IzanamiLogger.debug(s"Mongo query $collectionName count ${Json.stringify(q)}")
    collection
      .count(
        selector = Some(q),
        limit = None,
        skip = 0,
        hint = None,
        readConcern = ReadConcern.Majority
      )
      .toF[F]
  }

  override def deleteAll(query: Query): F[Result[Done]] =
    storeCollection.map(_.delete.element(buildMongoQuery(query))).toF.map(_ => Result.ok(Done))

  override def count(query: Query): F[Long] =
    storeCollection.toF.flatMap(countRaw(query)(_)).map(_.longValue())

  private def buildMongoQuery(query: Query): JsObject = {

    val searchs = query.ands
      .map { clauses =>

        val andClause = clauses.patterns
          .map {
            case StringPattern(str) => str match {
              case p if p.contains("*") =>
                val regex = Json.obj("$regex" -> p.replaceAll("\\*", ".*"), "$options" -> "i")
                Json.obj("id" -> regex)
              case p =>
                Json.obj("id" -> p)
            }
            case EmptyPattern =>
              JsBoolean(false)
          }.toList
        Json.obj("$or" -> andClause.toList)
      }
    Json.obj("$and" -> searchs.toList)
  }

}
