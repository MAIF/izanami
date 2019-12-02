package store

import akka.actor.ActorSystem
import akka.stream.scaladsl.{Flow, Source}
import akka.NotUsed
import cats.Semigroup
import cats.data.{NonEmptyList, Validated}
import cats.kernel.Monoid
import domains.events.EventStoreContext
import domains.Key
import domains.events.Events.IzanamiEvent
import env._
import libs.database.Drivers
import libs.logs.{IzanamiLogger, LoggerModule}
import play.api.inject.ApplicationLifecycle
import play.api.libs.json._
import play.api.mvc.Results
import store.Result.IzanamiErrors
import store.cassandra.CassandraJsonDataStore
import store.elastic.ElasticJsonDataStore
import store.leveldb.LevelDBJsonDataStore
import store.memory.InMemoryJsonDataStore
import store.memorywithdb.{CacheEvent, InMemoryWithDbStore}
import store.mongo.MongoJsonDataStore
import store.redis.RedisJsonDataStore
import store.dynamo.DynamoJsonDataStore
import store.postgresql.PostgresqlJsonDataStore

object Result {

  case class ErrorMessage(message: String, args: String*)

  object ErrorMessage {
    implicit val format = Json.format[ErrorMessage]
  }

  type IzanamiErrors = NonEmptyList[IzanamiError]

  object IzanamiErrors {

    import cats.implicits._

    def apply(error: IzanamiError, rest: IzanamiError*): IzanamiErrors = NonEmptyList.of[IzanamiError](error, rest: _*)

    def toHttpResult(error: IzanamiErrors) =
      Results.BadRequest(error.foldMap {
        case err: ValidationError => err
        case IdMustBeTheSame(fromObject, inParam) =>
          ValidationError.error("error.id.not.the.same", inParam.key, inParam.key)
        case DataShouldExists(id)    => ValidationError.error("error.data.missing", id.key)
        case DataShouldNotExists(id) => ValidationError.error("error.data.exists", id.key)
      }.toJson)

    implicit class ToErrorsOps(err: IzanamiError) {
      def toErrors: IzanamiErrors = apply(err)
    }

    implicit def semigroup(implicit SG: Semigroup[NonEmptyList[IzanamiError]]): Semigroup[IzanamiErrors] =
      new Semigroup[IzanamiErrors] {
        override def combine(x: IzanamiErrors, y: IzanamiErrors): IzanamiErrors = SG.combine(x, y)
      }
  }

  sealed trait IzanamiError
  case class IdMustBeTheSame(fromObject: Key, inParam: Key) extends IzanamiError
  case class DataShouldExists(id: Key)                      extends IzanamiError
  case class DataShouldNotExists(id: Key)                   extends IzanamiError
  case class ValidationError(errors: Seq[ErrorMessage] = Seq.empty,
                             fieldErrors: Map[String, List[ErrorMessage]] = Map.empty)
      extends IzanamiError {
    def ++(s: ValidationError): ValidationError =
      this.copy(errors = errors ++ s.errors, fieldErrors = fieldErrors ++ s.fieldErrors)
    def addFieldError(field: String, errors: List[ErrorMessage]): ValidationError =
      fieldErrors.get(field) match {
        case Some(err) =>
          ValidationError(errors, fieldErrors + (field -> (err ++ errors)))
        case None => ValidationError(errors, fieldErrors + (field -> errors))
      }

    def toJson: JsValue =
      ValidationError.format.writes(this)

    def isEmpty: Boolean = errors.isEmpty && fieldErrors.isEmpty
  }

  object ValidationError {
    import cats.syntax.semigroup._
    import cats.instances.all._

    implicit val format = Json.format[ValidationError]

    def fromJsError(jsError: Seq[(JsPath, Seq[JsonValidationError])]): ValidationError = {
      val fieldErrors = jsError.map {
        case (k, v) =>
          (k.toJsonString, v.map(err => ErrorMessage(err.message, err.args.map(_.toString): _*)).toList)
      }.toMap
      ValidationError(fieldErrors = fieldErrors)
    }

    def error(message: String): ValidationError =
      ValidationError(Seq(ErrorMessage(message)))

    def error(message: String, args: String*): ValidationError =
      ValidationError(Seq(ErrorMessage(message, args: _*)))

    private def optionCombine[A: Semigroup](a: A, opt: Option[A]): A =
      opt.map(a |+| _).getOrElse(a)

    private def mergeMap[K, V: Semigroup](lhs: Map[K, V], rhs: Map[K, V]): Map[K, V] =
      lhs.foldLeft(rhs) {
        case (acc, (k, v)) => acc.updated(k, optionCombine(v, acc.get(k)))
      }

    implicit val monoid: Monoid[ValidationError] = new Monoid[ValidationError] {
      override def empty = ValidationError()
      override def combine(x: ValidationError, y: ValidationError) = {
        val errors      = x.errors ++ y.errors
        val fieldErrors = mergeMap(x.fieldErrors, y.fieldErrors)
        ValidationError(errors, fieldErrors)
      }
    }
  }

