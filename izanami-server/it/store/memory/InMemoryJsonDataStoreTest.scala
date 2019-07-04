package store.memory

import cats.effect.IO
import env.{DbDomainConfig, DbDomainConfigDetails, InMemory}
import store.{AbstractJsonDataStoreTest, JsonDataStore}

class InMemoryJsonDataStoreTest extends AbstractJsonDataStoreTest("InMemory") {

  override def dataStore(name: String): JsonDataStore[IO] = InMemoryJsonDataStore[IO](
    DbDomainConfig(InMemory, DbDomainConfigDetails(name, None), None)
  )
}
