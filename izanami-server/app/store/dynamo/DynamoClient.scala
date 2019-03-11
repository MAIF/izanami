package store.dynamo

import akka.actor.ActorSystem
import akka.stream.alpakka.dynamodb.impl.DynamoSettings
import akka.stream.{ActorMaterializer, Materializer}
import env.DynamoConfig
import libs.logs.IzanamiLogger
import akka.stream.alpakka.dynamodb.scaladsl.{DynamoClient => AlpakkaClient}
import com.amazonaws.auth.{AWSStaticCredentialsProvider, BasicAWSCredentials, DefaultAWSCredentialsProviderChain}
import com.amazonaws.services.dynamodbv2.model._

import scala.concurrent.ExecutionContext
import scala.util.{Failure, Success}

object DynamoClient {
  import akka.stream.alpakka.dynamodb.scaladsl.DynamoImplicits._

  def dynamoClient(mayBeConfig: Option[DynamoConfig])(implicit actorSystem: ActorSystem): Option[AlpakkaClient] =
    mayBeConfig.map { config =>
      IzanamiLogger.info(s"Initializing Dynamo cluster for $config")
      implicit val mat: Materializer    = ActorMaterializer()(actorSystem)
      implicit val ec: ExecutionContext = actorSystem.dispatcher

      val credentials = for {
        access <- config.accessKey.map(_.trim).filter(_.nonEmpty)
        secret <- config.secretKey.map(_.trim).filter(_.nonEmpty)
      } yield new AWSStaticCredentialsProvider(new BasicAWSCredentials(access, secret))

      val settings = DynamoSettings(config.region,
                                    config.host,
                                    config.port,
                                    config.parallelism,
                                    credentials.getOrElse(new DefaultAWSCredentialsProviderChain()))

      val client = AlpakkaClient(settings)

      createIfNotExist(
        client,
        config.tableName,
        List(
          new AttributeDefinition().withAttributeName("store").withAttributeType(ScalarAttributeType.S),
          new AttributeDefinition().withAttributeName("id").withAttributeType(ScalarAttributeType.S)
        ),
        List(
          new KeySchemaElement().withAttributeName("store").withKeyType(KeyType.HASH),
          new KeySchemaElement().withAttributeName("id").withKeyType(KeyType.RANGE)
        )
      )

      createIfNotExist(
        client,
        config.eventsTableName,
        List(
          new AttributeDefinition().withAttributeName("experimentId").withAttributeType(ScalarAttributeType.S),
          new AttributeDefinition().withAttributeName("variantId").withAttributeType(ScalarAttributeType.S),
        ),
        List(
          new KeySchemaElement().withAttributeName("experimentId").withKeyType(KeyType.HASH),
          new KeySchemaElement().withAttributeName("variantId").withKeyType(KeyType.RANGE)
        )
      )

      client
    }

  private def createIfNotExist(client: AlpakkaClient,
                               tableName: String,
                               attributes: List[AttributeDefinition],
                               keys: List[KeySchemaElement])(implicit ec: ExecutionContext) =
    client
      .single(
        new DescribeTableRequest()
          .withTableName(tableName)
      )
      .recover {
        case _: ResourceNotFoundException =>
          IzanamiLogger.info(s"Table $tableName did not exist, creating it")
          client
            .single(
              new CreateTableRequest()
                .withTableName(tableName)
                .withAttributeDefinitions(attributes: _*)
                .withKeySchema(keys: _*)
                .withProvisionedThroughput(
                  new ProvisionedThroughput()
                    .withReadCapacityUnits(1L)
                    .withWriteCapacityUnits(1L)
                )
            )
            .onComplete {
              case Success(_)     => IzanamiLogger.info(s"Table $tableName created successfully")
              case Failure(error) => IzanamiLogger.error(s"Could not create $tableName", error)
            }
        case error =>
          IzanamiLogger.error(s"Could not check existence of $tableName", error)
      }

}
