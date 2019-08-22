package specs.cassandra.store

import env.{CassandraConfig, DbDomainConfig, DbDomainConfigDetails}
import org.scalatest.{BeforeAndAfter, BeforeAndAfterAll}
import store.AbstractJsonDataStoreTest
import store.cassandra.CassandraClient
import store.cassandra.CassandraJsonDataStore



class CassandraJsonDataStoreTest extends AbstractJsonDataStoreTest("Cassandra")  with BeforeAndAfter with BeforeAndAfterAll {

  val cassandraConfig = CassandraConfig(Seq("127.0.0.1:9042"), None, 1, "izanami_test")
  val Some((_, session)) = CassandraClient.cassandraClient(Some(cassandraConfig))

  override def dataStore(name: String): CassandraJsonDataStore =
    CassandraJsonDataStore(
      session,
      cassandraConfig,
      DbDomainConfig(env.Cassandra, DbDomainConfigDetails(name, None), None)
    )


}
