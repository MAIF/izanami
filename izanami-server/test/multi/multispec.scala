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

import scala.concurrent.Await
import scala.concurrent.ExecutionContext.Implicits.global
import scala.concurrent.duration.DurationLong
import scala.util.{Random, Try}
import Configs.idGenerator

object Configs {

  val idGenerator = IdGenerator(0L)

  val redisPort: Int = 6380

  val cassandraPort: Int = 9042

  val elasticHttpPort: Int = 9210

  val elasticConfiguration: Configuration = Configuration(
    ConfigFactory.parseString(s"""
      |izanami.db.default="Elastic"
      |izanami.patchEnabled = false
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

  def redisConfiguration: Configuration = Configuration(
    ConfigFactory.parseString(s"""
         |izanami.db.default="Redis"
         |izanami.patchEnabled = false
         |izanami.mode= "test"
         |izanami.namespace="izanami-${Random.nextInt(1000)}"
         |izanami.config.db.type=$${izanami.db.default}
         |izanami.config.db.conf.namespace="izanami-${Random.nextInt(1000)}:configuration"
         |izanami.features.db.type=$${izanami.db.default}
         |izanami.features.db.conf.namespace="izanami-${Random.nextInt(1000)}:feature"
         |izanami.globalScript.db.type=$${izanami.db.default}
         |izanami.globalScript.db.conf.namespace="izanami-${Random.nextInt(1000)}:script"
         |izanami.experiment.db.type=$${izanami.db.default}
         |izanami.experiment.db.conf.namespace="izanami-${Random.nextInt(1000)}:experiment"
         |izanami.experimentEvent.db.type=$${izanami.db.default}
         |izanami.experimentEvent.db.conf.namespace="izanami-${Random.nextInt(1000)}:events"
         |izanami.webhook.db.type=$${izanami.db.default}
         |izanami.webhook.db.conf.namespace="izanami-${Random.nextInt(1000)}:hooks"
         |izanami.user.db.type=$${izanami.db.default}
         |izanami.user.db.conf.namespace="izanami-${Random.nextInt(1000)}:user"
         |izanami.apikey.db.type=$${izanami.db.default}
         |izanami.apikey.db.conf.namespace="izanami-${Random.nextInt(1000)}:apikey"
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
         |izanami.patchEnabled = false
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
         |izanami.patchEnabled = false
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
        |izanami.patchEnabled = false
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
       |izanami.patchEnabled = false
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
       |    mongo {
       |      url = "mongodb://localhost:27017/$test-${Random.nextInt(1000)}"
       |    }
       |  }
       |}
      """.stripMargin)
      .resolve()
  )

  val inMemoryWithDbConfiguration: Configuration = Configuration(
    ConfigFactory
      .parseString("""
                     |izanami.db.default="InMemoryWithDb"
                     |izanami.db.inMemoryWithDb.db="InMemory"
                     |izanami.patchEnabled = false
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

}

object Tests {
  def getSuite(name: String, conf: Configuration): Seq[Suite] =
    Seq(
      new ConfigControllerSpec(name, conf),
      new ExperimentControllerSpec(name, conf),
      new FeatureControllerSpec(name, conf),
      new FeatureControllerStrictAccessSpec(name, conf),
      new FeatureControllerWildcardAccessSpec(name, conf),
      new GlobalScriptControllerSpec(name, conf),
      new WebhookControllerSpec(name, conf),
      new UserControllerSpec(name, conf),
      new ApikeyControllerSpec(name, conf)
    )

  def getSuites(): Seq[Suite] =
    if (Try(Option(System.getenv("CI"))).toOption.flatten.exists(!_.isBlank)) {
      getSuite("InMemory", Configs.inMemoryConfiguration) ++
      getSuite("InMemoryWithDb", Configs.inMemoryWithDbConfiguration) ++
      getSuite("Redis", Configs.redisConfiguration) ++
      getSuite("Elastic", Configs.elasticConfiguration) ++
      getSuite("Cassandra", Configs.cassandraConfiguration(s"config${idGenerator.nextId()}")) ++
      getSuite("LevelDb", Configs.levelDBConfiguration(Configs.folderConfig)) ++
      getSuite("Mongo", Configs.mongoConfig("config"))
    } else {
      getSuite("InMemory", Configs.inMemoryConfiguration)
    }
}

class IzanamiIntegrationTests extends Suites(Tests.getSuites(): _*) with BeforeAndAfterAll {
  override protected def beforeAll(): Unit =
    if (Try(Option(System.getenv("CI"))).toOption.flatten.exists(!_.isBlank)) {
      import elastic.codec.PlayJson._
      val client = ElasticClient[JsValue](port = Configs.elasticHttpPort)
      println("Cleaning ES indices")
      Await.result(client.deleteIndex("izanami_*"), 5.seconds)
    }

  override protected def afterAll(): Unit =
    if (Try(Option(System.getenv("CI"))).toOption.flatten.exists(!_.isBlank)) {
      Try {
        FileUtils.deleteRecursively(new File("./target/leveldb"))
      }
    }
}
//
//class InMemoryTests
//    extends Suites(Tests.getSuite("InMemory", Configs.inMemoryConfiguration): _*)
//    with BeforeAndAfterAll {}
//
//class InMemoryWithDbTests
//    extends Suites(Tests.getSuite("InMemoryWithDb", Configs.inMemoryWithDbConfiguration): _*)
//    with BeforeAndAfterAll {}
//
//class RedisTests extends Suites(Tests.getSuite("Redis", Configs.redisConfiguration): _*)
//
//class ElasticTests extends Suites(Tests.getSuite("Elastic", Configs.elasticConfiguration): _*) with BeforeAndAfterAll {
//
//  override protected def beforeAll(): Unit = {
//    import elastic.codec.PlayJson._
//    val client = ElasticClient[JsValue](port = Configs.elasticHttpPort)
//    println("Cleaning ES indices")
//    Await.result(client.deleteIndex("izanami_*"), 5.seconds)
//  }
//
//  override protected def afterAll(): Unit = ()
//}
//
//class CassandraTests
//    extends Suites(Tests.getSuite("Cassandra", Configs.cassandraConfiguration(s"config${idGenerator.nextId()}")): _*)
//
//class LevelDBTests
//    extends Suites(Tests.getSuite("LevelDb", Configs.levelDBConfiguration(Configs.folderConfig)): _*)
//    with BeforeAndAfterAll {
//
//  override protected def afterAll(): Unit =
//    Try {
//      FileUtils.deleteRecursively(new File("./target/leveldb"))
//    }
//}
//
//class MongoTests extends Suites(Tests.getSuite("Mongo", Configs.mongoConfig("config")): _*)
