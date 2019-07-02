package store.elastic

import cats.effect.IO
import elastic.api.Elastic
import env.{DbDomainConfig, DbDomainConfigDetails, ElasticConfig}
import org.scalactic.source.Position
import org.scalatest.{BeforeAndAfter, BeforeAndAfterAll, FunSuite}
import play.api.libs.json.JsValue
import store.{AbstractJsonDataStoreTest, JsonDataStore}

class ElasticJsonDataStoreTest extends AbstractJsonDataStoreTest("Elastic")  with BeforeAndAfter with BeforeAndAfterAll {


  private val config = ElasticConfig("localhost", 9210, "http", None, None, true)
  val elastic: Elastic[JsValue] = ElasticClient(config, system)

  override def dataStore(dataStore: String): JsonDataStore[IO] = ElasticJsonDataStore[IO](
    elastic, config, DbDomainConfig(env.Elastic, DbDomainConfigDetails(dataStore, None), None)
  )

  override protected def before(fun: => Any)(implicit pos: Position): Unit = {
    super.before(fun)
    import _root_.elastic.implicits._
    import _root_.elastic.codec.PlayJson._
    elastic.deleteIndex("*").futureValue
  }
}
