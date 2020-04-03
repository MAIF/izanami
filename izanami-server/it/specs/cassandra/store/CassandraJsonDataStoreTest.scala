package specs.cassandra.store

import com.datastax.driver.core.{Cluster, Session}
import env.{CassandraConfig, DbDomainConfig, DbDomainConfigDetails}
import libs.logs.ZLogger
import org.scalatest.{BeforeAndAfter, BeforeAndAfterAll}
import store.AbstractJsonDataStoreTest
import store.cassandra.CassandraClient
import store.cassandra.CassandraJsonDataStore
import zio.{Exit, Reservation}

class CassandraJsonDataStoreTest
    extends AbstractJsonDataStoreTest("Cassandra")
    with BeforeAndAfter
    with BeforeAndAfterAll {

  val cassandraConfig = CassandraConfig(Seq("127.0.0.1:9042"), None, 1, "izanami_test")

  private val rDriver: Reservation[ZLogger, Throwable, Option[(Cluster, Session)]] =
    runtime.unsafeRun(CassandraClient.cassandraClient(Some(cassandraConfig)).reserve.provideLayer(ZLogger.live))

  val Some((_, session)) = runtime.unsafeRun(rDriver.acquire.provideLayer(ZLogger.live))

  override protected def afterAll(): Unit = {
    super.afterAll()
    runtime.unsafeRun(rDriver.release(Exit.unit).provideLayer(ZLogger.live))
  }

  override def dataStore(name: String): CassandraJsonDataStore =
    CassandraJsonDataStore(
      session,
      cassandraConfig,
      DbDomainConfig(env.Cassandra, DbDomainConfigDetails(name, None), None)
    )

}
