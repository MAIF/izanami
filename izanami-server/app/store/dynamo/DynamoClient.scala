package store.dynamo

import akka.Done
import akka.actor.ActorSystem
import akka.stream.alpakka.dynamodb.{DynamoAttributes, DynamoSettings, DynamoClient => AlpakkaClient}
import akka.stream.alpakka.dynamodb.AwsOp._
import akka.stream.alpakka.dynamodb.scaladsl.DynamoDb
import akka.stream.scaladsl.{Sink, Source}
import akka.stream.{ActorMaterializer, Materializer}
import env.DynamoConfig
import libs.logs.IzanamiLogger
import com.amazonaws.auth.{AWSStaticCredentialsProvider, BasicAWSCredentials, DefaultAWSCredentialsProviderChain}
import com.amazonaws.services.dynamodbv2.model._
import domains.abtesting.impl.ExperimentVariantEventDynamoService

import scala.concurrent.duration.DurationDouble
import scala.concurrent.{Await, ExecutionContext, Future}
import scala.util.{Failure, Success}

object DynamoClient {

  def dynamoClient(mayBeConfig: Option[DynamoConfig])(implicit actorSystem: ActorSystem): Option[AlpakkaClient] =
    mayBeConfig.map { config =>
      IzanamiLogger.info(s"Initializing Dynamo cluster for $config")
      implicit val ec: ExecutionContext = actorSystem.dispatcher

      val credentials = for {
        access <- config.accessKey.map(_.trim).filter(_.nonEmpty)
        secret <- config.secretKey.map(_.trim).filter(_.nonEmpty)
      } yield new AWSStaticCredentialsProvider(new BasicAWSCredentials(access, secret))

      val settings = DynamoSettings(config.region, config.host)
        .withPort(config.port)
        .withTls(config.tls)
        .withParallelism(config.parallelism)
        .withCredentialsProvider(credentials.getOrElse(new DefaultAWSCredentialsProviderChain()))

      val client = AlpakkaClient(settings)
      IzanamiLogger.info(s"Initialization json data store table creation")
      Await.result(
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
        ),
        30.seconds
      )
      IzanamiLogger.info(s"Initialization experiment events table creation")
      val _ = {
        import ExperimentVariantEventDynamoService._
        Await.result(
          createIfNotExist(
            client,
            config.eventsTableName,
            List(
              new AttributeDefinition().withAttributeName(experimentId).withAttributeType(ScalarAttributeType.S),
              new AttributeDefinition().withAttributeName(variantId).withAttributeType(ScalarAttributeType.S),
            ),
            List(
              new KeySchemaElement().withAttributeName(experimentId).withKeyType(KeyType.HASH),
              new KeySchemaElement().withAttributeName(variantId).withKeyType(KeyType.RANGE)
            )
          ),
          30.seconds
        )
      }
      IzanamiLogger.info(s"Initialization of Dynamo is done")
      client
    }

  private def createIfNotExist(
      client: AlpakkaClient,
      tableName: String,
      attributes: List[AttributeDefinition],
      keys: List[KeySchemaElement]
  )(implicit mat: Materializer, ec: ExecutionContext): Future[Done] =
    DynamoDb
      .source(new DescribeTableRequest().withTableName(tableName))
      .withAttributes(DynamoAttributes.client(client))
      .recoverWithRetries(
        1, {
          case _: ResourceNotFoundException =>
            IzanamiLogger.info(s"Table $tableName did not exist, creating it")
            DynamoDb
              .source(
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
              .withAttributes(DynamoAttributes.client(client))
              .watchTermination() { (nu, f) =>
                f.onComplete {
                  case Success(_)     => IzanamiLogger.info(s"Table $tableName created successfully")
                  case Failure(error) => IzanamiLogger.error(s"Could not create $tableName", error)
                }
                nu
              }
          case error =>
            IzanamiLogger.error(s"Could not check existence of $tableName", error)
            Source.failed(error)
        }
      )
      .runWith(Sink.ignore)

}
