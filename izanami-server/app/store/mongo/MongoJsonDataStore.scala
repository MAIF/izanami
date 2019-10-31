package store.mongo

import akka.NotUsed
import akka.actor.ActorSystem
import akka.http.scaladsl.util.FastFuture
import akka.stream.scaladsl.Source
import akka.stream.{ActorMaterializer, Materializer}
import cats.implicits._
import domains.Key
import env.DbDomainConfig
import libs.mongo.MongoUtils
import play.api.libs.json.{JsObject, JsValue, Json}
import play.modules.reactivemongo.ReactiveMongoApi
import reactivemongo.akkastream._
import reactivemongo.api.indexes.{Index, IndexType}
import reactivemongo.api.{Cursor, QueryOpts, ReadConcern, ReadPreference, WriteConcern}
import reactivemongo.play.json._
import reactivemongo.play.json.collection.JSONCollection
import store.Result.IzanamiErrors
import store._

import scala.concurrent.{ExecutionContext, Future}
import libs.logs.Logger
import store.Result.DataShouldExists
import store.Result.DataShouldNotExists

case class MongoDoc(id: Key, data: JsValue)

object MongoDoc {
  implicit val format = Json.format[MongoDoc]
}

object MongoJsonDataStore {
  def apply(mongoApi: ReactiveMongoApi, config: DbDomainConfig)(implicit actorSystem: ActorSystem): MongoJsonDataStore =
    new MongoJsonDataStore(config.conf.namespace, mongoApi)
}

