package store.memory

import env.{DbDomainConfig, DbDomainConfigDetails, InMemory}
import store.AbstractJsonDataStoreTest

class InMemoryJsonDataStoreTest extends AbstractJsonDataStoreTest("InMemory") {

  override def dataStore(name: String): InMemoryJsonDataStore = InMemoryJsonDataStore(
    DbDomainConfig(InMemory, DbDomainConfigDetails(name, None), None)
  )
}
