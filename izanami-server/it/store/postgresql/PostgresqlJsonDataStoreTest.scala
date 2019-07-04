package store.postgresql

import cats.effect.{ContextShift, IO}
import env.{DbDomainConfig, DbDomainConfigDetails, PostgresqlConfig}
import org.scalatest.{BeforeAndAfter, BeforeAndAfterAll}
import store.AbstractJsonDataStoreTest
import test.FakeApplicationLifecycle

class PostgresqlJsonDataStoreTest extends AbstractJsonDataStoreTest("Postgresql")  with BeforeAndAfter with BeforeAndAfterAll {

  implicit val cs: ContextShift[IO] = IO.contextShift(scala.concurrent.ExecutionContext.global)

  private val pgConfig = PostgresqlConfig(
    "org.postgresql.Driver",
    "jdbc:postgresql://localhost:5556/izanami",
    "izanami", "izanami", 32, None
  )

  private def client: Option[PostgresqlClient[IO]] = PostgresqlClient.postgresqlClient[IO](
    system, new FakeApplicationLifecycle(), Some(pgConfig)
  )

  override def dataStore(name: String): PostgresqlJsonDataStore[IO] =
    PostgresqlJsonDataStore[IO](client.get, DbDomainConfig(env.Postgresql, DbDomainConfigDetails(name, None), None))


}
