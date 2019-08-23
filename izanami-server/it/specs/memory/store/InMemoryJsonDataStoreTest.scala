package specs.memory.store

import env.{DbDomainConfig, DbDomainConfigDetails, InMemory}
import store.AbstractJsonDataStoreTest
import store.memory.InMemoryJsonDataStore

class InMemoryJsonDataStoreTest extends AbstractJsonDataStoreTest("InMemory") {

  override def dataStore(name: String): InMemoryJsonDataStore = InMemoryJsonDataStore(
    DbDomainConfig(InMemory, DbDomainConfigDetails(name, None), None)
  )
}
