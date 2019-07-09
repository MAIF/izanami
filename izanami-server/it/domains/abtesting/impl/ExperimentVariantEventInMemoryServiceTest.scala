package domains.abtesting.impl

import cats.effect.IO
import domains.abtesting.{AbstractExperimentServiceTest, ExperimentVariantEventService}
import domains.events.impl.BasicEventStore
import env.{DbDomainConfig, DbDomainConfigDetails}

class ExperimentVariantEventInMemoryServiceTest extends AbstractExperimentServiceTest("InMemory") {
  override def dataStore(dataStore: String): ExperimentVariantEventService[IO] = ExperimentVariantEventInMemoryService[IO](
    DbDomainConfig(env.InMemory, DbDomainConfigDetails(s"test-events:$dataStore", None), None), new BasicEventStore[IO]
  )
}
