package store

import akka.actor.ActorSystem
import akka.stream.scaladsl.{Flow, Source}
import akka.{Done, NotUsed}
import cats.Semigroup
import cats.data.{NonEmptyList, Validated}
import cats.effect.{ConcurrentEffect, ContextShift, Effect}
import cats.kernel.Monoid
import domains.events.EventStore
import domains.Key
import domains.events.Events.IzanamiEvent
import env._
import libs.database.Drivers
import libs.logs.IzanamiLogger
import play.api.inject.ApplicationLifecycle
import play.api.libs.json._
import store.Result.Result
import store.cassandra.CassandraJsonDataStore
import store.elastic.ElasticJsonDataStore
import store.leveldb.{DbStores, LevelDBJsonDataStore}
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

  case class AppErrors(errors: Seq[ErrorMessage] = Seq.empty,
                       fieldErrors: Map[String, List[ErrorMessage]] = Map.empty) {
    def ++(s: AppErrors): AppErrors =
      this.copy(errors = errors ++ s.errors, fieldErrors = fieldErrors ++ s.fieldErrors)
    def addFieldError(field: String, errors: List[ErrorMessage]): AppErrors =
      fieldErrors.get(field) match {
        case Some(err) =>
          AppErrors(errors, fieldErrors + (field -> (err ++ errors)))
        case None => AppErrors(errors, fieldErrors + (field -> errors))
      }

    def toJson: JsValue =
      AppErrors.format.writes(this)

    def isEmpty: Boolean = errors.isEmpty && fieldErrors.isEmpty
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

    def error(message: String): AppErrors =
      AppErrors(Seq(ErrorMessage(message)))

    def error(message: String, args: String*): AppErrors =
      AppErrors(Seq(ErrorMessage(message, args: _*)))

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

  type ValidatedResult[+E] = Validated[AppErrors, E]
  type Result[+E]          = Either[AppErrors, E]
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
    query.ands.foldLeft(true) {
      case (acc, clause) => acc && key.matchOnePatterns(clause.patterns.toList: _*)
    }

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

trait DataStore[F[_], Key, Data] {
  def create(id: Key, data: Data): F[Result[Data]]
  def update(oldId: Key, id: Key, data: Data): F[Result[Data]]
  def delete(id: Key): F[Result[Data]]
  def deleteAll(query: Query): F[Result[Done]]
  def deleteAll(patterns: Seq[String]): F[Result[Done]] = deleteAll(Query.oneOf(patterns))
  def getById(id: Key): F[Option[Data]]
  def findByQuery(query: Query, page: Int = 1, nbElementPerPage: Int = 15): F[PagingResult[Data]]
  def findByQuery(query: Query): Source[(Key, Data), NotUsed]
  def count(query: Query): F[Long]
}

trait JsonDataStore[F[_]] extends DataStore[F, Key, JsValue]

object JsonDataStore {
  def apply[F[_]: ContextShift: ConcurrentEffect](
      drivers: Drivers[F],
      izanamiConfig: IzanamiConfig,
      conf: DbDomainConfig,
      eventStore: EventStore[F],
      eventAdapter: Flow[IzanamiEvent, CacheEvent, NotUsed],
      applicationLifecycle: ApplicationLifecycle
  )(implicit actorSystem: ActorSystem, stores: DbStores[F]): JsonDataStore[F] =
    conf.`type` match {
      case InMemoryWithDb =>
        val dbType: DbType                  = conf.conf.db.map(_.`type`).orElse(izanamiConfig.db.inMemoryWithDb.map(_.db)).get
        val jsonDataStore: JsonDataStore[F] = storeByType(drivers, izanamiConfig, conf, dbType, applicationLifecycle)
        IzanamiLogger.info(
          s"Loading InMemoryWithDbStore for namespace ${conf.conf.namespace} with underlying store ${dbType.getClass.getSimpleName}"
        )
        InMemoryWithDbStore[F](izanamiConfig.db.inMemoryWithDb.get,
                               conf,
                               jsonDataStore,
                               eventStore,
                               eventAdapter,
                               applicationLifecycle)
      case other =>
        storeByType(drivers, izanamiConfig, conf, other, applicationLifecycle)
    }

  private def storeByType[F[_]: ContextShift: ConcurrentEffect](
      drivers: Drivers[F],
      izanamiConfig: IzanamiConfig,
      conf: DbDomainConfig,
      dbType: DbType,
      applicationLifecycle: ApplicationLifecycle
  )(implicit actorSystem: ActorSystem, stores: DbStores[F]): JsonDataStore[F] = {
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
