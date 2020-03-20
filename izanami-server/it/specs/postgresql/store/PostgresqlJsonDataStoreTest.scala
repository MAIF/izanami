package specs.postgresql.store

import env.{DbDomainConfig, DbDomainConfigDetails, PostgresqlConfig}
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

  private val pgConfig = PostgresqlConfig(
    "org.postgresql.Driver",
    "jdbc:postgresql://localhost:5555/izanami",
    "izanami",
    "izanami",
    32,
    None
  )

  private val client: Option[PostgresqlClient] = PostgresqlClient.postgresqlClient(
    system,
    new FakeApplicationLifecycle(),
    Some(pgConfig)
  )

  override def dataStore(name: String): PostgresqlJsonDataStore = {
    val store =
      PostgresqlJsonDataStore(client.get, DbDomainConfig(env.Postgresql, DbDomainConfigDetails(name, None), None))
    store
  }

  override protected def afterAll(): Unit = {
    super.afterAll()
    client.get.database.shutdown()
  }
}
