package store

import akka.actor.ActorSystem
import akka.stream.Materializer
import akka.stream.scaladsl.{Flow, Sink, Source}
import akka.{Done, NotUsed}
import cats.Semigroup
import cats.kernel.Monoid
import cats.syntax.option._
import domains.events.EventStore
import domains.Key
import domains.events.Events.IzanamiEvent
import env._
import libs.database.Drivers
import play.api.Logger
import play.api.inject.ApplicationLifecycle
import play.api.libs.json._
import store.Result.Result
import store.cassandra.CassandraJsonDataStore
import store.elastic.ElasticJsonDataStore
import store.leveldb.LevelDBJsonDataStore
import store.memory.InMemoryJsonDataStore
import store.memorywithdb.{CacheEvent, InMemoryWithDbStore}
import store.mongo.MongoJsonDataStore
import store.redis.RedisJsonDataStore

import scala.concurrent.{ExecutionContext, Future}

object Result {

  import cats.syntax.either._
  case class ErrorMessage(message: String, args: String*)
  object ErrorMessage {
    implicit val format = Json.format[ErrorMessage]
  }

  case class AppErrors(errors: Seq[ErrorMessage] = Seq.empty,
                       fieldErrors: Map[String, List[ErrorMessage]] = Map.empty) {
    def ++(s: AppErrors) =
      this.copy(errors = errors ++ s.errors, fieldErrors = fieldErrors ++ s.fieldErrors)
    def addFieldError(field: String, errors: List[ErrorMessage]) =
      fieldErrors.get(field) match {
        case Some(err) =>
          AppErrors(errors, fieldErrors + (field -> (err ++ errors)))
        case None => AppErrors(errors, fieldErrors + (field -> errors))
      }

    def toJson: JsValue =
      AppErrors.format.writes(this)

    def isEmpty = errors.isEmpty && fieldErrors.isEmpty
  }

  object AppErrors {
    import cats.syntax.semigroup._
    import cats.instances.all._

    implicit val format = Json.format[AppErrors]

    def fromJsError(jsError: Seq[(JsPath, Seq[JsonValidationError])]): AppErrors = {
      val fieldErrors = jsError.map {
        case (k, v) =>
          (k.toJsonString, v.map(err => ErrorMessage(err.message, err.args.map(_.toString): _*)).toList)
      }.toMap
      AppErrors(fieldErrors = fieldErrors)
    }

    def error(messages: String*): AppErrors =
      AppErrors(messages.map(m => ErrorMessage(m)))

    private def optionCombine[A: Semigroup](a: A, opt: Option[A]): A =
      opt.map(a |+| _).getOrElse(a)

    private def mergeMap[K, V: Semigroup](lhs: Map[K, V], rhs: Map[K, V]): Map[K, V] =
      lhs.foldLeft(rhs) {
        case (acc, (k, v)) => acc.updated(k, optionCombine(v, acc.get(k)))
      }

    implicit val monoid: Monoid[AppErrors] = new Monoid[AppErrors] {
      override def empty = AppErrors()
      override def combine(x: AppErrors, y: AppErrors) = {
        val errors      = x.errors ++ y.errors
        val fieldErrors = mergeMap(x.fieldErrors, y.fieldErrors)
        AppErrors(errors, fieldErrors)
      }
    }

  }

  type Result[+E] = Either[AppErrors, E]
  def ok[E](event: E): Result[E]            = Right(event)
  def error[E](error: AppErrors): Result[E] = Left(error)
  def error[E](messages: String*): Result[E] =
    Left(AppErrors(messages.map(m => ErrorMessage(m))))
  def errors[E](errs: ErrorMessage*): Result[E] = Left(AppErrors(errs))
  def fieldError[E](field: String, errs: ErrorMessage*): Result[E] =
    Left(AppErrors(fieldErrors = Map(field -> errs.toList)))

  implicit class ResultOps[E](r: Result[E]) {
    def collect[E2](p: PartialFunction[E, E2]): Result[E2] =
      r match {
        case Right(elt) if p.isDefinedAt(elt) => Result.ok(p(elt))
        case Right(elt)                       => Result.error("error.result.filtered")
        case Left(e)                          => Result.error(e)
      }
  }

