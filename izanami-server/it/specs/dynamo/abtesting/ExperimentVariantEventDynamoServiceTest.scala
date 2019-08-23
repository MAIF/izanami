package specs.dynamo.abtesting

import cats.effect.IO
import akka.stream.alpakka.dynamodb.{DynamoClient => AlpakkaClient}
import com.typesafe.config.{Config, ConfigFactory}
import domains.abtesting.{AbstractExperimentServiceTest, ExperimentVariantEventService}
import domains.events.impl.BasicEventStore
import env.DynamoConfig
import store.dynamo.DynamoClient
import domains.abtesting.impl.ExperimentVariantEventDynamoService

class ExperimentVariantEventDynamoServiceTest extends AbstractExperimentServiceTest("DynamoDb") {

 private val region = "eu-west-1"
 private val host = "127.0.0.1"
 private val port = 8001
 private val accessKey = "dummy-access-key"
 private val secretKey = "dummy-secretKey-key"

 override def akkaConfig: Option[Config] = Some(ConfigFactory.parseString(
   s"""
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

 def getClient(name: String): AlpakkaClient = {
   val Some(client) = DynamoClient.dynamoClient(Some(
     DynamoConfig(name, name, region, host, port, tls = false, accessKey = Some(accessKey), secretKey = Some(secretKey))
   ))
   client
 }

 override def dataStore(name: String): ExperimentVariantEventService = ExperimentVariantEventDynamoService(
   DynamoConfig(name, name, region, host, port, tls = false, accessKey = Some(accessKey), secretKey = Some(secretKey)),
   getClient(name)
 )
}
