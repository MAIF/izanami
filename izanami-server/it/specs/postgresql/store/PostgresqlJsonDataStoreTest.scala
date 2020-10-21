package specs.postgresql.store

import env.{DbDomainConfig, DbDomainConfigDetails, PostgresqlConfig}
import libs.logs.ZLogger
import org.scalatest.{BeforeAndAfter, BeforeAndAfterAll}
import store.AbstractJsonDataStoreTest
import test.FakeApplicationLifecycle
import store.postgresql.PostgresqlClient
import store.postgresql.PostgresqlJsonDataStore

class PostgresqlJsonDataStoreTest
    extends AbstractJsonDataStoreTest("Postgresql")
    with BeforeAndAfter
    with BeforeAndAfterAll {
  import zio._
  import zio.interop.catz._
  import zio.NeedsEnv.needsEnv

  private val pgConfig = PostgresqlConfig(
    "org.postgresql.Driver",
    "jdbc:postgresql://localhost:5555/izanami",
    "izanami",
    "izanami",
    32,
    None
  )

  val rPgClient: Reservation[ZLogger, Throwable, Option[PostgresqlClient]] = runtime.unsafeRun(
    PostgresqlClient
      .postgresqlClient(
        system,
        Some(pgConfig)
      )
      .reserve
      .provideLayer(ZLogger.live)
  )

  private val client: Option[PostgresqlClient] = runtime.unsafeRun(rPgClient.acquire.provideLayer(ZLogger.live))

  override def dataStore(name: String): PostgresqlJsonDataStore = {
    val store =
      PostgresqlJsonDataStore(client.get, DbDomainConfig(env.Postgresql, DbDomainConfigDetails(name, None), None))
    store
  }

  override protected def afterAll(): Unit = {
    super.afterAll()
    runtime.unsafeRun(rPgClient.release(Exit.unit).provideLayer(ZLogger.live))
  }
}
