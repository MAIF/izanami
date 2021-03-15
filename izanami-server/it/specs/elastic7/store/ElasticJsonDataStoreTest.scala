package specs.elastic7.store

import controllers.Configs
import elastic.es6.api.{Elastic => Elastic6}
import elastic.es7.api.{Elastic => Elastic7}
import env.{DbDomainConfig, DbDomainConfigDetails, ElasticConfig}
import org.scalactic.source.Position
import org.scalatest.{BeforeAndAfter, BeforeAndAfterAll}
import play.api.libs.json.JsValue
import store.AbstractJsonDataStoreTest
import store.datastore.JsonDataStore
import store.elastic.Elastic6JsonDataStore
import store.elastic._

class ElasticJsonDataStoreTest extends AbstractJsonDataStoreTest("Elastic") with BeforeAndAfter with BeforeAndAfterAll {

  private val config             = ElasticConfig("localhost", Configs.elastic7HttpPort, "http", 7, None, None, true)
  val elastic: Elastic7[JsValue] = Elastic7Client(config, system)

  override def dataStore(dataStore: String): JsonDataStore.Service = new Elastic7JsonDataStore(
    elastic,
    config,
    DbDomainConfig(env.Elastic, DbDomainConfigDetails(dataStore, None), None)
  )

  override protected def before(fun: => Any)(implicit pos: Position): Unit = {
    super.before(fun)
    cleanUpElastic
  }

  private def cleanUpElastic = {
    import _root_.elastic.implicits._
    import _root_.elastic.es7.codec.PlayJson._
    elastic.deleteIndex("*").futureValue
  }

  override protected def afterAll(): Unit = {
    super.afterAll()
    cleanUpElastic
  }
}