  implicit class FResultOps[E](value: Future[Result[E]]) {
    def mapResult[F](func: E => F)(implicit ec: ExecutionContext): Future[Result[F]] = value.map {
      case Right(e)  => func(e).asRight
      case Left(err) => err.asLeft
    }

    def flatMapResult[F](func: E => Result[F])(implicit ec: ExecutionContext): Future[Result[F]] = value.map {
      case Right(e)  => func(e)
      case Left(err) => err.asLeft
    }
    def mapJsResult[F](func: E => Result[F])(implicit ec: ExecutionContext): Future[Result[F]] = value.map {
      case Right(e)  => func(e)
      case Left(err) => err.asLeft
    }
  }

  implicit class JsResultOps(r: Future[Result[JsValue]]) {
    def to[E](implicit ec: ExecutionContext, reads: Reads[E]): Future[Result[E]] =
      r.mapResult(json => json.validate[E]).flatMapResult {
        case JsSuccess(e, _) => e.asRight
        case JsError(err)    =>
          //FIXME better error handling
          Logger.error(s"Error parsing json from database $err")
          AppErrors(Seq(ErrorMessage("error.json.parsing"))).asLeft
      }
  }
}

trait FindResult[Data] {

  def one(implicit ec: ExecutionContext, format: Format[Data]): Future[Option[Data]]
  def list(implicit ec: ExecutionContext, format: Format[Data]): Future[Seq[Data]]
  def stream(implicit format: Format[Data]): Source[Data, NotUsed]

}

trait PagingResult[Data] {
  def results: Seq[Data]
  def page: Int
  def pageSize: Int
  def count: Int
  def nbPages = Math.ceil(count.toFloat / pageSize)
}

case class JsonPagingResult[Data](jsons: PagingResult[JsValue])(implicit reads: Reads[Data])
    extends PagingResult[Data] {
  override def results  = jsons.results.flatMap(json => reads.reads(json).asOpt)
  override def page     = jsons.page
  override def pageSize = jsons.pageSize
  override def count    = jsons.count
}

case class DefaultPagingResult[Data](results: Seq[Data], page: Int, pageSize: Int, count: Int)
    extends PagingResult[Data]

object SourceUtils {

  implicit class SourceKV(source: Source[(Key, JsValue), NotUsed]) {
    def readsKV[V](implicit reads: Reads[V]): Source[(Key, V), NotUsed] =
      source.mapConcat {
        case (k, v) =>
          reads
            .reads(v)
            .fold(
              { err =>
                Logger.error(s"Error parsing $v : $err")
                List.empty[(Key, V)]
              }, { v =>
                List((k, v))
              }
            )
      }
  }
}

case class SimpleFindResult[Data](datas: Future[List[Data]]) extends FindResult[Data] {

  override def one(implicit ec: ExecutionContext, format: Format[Data]): Future[Option[Data]] =
    datas.map(_.headOption)

  override def list(implicit ec: ExecutionContext, format: Format[Data]): Future[Seq[Data]] = datas

  override def stream(implicit format: Format[Data]): Source[Data, NotUsed] =
    Source.fromFuture(datas).mapConcat(d => d)
}

case class SourceFindResult[Data](datas: Source[Data, NotUsed])(implicit mat: Materializer) extends FindResult[Data] {

  override def one(implicit ec: ExecutionContext, format: Format[Data]): Future[Option[Data]] =
    datas.runWith(Sink.headOption)

  override def list(implicit ec: ExecutionContext, format: Format[Data]): Future[Seq[Data]] =
    datas.runWith(Sink.seq)

  override def stream(implicit format: Format[Data]): Source[Data, NotUsed] =
    datas
}

