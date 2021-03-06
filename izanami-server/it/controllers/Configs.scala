package controllers

import java.io.File
import java.util.concurrent.atomic.AtomicInteger

import akka.testkit.SocketUtil._
import com.typesafe.config.ConfigFactory
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
import akka.http.scaladsl.model.Uri.Path

object Configs {

  val idGenerator = IdGenerator(0L)

  val redisPort: Int = 6380

  val elastic6HttpPort: Int = 9210

  val elastic7HttpPort: Int = 9220

  def elastic6Configuration: Configuration = elasticConfiguration(elastic6HttpPort, 6)

  def elastic7Configuration: Configuration = elasticConfiguration(elastic7HttpPort, 7)

  def elasticConfiguration(elasticHttpPort: Int, version: Int): Configuration = Configuration(
    ConfigFactory.parseString(s"""
                                 |izanami.db.default="Elastic"
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
                                 |    elastic {
                                 |      host = "localhost"
                                 |      port = $elasticHttpPort
                                 |      version = $version
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

  def levelDBConfiguration(folder: String): Configuration = Configuration(
    ConfigFactory.parseString(s"""
          |izanami.db.default="LevelDB"
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
          |    leveldb {
          |      parentPath = "./target/leveldb-controllertest/$folder"
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
                     |izanami.experimentEvent.db.type=${izanami.db.default}
                     |izanami.webhook.db.type=${izanami.db.default}
                     |izanami.user.db.type=${izanami.db.default}
                     |izanami.apikey.db.type=${izanami.db.default}
                     |izanami.patch.db.type=${izanami.db.default}
                     |
      """.stripMargin)
      .resolve()
  )

  def dynamoDbConfig(id: Long): Configuration = Configuration(
    ConfigFactory
      .parseString(s"""
                     |izanami.db.default="Dynamo"
                     |izanami.patchEnabled = false
                     |
                     |izanami.mode= "test"
                     |izanami.config.db.type=$${izanami.db.default}
                     |izanami.db.dynamo.tableName=izanami_$id
                     |izanami.db.dynamo.eventsTableName=izanami_experimentevents_$id
                     |izanami.db.dynamo.region="eu-west-1"
                     |izanami.db.dynamo.host=localhost
                     |izanami.db.dynamo.port=8001
                     |izanami.db.dynamo.tls=false
                     |izanami.db.dynamo.parallelism=32
                     |izanami.config.db.type=$${izanami.db.default}
                     |izanami.features.db.type=$${izanami.db.default}
                     |izanami.globalScript.db.type=$${izanami.db.default}
                     |izanami.experiment.db.type=$${izanami.db.default}
                     |izanami.experimentEvent.db.type=$${izanami.db.default}
                     |izanami.webhook.db.type=$${izanami.db.default}
                     |izanami.user.db.type=$${izanami.db.default}
                     |izanami.apikey.db.type=$${izanami.db.default}
                     |izanami.patch.db.type=$${izanami.db.default}
                     |
                     |akka.stream.alpakka.dynamodb {
                     |  region = $${izanami.db.dynamo.region}
                     |  host = $${izanami.db.dynamo.host}
                     |  port = $${izanami.db.dynamo.port}
                     |  tls = false
                     |  parallelism = $${izanami.db.dynamo.parallelism}
                     |  credentials {
                     |    access-key-id = $${?DYNAMO_ACCESS_KEY}
                     |    secret-key-id = $${?DYNAMO_SECRET_KEY}
                     |  }
                     |}
      """.stripMargin)
      .resolve()
  )

  val inc = new AtomicInteger(0)

  def pgConfig(id: Long): Configuration = Configuration(
    ConfigFactory
      .parseString(s"""
                      |izanami.db.default="Postgresql"
                      |izanami.patchEnabled = false
                      |
                      |izanami.mode= "test"
                      |izanami.config.db.type=$${izanami.db.default}
                      |izanami.db.postgresql.url="jdbc:postgresql://localhost:5555/izanami"
                      |izanami.config.db.type=$${izanami.db.default}
                      |izanami.config.db.conf.namespace="izanami$id:config"
                      |izanami.features.db.type=$${izanami.db.default}
                      |izanami.features.db.conf.namespace="izanami$id:feature"
                      |izanami.globalScript.db.type=$${izanami.db.default}
                      |izanami.globalScript.db.conf.namespace="izanami$id:globalscript"
                      |izanami.experiment.db.type=$${izanami.db.default}
                      |izanami.experiment.db.conf.namespace="izanami$id:experiment"
                      |izanami.experimentEvent.db.type=$${izanami.db.default}
                      |izanami.experimentEvent.db.conf.namespace="izanami$id:experimentevent"
                      |izanami.webhook.db.type=$${izanami.db.default}
                      |izanami.webhook.db.conf.namespace="izanami$id:webhook"
                      |izanami.user.db.type=$${izanami.db.default}
                      |izanami.user.db.conf.namespace="izanami$id:user"
                      |izanami.apikey.db.type=$${izanami.db.default}
                      |izanami.apikey.db.conf.namespace="izanami$id:apikey"
                      |izanami.patch.db.type=$${izanami.db.default}
                      |izanami.patch.db.conf.namespace="izanami$id:patch"
      """.stripMargin)
      .resolve()
  )

  def cleanLevelDb = Try {
    FileUtils.deleteRecursively(new File("./target/leveldb/"))
  }

  def initEs6 = if (Try(Option(System.getenv("CI"))).toOption.flatten.exists(!_.isEmpty)) {
    import elastic.es6.codec.PlayJson._
    import elastic.es6.client.{ElasticClient => ElasticClient6}
    val client: ElasticClient6[JsValue] = ElasticClient6[JsValue](port = elastic6HttpPort)
    println("Cleaning ES indices")
    Await.result(client.deleteIndex("*"), 5.seconds)
    Await.result(
      client.put(
        Path.Empty / "_cluster" / "settings",
        Some("""
               |{
               |    "persistent" : {
               |        "cluster.routing.allocation.disk.threshold_enabled" : false
               |    }
               |}
      """.stripMargin)
      ),
      5.seconds
    )
    Thread.sleep(1000)
  }

  def initEs7 = if (Try(Option(System.getenv("CI"))).toOption.flatten.exists(!_.isEmpty)) {
    import elastic.es7.codec.PlayJson._
    import elastic.es7.client.{ElasticClient => ElasticClient7}
    val client: ElasticClient7[JsValue] = ElasticClient7[JsValue](port = elastic7HttpPort)
    println("Cleaning ES indices")
    Await.result(client.deleteIndex("*"), 5.seconds)
    Await.result(
      client.put(
        Path.Empty / "_cluster" / "settings",
        Some("""
               |{
               |    "persistent" : {
               |        "cluster.routing.allocation.disk.threshold_enabled" : false
               |    }
               |}
      """.stripMargin)
      ),
      5.seconds
    )
    Thread.sleep(1000)
  }
}
