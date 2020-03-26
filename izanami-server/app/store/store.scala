package store

import akka.actor.ActorSystem
import akka.stream.scaladsl.{Flow, Source}
import akka.NotUsed
import cats.Semigroup
import cats.data.{NonEmptyList, Validated}
import cats.kernel.Monoid
import domains.events.EventStore
import domains.Key
import domains.configuration.AuthInfoModule
import domains.events.Events.IzanamiEvent
import env._
import libs.database.Drivers
import libs.logs.{IzanamiLogger, ZLogger}
import play.api.inject.ApplicationLifecycle
import play.api.libs.json._
import play.api.mvc.Results
import domains.errors.IzanamiErrors
import store.cassandra.CassandraJsonDataStore
import store.elastic.ElasticJsonDataStore
import store.leveldb.LevelDBJsonDataStore
import store.memory.InMemoryJsonDataStore
import store.memorywithdb.{CacheEvent, InMemoryWithDbStore}
import store.mongo.MongoJsonDataStore
import store.redis.RedisJsonDataStore
import store.dynamo.DynamoJsonDataStore
import store.postgresql.PostgresqlJsonDataStore

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

package object datastore {

  type DataStoreContext = ZLogger with EventStore with AuthInfoModule

  object DataStore {
    type DataStoreIO[A] = zio.RIO[DataStoreContext, A]

    trait Service[Key, Data] {
      import zio._
      def create(id: Key, data: Data): ZIO[DataStoreContext, IzanamiErrors, Data]
      def update(oldId: Key, id: Key, data: Data): ZIO[DataStoreContext, IzanamiErrors, Data]

      def upsert(oldId: Key, id: Key, data: Data): ZIO[DataStoreContext, IzanamiErrors, Data] =
        for {
          mayBeData <- getById(oldId).refineOrDie[IzanamiErrors](PartialFunction.empty)
          result <- mayBeData match {
                     case Some(_) => update(oldId, id, data)
                     case None    => create(id, data)
                   }
        } yield result

      def delete(id: Key): ZIO[DataStoreContext, IzanamiErrors, Data]
      def deleteAll(query: Query): ZIO[DataStoreContext, IzanamiErrors, Unit]
      def deleteAll(patterns: Seq[String]): ZIO[DataStoreContext, IzanamiErrors, Unit] =
        deleteAll(Query.oneOf(patterns))
      def getById(id: Key): RIO[DataStoreContext, Option[Data]]
      def findByQuery(query: Query,
                      page: Int = 1,
                      nbElementPerPage: Int = 15): RIO[DataStoreContext, PagingResult[Data]]
      def findByQuery(query: Query): RIO[DataStoreContext, Source[(Key, Data), NotUsed]]
      def count(query: Query): RIO[DataStoreContext, Long]
      def start: RIO[DataStoreContext, Unit] = Task.succeed(())
      def close: RIO[DataStoreContext, Unit] = Task.succeed(())
    }
  }

  trait DataStore[Key, Data] {
    import zio._
    def create(id: Key, data: Data): ZIO[DataStoreContext, IzanamiErrors, Data]
    def update(oldId: Key, id: Key, data: Data): ZIO[DataStoreContext, IzanamiErrors, Data]

    def upsert(oldId: Key, id: Key, data: Data): ZIO[DataStoreContext, IzanamiErrors, Data] =
      for {
        mayBeData <- getById(oldId).refineOrDie[IzanamiErrors](PartialFunction.empty)
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
    def close: RIO[DataStoreContext, Unit] = Task.succeed(())
  }

  trait JsonDataStoreHelper[R <: zio.Has[JsonDataStore.Service] with DataStoreContext] {
    import zio._

    def getStore: URIO[R, JsonDataStore.Service] = ZIO.access(_.get)

    def create(id: Key, data: JsValue): ZIO[R, IzanamiErrors, JsValue] =
      getStore.flatMap(_.create(id, data))

    def update(oldId: Key, id: Key, data: JsValue): ZIO[R, IzanamiErrors, JsValue] =
      getStore.flatMap(_.update(oldId, id, data))

    def upsert(oldId: Key, id: Key, data: JsValue): ZIO[R, IzanamiErrors, JsValue] =
      getStore.flatMap(_.upsert(oldId, id, data))

    def delete(id: Key): ZIO[R, IzanamiErrors, JsValue] =
      getStore.flatMap(_.delete(id))

    def deleteAll(query: Query): ZIO[R, IzanamiErrors, Unit] =
      getStore.flatMap(_.deleteAll(query))

    def deleteAll(patterns: Seq[String]): ZIO[R, IzanamiErrors, Unit] =
      deleteAll(Query.oneOf(patterns))

    def getById(id: Key): RIO[R, Option[JsValue]] =
      getStore.flatMap(_.getById(id))

    def findByQuery(query: Query, page: Int = 1, nbElementPerPage: Int = 15): RIO[R, PagingResult[JsValue]] =
      getStore.flatMap(_.findByQuery(query, page, nbElementPerPage))

    def findByQuery(query: Query): RIO[R, Source[(Key, JsValue), NotUsed]] =
      getStore.flatMap(_.findByQuery(query))

    def count(query: Query): RIO[R, Long] =
      getStore.flatMap(_.count(query))

    def start: RIO[R, Unit] = getStore.flatMap(_.start)

    def close: RIO[R, Unit] = getStore.flatMap(_.close)
  }

  type JsonDataStore = zio.Has[JsonDataStore.Service]

  object JsonDataStore {

    trait Service extends DataStore[Key, JsValue]

    def apply(
        drivers: Drivers,
        izanamiConfig: IzanamiConfig,
        conf: DbDomainConfig,
        eventAdapter: Flow[IzanamiEvent, CacheEvent, NotUsed],
        applicationLifecycle: ApplicationLifecycle
    )(implicit actorSystem: ActorSystem): JsonDataStore.Service =
      conf.`type` match {
        case InMemoryWithDb =>
          val dbType: DbType = conf.conf.db.map(_.`type`).orElse(izanamiConfig.db.inMemoryWithDb.map(_.db)).get
          val jsonDataStore: JsonDataStore.Service =
            storeByType(drivers, izanamiConfig, conf, dbType, applicationLifecycle)
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
    )(implicit actorSystem: ActorSystem): JsonDataStore.Service = {
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
}
