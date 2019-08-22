package specs.memory.abtesting

import domains.abtesting.{AbstractExperimentServiceTest, ExperimentVariantEventService}
import env.{DbDomainConfig, DbDomainConfigDetails}
import domains.abtesting.impl.ExperimentVariantEventInMemoryService

class ExperimentVariantEventInMemoryServiceTest extends AbstractExperimentServiceTest("InMemory") {
  override def dataStore(dataStore: String): ExperimentVariantEventService = ExperimentVariantEventInMemoryService(
    DbDomainConfig(env.InMemory, DbDomainConfigDetails(s"test-events:$dataStore", None), None)
  )
}
