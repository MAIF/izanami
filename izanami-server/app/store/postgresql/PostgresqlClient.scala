package store.postgresql

import akka.actor.ActorSystem
import cats.effect.Blocker
import doobie.util.transactor.Transactor
import doobie.util.transactor.Transactor.Aux
import env.PostgresqlConfig
import javax.sql.DataSource
import libs.logs.{IzanamiLogger, ZLogger}
import play.api.db.{Database, Databases}
import zio.{Task, UIO, ZManaged}
import zio.interop.catz._

import scala.concurrent.ExecutionContext

case class PostgresqlClient(database: Database, transactor: Transactor[Task])

object PostgresqlClient {

  def postgresqlClient(system: ActorSystem,
                       cf: Option[PostgresqlConfig]): ZManaged[ZLogger, Throwable, Option[PostgresqlClient]] =
    cf.map { config =>
        ZManaged
          .make(
            ZLogger.info(s"Creating database instance") *>
            Task {
              Databases(
                config.driver,
                config.url,
                config = Map(
                  "username" -> config.username,
                  "password" -> config.password,
                  "pool"     -> "hikaricp"
                )
              )
            }
          )(database => UIO(database.shutdown()))
          .mapM { database =>
            ZLogger.info(s"Creating transactor instance") *>
            Task {
              val ce: ExecutionContext = system.dispatchers.lookup("izanami.jdbc-connection-dispatcher")
              val te: ExecutionContext = system.dispatchers.lookup("izanami.jdbc-transaction-dispatcher")
              val transact: Aux[Task, DataSource] = Transactor
                .fromDataSource[Task](database.dataSource, ce, Blocker.liftExecutionContext(te))
              Some(PostgresqlClient(database, transact))
            }
          }
      }
      .getOrElse(ZManaged.effectTotal(None))
}
