package multi

import java.io.File

import akka.testkit.SocketUtil._
import com.typesafe.config.ConfigFactory
import controllers.{UserControllerSpec, _}
import elastic.client.ElasticClient
import org.iq80.leveldb.util.FileUtils
import org.scalatest._
import play.api.Configuration
import play.api.libs.json.JsValue
import redis.embedded.RedisServer

import scala.concurrent.Await
import scala.concurrent.ExecutionContext.Implicits.global
import scala.concurrent.duration.DurationLong
import scala.util.Random

object Configs {

  val redisPort: Int = temporaryServerAddress("localhost", udp = false).getPort

  val cassandraPort: Int = 9042

  val elasticHttpPort: Int = 9210
  val elasticTcpPort: Int = 9300

  val elasticConfiguration: Configuration = Configuration(
    ConfigFactory.parseString(s"""
      |izanami.db.default="Elastic"
      |
      |izanami.mode= "test"
      |izanami.config.db.type=$${izanami.db.default}
      |izanami.features.db.type=$${izanami.db.default}
      |izanami.globalScript.db.type=$${izanami.db.default}
      |izanami.experiment.db.type=$${izanami.db.default}
      |izanami.variantBinding.db.type=$${izanami.db.default}
      |izanami.experimentEvent.db.type=$${izanami.db.default}
      |izanami.webhook.db.type=$${izanami.db.default}
      |izanami.user.db.type=$${izanami.db.default}
      |izanami.apikey.db.type=$${izanami.db.default}
      |
      |izanami {
      |  db {
      |    elastic {
      |      host = "localhost"
      |      port = $elasticHttpPort
      |      automaticRefresh = true
      |    }
      |  }
      |}
      """.stripMargin).resolve()
  )

  val redisConfiguration: Configuration = Configuration(
    ConfigFactory.parseString(s"""
         |izanami.db.default="Redis"
         |
         |izanami.mode= "test"
         |izanami.config.db.type=$${izanami.db.default}
         |izanami.features.db.type=$${izanami.db.default}
         |izanami.globalScript.db.type=$${izanami.db.default}
         |izanami.experiment.db.type=$${izanami.db.default}
         |izanami.variantBinding.db.type=$${izanami.db.default}
         |izanami.experimentEvent.db.type=$${izanami.db.default}
         |izanami.webhook.db.type=$${izanami.db.default}
         |izanami.user.db.type=$${izanami.db.default}
         |izanami.apikey.db.type=$${izanami.db.default}
         |
         |izanami {
         |  db {
         |    redis {
         |      host = "localhost"
         |      port = $redisPort
         |      windowSize = 99
         |      transaction = false
         |      fastLookupTTL = 60000
         |    }
         |  }
         |}
      """.stripMargin).resolve()
  )

  def cassandraConfiguration(keyspace: String): Configuration = Configuration(
    ConfigFactory.parseString(s"""
         |izanami.db.default="Cassandra"
         |
         |izanami.mode= "test"
         |izanami.config.db.type=$${izanami.db.default}
         |izanami.features.db.type=$${izanami.db.default}
         |izanami.globalScript.db.type=$${izanami.db.default}
         |izanami.experiment.db.type=$${izanami.db.default}
         |izanami.variantBinding.db.type=$${izanami.db.default}
         |izanami.experimentEvent.db.type=$${izanami.db.default}
         |izanami.webhook.db.type=$${izanami.db.default}
         |izanami.user.db.type=$${izanami.db.default}
         |izanami.apikey.db.type=$${izanami.db.default}
         |
         |izanami {
         |  db {
         |    cassandra {
         |      addresses = ["127.0.0.1:$cassandraPort"]
         |      replicationFactor = 1
         |      keyspace: "$keyspace"
         |    }
         |  }
         |}
      """.stripMargin).resolve()
  )

  def levelDBConfiguration(folder: String): Configuration = Configuration(
    ConfigFactory.parseString(s"""
         |izanami.db.default="LevelDB"
         |izanami.mode= "test"
        |izanami.config.db.type=$${izanami.db.default}
         |izanami.features.db.type=$${izanami.db.default}
         |izanami.globalScript.db.type=$${izanami.db.default}
         |izanami.experiment.db.type=$${izanami.db.default}
         |izanami.variantBinding.db.type=$${izanami.db.default}
         |izanami.experimentEvent.db.type=$${izanami.db.default}
         |izanami.webhook.db.type=$${izanami.db.default}
         |izanami.user.db.type=$${izanami.db.default}
         |izanami.apikey.db.type=$${izanami.db.default}
         |
        |izanami {
         |  db {
         |    leveldb {
         |      parentPath = "./target/leveldb/$folder"
         |    }
         |  }
         |}
      """.stripMargin).resolve()
  )

