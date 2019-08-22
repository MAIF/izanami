package store.postgresql

import akka.actor.ActorSystem
import akka.http.scaladsl.util.FastFuture
import doobie.util.transactor.Transactor
import env.PostgresqlConfig
import libs.logs.IzanamiLogger
import play.api.db.{Database, Databases}
import play.api.inject.ApplicationLifecycle
import zio.Task
import zio.interop.catz._

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
      val ce = system.dispatchers.lookup("izanami.jdbc-connection-dispatcher")
      val te = system.dispatchers.lookup("izanami.jdbc-transaction-dispatcher")
      PostgresqlClient(database, Transactor.fromDataSource[Task](database.dataSource, ce, te))
    }
}
