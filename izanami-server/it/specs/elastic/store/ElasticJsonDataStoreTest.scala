package specs.elastic.store

import elastic.api.Elastic
import env.{DbDomainConfig, DbDomainConfigDetails, ElasticConfig}
import org.scalactic.source.Position
import org.scalatest.{BeforeAndAfter, BeforeAndAfterAll}
import play.api.libs.json.JsValue
import store.AbstractJsonDataStoreTest
import store.elastic._

class ElasticJsonDataStoreTest extends AbstractJsonDataStoreTest("Elastic") with BeforeAndAfter with BeforeAndAfterAll {


  private val config = ElasticConfig("localhost", 9210, "http", None, None, true)
  val elastic: Elastic[JsValue] = ElasticClient(config, system)

  override def dataStore(dataStore: String): ElasticJsonDataStore = ElasticJsonDataStore(
    elastic, config, DbDomainConfig(env.Elastic, DbDomainConfigDetails(dataStore, None), None)
  )

  override protected def before(fun: => Any)(implicit pos: Position): Unit = {
    super.before(fun)
    cleanUpElastic
  }

  private def cleanUpElastic = {
    import _root_.elastic.implicits._
    import _root_.elastic.codec.PlayJson._
    elastic.deleteIndex("*").futureValue
  }

  override protected def afterAll(): Unit = {
    super.afterAll()
    cleanUpElastic
  }
}
