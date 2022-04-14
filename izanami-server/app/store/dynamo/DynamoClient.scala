package store.dynamo

import akka.Done
import akka.actor.ActorSystem
import akka.stream.alpakka.dynamodb.{DynamoAttributes, DynamoSettings, DynamoClient => AlpakkaClient}
import akka.stream.alpakka.dynamodb.AwsOp._
import akka.stream.alpakka.dynamodb.scaladsl.DynamoDb
import akka.stream.scaladsl.{Sink, Source}
import akka.stream.{ActorMaterializer, Materializer}
import env.DynamoConfig
import libs.logs.{IzanamiLogger, ZLogger}
import com.amazonaws.auth.{AWSStaticCredentialsProvider, BasicAWSCredentials, DefaultAWSCredentialsProviderChain}
import com.amazonaws.services.dynamodbv2.model._
import domains.abtesting.events.impl.ExperimentVariantEventDynamoService
import domains.abtesting.events.impl.ExperimentVariantEventDynamoService.{experimentId, variantId}
import zio.{Task, ZIO, ZManaged}

import scala.concurrent.duration.DurationDouble
import scala.concurrent.{Await, ExecutionContext, Future}
import scala.util.{Failure, Success}

object DynamoClient {

  def createClient(
      config: DynamoConfig
  )(implicit actorSystem: ActorSystem): ZIO[ZLogger, Throwable, AlpakkaClient] =
    ZLogger.info(s"Initializing Dynamo cluster for ${config.host}:${config.port}") *> Task {
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

      AlpakkaClient(settings)
    }

  def dynamoClient(
      mayBeConfig: Option[DynamoConfig]
  )(implicit actorSystem: ActorSystem): ZIO[ZLogger, Throwable, Option[AlpakkaClient]] =
    mayBeConfig
      .map { config =>
        createClient(config).flatMap { client =>
          val value: ZIO[ZLogger, Throwable, Option[AlpakkaClient]] = ZLogger.info(
              s"Initialization json data store table creation"
            ) *>
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
            ) *>
            ZLogger.info(s"Initialization experiment events table creation") *>
            createIfNotExist(
              client,
              config.eventsTableName,
              List(
                new AttributeDefinition().withAttributeName(experimentId).withAttributeType(ScalarAttributeType.S),
                new AttributeDefinition().withAttributeName(variantId).withAttributeType(ScalarAttributeType.S)
              ),
              List(
                new KeySchemaElement().withAttributeName(experimentId).withKeyType(KeyType.HASH),
                new KeySchemaElement().withAttributeName(variantId).withKeyType(KeyType.RANGE)
              )
            ) *> zio.Task(Option(client))
          value
        }
      }
      .getOrElse {
        ZIO(None)
      }

  private def createIfNotExist(
      client: AlpakkaClient,
      tableName: String,
      attributes: List[AttributeDefinition],
      keys: List[KeySchemaElement]
  )(implicit mat: Materializer): Task[Done] =
    Task.fromFuture { implicit ec =>
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

}
