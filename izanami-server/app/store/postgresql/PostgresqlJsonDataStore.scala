package store.postgresql

import akka.{Done, NotUsed}
import akka.stream.scaladsl.Source
import cats.effect._
import domains.Key
import play.api.libs.json.{JsValue, Json}
import store.Result.{ErrorMessage, Result}
import store.{Query, _}
import cats._
import cats.data._
import cats.free.Free
import cats.implicits._
import doobie._
import doobie.free.connection
import doobie.implicits._
import doobie.Fragments._
import fs2.Stream
import env.{DbDomainConfig, PostgresqlConfig}
import org.postgresql.util.PGobject
import libs.logs.IzanamiLogger

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

  def apply[F[_]: ContextShift: ConcurrentEffect](client: PostgresqlClient[F],
                                                  config: PostgresqlConfig,
                                                  domainConfig: DbDomainConfig): PostgresqlJsonDataStore[F] =
    new PostgresqlJsonDataStore[F](config, client, domainConfig.conf.namespace)
}

class PostgresqlJsonDataStore[F[_]: ContextShift: ConcurrentEffect](config: PostgresqlConfig,
                                                                    client: PostgresqlClient[F],
                                                                    namespace: String)
    extends JsonDataStore[F] {

  private val xa                = client.transactor
  private val tableName: String = namespace.replaceAll(":", "_")
  private val fragTableName     = Fragment.const(tableName)

  import PgData._

  private val dbScript = sql"""
       create table if not exists """ ++ fragTableName ++ sql""" (
         id varchar(100) primary key,
         payload jsonb not null
       )
    """

  {
    import cats.effect.implicits._
    IzanamiLogger.debug(s"Applying script $dbScript")
    dbScript.update.run.transact(xa).toIO.unsafeRunSync()
  }

  override def create(
      id: Key,
      data: JsValue
  ): F[Result[JsValue]] = {

    val result = for {
      exists <- getByKeyQuery(id)
      r <- exists match {
            case Some(_) =>
              Result
                .errors[JsValue](ErrorMessage("error.data.exists", id.key))
                .pure[ConnectionIO]
            case None =>
              insertQuery(id, data).run
                .map(_ => Result.ok(data))
          }
    } yield r

    result.transact(xa)
  }

  override def update(
      oldId: Key,
      id: Key,
      data: JsValue
  ): F[Result[JsValue]] = {

    val result = for {
      exists <- getByKeyQuery(oldId)
      r <- exists match {
            case None =>
              Result
                .errors[JsValue](ErrorMessage("error.data.missing", id.key))
                .pure[ConnectionIO]
            case Some(_) =>
              if (oldId == id) {
                updateQuery(id, data).run
                  .map(_ => Result.ok(data))
              } else {
                for {
                  _ <- deleteByIdQuery(oldId).run
                  _ <- insertQuery(id, data).run
                } yield Result.ok(data)
              }
          }
    } yield r

    result.transact(xa)
  }

  override def delete(id: Key): F[Result[JsValue]] = {
    val result = for {
      exists <- getByKeyQuery(id)
      r <- exists match {
            case None =>
              Result
                .errors[JsValue](ErrorMessage("error.data.missing", id.key))
                .pure[ConnectionIO]
            case Some(data) =>
              deleteByIdQuery(id).run
                .map(_ => Result.ok(data.data))
          }
    } yield r

    result.transact(xa)
  }

  override def getById(id: Key): F[Option[JsValue]] =
    getByKeyQuery(id).map(_.map(_.data)).transact(xa)

  override def findByQuery(query: Query, page: Int, nbElementPerPage: Int): F[PagingResult[JsValue]] = {

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

  override def findByQuery(query: Query): Source[(Key, JsValue), NotUsed] = {
    import streamz.converter._

    val findQuery = sql"select id, payload from " ++ fragTableName ++ whereAnd(queryClause(query))

    val s: Stream[F, (Key, JsValue)] = findQuery.query[(Key, JsValue)].stream.transact(xa)
    Source.fromGraph(s.toSource)
  }

  override def deleteAll(query: Query): F[Result[Done]] = {
    val q = sql"delete from " ++ fragTableName ++ whereAnd(queryClause(query))
    q.update.run.transact(xa).map(_ => Result.ok(Done))
  }

  override def count(query: Query): F[Long] =
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

  private def patternsClause(patterns: Seq[String]): Option[Fragment] =
    NonEmptyList
      .fromList(patterns.toList)
      .map(_.map { p =>
        val updatedP = p.replaceAll("\\*", ".*")
        fr" id ~ $updatedP "
      }.intercalate(fr" AND "))

  private def insertQuery(id: Key, data: JsValue): Update0 =
    (sql"insert into " ++ fragTableName ++ fr" (id, payload) values (${id.key}, $data) ").update

  private def updateQuery(id: Key, data: JsValue): Update0 =
    (sql"update " ++ fragTableName ++ fr" set payload = $data where id = ${id.key}").update

  private def deleteByIdQuery(id: Key): Update0 =
    (sql"delete from " ++ fragTableName ++ fr" where id = ${id.key} ").update
}
