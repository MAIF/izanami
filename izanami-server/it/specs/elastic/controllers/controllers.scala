package specs.elastic.controllers

import org.scalatest.{BeforeAndAfter, BeforeAndAfterAll}
import store.elastic._
import controllers._
import scala.util.Random

class ElasticApikeyControllerSpec
    extends ApikeyControllerSpec("Elastic", Configs.elasticConfiguration)
    with BeforeAndAfterAll {
  override protected def beforeAll(): Unit = Configs.initEs
}
class ElasticConfigControllerSpec
    extends ConfigControllerSpec("Elastic", Configs.elasticConfiguration)
    with BeforeAndAfterAll {
  override protected def beforeAll(): Unit = Configs.initEs
}
class ElasticExperimentControllerSpec
    extends ExperimentControllerSpec("Elastic", Configs.elasticConfiguration)
    with BeforeAndAfterAll {
  override protected def beforeAll(): Unit = Configs.initEs
}
class ElasticFeatureControllerWildcardAccessSpec
    extends FeatureControllerWildcardAccessSpec("Elastic", Configs.elasticConfiguration)
    with BeforeAndAfterAll {
  override protected def beforeAll(): Unit = Configs.initEs
}
class ElasticFeatureControllerSpec
    extends FeatureControllerSpec("Elastic", Configs.elasticConfiguration)
    with BeforeAndAfterAll {
  override protected def beforeAll(): Unit = Configs.initEs
}
class ElasticGlobalScriptControllerSpec
    extends GlobalScriptControllerSpec("Elastic", Configs.elasticConfiguration)
    with BeforeAndAfterAll {
  override protected def beforeAll(): Unit = Configs.initEs
}
class ElasticUserControllerSpec
    extends UserControllerSpec("Elastic", Configs.elasticConfiguration)
    with BeforeAndAfterAll {
  override protected def beforeAll(): Unit = Configs.initEs
}
class ElasticWebhookControllerSpec
    extends WebhookControllerSpec("Elastic", Configs.elasticConfiguration)
    with BeforeAndAfterAll {
  override protected def beforeAll(): Unit = Configs.initEs
}
