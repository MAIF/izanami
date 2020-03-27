package store.postgresql

import akka.NotUsed
import akka.stream.scaladsl.Source
import domains.{errors, Key}
import play.api.libs.json.{JsValue, Json}
import domains.errors.{IzanamiErrors, Result}
import store.{Query, _}
import store.datastore._
import cats.free.Free
import cats.implicits._
import doobie._
import doobie.free.connection
import doobie.implicits._
import doobie.Fragments._
import fs2.Stream
import env.DbDomainConfig
import org.postgresql.util.PGobject
import libs.logs.ZLogger
import domains.errors.DataShouldExists
import domains.errors.DataShouldNotExists

case class PgData(id: Key, data: JsValue)

object PgData {

  implicit val jsonMeta: Meta[JsValue] =
    Meta.Advanced
      .other[PGobject]("jsonb")
      .timap[JsValue](a => Json.parse(a.getValue))(
        a => {
          val o = new PGobject
          o.setType("jsonb")
          o.setValue(Json.stringify(a))
          o
        }
      )

  implicit val keyRead: Read[Key] = Read[String].map { s =>
    Key(s)
  }
  implicit val pointWrite: Write[Key] = Write[String].contramap(k => k.key)

  implicit val pgDataRead: Read[PgData]   = Read[(Key, JsValue)].map { case (x, y) => new PgData(x, y) }
  implicit val pgDataWrite: Write[PgData] = Write[(Key, JsValue)].contramap(data => (data.id, data.data))

}

object PostgresqlJsonDataStore {

  def apply(client: PostgresqlClient, domainConfig: DbDomainConfig): PostgresqlJsonDataStore =
    new PostgresqlJsonDataStore(client, domainConfig.conf.namespace)
}

class PostgresqlJsonDataStore(client: PostgresqlClient, namespace: String) extends JsonDataStore.Service {

  private val xa                = client.transactor
  private val tableName: String = namespace.replaceAll(":", "_")
  private val fragTableName     = Fragment.const(tableName)

  import PgData._
  import zio._
  import zio.interop.catz._

  private val dbScript = sql"""
       create table if not exists """ ++ fragTableName ++ sql""" (
         id varchar(100) primary key,
         payload jsonb not null
       )
    """

  private val createIndex = sql"""
       CREATE INDEX IF NOT EXISTS trgm_idx_""" ++ fragTableName ++ fr""" ON """ ++ fragTableName ++ fr"""  USING gin (id gin_trgm_ops)"""

  override def start: RIO[DataStoreContext, Unit] =
    ZLogger.debug(s"Applying script $dbScript") *>
    (for {
      _ <- dbScript.update.run.transact(xa).unit
      _ <- createIndex.update.run.transact(xa).unit.catchAll { e =>
            ZLogger.error(s"Error creating search index to $tableName, you should add the extension pg_tgrm", e) *>
            ZIO.unit
          }
    } yield ())

  override def create(
      id: Key,
      data: JsValue
  ): IO[IzanamiErrors, JsValue] = {

    val result = for {
      exists <- getByKeyQuery(id)
      r <- exists match {
            case Some(_) =>
              errors.Result
                .error[JsValue](DataShouldNotExists(id))
                .pure[ConnectionIO]
            case None =>
              insertQuery(id, data).run
                .map(_ => errors.Result.ok(data))
          }
    } yield r

    val value: IO[IzanamiErrors, Result[JsValue]] = result
      .transact(xa)
      .orDie
    value.absolve
  }

  override def update(
      oldId: Key,
      id: Key,
      data: JsValue
  ): IO[IzanamiErrors, JsValue] = {

    val result = for {
      exists <- getByKeyQuery(oldId)
      r <- exists match {
            case None =>
              errors.Result
                .error[JsValue](DataShouldExists(oldId))
                .pure[ConnectionIO]
            case Some(_) =>
              if (oldId === id) {
                updateQuery(id, data).run
                  .map(_ => errors.Result.ok(data))
              } else {
                for {
                  _ <- deleteByIdQuery(oldId).run
                  _ <- insertQuery(id, data).run
                } yield errors.Result.ok(data)
              }
          }
    } yield r

    val value: IO[IzanamiErrors, Result[JsValue]] = result
      .transact(xa)
      .orDie
    value.absolve
  }

