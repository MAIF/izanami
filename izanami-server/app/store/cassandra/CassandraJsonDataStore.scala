package store.cassandra

import java.util.concurrent.Executor

import akka.actor.ActorSystem
import akka.stream.alpakka.cassandra.scaladsl.CassandraSource
import akka.stream.scaladsl.{Flow, Sink, Source}
import akka.NotUsed
import cats.implicits._
import com.datastax.driver.core._
import com.google.common.util.concurrent.{FutureCallback, Futures, ListenableFuture}
import domains.Key
import domains.configuration.PlayModule
import env.{CassandraConfig, DbDomainConfig, IzanamiConfig}
import libs.streams.Flows
import libs.logs.IzanamiLogger
import play.api.libs.json.{JsValue, Json}
import domains.errors.IzanamiErrors
import store._
import store.datastore._

import scala.concurrent.{ExecutionContext, Future, Promise}
import libs.logs.ZLogger
import domains.errors.DataShouldExists
import domains.errors.DataShouldNotExists
import env.configuration.IzanamiConfigModule
import libs.database.Drivers.{CassandraDriver, DriverLayerContext}
import store.datastore.DataStore.DataStoreIO
import zio.{Has, ZLayer}

object CassandraJsonDataStore {

  def live(config: DbDomainConfig): ZLayer[CassandraDriver with DriverLayerContext, Throwable, JsonDataStore] =
    ZLayer.fromFunction { mix =>
      val playModule: PlayModule.Service    = mix.get[PlayModule.Service]
      val izanamiConfig: IzanamiConfig      = mix.get[IzanamiConfigModule.Service].izanamiConfig
      implicit val actorSystem: ActorSystem = playModule.system
      val Some(cassandraConfig)             = izanamiConfig.db.cassandra
      val Some((_, session))                = mix.get[Option[(Cluster, Session)]]
      new CassandraJsonDataStore(config.conf.namespace, cassandraConfig.keyspace, session)
    }

  def apply(session: Session, cassandraConfig: CassandraConfig, config: DbDomainConfig)(
      implicit actorSystem: ActorSystem
  ): CassandraJsonDataStore =
    new CassandraJsonDataStore(config.conf.namespace, cassandraConfig.keyspace, session)
}

