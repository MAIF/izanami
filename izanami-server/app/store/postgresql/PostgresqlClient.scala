package store.postgresql

import akka.actor.ActorSystem
import akka.http.scaladsl.util.FastFuture
import cats.effect.Blocker
import doobie.util.transactor.Transactor
import doobie.util.transactor.Transactor.Aux
import env.PostgresqlConfig
import javax.sql.DataSource
import libs.logs.IzanamiLogger
import play.api.db.{Database, Databases}
import play.api.inject.ApplicationLifecycle
import zio.Task
import zio.interop.catz._

import scala.concurrent.ExecutionContext

case class PostgresqlClient(database: Database, transactor: Transactor[Task])

object PostgresqlClient {

  def postgresqlClient(system: ActorSystem,
                       applicationLifecycle: ApplicationLifecycle,
                       cf: Option[PostgresqlConfig]): Option[PostgresqlClient] =
    cf.map { config =>
      IzanamiLogger.info(s"Creating database instance")
      val database = Databases(
        config.driver,
        config.url,
        config = Map(
          "username" -> config.username,
          "password" -> config.password,
          "pool"     -> "hikaricp"
        )
      )

      applicationLifecycle.addStopHook { () =>
        FastFuture.successful(database.shutdown())
      }

      IzanamiLogger.info(s"Creating transactor instance")
      val ce: ExecutionContext = system.dispatchers.lookup("izanami.jdbc-connection-dispatcher")
      val te: ExecutionContext = system.dispatchers.lookup("izanami.jdbc-transaction-dispatcher")
      val transact: Aux[Task, DataSource] = Transactor
        .fromDataSource[Task](database.dataSource, ce, Blocker.liftExecutionContext(te))
      PostgresqlClient(database, transact)
    }
}