case class JsonFindResult[Data](datas: FindResult[JsValue]) extends FindResult[Data] {

  override def one(implicit ec: ExecutionContext, format: Format[Data]): Future[Option[Data]] =
    datas.one
      .map(
        mayBeJson =>
          mayBeJson
            .map(json => format.reads(json))
            .flatMap {
              case JsSuccess(s, _) => s.some
              case JsError(e) =>
                Logger.error(s"Error reading data from DB $e")
                none[Data]
          }
      )

  override def list(implicit ec: ExecutionContext, format: Format[Data]): Future[Seq[Data]] =
    datas.list.map(
      l =>
        l.map(json => format.reads(json))
          .flatMap {
            case JsSuccess(s, _) => s.some
            case JsError(e) =>
              Logger.error(s"Error reading data from DB $e")
              none[Data]
        }
    )

  override def stream(implicit format: Format[Data]): Source[Data, NotUsed] =
    datas.stream
      .map(json => format.reads(json))
      .mapConcat {
        case JsSuccess(s, _) => List(s)
        case JsError(e) =>
          Logger.error(s"Error reading data from DB $e")
          List.empty[Data]
      }
}

trait StoreOps {
  import domains.events.Events._

  implicit class EventsOps[E](f: Future[Result[E]]) {
    def andPublishEvent[Event <: IzanamiEvent](
        func: E => Event
    )(implicit ec: ExecutionContext, actorSystem: ActorSystem, eventStore: EventStore): Future[Result[E]] = {
      f.foreach {
        case Right(v) =>
          eventStore.publish(func(v).asInstanceOf[IzanamiEvent])
          actorSystem.eventStream.publish(func(v))
        case _ =>
      }
      f
    }
  }
}

trait DataStore[Key, Data] extends StoreOps {
  def create(id: Key, data: Data): Future[Result[Data]]
  def update(oldId: Key, id: Key, data: Data): Future[Result[Data]]
  def delete(id: Key): Future[Result[Data]]
  def deleteAll(patterns: Seq[String]): Future[Result[Done]]
  def getById(id: Key): FindResult[Data]
  def getByIdLike(patterns: Seq[String], page: Int = 1, nbElementPerPage: Int = 15): Future[PagingResult[Data]]
  def getByIdLike(patterns: Seq[String]): Source[(Key, Data), NotUsed]
  def count(patterns: Seq[String]): Future[Long]
}

trait JsonDataStore extends DataStore[Key, JsValue]

object JsonDataStore {
  def apply(drivers: Drivers,
            izanamiConfig: IzanamiConfig,
            conf: DbDomainConfig,
            eventStore: EventStore,
            eventAdapter: Flow[IzanamiEvent, CacheEvent, NotUsed],
            applicationLifecycle: ApplicationLifecycle)(implicit actorSystem: ActorSystem): JsonDataStore =
    conf.`type` match {
      case InMemoryWithDb =>
        val dbType: DbType = conf.conf.db.map(_.`type`).orElse(izanamiConfig.db.inMemoryWithDb.map(_.db)).get
        val jsonDataStore  = storeByType(drivers, izanamiConfig, conf, dbType, applicationLifecycle)
        Logger.info(
          s"Loading InMemoryWithDbStore for namespace ${conf.conf.namespace} with underlying store ${dbType.getClass.getSimpleName}"
        )
        InMemoryWithDbStore(conf, jsonDataStore, eventStore, eventAdapter)
      case other =>
        storeByType(drivers, izanamiConfig, conf, other, applicationLifecycle)
    }

  private def storeByType(
      drivers: Drivers,
      izanamiConfig: IzanamiConfig,
      conf: DbDomainConfig,
      dbType: DbType,
      applicationLifecycle: ApplicationLifecycle
  )(implicit actorSystem: ActorSystem): JsonDataStore =
    dbType match {
      case InMemory => InMemoryJsonDataStore(conf, actorSystem)
      case Redis    => RedisJsonDataStore(drivers.redisClient.get, conf, actorSystem)
      case LevelDB  => LevelDBJsonDataStore(izanamiConfig.db.leveldb.get, conf, actorSystem, applicationLifecycle)
      case Cassandra =>
        CassandraJsonDataStore(drivers.cassandraClient.get._2, izanamiConfig.db.cassandra.get, conf, actorSystem)
      case Elastic => ElasticJsonDataStore(drivers.elasticClient.get, izanamiConfig.db.elastic.get, conf, actorSystem)
      case Mongo   => MongoJsonDataStore(drivers.mongoApi.get, conf, actorSystem)
      case _       => throw new IllegalArgumentException(s"Unsupported store type $dbType")
    }

}
