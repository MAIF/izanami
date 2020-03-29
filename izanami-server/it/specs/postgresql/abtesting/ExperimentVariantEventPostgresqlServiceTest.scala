package specs.postgresql.abtesting

import cats.effect.{ContextShift, IO}
import domains.abtesting.events.impl.ExperimentVariantEventPostgresqlService
import domains.abtesting.AbstractExperimentServiceTest
import domains.abtesting.events.ExperimentVariantEventService
import env.{DbDomainConfig, DbDomainConfigDetails, PostgresqlConfig}
import org.scalatest.{BeforeAndAfter, BeforeAndAfterAll}
import store.postgresql.PostgresqlClient
import test.FakeApplicationLifecycle
import zio.Task

class ExperimentVariantEventPostgresqlServiceTest
    extends AbstractExperimentServiceTest("Postgresql")
    with BeforeAndAfter
    with BeforeAndAfterAll {

  implicit val cs: ContextShift[IO] = IO.contextShift(scala.concurrent.ExecutionContext.global)
  import zio.interop.catz._

  private val pgConfig = PostgresqlConfig(
    "org.postgresql.Driver",
    "jdbc:postgresql://localhost:5555/izanami",
    "izanami",
    "izanami",
    32,
    None
  )

  private def client: Option[PostgresqlClient] = PostgresqlClient.postgresqlClient(
    system,
    new FakeApplicationLifecycle(),
    Some(pgConfig)
  )

  override def dataStore(name: String): ExperimentVariantEventService.Service = ExperimentVariantEventPostgresqlService(
    client.get,
    DbDomainConfig(env.Postgresql, DbDomainConfigDetails(name, None), None)
  )

}
