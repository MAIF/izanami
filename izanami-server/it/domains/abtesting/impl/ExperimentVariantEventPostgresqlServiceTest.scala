package domains.abtesting.impl

import cats.effect.{ContextShift, IO}
import domains.abtesting.{AbstractExperimentServiceTest, ExperimentVariantEventService}
import domains.events.impl.BasicEventStore
import env.{DbDomainConfig, DbDomainConfigDetails, PostgresqlConfig}
import org.scalatest.{BeforeAndAfter, BeforeAndAfterAll, FunSuite}
import store.postgresql.PostgresqlClient
import test.FakeApplicationLifecycle

class ExperimentVariantEventPostgresqlServiceTest extends AbstractExperimentServiceTest("Postgresql") with BeforeAndAfter with BeforeAndAfterAll {


  implicit val cs: ContextShift[IO] = IO.contextShift(scala.concurrent.ExecutionContext.global)

  private val pgConfig = PostgresqlConfig(
    "org.postgresql.Driver",
    "jdbc:postgresql://localhost:5556/izanami",
    "izanami", "izanami", 32, None
  )

  private def client: Option[PostgresqlClient[IO]] = PostgresqlClient.postgresqlClient[IO](
    system, new FakeApplicationLifecycle(), Some(pgConfig)
  )

  override def dataStore(name: String): ExperimentVariantEventService[IO] = ExperimentVariantEventPostgresqlService[IO](
    pgConfig, client.get, DbDomainConfig(env.Postgresql, DbDomainConfigDetails(name, None), None), new BasicEventStore[IO]
  )

}
