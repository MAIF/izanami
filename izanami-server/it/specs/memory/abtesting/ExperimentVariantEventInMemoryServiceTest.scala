package specs.memory.abtesting

import domains.abtesting.events.impl.ExperimentVariantEventInMemoryService
import domains.abtesting.AbstractExperimentServiceTest
import domains.abtesting.events.ExperimentVariantEventService
import env.{DbDomainConfig, DbDomainConfigDetails}

class ExperimentVariantEventInMemoryServiceTest extends AbstractExperimentServiceTest("InMemory") {
  override def dataStore(dataStore: String): ExperimentVariantEventService.Service =
    ExperimentVariantEventInMemoryService(
      DbDomainConfig(env.InMemory, DbDomainConfigDetails(s"test-events:$dataStore", None), None)
    )
}