class MongoJsonDataStore(namespace: String, mongoApi: ReactiveMongoApi)(implicit actorSystem: ActorSystem)
    extends JsonDataStore {

  import zio._
  //import actorSystem.dispatcher

  private val collectionName = namespace.replaceAll(":", "_")

  private implicit val mapi: ReactiveMongoApi = mongoApi
  private implicit val mat: Materializer      = ActorMaterializer()

  private val indexesDefinition: Seq[Index] = Seq(Index(Seq("id" -> IndexType.Ascending), unique = true))

  private def initIndexes()(implicit ec: ExecutionContext): Future[Unit] =
    MongoUtils.initIndexes(collectionName, indexesDefinition)

  override def start: RIO[DataStoreContext, Unit] =
    Logger.debug(s"Initializing mongo collection $collectionName") *>
    Task.fromFuture { implicit ec =>
      initIndexes()
    }

  private def storeCollection(implicit ec: ExecutionContext) =
    mongoApi.database.map(_.collection[JSONCollection](collectionName))
  private def storeCollectionT: Task[JSONCollection] = Task.fromFuture { implicit ec =>
    mongoApi.database.map(_.collection[JSONCollection](collectionName))
  }
  private def storeCollectionIO: IO[IzanamiErrors, JSONCollection] = storeCollectionT.refineToOrDie[IzanamiErrors]

  override def create(id: Key, data: JsValue): ZIO[DataStoreContext, IzanamiErrors, JsValue] =
    for {
      _     <- Logger.debug(s"Creating $id => $data")
      coll  <- storeCollectionIO
      mayBe <- getByIdRaw(id)(coll).refineToOrDie[IzanamiErrors]
      _     <- IO.fromOption(mayBe).flip.mapError(_ => DataShouldNotExists(id))
      res <- IO
              .fromFuture { implicit ec =>
                coll
                  .insert(ordered = false, WriteConcern.Acknowledged)
                  .one(MongoDoc(id, data))
                  .map { _ =>
                    data
                  }
              }
              .refineToOrDie[IzanamiErrors]
    } yield res

  override def update(oldId: Key, id: Key, data: JsValue): ZIO[DataStoreContext, IzanamiErrors, JsValue] =
    storeCollectionIO.flatMap { implicit coll =>
      for {
        mayBe <- getByIdRaw(oldId).refineToOrDie[IzanamiErrors]
        _     <- IO.fromOption(mayBe).mapError(_ => DataShouldExists(oldId))
        _     <- ZIO.when(oldId =!= id)(deleteRaw(oldId))
        _     <- if (oldId === id) updateRaw(id, data) else createRaw(id, data)
      } yield data
    }

  override def delete(id: Key): ZIO[DataStoreContext, IzanamiErrors, JsValue] =
    storeCollectionIO.flatMap { implicit collection: JSONCollection =>
      getByIdRaw(id).refineToOrDie[IzanamiErrors].flatMap {
        case Some(data) => deleteRaw(id).map(_ => data)
        case None       => IO.fail(DataShouldExists(id))
      }
    }

  private def deleteRaw(id: Key)(implicit collection: JSONCollection): ZIO[DataStoreContext, IzanamiErrors, Unit] =
    IO.fromFuture { implicit ec =>
        collection.delete.one(Json.obj("id" -> id.key))
      }
      .refineToOrDie[IzanamiErrors]
      .map(_ => ())

  private def updateRaw(id: Key, data: JsValue)(
      implicit collection: JSONCollection
  ): ZIO[DataStoreContext, IzanamiErrors, JsValue] =
    IO.fromFuture { implicit ec =>
        collection.update.one(Json.obj("id" -> id.key), MongoDoc(id, data), upsert = true)
      }
      .refineToOrDie[IzanamiErrors]
      .map(_ => data)

  private def createRaw(id: Key, data: JsValue)(
      implicit collection: JSONCollection
  ): ZIO[DataStoreContext, IzanamiErrors, JsValue] =
    IO.fromFuture { implicit ec =>
        collection.insert.one(MongoDoc(id, data))
      }
      .refineToOrDie[IzanamiErrors]
      .map(_ => data)

  private def getByIdRaw(id: Key)(implicit collection: JSONCollection): RIO[DataStoreContext, Option[JsValue]] =
    Logger.debug(s"Mongo query $collectionName findById ${id.key}") *>
    IO.fromFuture { implicit ec =>
        collection
          .find(Json.obj("id" -> id.key), projection = Option.empty[JsObject])
          .one[MongoDoc]
      }
      .map(_.map(_.data))

  override def getById(id: Key): RIO[DataStoreContext, Option[JsValue]] =
    storeCollectionT.flatMap(getByIdRaw(id)(_))

  override def findByQuery(q: Query, page: Int, nbElementPerPage: Int): RIO[DataStoreContext, PagingResult[JsValue]] = {
    val from    = (page - 1) * nbElementPerPage
    val options = QueryOpts(skipN = from, batchSizeN = nbElementPerPage, flagsN = 0)
    val query   = buildMongoQuery(q)
    Logger.debug(
      s"Mongo query $collectionName find ${Json.stringify(query)}, page = $page, pageSize = $nbElementPerPage (from $from, batchSizeN $nbElementPerPage)"
    ) *>
    storeCollectionT.flatMap { implicit collection =>
      val findResult: Task[Seq[MongoDoc]] = IO.fromFuture { implicit ec =>
        collection
          .find(query, projection = Option.empty[JsObject])
          .options(options)
          .cursor[MongoDoc](ReadPreference.primary)
          .collect[Seq](maxDocs = nbElementPerPage, Cursor.FailOnError[Seq[MongoDoc]]())
      }

      val countResult = countRaw(q)

      (countResult <*> findResult).map {
        case (count, res) =>
          DefaultPagingResult(res.map(_.data), page, nbElementPerPage, count.toInt)
      }
    }
  }

  override def findByQuery(q: Query): RIO[DataStoreContext, Source[(Key, JsValue), NotUsed]] = {
    val query = buildMongoQuery(q)
    Logger.debug(s"Mongo query $collectionName find ${Json.stringify(query)} as stream") *>
    Task.fromFuture(
      implicit ec =>
        FastFuture.successful(Source.fromFuture(storeCollection).flatMapConcat {
          _.find(query, projection = Option.empty[JsObject])
            .cursor[MongoDoc](ReadPreference.primary)
            .documentSource()
            .map(mongoDoc => (mongoDoc.id, mongoDoc.data))
        })
    )
  }

  private def countRaw(query: Query)(implicit collection: JSONCollection): RIO[DataStoreContext, Long] = {
    val q = buildMongoQuery(query)
    Logger.debug(s"Mongo query $collectionName count ${Json.stringify(q)}") *>
    IO.fromFuture { implicit ec =>
      collection
        .count(
          selector = Some(q),
          limit = None,
          skip = 0,
          hint = None,
          readConcern = ReadConcern.Local
        )
    }
  }

  override def deleteAll(query: Query): ZIO[DataStoreContext, IzanamiErrors, Unit] =
    storeCollectionIO
      .flatMap { col =>
        IO.fromFuture { implicit ec =>
            val deleteBuilder: col.DeleteBuilder = col.delete(false, WriteConcern.Acknowledged)
            deleteBuilder.element(buildMongoQuery(query)).flatMap { toDelete =>
              deleteBuilder.many(List(toDelete))
            }
          }
          .refineToOrDie[IzanamiErrors]
          .map(_ => ())
      }

  override def count(query: Query): RIO[DataStoreContext, Long] =
    storeCollectionT.flatMap(countRaw(query)(_)).map(_.longValue())

  private def buildMongoQuery(query: Query): JsObject = {

    val searchs = query.ands.toList
      .map { clauses =>
        val andClause = clauses.patterns.toList
          .map {
            case StringPattern(str) =>
              str match {
                case p if p.contains("*") =>
                  val regex = Json.obj("$regex" -> p.replaceAll("\\*", ".*"), "$options" -> "i")
                  Json.obj("id" -> regex)
                case p =>
                  Json.obj("id" -> p)
              }
            case EmptyPattern =>
              Json.obj("id" -> "")
          }
        andClause match {
          case head :: Nil => head
          case _           => Json.obj("$or" -> andClause.toList)
        }
      }
    searchs match {
      case head :: Nil =>
        head
      case _ =>
        Json.obj("$and" -> searchs.toList)
    }
  }

}
