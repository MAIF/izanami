package specs.memory.abtesting

import domains.abtesting.events.impl.ExperimentVariantEventInMemoryService
import domains.abtesting.{AbstractExperimentServiceTest, ExperimentVariantEventService}
import env.{DbDomainConfig, DbDomainConfigDetails}

class ExperimentVariantEventInMemoryServiceTest extends AbstractExperimentServiceTest("InMemory") {
  override def dataStore(dataStore: String): ExperimentVariantEventService = ExperimentVariantEventInMemoryService(
    DbDomainConfig(env.InMemory, DbDomainConfigDetails(s"test-events:$dataStore", None), None)
  )
}
