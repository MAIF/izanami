package fr.maif.izanami.services

import scala.concurrent.Future
import fr.maif.izanami.utils.FutureEither
import fr.maif.izanami.utils.syntax.implicits.BetterFutureEither
import org.slf4j.Logger
import io.vertx.sqlclient.SqlConnection
import io.vertx.sqlclient.Pool
import fr.maif.izanami.env.pgimplicits.ScalaFutureEnhancer
import fr.maif.izanami.env.pgimplicits.VertxFutureEnhancer
import scala.concurrent.ExecutionContext

sealed trait TransactionProvider[Tx] {
  def logger: Logger
  
  def executeInTransaction[Res](
      callback: Tx => Future[Res],
  ): Future[Res];

  def executeInTransaction[Res](
      callback: Tx => FutureEither[Res]
  ): FutureEither[Res] = executeInTransaction(tx => callback(tx).value).toFEither

  def executeInOptionalTransaction[Res](
      maybeTransaction: Option[Tx],
      callback: Tx => Future[Res]
  ): Future[Res] = maybeTransaction.fold(executeInTransaction(callback))(tx => callback(tx))

  def executeInOptionalTransaction[Res](
      maybeTransaction: Option[Tx],
      callback: Tx => FutureEither[Res]
  ): FutureEither[Res] = {
    executeInOptionalTransaction(
      maybeTransaction,
      conn => {
        callback(conn).value
      }
    ).toFEither
  }
}


class PostgresTransactionProvider(private val pool: Pool, override val logger: Logger)(implicit ec: ExecutionContext) extends TransactionProvider[SqlConnection] {
  override def executeInTransaction[Res](callback: SqlConnection => Future[Res]): Future[Res] = {
    var future: io.vertx.core.Future[Res] = io.vertx.core.Future.succeededFuture()
    pool
      .withTransaction(conn => {
        future = callback(conn).vertx(ec)
        future
      })
      .scala // Bubble up query error instead of TransactionRollbackException that does not carry much information
  }


}