class CassandraJsonDataStore(namespace: String, keyspace: String, session: Session)(
    implicit actorSystem: ActorSystem
) extends JsonDataStore.Service {

  IzanamiLogger.info(s"Load store Cassandra for namespace $namespace")

  private val namespaceFormatted = namespace.replaceAll(":", "_")

  import zio._
  import Cassandra._
  import IzanamiErrors._

  implicit private val _session: Session = session

  override def start: RIO[DataStoreContext, Unit] =
    ZLogger.info(s"Creating table $keyspace.$namespaceFormatted if not exists") *>
    Task(
      session.execute(s"""
                         | CREATE TABLE IF NOT EXISTS $keyspace.$namespaceFormatted (
                         |   namespace text,
                         |   id text,
                         |   key text,
                         |   value text,
                         |   PRIMARY KEY ((namespace), id)
                         | )
                         | """.stripMargin)
    ) *> Task(
      session
        .execute(
          s"""
             | CREATE CUSTOM INDEX  IF NOT EXISTS ${keyspace}_${namespaceFormatted}_sasi ON $keyspace.$namespaceFormatted(key)  USING 'org.apache.cassandra.index.sasi.SASIIndex' WITH OPTIONS = {
             |   'analyzer_class' : 'org.apache.cassandra.index.sasi.analyzer.NonTokenizingAnalyzer',
             |   'case_sensitive' : 'false',
             |   'mode' : 'CONTAINS'
             | }
    """.stripMargin
        )
    )

  override def create(id: Key, data: JsValue): ZIO[DataStoreContext, IzanamiErrors, JsValue] =
    getByIdRaw(id).orDie
      .flatMap {
        case Some(_) =>
          ZIO.fail(DataShouldNotExists(id).toErrors)
        case None =>
          createRaw(id, data)
      }

  private def createRaw(id: Key, data: JsValue): ZIO[DataStoreContext, IzanamiErrors, JsValue] = {
    val query =
      s"INSERT INTO $keyspace.$namespaceFormatted (namespace, id, key, value) values (?, ?, ?, ?) IF NOT EXISTS "

    val args = Seq(namespaceFormatted, id.key, id.key, Json.stringify(data))
    executeWithSessionT(query, args: _*).map { _ =>
      data
    }.orDie
  }

  override def update(oldId: Key, id: Key, data: JsValue): ZIO[DataStoreContext, IzanamiErrors, JsValue] =
    if (oldId.key === id.key) {
      getByIdRaw(id).orDie
        .flatMap {
          case Some(_) => updateRaw(id: Key, data)
          case None    => ZIO.fail(DataShouldExists(id).toErrors)
        }
    } else {
      deleteRaw(oldId)
        .flatMap { _ =>
          createRaw(id, data)
        }
    }

  private def updateRaw(id: Key, data: JsValue): ZIO[DataStoreContext, IzanamiErrors, JsValue] = {
    val query =
      s"UPDATE $keyspace.$namespaceFormatted SET value = ? WHERE namespace = ? AND id = ? IF EXISTS "
    val args = Seq(Json.stringify(data), namespaceFormatted, id.key)
    executeWithSessionT(query, args: _*)
      .map(_ => data)
      .orDie
  }

  override def delete(id: Key): ZIO[DataStoreContext, IzanamiErrors, JsValue] =
    getByIdRaw(id).orDie
      .flatMap {
        case Some(d) => deleteRaw(id).map(_ => d)
        case None    => IO.fail(DataShouldExists(id).toErrors)
      }

  private def deleteRaw(id: Key): ZIO[DataStoreContext, IzanamiErrors, Unit] = {
    val query =
      s"DELETE FROM $keyspace.$namespaceFormatted WHERE namespace = ? AND id = ? IF EXISTS "
    val args = Seq(namespaceFormatted, id.key)
    executeWithSessionT(
      query,
      args: _*
    ).map(_ => ()).orDie
  }

  override def getById(id: Key): RIO[DataStoreContext, Option[JsValue]] =
    getByIdRaw(id)

  private def getByIdRaw(id: Key): RIO[DataStoreContext, Option[JsValue]] = {
    val query =
      s"SELECT value FROM $keyspace.$namespaceFormatted WHERE namespace = ? AND id = ? "
    val args = Seq(namespaceFormatted, id.key)
    ZLogger.debug(s"Running query $query with args ${args.mkString("[", ",", "]")}") *>
    executeWithSessionT(
      query = query,
      args = args: _*
    ).map { rs =>
      val row: Row = rs.one()
      if (row != null && !row.isNull("value")) {
        Some(Json.parse(row.getString("value")))
      } else {
        None
      }
    }
  }

  override def findByQuery(query: Query,
                           page: Int,
                           nbElementPerPage: Int): RIO[DataStoreContext, PagingResult[JsValue]] =
    IO.fromFuture { _ =>
      getByIdLikeRaw(query)
        .via(Flows.count {
          Flow[(Key, JsValue)]
            .map(_._2)
            .drop(nbElementPerPage * (page - 1))
            .take(nbElementPerPage)
            .fold(Seq.empty[JsValue])(_ :+ _)
        })
        .map {
          case (res, count) =>
            DefaultPagingResult(res, page, nbElementPerPage, count)
              .asInstanceOf[PagingResult[JsValue]]
        }
        .runWith(Sink.head)
    }

  override def findByQuery(query: Query): Task[Source[(Key, JsValue), NotUsed]] =
    Task(getByIdLikeRaw(query))

  private def getByIdLikeRaw(q: Query): Source[(Key, JsValue), NotUsed] =
    if (q.hasEmpty) {
      Source.empty
    } else {
      val query =
        s"SELECT key, value FROM $keyspace.$namespaceFormatted WHERE namespace = ? "
      IzanamiLogger.debug(s"Running query $query with args [$namespaceFormatted]")
      val stmt = new SimpleStatement(query, namespaceFormatted)
      CassandraSource(stmt)
        .map { rs =>
          (Key(rs.getString("key")), Json.parse(rs.getString("value")))
        }
        .filter { case (k, _) => Query.keyMatchQuery(k, q) }
    }

  override def deleteAll(q: Query): ZIO[DataStoreContext, IzanamiErrors, Unit] =
    if (q.hasEmpty) {
      IO.succeed(())
    } else {
      for {
        runtime <- ZIO.runtime[DataStoreContext]
        res <- IO.fromFuture { implicit ec =>
                val query =
                  s"SELECT namespace, id, value FROM $keyspace.$namespaceFormatted WHERE namespace = ? "
                IzanamiLogger.debug(s"Running query $query with args [$namespaceFormatted]")
                val stmt: Statement =
                  new SimpleStatement(query, namespaceFormatted).setFetchSize(200)
                CassandraSource(stmt)
                  .map { rs =>
                    (rs.getString("namespace"), rs.getString("id"))
                  }
                  .filter { case (_, k) => Query.keyMatchQuery(Key(k), q) }
                  .mapAsync(4) {
                    case (n, id) =>
                      val query =
                        s"DELETE FROM $keyspace.$namespaceFormatted WHERE namespace = ? AND id = ? IF EXISTS "
                      Seq(n, id)
                      runtime.unsafeRunToFuture(executeWithSessionT(query, n, id))
                  }
                  .runFold(0)((acc, _) => acc + 1)
                  .map(_ => ())
              }.orDie
      } yield res
    }

  override def count(query: Query): Task[Long] = countRaw(query)

  private def countRaw(query: Query): Task[Long] =
    if (query.hasEmpty) {
      Task.succeed(0L)
    } else {
      Task.fromFuture { _ =>
        getByIdLikeRaw(query)
          .runFold(0L) { (acc, _) =>
            acc + 1
          }
      }
    }
}

object Cassandra {

  def sessionT()(implicit cluster: Cluster): zio.Task[Session] =
    zio.Task.fromFuture(implicit ec => cluster.connectAsync().toFuture)

  def executeWithSessionT(query: String, args: Any*)(implicit session: Session): DataStoreIO[ResultSet] =
    if (args.isEmpty) {
      ZLogger.debug(s"Running query $query ") *>
      zio.Task.fromFuture(implicit ec => session.executeAsync(query).toFuture)
    } else {
      ZLogger.debug(s"Running query $query with args ${args.mkString("[", ",", "]")} ") *>
      zio.Task.fromFuture(
        implicit ec =>
          session
            .executeAsync(new SimpleStatement(query, args.map(_.asInstanceOf[Object]): _*))
            .toFuture
      )
    }

  implicit class AsyncConvertions[T](f: ListenableFuture[T]) {

    def toFuture(implicit ec: ExecutionContext): Future[T] = {
      val promise: Promise[T] = Promise[T]()
      Futures.addCallback(
        f,
        new FutureCallback[T] {
          override def onFailure(t: Throwable): Unit = promise.failure(t)
          override def onSuccess(result: T): Unit    = promise.success(result)
        },
        ExecutorConversions.toExecutor(ec)
      )
      promise.future
    }

  }

  object ExecutorConversions {
    def toExecutor(ec: ExecutionContext): Executor =
      (command: Runnable) => ec.execute(command)
  }
}
