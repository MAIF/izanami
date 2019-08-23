package specs.dynamo.controllers

import org.scalatest.{BeforeAndAfter, BeforeAndAfterAll}
import store.dynamo._
import controllers._
import scala.util.Random


class DynamoApikeyControllerSpec extends ApikeyControllerSpec("Dynamo", Configs.dynamoDbConfig(Random.nextInt(1000)))
class DynamoExperimentControllerSpec extends ExperimentControllerSpec("Dynamo", Configs.dynamoDbConfig(Random.nextInt(1000)))
class DynamoConfigControllerSpec extends ConfigControllerSpec("Dynamo", Configs.dynamoDbConfig(Random.nextInt(1000)))
class DynamoFeatureControllerWildcardAccessSpec extends FeatureControllerWildcardAccessSpec("Dynamo", Configs.dynamoDbConfig(Random.nextInt(1000)))
class DynamoFeatureControllerSpec extends FeatureControllerSpec("Dynamo", Configs.dynamoDbConfig(Random.nextInt(1000)))
class DynamoGlobalScriptControllerSpec extends GlobalScriptControllerSpec("Dynamo", Configs.dynamoDbConfig(Random.nextInt(1000)))
class DynamoUserControllerSpec extends UserControllerSpec("Dynamo", Configs.dynamoDbConfig(Random.nextInt(1000)))
class DynamoWebhookControllerSpec extends WebhookControllerSpec("Dynamo", Configs.dynamoDbConfig(Random.nextInt(1000)))

