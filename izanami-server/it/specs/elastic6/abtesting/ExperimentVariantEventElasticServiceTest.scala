package specs.elastic6.abtesting

import controllers.Configs
import domains.abtesting.events.impl.ExperimentVariantEventElastic6Service
import domains.abtesting.AbstractExperimentServiceTest
import domains.abtesting.events.ExperimentVariantEventService
import elastic.es6.api.{Elastic => Elastic6}
import elastic.es7.api.{Elastic => Elastic7}
import env.{DbDomainConfig, DbDomainConfigDetails, ElasticConfig}
import org.scalactic.source.Position
import org.scalatest.{BeforeAndAfter, BeforeAndAfterAll}
import play.api.libs.json.JsValue
import store.elastic.Elastic6Client

class ExperimentVariantEventElasticServiceTest
    extends AbstractExperimentServiceTest("Elastic")
    with BeforeAndAfter
    with BeforeAndAfterAll {

  private val config             = ElasticConfig("localhost", Configs.elastic6HttpPort, "http", 6, None, None, true)
  val elastic: Elastic6[JsValue] = Elastic6Client(config, system)

  override def dataStore(name: String): ExperimentVariantEventService.Service =
    new ExperimentVariantEventElastic6Service(
      elastic,
      config,
      DbDomainConfig(env.Elastic, DbDomainConfigDetails(name, None), None)
    )

  override protected def before(fun: => Any)(implicit pos: Position): Unit = {
    cleanUpElastic
    super.before(fun)
  }

  override protected def afterAll(): Unit = {
    cleanUpElastic
    super.afterAll()
  }

  private def cleanUpElastic = {
    import _root_.elastic.es6.codec.PlayJson._
    elastic.deleteIndex("*").futureValue
  }

}