  val inMemoryConfiguration: Configuration = Configuration(
    ConfigFactory
      .parseString("""
        |izanami.db.default="InMemory"
        |
        |izanami.mode= "test"
        |izanami.config.db.type=${izanami.db.default}
        |izanami.features.db.type=${izanami.db.default}
        |izanami.globalScript.db.type=${izanami.db.default}
        |izanami.experiment.db.type=${izanami.db.default}
        |izanami.variantBinding.db.type=${izanami.db.default}
        |izanami.experimentEvent.db.type=${izanami.db.default}
        |izanami.webhook.db.type=${izanami.db.default}
        |izanami.user.db.type=${izanami.db.default}
        |izanami.apikey.db.type=${izanami.db.default}
      """.stripMargin)
      .resolve()
  )

  def folderConfig = s"data-${Random.nextInt(1000)}"

}

class InMemoryTests
    extends Suites(
      new ConfigControllerSpec(Configs.inMemoryConfiguration),
      new ExperimentControllerSpec(Configs.inMemoryConfiguration),
      new FeatureControllerSpec(Configs.inMemoryConfiguration),
      new GlobalScriptControllerSpec(Configs.inMemoryConfiguration),
      new WebhookControllerSpec(Configs.inMemoryConfiguration),
      new UserControllerSpec(Configs.inMemoryConfiguration),
      new ApikeyControllerSpec(Configs.inMemoryConfiguration)
    )
    with BeforeAndAfterAll {}

class RedisTests
    extends Suites(
      new ConfigControllerSpec(Configs.redisConfiguration),
      new ExperimentControllerSpec(Configs.redisConfiguration),
      new FeatureControllerSpec(Configs.redisConfiguration),
      new GlobalScriptControllerSpec(Configs.redisConfiguration),
      new WebhookControllerSpec(Configs.redisConfiguration),
      new UserControllerSpec(Configs.redisConfiguration),
      new ApikeyControllerSpec(Configs.redisConfiguration)
    )
    with BeforeAndAfterAll {

  val redisEmbeddedServer: RedisServer = new RedisServer(Configs.redisPort)

  override protected def beforeAll(): Unit =
    redisEmbeddedServer.start()

  override protected def afterAll(): Unit =
    redisEmbeddedServer.stop()
}

class ElasticTests
    extends Suites(
      new ConfigControllerSpec(Configs.elasticConfiguration),
      new ExperimentControllerSpec(Configs.elasticConfiguration),
      new FeatureControllerSpec(Configs.elasticConfiguration),
      new GlobalScriptControllerSpec(Configs.elasticConfiguration),
      new WebhookControllerSpec(Configs.elasticConfiguration),
      new UserControllerSpec(Configs.elasticConfiguration),
      new ApikeyControllerSpec(Configs.elasticConfiguration)
    )
    with BeforeAndAfterAll {

  override protected def beforeAll(): Unit = {
    import elastic.codec.PlayJson._
    val client = ElasticClient[JsValue](port = Configs.elasticHttpPort)
    println("Cleaning ES indices")
    Await.result(client.deleteIndex("izanami_*"), 5.seconds)
  }

  override protected def afterAll(): Unit = ()
}

class CassandraTests
    extends Suites(
      new ConfigControllerSpec(Configs.cassandraConfiguration("config")),
      new ExperimentControllerSpec(
        Configs.cassandraConfiguration("experiment")),
      new FeatureControllerSpec(Configs.cassandraConfiguration("features")),
      new GlobalScriptControllerSpec(Configs.cassandraConfiguration("script")),
      new WebhookControllerSpec(Configs.cassandraConfiguration("webhook")),
      new UserControllerSpec(Configs.cassandraConfiguration("user")),
      new ApikeyControllerSpec(Configs.cassandraConfiguration("apikey"))
    )

class LevelDBTests
    extends Suites(
      new ConfigControllerSpec(
        Configs.levelDBConfiguration(Configs.folderConfig)),
      new ExperimentControllerSpec(
        Configs.levelDBConfiguration(Configs.folderConfig)),
      new FeatureControllerSpec(
        Configs.levelDBConfiguration(Configs.folderConfig)),
      new GlobalScriptControllerSpec(
        Configs.levelDBConfiguration(Configs.folderConfig)),
      new WebhookControllerSpec(
        Configs.levelDBConfiguration(Configs.folderConfig)),
      new UserControllerSpec(
        Configs.levelDBConfiguration(Configs.folderConfig)),
      new ApikeyControllerSpec(
        Configs.levelDBConfiguration(Configs.folderConfig))
    )
    with BeforeAndAfterAll {

  override protected def afterAll(): Unit =
    FileUtils.deleteRecursively(new File("./target/leveldb"))
}
