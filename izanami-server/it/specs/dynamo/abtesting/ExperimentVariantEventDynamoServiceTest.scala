package specs.dynamo.abtesting

import cats.effect.IO
import akka.stream.alpakka.dynamodb.{DynamoClient => AlpakkaClient}
import com.typesafe.config.{Config, ConfigFactory}
import domains.abtesting.events.impl.ExperimentVariantEventDynamoService
import domains.abtesting.AbstractExperimentServiceTest
import domains.abtesting.events.ExperimentVariantEventService
import domains.events.impl.BasicEventStore
import env.DynamoConfig
import libs.logs.ZLogger
import store.dynamo.DynamoClient

class ExperimentVariantEventDynamoServiceTest extends AbstractExperimentServiceTest("DynamoDb") {

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

  def getClient(config: DynamoConfig): AlpakkaClient = {
    val Some(client) = runtime.unsafeRun(DynamoClient.dynamoClient(Some(config)).provideLayer(ZLogger.live))
    client
  }

  override def dataStore(name: String): ExperimentVariantEventService.Service = {
    val config = DynamoConfig("othername",
                              name,
                              region,
                              host,
                              port,
                              tls = false,
                              accessKey = Some(accessKey),
                              secretKey = Some(secretKey))
    ExperimentVariantEventDynamoService(config, getClient(config))
  }
}
