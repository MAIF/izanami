package store.cassandra

import java.util.concurrent.Executor

import akka.actor.ActorSystem
import akka.stream.{ActorMaterializer, Materializer}
import akka.stream.alpakka.cassandra.scaladsl.CassandraSource
import akka.stream.scaladsl.{Flow, Sink, Source}
import akka.{Done, NotUsed}
import cats.effect.{Async, Effect}
import com.datastax.driver.core._
import com.google.common.util.concurrent.{FutureCallback, Futures, ListenableFuture}
import domains.Key
import env.{CassandraConfig, DbDomainConfig}
import libs.streams.Flows
import libs.logs.IzanamiLogger
import play.api.libs.json.{JsValue, Json}
import store.Result.{ErrorMessage, Result}
import store._

import scala.concurrent.{ExecutionContext, Promise}

object CassandraJsonDataStore {
  def apply[F[_]: Effect](session: Session, cassandraConfig: CassandraConfig, config: DbDomainConfig)(
      implicit actorSystem: ActorSystem
  ): CassandraJsonDataStore[F] =
    new CassandraJsonDataStore(config.conf.namespace, cassandraConfig.keyspace, session)

}

class CassandraJsonDataStore[F[_]: Effect](namespace: String, keyspace: String, session: Session)(
    implicit actorSystem: ActorSystem
) extends JsonDataStore[F] {

  IzanamiLogger.info(s"Load store Cassandra for namespace $namespace")

  private val namespaceFormatted = namespace.replaceAll(":", "_")

  import Cassandra._
  import cats.implicits._
  import libs.streams.syntax._
  import libs.effects._

  implicit private val mat: Materializer = ActorMaterializer()(actorSystem)
  implicit private val _session: Session = session

  import actorSystem.dispatcher

  IzanamiLogger.info(s"Creating table $keyspace.$namespaceFormatted if not exists")
  session.execute(s"""
      | CREATE TABLE IF NOT EXISTS $keyspace.$namespaceFormatted (
      |   namespace text,
      |   id text,
      |   key text,
      |   value text,
      |   PRIMARY KEY ((namespace), id)
      | )
      | """.stripMargin)

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

  private val regex = """[\*]?(([\w@\.0-9\-]+)(:?))+[\*]?""".r

  private def patternStatement(field: String, patterns: Seq[String]): Result[String] = {
    val hasError: Boolean = patterns
      .map {
        case "*"       => false
        case regex(_*) => false
        case _         => true
      }
      .foldLeft(false)(_ || _)

    if (hasError) {
      Result.error("pattern.invalid")
    } else {
      Result.ok(
        patterns
          .collect {
            case str if str != "*" =>
              s" $field LIKE '${str.replaceAll("\\*", "%")}' "
          }
          .mkString(" OR ")
      )
    }

  }

  override def create(id: Key, data: JsValue): F[Result[JsValue]] =
    getByIdRaw(id)
      .flatMap {
        case Some(d) =>
          Result.errors[JsValue](ErrorMessage("error.data.exists", id.key)).pure[F]
        case None =>
          createRaw(id, data)
      }

  private def createRaw(id: Key, data: JsValue): F[Result[JsValue]] = {
    val query =
      s"INSERT INTO $keyspace.$namespaceFormatted (namespace, id, key, value) values (?, ?, ?, ?) IF NOT EXISTS "

    val args = Seq(namespaceFormatted, id.key, id.key, Json.stringify(data))
    executeWithSession(
      query,
      args: _*
    ).map { _ =>
      Result.ok(data)
    }
  }

  override def update(oldId: Key, id: Key, data: JsValue): F[Result[JsValue]] =
    if (oldId.key == id.key) {
      getByIdRaw(id)
        .flatMap {
          case Some(_) => updateRaw(id: Key, data)
          case None    => Result.error[JsValue]("error.data.missing").pure[F]
        }
    } else {
      deleteRaw(oldId)
        .flatMap { _ =>
          createRaw(id, data)
        }
    }

  private def updateRaw(id: Key, data: JsValue): F[Result[JsValue]] = {
    val query =
      s"UPDATE $keyspace.$namespaceFormatted SET value = ? WHERE namespace = ? AND id = ? IF EXISTS "
    val args = Seq(Json.stringify(data), namespaceFormatted, id.key)
    executeWithSession(query, args: _*).map(rs => Result.ok(data))
  }

  override def delete(id: Key): F[Result[JsValue]] =
    getByIdRaw(id)
      .flatMap {
        case Some(d) =>
          deleteRaw(id).map(_ => Result.ok(d))
        case None =>
          Result.error[JsValue]("error.data.missing").pure[F]
      }

  private def deleteRaw(id: Key): F[Unit] = {
    val query =
      s"DELETE FROM $keyspace.$namespaceFormatted WHERE namespace = ? AND id = ? IF EXISTS "
    val args = Seq(namespaceFormatted, id.key)
    executeWithSession(
      query,
      args: _*
    ).map(_ => ())
  }

  override def deleteAll(patterns: Seq[String]): F[Result[Done]] =
    if (patterns.isEmpty || patterns.contains("")) {
      Effect[F].pure(Result.ok(Done))
    } else {
      patternStatement("key", patterns) match {
        case Right(s) =>
          val stm: String = if (!s.isEmpty) s"AND $s" else ""
          val query =
            s"SELECT namespace, id, value FROM $keyspace.$namespaceFormatted WHERE namespace = ? $stm"
          IzanamiLogger.debug(s"Running query $query with args [$namespaceFormatted]")
          val stmt: Statement =
            new SimpleStatement(query, namespaceFormatted).setFetchSize(200)
          CassandraSource(stmt)
            .map { rs =>
              (rs.getString("namespace"), rs.getString("id"))
            }
            .mapAsyncF(4) {
              case (n, id) =>
                val query =
                  s"DELETE FROM $keyspace.$namespaceFormatted WHERE namespace = ? AND id = ? IF EXISTS "
                val args = Seq(n, id)
                executeWithSession(query, n, id)
            }
            .runFold(0)((acc, _) => acc + 1)
            .toF
            .map(_ => Result.ok(Done))
        case Left(s) => Result.error[Done](s).pure[F]
      }
    }

  override def getById(id: Key): F[Option[JsValue]] =
    getByIdRaw(id)

  private def getByIdRaw(id: Key): F[Option[JsValue]] = {
    val query =
      s"SELECT value FROM $keyspace.$namespaceFormatted WHERE namespace = ? AND id = ? "
    val args = Seq(namespaceFormatted, id.key)
    IzanamiLogger.debug(s"Running query $query with args ${args.mkString("[", ",", "]")}")
    executeWithSession(
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

  override def getByIdLike(patterns: Seq[String], page: Int, nbElementPerPage: Int): F[PagingResult[JsValue]] =
    getByIdLikeRaw(patterns)
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
      .toF

  override def getByIdLike(patterns: Seq[String]): Source[(Key, JsValue), NotUsed] =
    getByIdLikeRaw(patterns)

  private def getByIdLikeRaw(patterns: Seq[String]): Source[(Key, JsValue), NotUsed] =
    if (patterns.isEmpty || patterns.contains("")) {
      Source.empty
    } else {
      patternStatement("key", patterns) match {
        case Right(s) =>
          val stm: String = if (!s.isEmpty) s"AND $s" else ""
          val query =
            s"SELECT key, value FROM $keyspace.$namespaceFormatted WHERE namespace = ? $stm"
          IzanamiLogger.debug(s"Running query $query with args [$namespaceFormatted]")
          val stmt = new SimpleStatement(query, namespaceFormatted)
          CassandraSource(stmt).map { rs =>
            (Key(rs.getString("key")), Json.parse(rs.getString("value")))
          }
        case Left(err) =>
          Source.failed(new IllegalArgumentException("pattern.invalid"))
      }
    }

  override def count(patterns: Seq[String]): F[Long] =
    countRaw(patterns).flatMap {
      case Right(r) => r.pure[F]
      case Left(r) =>
        Effect[F].raiseError(new IllegalArgumentException("pattern.invalid"))
    }

  private def countRaw(patterns: Seq[String]): F[Result[Long]] =
    if (patterns.isEmpty || patterns.contains("")) {
      Result.ok(0L).pure[F]
    } else {
      patternStatement("key", patterns) match {
        case Right(s) =>
          val stm: String = if (!s.isEmpty) s"AND $s" else ""
          val query =
            s"SELECT COUNT(*) AS count FROM $keyspace.$namespaceFormatted WHERE namespace = ? $stm"
          IzanamiLogger.debug(s"Running query $query with args [$namespaceFormatted]")
          val stmt: SimpleStatement =
            new SimpleStatement(
              query,
              namespaceFormatted
            )
          CassandraSource(stmt)
            .map { rs =>
              Result.ok(rs.getLong("count"))
            }
            .runWith(Sink.head)
            .toF
        case Left(err) => Result.error[Long](err).pure[F]
      }
    }
}

object Cassandra {

  def session[F[_]: Async]()(implicit cluster: Cluster, ec: ExecutionContext): F[Session] =
    cluster.connectAsync().toF

  def executeWithSession[F[_]: Async](query: String, args: Any*)(implicit session: Session,
                                                                 ec: ExecutionContext): F[ResultSet] =
    if (args.isEmpty) {
      IzanamiLogger.debug(s"Running query $query ")
      session.executeAsync(query).toF
    } else {
      IzanamiLogger.debug(s"Running query $query with args ${args.mkString("[", ",", "]")} ")
      session
        .executeAsync(new SimpleStatement(query, args.map(_.asInstanceOf[Object]): _*))
        .toF
    }

  def executeWithSession[F[_]: Async](query: Statement)(implicit session: Session, ec: ExecutionContext): F[ResultSet] =
    session.executeAsync(query).toF

  implicit class AsyncConvertions[T](f: ListenableFuture[T]) {
    def toF[F[_]: Async](implicit ec: ExecutionContext): F[T] = {
      val promise: Promise[T] = Promise[T]
      Async[F].async { cb =>
        Futures.addCallback(
          f,
          new FutureCallback[T] {
            override def onFailure(t: Throwable): Unit = cb(Left(t))
            override def onSuccess(result: T): Unit    = cb(Right(result))
          },
          ExecutorConversions.toExecutor(ec)
        )
      }
    }
  }

  object ExecutorConversions {
    def toExecutor(ec: ExecutionContext): Executor =
      (command: Runnable) => ec.execute(command)
  }
}
