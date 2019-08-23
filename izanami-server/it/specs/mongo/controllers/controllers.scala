package specs.mongo.controllers

import org.scalatest.{BeforeAndAfter, BeforeAndAfterAll}
import store.mongo._
import controllers._
import scala.util.Random


class MongoApikeyControllerSpec extends ApikeyControllerSpec("Mongo", Configs.mongoConfig("apikey"))
class MongoConfigControllerSpec extends ConfigControllerSpec("Mongo", Configs.mongoConfig("config"))
class MongoExperimentControllerSpec extends ExperimentControllerSpec("Mongo", Configs.mongoConfig("experiment"))
class MongoFeatureControllerWildcardAccessSpec extends FeatureControllerWildcardAccessSpec("Mongo", Configs.mongoConfig("featureaccess"))
class MongoFeatureControllerSpec extends FeatureControllerSpec("Mongo", Configs.mongoConfig("feature"))
class MongoGlobalScriptControllerSpec extends GlobalScriptControllerSpec("Mongo", Configs.mongoConfig("globalscript"))
class MongoUserControllerSpec extends UserControllerSpec("Mongo", Configs.mongoConfig("user"))
class MongoWebhookControllerSpec extends WebhookControllerSpec("Mongo", Configs.mongoConfig("webhook"))
