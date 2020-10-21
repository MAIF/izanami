package specs.postgresql.abtesting

import cats.effect.{ContextShift, IO}
import domains.abtesting.events.impl.ExperimentVariantEventPostgresqlService
import domains.abtesting.AbstractExperimentServiceTest
import domains.abtesting.events.ExperimentVariantEventService
import env.{DbDomainConfig, DbDomainConfigDetails, PostgresqlConfig}
import libs.logs.ZLogger
import org.scalatest.{BeforeAndAfter, BeforeAndAfterAll}
import store.postgresql.PostgresqlClient
import zio.{Exit, Reservation}

class ExperimentVariantEventPostgresqlServiceTest
    extends AbstractExperimentServiceTest("Postgresql")
    with BeforeAndAfter
    with BeforeAndAfterAll {
  import zio.NeedsEnv.needsEnv

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

  override def dataStore(name: String): ExperimentVariantEventService.Service = ExperimentVariantEventPostgresqlService(
    client.get,
    DbDomainConfig(env.Postgresql, DbDomainConfigDetails(name, None), None)
  )

  override protected def afterAll(): Unit = {
    super.afterAll()
    runtime.unsafeRun(rPgClient.release(Exit.unit).provideLayer(ZLogger.live))
  }
}
