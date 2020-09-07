package specs.cassandra.abtesting

import com.datastax.driver.core.{Cluster, Session}
import domains.abtesting.events.impl.ExperimentVariantEventCassandraService
import domains.abtesting.AbstractExperimentServiceTest
import domains.abtesting.events.ExperimentVariantEventService
import env.{CassandraConfig, DbDomainConfig, DbDomainConfigDetails}
import libs.logs.ZLogger
import org.scalatest.BeforeAndAfterAll
import store.cassandra.CassandraClient
import zio.{Exit, Reservation}

class ExperimentVariantEventCassandraServiceTest
    extends AbstractExperimentServiceTest("Cassandra")
    with BeforeAndAfterAll {
  import zio.NeedsEnv.needsEnv

  val cassandraConfig = CassandraConfig(Seq("127.0.0.1:9042"), None, 1, "izanami_test")
  private val rDriver: Reservation[ZLogger, Throwable, Option[(Cluster, Session)]] =
    runtime.unsafeRun(CassandraClient.cassandraClient(Some(cassandraConfig)).reserve.provideLayer(ZLogger.live))

  val Some((_, session)) = runtime.unsafeRun(rDriver.acquire.provideLayer(ZLogger.live))

  override protected def afterAll(): Unit = {
    super.afterAll()
    runtime.unsafeRun(rDriver.release(Exit.unit).provideLayer(ZLogger.live))
  }

  override def dataStore(name: String): ExperimentVariantEventService.Service = ExperimentVariantEventCassandraService(
    session,
    DbDomainConfig(env.Cassandra, DbDomainConfigDetails(name, None), None),
    cassandraConfig
  )
}
