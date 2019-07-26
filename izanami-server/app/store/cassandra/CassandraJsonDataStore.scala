package store.cassandra

import java.util.concurrent.Executor

import akka.actor.ActorSystem
import akka.stream.{ActorMaterializer, Materializer}
import akka.stream.alpakka.cassandra.scaladsl.CassandraSource
import akka.stream.scaladsl.{Flow, Sink, Source}
import akka.NotUsed
import com.datastax.driver.core._
import com.google.common.util.concurrent.{FutureCallback, Futures, ListenableFuture}
import domains.Key
import env.{CassandraConfig, DbDomainConfig}
import libs.streams.Flows
import libs.logs.IzanamiLogger
import play.api.libs.json.{JsValue, Json}
import store.Result.{AppErrors, ErrorMessage, IzanamiErrors}
import store._

import scala.concurrent.{ExecutionContext, Future, Promise}
import libs.logs.Logger
import store.Result.DataShouldExists
import store.Result.DataShouldNotExists

object CassandraJsonDataStore {
  def apply(session: Session, cassandraConfig: CassandraConfig, config: DbDomainConfig)(
      implicit actorSystem: ActorSystem
  ): CassandraJsonDataStore =
    new CassandraJsonDataStore(config.conf.namespace, cassandraConfig.keyspace, session)
}

class CassandraJsonDataStore(namespace: String, keyspace: String, session: Session)(
    implicit actorSystem: ActorSystem
) extends JsonDataStore {

  IzanamiLogger.info(s"Load store Cassandra for namespace $namespace")

  private val namespaceFormatted = namespace.replaceAll(":", "_")

  import zio._
  import Cassandra._

  implicit private val mat: Materializer = ActorMaterializer()(actorSystem)
  implicit private val _session: Session = session

  override def start: RIO[DataStoreContext, Unit] =
    Logger.info(s"Creating table $keyspace.$namespaceFormatted if not exists") *>
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
    getByIdRaw(id)
      .refineToOrDie[IzanamiErrors]
      .flatMap {
        case Some(_) =>
          ZIO.fail(DataShouldNotExists(id))
        case None =>
          createRaw(id, data)
      }

  private def createRaw(id: Key, data: JsValue): ZIO[DataStoreContext, IzanamiErrors, JsValue] = {
    val query =
      s"INSERT INTO $keyspace.$namespaceFormatted (namespace, id, key, value) values (?, ?, ?, ?) IF NOT EXISTS "

    val args = Seq(namespaceFormatted, id.key, id.key, Json.stringify(data))
    executeWithSessionT(query, args: _*)
      .map { _ =>
        data
      }
      .refineToOrDie[IzanamiErrors]
  }

  override def update(oldId: Key, id: Key, data: JsValue): ZIO[DataStoreContext, IzanamiErrors, JsValue] =
    if (oldId.key == id.key) {
      getByIdRaw(id)
        .refineToOrDie[IzanamiErrors]
        .flatMap {
          case Some(_) => updateRaw(id: Key, data)
          case None    => ZIO.fail(DataShouldExists(id))
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
      .refineToOrDie[IzanamiErrors]
  }

  override def delete(id: Key): ZIO[DataStoreContext, IzanamiErrors, JsValue] =
    getByIdRaw(id)
      .refineToOrDie[IzanamiErrors]
      .flatMap {
        case Some(d) => deleteRaw(id).map(_ => d)
        case None    => IO.fail(DataShouldExists(id))
      }

  private def deleteRaw(id: Key): ZIO[DataStoreContext, IzanamiErrors, Unit] = {
    val query =
      s"DELETE FROM $keyspace.$namespaceFormatted WHERE namespace = ? AND id = ? IF EXISTS "
    val args = Seq(namespaceFormatted, id.key)
    executeWithSessionT(
      query,
      args: _*
    ).map(_ => ())
      .refineToOrDie[IzanamiErrors]
  }

  override def getById(id: Key): RIO[DataStoreContext, Option[JsValue]] =
    getByIdRaw(id)

  private def getByIdRaw(id: Key): RIO[DataStoreContext, Option[JsValue]] = {
    val query =
      s"SELECT value FROM $keyspace.$namespaceFormatted WHERE namespace = ? AND id = ? "
    val args = Seq(namespaceFormatted, id.key)
    Logger.debug(s"Running query $query with args ${args.mkString("[", ",", "]")}") *>
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
        res <- IO
                .fromFuture { implicit ec =>
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
                        val args = Seq(n, id)
                        runtime.unsafeRunToFuture(executeWithSessionT(query, n, id))
                    }
                    .runFold(0)((acc, _) => acc + 1)
                    .map(_ => ())
                }
                .refineToOrDie[IzanamiErrors]
      } yield res
    }

  override def count(query: Query): Task[Long] = countRaw(query)

  private def countRaw(query: Query): Task[Long] =
    if (query.hasEmpty) {
      Task.succeed(0L)
    } else {
      Task.fromFuture { implicit ec =>
        getByIdLikeRaw(query)
          .runFold(0L) { (acc, _) =>
            acc + 1
          }
      }
    }
}

object Cassandra {

  import cats.effect.IO

  def sessionT()(implicit cluster: Cluster): zio.Task[Session] =
    zio.Task.fromFuture(implicit ec => cluster.connectAsync().toFuture)

  def executeWithSessionT(query: String, args: Any*)(implicit session: Session): zio.RIO[DataStoreContext, ResultSet] =
    if (args.isEmpty) {
      Logger.debug(s"Running query $query ") *>
      zio.Task.fromFuture(implicit ec => session.executeAsync(query).toFuture)
    } else {
      Logger.debug(s"Running query $query with args ${args.mkString("[", ",", "]")} ") *>
      zio.Task.fromFuture(
        implicit ec =>
          session
            .executeAsync(new SimpleStatement(query, args.map(_.asInstanceOf[Object]): _*))
            .toFuture
      )
    }

  implicit class AsyncConvertions[T](f: ListenableFuture[T]) {

    def toFuture(implicit ec: ExecutionContext): Future[T] = {
      val promise: Promise[T] = Promise[T]
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
