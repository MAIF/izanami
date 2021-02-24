package specs.elastic7.abtesting

import controllers.Configs
import domains.abtesting.events.impl.ExperimentVariantEventElastic7Service
import domains.abtesting.AbstractExperimentServiceTest
import domains.abtesting.events.ExperimentVariantEventService
import elastic.es7.api.{Elastic => Elastic7}
import env.{DbDomainConfig, DbDomainConfigDetails, ElasticConfig}
import org.scalactic.source.Position
import org.scalatest.{BeforeAndAfter, BeforeAndAfterAll}
import play.api.libs.json.JsValue
import store.elastic.Elastic7Client

class ExperimentVariantEventElasticServiceTest
    extends AbstractExperimentServiceTest("Elastic")
    with BeforeAndAfter
    with BeforeAndAfterAll {

  private val config             = ElasticConfig("localhost", Configs.elastic7HttpPort, "http", 7, None, None, true)
  val elastic: Elastic7[JsValue] = Elastic7Client(config, system)

  override def dataStore(name: String): ExperimentVariantEventService.Service =
    new ExperimentVariantEventElastic7Service(
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
    import _root_.elastic.es7.codec.PlayJson._
    elastic.deleteIndex("*").futureValue
  }

}
