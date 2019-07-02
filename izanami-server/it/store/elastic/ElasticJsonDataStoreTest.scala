package store.elastic

import cats.effect.IO
import env.{DbDomainConfig, DbDomainConfigDetails, ElasticConfig}
import org.scalatest.{BeforeAndAfter, BeforeAndAfterAll, FunSuite}
import store.{AbstractJsonDataStoreTest, JsonDataStore}

class ElasticJsonDataStoreTest extends AbstractJsonDataStoreTest("Elastic")  with BeforeAndAfter with BeforeAndAfterAll {


  private val config = ElasticConfig("localhost", 9210, "http", None, None, true)
  val elastic = ElasticClient(config, system)

  override def dataStore(dataStore: String): JsonDataStore[IO] = ElasticJsonDataStore[IO](
    elastic, config, DbDomainConfig(env.Elastic, DbDomainConfigDetails(dataStore, None), None)
  )


}