  type ValidatedResult[+E] = Validated[ValidationError, E]
  type Result[+E]          = Either[IzanamiErrors, E]
  def ok[E](event: E): Result[E]                = Right(event)
  def error[E](error: IzanamiErrors): Result[E] = Left(error)
  def error[E](error: IzanamiError): Result[E]  = Left(IzanamiErrors(error))
  def error[E](messages: String*): Result[E] =
    Left(IzanamiErrors(ValidationError(messages.map(m => ErrorMessage(m)))))
  def errors[E](errs: ErrorMessage*): Result[E] = Left(IzanamiErrors(ValidationError(errs)))
  def fieldError[E](field: String, errs: ErrorMessage*): Result[E] =
    Left(IzanamiErrors.apply(ValidationError(fieldErrors = Map(field -> errs.toList))))

  implicit class ResultOps[E](r: Result[E]) {
    def collect[E2](p: PartialFunction[E, E2]): Result[E2] =
      r match {
        case Right(elt) if p.isDefinedAt(elt) => Result.ok(p(elt))
        case Right(_)                         => Result.error("error.result.filtered")
        case Left(e)                          => Result.error(e)
      }
  }
}

trait PagingResult[Data] {
  def results: Seq[Data]
  def page: Int
  def pageSize: Int
  def count: Int
  def nbPages: Double = Math.ceil(count.toFloat / pageSize)
}

case class JsonPagingResult[Data](jsons: PagingResult[JsValue])(implicit reads: Reads[Data])
    extends PagingResult[Data] {
  override def results: Seq[Data] = jsons.results.flatMap(json => reads.reads(json).asOpt)
  override def page: Int          = jsons.page
  override def pageSize: Int      = jsons.pageSize
  override def count: Int         = jsons.count
}

case class DefaultPagingResult[Data](results: Seq[Data], page: Int, pageSize: Int, count: Int)
    extends PagingResult[Data]

sealed trait Pattern
final case class StringPattern(str: String) extends Pattern {
  override def toString: String = str
}
final case object EmptyPattern extends Pattern with Product with Serializable

final case class OneOfPatternClause(patterns: NonEmptyList[Pattern]) {
  override def toString: String =
    patterns.map(_.toString).toList.mkString(" OR ")
}

object OneOfPatternClause {
  def of(pattern: String, rest: String*): OneOfPatternClause =
    OneOfPatternClause(NonEmptyList.of(StringPattern(pattern), rest.map(StringPattern.apply): _*))
  def fromStrings(patterns: NonEmptyList[String]) = OneOfPatternClause(patterns.map(StringPattern.apply))
}
final case class Query(ands: NonEmptyList[OneOfPatternClause]) {
  def and(patterns: NonEmptyList[String]): Query = and(OneOfPatternClause(patterns.map(StringPattern.apply)))
  def and(patterns: Seq[String]): Query =
    and(OneOfPatternClause(NonEmptyList.fromList(patterns.toList).get.map(StringPattern.apply)))
  def and(pattern: String, rest: String*): Query = and(OneOfPatternClause.of(pattern, rest: _*))
  def and(clause: OneOfPatternClause): Query     = Query(ands :+ clause)
  def and(query: Query): Query                   = Query(ands ++ query.ands.toList)
  def hasEmpty: Boolean                          = ands.toList.flatMap(_.patterns.toList).contains(EmptyPattern)

  override def toString: String =
    ands.map(c => s"(${c.toString})").toList.mkString(" AND ")
}

object Query {
  def keyMatchQuery(key: Key, query: Query): Boolean =
    query.ands.forall(clause => key.matchOnePatterns(clause.patterns.toList: _*))

  def oneOf(patterns: NonEmptyList[String]) = Query(NonEmptyList.of(OneOfPatternClause.fromStrings(patterns)))
  def oneOf(pattern: String, rest: String*) = Query(NonEmptyList.of(OneOfPatternClause.of(pattern, rest: _*)))

  def oneOf(patterns: Seq[String]) =
    Query(
      NonEmptyList.of(
        NonEmptyList
          .fromList(patterns.toList)
          .map(OneOfPatternClause.fromStrings)
          .getOrElse(OneOfPatternClause(NonEmptyList.of(EmptyPattern)))
      )
    )
}

trait DataStoreContext extends LoggerModule with EventStoreContext

object DataStore {
  type DataStoreIO[A] = zio.RIO[DataStoreContext, A]
}

trait DataStore[Key, Data] {
  import zio._
  def create(id: Key, data: Data): ZIO[DataStoreContext, IzanamiErrors, Data]
  def update(oldId: Key, id: Key, data: Data): ZIO[DataStoreContext, IzanamiErrors, Data]

  def upsert(oldId: Key, id: Key, data: Data): ZIO[DataStoreContext, IzanamiErrors, Data] =
    for {
      mayBeData <- getById(oldId).refineToOrDie[IzanamiErrors]
      result <- mayBeData match {
                 case Some(_) => update(oldId, id, data)
                 case None    => create(id, data)
               }
    } yield result

