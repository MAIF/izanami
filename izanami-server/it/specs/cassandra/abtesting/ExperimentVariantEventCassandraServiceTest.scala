package specs.cassandra.abtesting

import domains.abtesting.events.impl.ExperimentVariantEventCassandraService
import domains.abtesting.{AbstractExperimentServiceTest, ExperimentVariantEventService}
import env.{CassandraConfig, DbDomainConfig, DbDomainConfigDetails}
import store.cassandra.CassandraClient

class ExperimentVariantEventCassandraServiceTest extends AbstractExperimentServiceTest("Cassandra") {

  val cassandraConfig    = CassandraConfig(Seq("127.0.0.1:9042"), None, 1, "izanami_test")
  val Some((_, session)) = CassandraClient.cassandraClient(Some(cassandraConfig))

  override def dataStore(name: String): ExperimentVariantEventService = ExperimentVariantEventCassandraService(
    session,
    DbDomainConfig(env.Cassandra, DbDomainConfigDetails(name, None), None),
    cassandraConfig
  )
}
