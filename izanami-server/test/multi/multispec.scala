package multi

import java.io.File

import akka.testkit.SocketUtil._
import com.typesafe.config.ConfigFactory
import controllers.{UserControllerSpec, _}
import elastic.client.ElasticClient
import libs.IdGenerator
import org.iq80.leveldb.util.FileUtils
import org.scalatest._
import play.api.Configuration
import play.api.libs.json.JsValue
import redis.embedded.RedisServer

import scala.concurrent.Await
import scala.concurrent.ExecutionContext.Implicits.global
import scala.concurrent.duration.DurationLong
import scala.util.{Random, Try}
import  Configs.idGenerator

object Configs {

  val idGenerator = IdGenerator(0L)

  val redisPort: Int = temporaryServerAddress("localhost", udp = false).getPort

  val cassandraPort: Int = 9042

  val elasticHttpPort: Int = 9210
  val elasticTcpPort: Int  = 9300

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
      |izanami.patch.db.type=$${izanami.db.default}
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
         |izanami.patch.db.type=$${izanami.db.default}
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
         |izanami.patch.db.type=$${izanami.db.default}
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
         |izanami.patch.db.type=$${izanami.db.default}
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
        |izanami.patch.db.type=${izanami.db.default}
        |
      """.stripMargin)
      .resolve()
  )

  def folderConfig = s"data-${Random.nextInt(1000)}"

  def mongoConfig(test: String): Configuration = Configuration(
    ConfigFactory
      .parseString(s"""
       |izanami.db.default="Mongo"
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
       |izanami.patch.db.type=$${izanami.db.default}
       |
       |izanami {
       |  db {
       |    mongo {
       |      url = "mongodb://localhost:27017/$test-${Random.nextInt(1000)}"
       |    }
       |  }
       |}
      """.stripMargin)
      .resolve()
  )

}

class InMemoryTests
    extends Suites(
      new ConfigControllerSpec("InMemory", Configs.inMemoryConfiguration),
      new ExperimentControllerSpec("InMemory", Configs.inMemoryConfiguration),
      new FeatureControllerSpec("InMemory", Configs.inMemoryConfiguration),
      new GlobalScriptControllerSpec("InMemory", Configs.inMemoryConfiguration),
      new WebhookControllerSpec("InMemory", Configs.inMemoryConfiguration),
      new UserControllerSpec("InMemory", Configs.inMemoryConfiguration),
      new ApikeyControllerSpec("InMemory", Configs.inMemoryConfiguration)
    )
    with BeforeAndAfterAll {}

class RedisTests
    extends Suites(
      new ConfigControllerSpec("Redis", Configs.redisConfiguration),
      new ExperimentControllerSpec("Redis", Configs.redisConfiguration),
      new FeatureControllerSpec("Redis", Configs.redisConfiguration),
      new GlobalScriptControllerSpec("Redis", Configs.redisConfiguration),
      new WebhookControllerSpec("Redis", Configs.redisConfiguration),
      new UserControllerSpec("Redis", Configs.redisConfiguration),
      new ApikeyControllerSpec("Redis", Configs.redisConfiguration)
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
      new ConfigControllerSpec("Elastic", Configs.elasticConfiguration),
      new ExperimentControllerSpec("Elastic", Configs.elasticConfiguration, strict = false),
      new FeatureControllerSpec("Elastic", Configs.elasticConfiguration),
      new GlobalScriptControllerSpec("Elastic", Configs.elasticConfiguration),
      new WebhookControllerSpec("Elastic", Configs.elasticConfiguration),
      new UserControllerSpec("Elastic", Configs.elasticConfiguration),
      new ApikeyControllerSpec("Elastic", Configs.elasticConfiguration)
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
      new ConfigControllerSpec("Cassandra", Configs.cassandraConfiguration(s"config${idGenerator.nextId()}")),
      new ExperimentControllerSpec("Cassandra",
                                   Configs.cassandraConfiguration(s"experiment${idGenerator.nextId()}")),
      new FeatureControllerSpec("Cassandra", Configs.cassandraConfiguration(s"features${idGenerator.nextId()}")),
      new GlobalScriptControllerSpec("Cassandra",
                                     Configs.cassandraConfiguration(s"script${idGenerator.nextId()}")),
      new WebhookControllerSpec("Cassandra", Configs.cassandraConfiguration(s"webhook${idGenerator.nextId()}")),
      new UserControllerSpec("Cassandra", Configs.cassandraConfiguration(s"user${idGenerator.nextId()}")),
      new ApikeyControllerSpec("Cassandra", Configs.cassandraConfiguration(s"apikey${idGenerator.nextId()}"))
    )

class LevelDBTests
    extends Suites(
      new ConfigControllerSpec("LevelDb", Configs.levelDBConfiguration(Configs.folderConfig)),
      new ExperimentControllerSpec("LevelDb", Configs.levelDBConfiguration(Configs.folderConfig)),
      new FeatureControllerSpec("LevelDb", Configs.levelDBConfiguration(Configs.folderConfig)),
      new GlobalScriptControllerSpec("LevelDb", Configs.levelDBConfiguration(Configs.folderConfig)),
      new WebhookControllerSpec("LevelDb", Configs.levelDBConfiguration(Configs.folderConfig)),
      new UserControllerSpec("LevelDb", Configs.levelDBConfiguration(Configs.folderConfig)),
      new ApikeyControllerSpec("LevelDb", Configs.levelDBConfiguration(Configs.folderConfig))
    )
    with BeforeAndAfterAll {

  override protected def afterAll(): Unit =
    Try {
      FileUtils.deleteRecursively(new File("./target/leveldb"))
    }
}



class MongoTests
  extends Suites(
    new ConfigControllerSpec("Mongo", Configs.mongoConfig("config")),
    new ExperimentControllerSpec("Mongo", Configs.mongoConfig("experiment")),
    new FeatureControllerSpec("Mongo", Configs.mongoConfig("feature")),
    new GlobalScriptControllerSpec("Mongo", Configs.mongoConfig("script")),
    new WebhookControllerSpec("Mongo", Configs.mongoConfig("webhook")),
    new UserControllerSpec("Mongo", Configs.mongoConfig("user")),
    new ApikeyControllerSpec("Mongo", Configs.mongoConfig("apikey"))
  )