  def delete(id: Key): ZIO[DataStoreContext, IzanamiErrors, Data]
  def deleteAll(query: Query): ZIO[DataStoreContext, IzanamiErrors, Unit]
  def deleteAll(patterns: Seq[String]): ZIO[DataStoreContext, IzanamiErrors, Unit] = deleteAll(Query.oneOf(patterns))
  def getById(id: Key): RIO[DataStoreContext, Option[Data]]
  def findByQuery(query: Query, page: Int = 1, nbElementPerPage: Int = 15): RIO[DataStoreContext, PagingResult[Data]]
  def findByQuery(query: Query): RIO[DataStoreContext, Source[(Key, Data), NotUsed]]
  def count(query: Query): RIO[DataStoreContext, Long]
  def start: RIO[DataStoreContext, Unit] = Task.succeed(())
}

trait JsonDataStore extends DataStore[Key, JsValue]

trait JsonDataStoreHelper[R <: DataStoreContext] {
  import zio._

  def accessStore: R => JsonDataStore

  def create(id: Key, data: JsValue): ZIO[R, IzanamiErrors, JsValue] =
    ZIO.accessM[R](accessStore(_).create(id, data))

  def update(oldId: Key, id: Key, data: JsValue): ZIO[R, IzanamiErrors, JsValue] =
    ZIO.accessM[R](accessStore(_).update(oldId, id, data))

  def upsert(oldId: Key, id: Key, data: JsValue): ZIO[R, IzanamiErrors, JsValue] =
    ZIO.accessM[R](accessStore(_).upsert(oldId, id, data))

  def delete(id: Key): ZIO[R, IzanamiErrors, JsValue] =
    ZIO.accessM[R](accessStore(_).delete(id))

  def deleteAll(query: Query): ZIO[R, IzanamiErrors, Unit] =
    ZIO.accessM(accessStore(_).deleteAll(query))

  def deleteAll(patterns: Seq[String]): ZIO[R, IzanamiErrors, Unit] =
    deleteAll(Query.oneOf(patterns))

  def getById(id: Key): RIO[R, Option[JsValue]] =
    ZIO.accessM[R](accessStore(_).getById(id))

  def findByQuery(query: Query, page: Int = 1, nbElementPerPage: Int = 15): RIO[R, PagingResult[JsValue]] =
    ZIO.accessM[R](accessStore(_).findByQuery(query, page, nbElementPerPage))

  def findByQuery(query: Query): RIO[R, Source[(Key, JsValue), NotUsed]] =
    ZIO.accessM[R](accessStore(_).findByQuery(query))

  def count(query: Query): RIO[R, Long] =
    ZIO.accessM[R](accessStore(_).count(query))

  def start: RIO[R, Unit] = ZIO.accessM[R](accessStore(_).start)

}

object JsonDataStore {
  def apply(
      drivers: Drivers,
      izanamiConfig: IzanamiConfig,
      conf: DbDomainConfig,
      eventAdapter: Flow[IzanamiEvent, CacheEvent, NotUsed],
      applicationLifecycle: ApplicationLifecycle
  )(implicit actorSystem: ActorSystem): JsonDataStore =
    conf.`type` match {
      case InMemoryWithDb =>
        val dbType: DbType               = conf.conf.db.map(_.`type`).orElse(izanamiConfig.db.inMemoryWithDb.map(_.db)).get
        val jsonDataStore: JsonDataStore = storeByType(drivers, izanamiConfig, conf, dbType, applicationLifecycle)
        IzanamiLogger.info(
          s"Loading InMemoryWithDbStore for namespace ${conf.conf.namespace} with underlying store ${dbType.getClass.getSimpleName}"
        )
        InMemoryWithDbStore(izanamiConfig.db.inMemoryWithDb.get,
                            conf,
                            jsonDataStore,
                            eventAdapter,
                            applicationLifecycle)
      case other =>
        storeByType(drivers, izanamiConfig, conf, other, applicationLifecycle)
    }

  private def storeByType(
      drivers: Drivers,
      izanamiConfig: IzanamiConfig,
      conf: DbDomainConfig,
      dbType: DbType,
      applicationLifecycle: ApplicationLifecycle
  )(implicit actorSystem: ActorSystem): JsonDataStore = {
    IzanamiLogger.info(s"Initializing store for $dbType")
    dbType match {
      case InMemory => InMemoryJsonDataStore(conf)
      case Redis    => RedisJsonDataStore(drivers.redisClient.get, conf)
      case LevelDB  => LevelDBJsonDataStore(izanamiConfig.db.leveldb.get, conf, applicationLifecycle)
      case Cassandra =>
        CassandraJsonDataStore(drivers.cassandraClient.get._2, izanamiConfig.db.cassandra.get, conf)
      case Elastic    => ElasticJsonDataStore(drivers.elasticClient.get, izanamiConfig.db.elastic.get, conf)
      case Mongo      => MongoJsonDataStore(drivers.mongoApi.get, conf)
      case Dynamo     => DynamoJsonDataStore(drivers.dynamoClient.get, izanamiConfig.db.dynamo.get, conf)
      case Postgresql => PostgresqlJsonDataStore(drivers.postgresqlClient.get, conf)
      case _          => throw new IllegalArgumentException(s"Unsupported store type $dbType")
    }
  }
}
