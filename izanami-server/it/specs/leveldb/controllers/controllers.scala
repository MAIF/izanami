package specs.leveldb.controllers

import org.scalatest.{BeforeAndAfter, BeforeAndAfterAll}
import store.leveldb._
import controllers._
import scala.util.Random


class LevelDbApikeyControllerSpec extends ApikeyControllerSpec("LevelDb", Configs.levelDBConfiguration(s"apikey-${Random.nextInt(1000)}")) with BeforeAndAfterAll {
    override protected def afterAll(): Unit = Configs.cleanLevelDb
  }
  class LevelDbConfigControllerSpec extends ConfigControllerSpec("LevelDb", Configs.levelDBConfiguration(s"config-${Random.nextInt(1000)}")) with BeforeAndAfterAll {
    override protected def afterAll(): Unit = Configs.cleanLevelDb
  }
  class LevelDbExperimentControllerSpec extends ExperimentControllerSpec("LevelDb", Configs.levelDBConfiguration(s"experiment-${Random.nextInt(1000)}"), parallelism = 1) with BeforeAndAfterAll {
    override protected def afterAll(): Unit = Configs.cleanLevelDb
  }
  class LevelDbFeatureControllerWildcardAccessSpec extends FeatureControllerWildcardAccessSpec("LevelDb", Configs.levelDBConfiguration(s"featureaccess-${Random.nextInt(1000)}")) with BeforeAndAfterAll {
    override protected def afterAll(): Unit = Configs.cleanLevelDb
  }
  class LevelDbFeatureControllerSpec extends FeatureControllerSpec("LevelDb", Configs.levelDBConfiguration(s"feature-${Random.nextInt(1000)}")) with BeforeAndAfterAll {
    override protected def afterAll(): Unit = Configs.cleanLevelDb
  }
  class LevelDbGlobalScriptControllerSpec extends GlobalScriptControllerSpec("LevelDb", Configs.levelDBConfiguration(s"globalscript-${Random.nextInt(1000)}")) with BeforeAndAfterAll {
    override protected def afterAll(): Unit = Configs.cleanLevelDb
  }
  class LevelDbUserControllerSpec extends UserControllerSpec("LevelDb", Configs.levelDBConfiguration(s"user-${Random.nextInt(1000)}")) with BeforeAndAfterAll {
    override protected def afterAll(): Unit = Configs.cleanLevelDb
  }
  class LevelDbWebhookControllerSpec extends WebhookControllerSpec("LevelDb", Configs.levelDBConfiguration(s"webhook-${Random.nextInt(1000)}")) with BeforeAndAfterAll {
    override protected def afterAll(): Unit = Configs.cleanLevelDb
  }
  