package specs.elastic6.store

import elastic.es6.api.{Elastic => Elastic6}
import elastic.es7.api.{Elastic => Elastic7}
import env.{DbDomainConfig, DbDomainConfigDetails, ElasticConfig}
import org.scalactic.source.Position
import org.scalatest.{BeforeAndAfter, BeforeAndAfterAll}
import play.api.libs.json.JsValue
import store.AbstractJsonDataStoreTest
import _root_.store.elastic.{Elastic6Client, Elastic6JsonDataStore}
import store.datastore.JsonDataStore

class ElasticJsonDataStoreTest extends AbstractJsonDataStoreTest("Elastic") with BeforeAndAfter with BeforeAndAfterAll {

  private val config             = ElasticConfig("localhost", 9210, "http", 6, None, None, true)
  val elastic: Elastic6[JsValue] = Elastic6Client(config, system)

  override def dataStore(dataStore: String): JsonDataStore.Service =
    new Elastic6JsonDataStore(
      elastic,
      config,
      DbDomainConfig(env.Elastic, DbDomainConfigDetails(dataStore, None), None)
    )

  override protected def before(fun: => Any)(implicit pos: Position): Unit = {
    super.before(fun)
    cleanUpElastic
  }

  private def cleanUpElastic = {
    import _root_.elastic.es6.codec.PlayJson._
    elastic.deleteIndex("*").futureValue
  }

  override protected def afterAll(): Unit = {
    super.afterAll()
    cleanUpElastic
  }
}
