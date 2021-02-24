package specs.elastic7.controllers

import org.scalatest.BeforeAndAfterAll
import controllers._

class ElasticApikeyControllerSpec
    extends ApikeyControllerSpec("Elastic", Configs.elastic7Configuration)
    with BeforeAndAfterAll {
  override protected def beforeAll(): Unit = Configs.initEs7
}
class ElasticConfigControllerSpec
    extends ConfigControllerSpec("Elastic", Configs.elastic7Configuration)
    with BeforeAndAfterAll {
  override protected def beforeAll(): Unit = Configs.initEs7
}
class ElasticExperimentControllerSpec
    extends ExperimentControllerSpec("Elastic", Configs.elastic7Configuration)
    with BeforeAndAfterAll {
  override protected def beforeAll(): Unit = Configs.initEs7
}
class ElasticFeatureControllerWildcardAccessSpec
    extends FeatureControllerWildcardAccessSpec("Elastic", Configs.elastic7Configuration)
    with BeforeAndAfterAll {
  override protected def beforeAll(): Unit = Configs.initEs7
}
class ElasticFeatureControllerSpec
    extends FeatureControllerSpec("Elastic", Configs.elastic7Configuration)
    with BeforeAndAfterAll {
  override protected def beforeAll(): Unit = Configs.initEs7
}
class ElasticGlobalScriptControllerSpec
    extends GlobalScriptControllerSpec("Elastic", Configs.elastic7Configuration)
    with BeforeAndAfterAll {
  override protected def beforeAll(): Unit = Configs.initEs7
}
class ElasticUserControllerSpec
    extends UserControllerSpec("Elastic", Configs.elastic7Configuration)
    with BeforeAndAfterAll {
  override protected def beforeAll(): Unit = Configs.initEs7
}
class ElasticWebhookControllerSpec
    extends WebhookControllerSpec("Elastic", Configs.elastic7Configuration)
    with BeforeAndAfterAll {
  override protected def beforeAll(): Unit = Configs.initEs7
}
