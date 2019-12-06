package specs.dynamo.store

import com.typesafe.config.{Config, ConfigFactory}
import akka.stream.alpakka.dynamodb.{DynamoAttributes, DynamoSettings, DynamoClient => AlpakkaClient}
import akka.stream.alpakka.dynamodb.AwsOp._
import akka.stream.alpakka.dynamodb.scaladsl.DynamoDb
import akka.stream.scaladsl.Sink
import com.amazonaws.auth.{AWSStaticCredentialsProvider, BasicAWSCredentials}
import com.amazonaws.services.dynamodbv2.model.{DeleteTableRequest, ListTablesRequest}
import env.DynamoConfig
import org.scalactic.source.Position
import org.scalatest.{BeforeAndAfter, BeforeAndAfterAll}
import store.AbstractJsonDataStoreTest

import scala.util.{Random, Try}
import store.dynamo.DynamoJsonDataStore
import store.dynamo.DynamoClient

class DynamoJsonDataStoreTest extends AbstractJsonDataStoreTest("DynamoDb") with BeforeAndAfter with BeforeAndAfterAll {

  private val region    = "eu-west-1"
  private val host      = "127.0.0.1"
  private val port      = 8001
  private val accessKey = "dummy-access-key"
  private val secretKey = "dummy-secretKey-key"

  override def akkaConfig: Option[Config] = Some(ConfigFactory.parseString(s"""
      |akka.stream.alpakka.dynamodb {
      |  region = "eu-west-1"
      |  host = $host
      |  port = $port
      |  tls = false
      |  parallelism = 32
      |  credentials {
      |    access-key-id = $accessKey
      |    secretKey-key-id = $secretKey
      |  }
      |}
    """.stripMargin))

  def getClient(name: String) = {
    val Some(client) = DynamoClient.dynamoClient(
      Some(
        DynamoConfig(name,
                     s"events_$name",
                     region,
                     host,
                     port,
                     tls = false,
                     accessKey = Some(accessKey),
                     secretKey = Some(secretKey))
      )
    )
    client
  }

  private val dbName = s"test_db_${Random.nextInt(10000)}"
  val dynamoClient   = getClient(dbName)

  override def dataStore(name: String): DynamoJsonDataStore =
    DynamoJsonDataStore(dynamoClient, dbName, name)

  override protected def before(fun: => Any)(implicit pos: Position): Unit = {
    super.before(fun)
    deleteAll
  }

  override protected def beforeAll(): Unit = {
    super.afterAll()
    deleteAll
  }

  private def deleteAll = {
    import scala.jdk.CollectionConverters._

    val settings = DynamoSettings(region, host)
      .withPort(port)
      .withTls(false)
      .withParallelism(32)
      .withCredentialsProvider(new AWSStaticCredentialsProvider(new BasicAWSCredentials(accessKey, secretKey)))

    val client = AlpakkaClient(settings)

    Try {
      DynamoDb
        .source(new ListTablesRequest())
        .withAttributes(DynamoAttributes.client(client))
        .mapConcat(_.getTableNames.asScala.toList)
        .flatMapMerge(2, { table =>
          DynamoDb.source(new DeleteTableRequest().withTableName(table))
        })
        .runWith(Sink.ignore)
        .futureValue
    }.recover {
      case e =>
        e.printStackTrace()
        ()
    }
  }
}