  override def delete(id: Key): IO[IzanamiErrors, JsValue] = {
    val result = for {
      exists <- getByKeyQuery(id)
      r <- exists match {
            case None =>
              errors.Result
                .error[JsValue](DataShouldExists(id))
                .pure[ConnectionIO]
            case Some(data) =>
              deleteByIdQuery(id).run
                .map(_ => errors.Result.ok(data.data))
          }
    } yield r

    val value: IO[IzanamiErrors, Result[JsValue]] = result
      .transact(xa)
      .orDie
    value.absolve
  }

  override def getById(id: Key): Task[Option[JsValue]] =
    getByKeyQuery(id)
      .map(_.map(_.data))
      .transact(xa)

  override def findByQuery(query: Query, page: Int, nbElementPerPage: Int): Task[PagingResult[JsValue]] = {

    import Fragments._

    val position   = (page - 1) * nbElementPerPage
    val findQuery  = sql"select payload from " ++ fragTableName ++ whereAnd(queryClause(query)) ++ fr" OFFSET $position LIMIT $nbElementPerPage"
    val countQuery = sql"select count(*) from " ++ fragTableName ++ whereAnd(queryClause(query))

    val queries: Free[connection.ConnectionOp, PagingResult[JsValue]] = for {
      count <- countQuery.query[Int].unique
      res   <- findQuery.query[JsValue].to[List]
    } yield DefaultPagingResult(res, page, nbElementPerPage, count)

    queries.transact(xa)
  }

  override def findByQuery(query: Query): Task[Source[(Key, JsValue), NotUsed]] =
    for {
      runtime <- ZIO.runtime[Any]
      res <- Task {
              implicit val r = runtime
              import zio.interop.catz._
              import streamz.converter._
              val findQuery = sql"select id, payload from " ++ fragTableName ++ whereAnd(queryClause(query))

              val s: Stream[Task, (Key, JsValue)] = findQuery.query[(Key, JsValue)].stream.transact(xa)
              Source.fromGraph(s.toSource)
            }
    } yield res

  override def deleteAll(query: Query): IO[IzanamiErrors, Unit] = {
    val q = sql"delete from " ++ fragTableName ++ whereAnd(queryClause(query))
    val value: IO[IzanamiErrors, Int] = q.update.run
      .transact(xa)
      .orDie
    value.unit
  }

  override def count(query: Query): Task[Long] =
    (sql"select count(*) from " ++ fragTableName ++ whereAnd(queryClause(query)))
      .query[Long]
      .unique
      .transact(xa)

  private def getByKeyQuery(key: Key): ConnectionIO[Option[PgData]] =
    (sql"select id, payload from " ++ fragTableName ++ fr" where id = ${key.key}").query[PgData].option

  private def queryClause(query: Query): Fragment =
    query.ands.map(oneOfPatternClause).intercalate(fr" AND ")

  private def oneOfPatternClause(query: OneOfPatternClause): Fragment =
    fr"( " ++ query.patterns
      .map {
        case StringPattern(str) =>
          val updatedP = str.replaceAll("\\*", ".*")
          fr" id ~ $updatedP "
        case EmptyPattern =>
          Fragment.empty
      }
      .intercalate(fr" OR ") ++ fr" ) "

  private def insertQuery(id: Key, data: JsValue): Update0 =
    (sql"insert into " ++ fragTableName ++ fr" (id, payload) values (${id.key}, $data) ").update

  private def updateQuery(id: Key, data: JsValue): Update0 =
    (sql"update " ++ fragTableName ++ fr" set payload = $data where id = ${id.key}").update

  private def deleteByIdQuery(id: Key): Update0 =
    (sql"delete from " ++ fragTableName ++ fr" where id = ${id.key} ").update
}
