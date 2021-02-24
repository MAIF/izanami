package specs.elastic6.controllers

import org.scalatest.{BeforeAndAfter, BeforeAndAfterAll}
import store.elastic._
import controllers._
import scala.util.Random

class ElasticApikeyControllerSpec
    extends ApikeyControllerSpec("Elastic", Configs.elastic6Configuration)
    with BeforeAndAfterAll {
  override protected def beforeAll(): Unit = Configs.initEs6
}
class ElasticConfigControllerSpec
    extends ConfigControllerSpec("Elastic", Configs.elastic6Configuration)
    with BeforeAndAfterAll {
  override protected def beforeAll(): Unit = Configs.initEs6
}
class ElasticExperimentControllerSpec
    extends ExperimentControllerSpec("Elastic", Configs.elastic6Configuration)
    with BeforeAndAfterAll {
  override protected def beforeAll(): Unit = Configs.initEs6
}
class ElasticFeatureControllerWildcardAccessSpec
    extends FeatureControllerWildcardAccessSpec("Elastic", Configs.elastic6Configuration)
    with BeforeAndAfterAll {
  override protected def beforeAll(): Unit = Configs.initEs6
}
class ElasticFeatureControllerSpec
    extends FeatureControllerSpec("Elastic", Configs.elastic6Configuration)
    with BeforeAndAfterAll {
  override protected def beforeAll(): Unit = Configs.initEs6
}
class ElasticGlobalScriptControllerSpec
    extends GlobalScriptControllerSpec("Elastic", Configs.elastic6Configuration)
    with BeforeAndAfterAll {
  override protected def beforeAll(): Unit = Configs.initEs6
}
class ElasticUserControllerSpec
    extends UserControllerSpec("Elastic", Configs.elastic6Configuration)
    with BeforeAndAfterAll {
  override protected def beforeAll(): Unit = Configs.initEs6
}
class ElasticWebhookControllerSpec
    extends WebhookControllerSpec("Elastic", Configs.elastic6Configuration)
    with BeforeAndAfterAll {
  override protected def beforeAll(): Unit = Configs.initEs6
}
