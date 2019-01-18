package store.postgresql
import java.util.concurrent.atomic.AtomicReference

import akka.actor.ActorSystem
import akka.http.scaladsl.util.FastFuture
import cats.effect.{Async, ContextShift}
import doobie.util.transactor.Transactor
import env.PostgresqlConfig
import play.api.Logger
import play.api.db.{Database, Databases}
import play.api.inject.ApplicationLifecycle

case class PostgresqlClient[F[_]: Async: ContextShift](database: Database, transactor: Transactor[F])

object PostgresqlClient {

  def postgresqlClient[F[_]: Async: ContextShift](system: ActorSystem,
                                                  applicationLifecycle: ApplicationLifecycle,
                                                  cf: Option[PostgresqlConfig]): Option[PostgresqlClient[F]] =
    cf.map { config =>
      Logger.info(s"Creating database instance")
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

      Logger.info(s"Creating transactor instance")
      val ce = system.dispatchers.lookup("izanami.jdbc-connection-dispatcher")
      val te = system.dispatchers.lookup("izanami.jdbc-transaction-dispatcher")
      PostgresqlClient(database, Transactor.fromDataSource[F](database.dataSource, ce, te))
    }
}
