package specs.memory.controllers

import org.scalatest.{BeforeAndAfter, BeforeAndAfterAll}
import store.memory._
import controllers._
import scala.util.Random


class InMemoryApikeyControllerSpec extends ApikeyControllerSpec("InMemory", Configs.inMemoryConfiguration)
class InMemoryConfigControllerSpec extends ConfigControllerSpec("InMemory", Configs.inMemoryConfiguration)
class InMemoryExperimentControllerSpec extends ExperimentControllerSpec("InMemory", Configs.inMemoryConfiguration)
class InMemoryFeatureControllerWildcardAccessSpec extends FeatureControllerWildcardAccessSpec("InMemory", Configs.inMemoryConfiguration)
class InMemoryFeatureControllerSpec extends FeatureControllerSpec("InMemory", Configs.inMemoryConfiguration)
class InMemoryGlobalScriptControllerSpec extends GlobalScriptControllerSpec("InMemory", Configs.inMemoryConfiguration)
class InMemoryUserControllerSpec extends UserControllerSpec("InMemory", Configs.inMemoryConfiguration)
class InMemoryWebhookControllerSpec extends WebhookControllerSpec("InMemory", Configs.inMemoryConfiguration)

