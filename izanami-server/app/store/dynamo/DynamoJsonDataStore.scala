package store.dynamo

import akka.actor.ActorSystem
import akka.stream.ActorMaterializer
import akka.stream.alpakka.dynamodb.{DynamoClient => AlpakkaClient, _}
import akka.stream.alpakka.dynamodb.AwsOp._
import akka.stream.alpakka.dynamodb.scaladsl.DynamoDb
import akka.stream.scaladsl.{Flow, Sink, Source}
import akka.NotUsed
import cats.implicits._
import com.amazonaws.services.dynamodbv2.model.{ConditionalCheckFailedException, _}
import domains.Key
import env.{DbDomainConfig, DynamoConfig}
import libs.streams.Flows
import libs.logs.Logger
import play.api.libs.json.JsValue
import store.Result.IzanamiErrors
import libs.dynamo.DynamoMapper
import store.{DataStoreContext, DefaultPagingResult, JsonDataStore, PagingResult, Query}

import scala.jdk.CollectionConverters._
import scala.concurrent.Future
import store.Result.DataShouldExists
import store.Result.DataShouldNotExists

object DynamoJsonDataStore {
  def apply(client: AlpakkaClient, dynamoConfig: DynamoConfig, config: DbDomainConfig)(
      implicit system: ActorSystem
  ): DynamoJsonDataStore = {
    val namespace = config.conf.namespace
    DynamoJsonDataStore(client, dynamoConfig.tableName, namespace)
  }

  def apply(client: AlpakkaClient, tableName: String, storeName: String)(
      implicit system: ActorSystem
  ): DynamoJsonDataStore =
    new DynamoJsonDataStore(client, tableName, storeName)
}

class DynamoJsonDataStore(client: AlpakkaClient, tableName: String, storeName: String)(
    implicit actorSystem: ActorSystem
) extends JsonDataStore {

  import zio._
  import IzanamiErrors._

  actorSystem.dispatcher
  private implicit val mat: ActorMaterializer = ActorMaterializer()(actorSystem)

  override def start: RIO[DataStoreContext, Unit] =
    Logger.info(s"Load store Dynamo for namespace $storeName")

  override def update(oldId: Key, id: Key, data: JsValue): ZIO[DataStoreContext, IzanamiErrors, JsValue] =
    for {
      _     <- Logger.debug(s"Dynamo update on $tableName and store $storeName update with id : $id and data : $data")
      mayBe <- getById(oldId).refineToOrDie[IzanamiErrors]
      _     <- ZIO.fromOption(mayBe).mapError(_ => DataShouldExists(oldId).toErrors)
      _     <- ZIO.when(oldId =!= id)(delete(oldId))
      _     <- put(id, data, createOnly = oldId =!= id)
    } yield data

  override def create(id: Key, data: JsValue): ZIO[DataStoreContext, IzanamiErrors, JsValue] =
    Logger.debug(s"Dynamo query on $tableName and store $storeName create with id : $id and data : $data") *>
    put(id, data, createOnly = true)

  private def put(id: Key, data: JsValue, createOnly: Boolean): ZIO[DataStoreContext, IzanamiErrors, JsValue] = {
    val request = new PutItemRequest()
      .withTableName(tableName)
      .withConditionExpression(if (createOnly) "attribute_not_exists(id)" else null)
      .withItem(
        Map(
          "id"    -> new AttributeValue().withS(id.key),
          "value" -> DynamoMapper.fromJsValue(data),
          "store" -> new AttributeValue().withS(storeName)
        ).asJava
      )
    IO.fromFuture { _ =>
        DynamoDb
          .source(request)
          .withAttributes(DynamoAttributes.client(client))
          .runWith(Sink.head)
      }
      .map(_ => data)
      .catchAll {
        case _: ConditionalCheckFailedException => ZIO.fail(DataShouldNotExists(id).toErrors)
      }
  }

  override def delete(id: Key): ZIO[DataStoreContext, IzanamiErrors, JsValue] = {
    val request = new DeleteItemRequest()
      .withTableName(tableName)
      .withConditionExpression("attribute_exists(id)")
      .withReturnValues(ReturnValue.ALL_OLD)
      .withKey(
        Map(
          "id"    -> new AttributeValue().withS(id.key),
          "store" -> new AttributeValue().withS(storeName)
        ).asJava
      )
    Logger.debug(s"Dynamo delete on $tableName and store $storeName delete with id : $id") *>
    IO.fromFuture { implicit ec =>
        DynamoDb
          .source(request)
          .withAttributes(DynamoAttributes.client(client))
          .runWith(Sink.head)
          .map(_.getAttributes.get("value"))
          .map(DynamoMapper.toJsValue)
      }
      .catchAll {
        case _: ConditionalCheckFailedException => ZIO.fail(DataShouldExists(id).toErrors)
      }
  }

  override def getById(id: Key): RIO[DataStoreContext, Option[JsValue]] = {

    val request = new GetItemRequest()
      .withTableName(tableName)
      .withKey(
        Map(
          "id"    -> new AttributeValue().withS(id.key),
          "store" -> new AttributeValue().withS(storeName)
        ).asJava
      )
    Logger.debug(s"Dynamo query on $tableName and store $storeName getById with id : $id") *>
    Task.fromFuture { implicit ec =>
      DynamoDb
        .source(request)
        .withAttributes(DynamoAttributes.client(client))
        .runWith(Sink.head)
        .map(r => Option(r.getItem).map(_.get("value").getM).map(DynamoMapper.toJsValue))
    }
  }

  override def findByQuery(query: Query,
                           page: Int,
                           nbElementPerPage: Int): RIO[DataStoreContext, PagingResult[JsValue]] = {
    val position = (page - 1) * nbElementPerPage
    Logger.debug(
      s"Dynamo query on $tableName and store $storeName findByQuery with patterns : $query, page $page, $nbElementPerPage elements by page"
    ) *>
    Task.fromFuture { implicit ec =>
      findKeys(query)
        .via(Flows.count {
          Flow[(Key, JsValue)]
            .drop(position)
            .take(nbElementPerPage)
            .grouped(nbElementPerPage)
            .map(_.map(_._2))
            .fold(Seq.empty[JsValue])(_ ++ _)
        })
        .runWith(Sink.head)
        .map {
          case (results, count) =>
            DefaultPagingResult(results, page, nbElementPerPage, count)
        }
    }
  }

  override def findByQuery(query: Query): RIO[DataStoreContext, Source[(Key, JsValue), NotUsed]] =
    Logger.debug(s"Dynamo query on $tableName and store $storeName findByQuery with patterns : $query") *>
    Task(findKeys(query))

  private def findKeys(query: Query): Source[(Key, JsValue), NotUsed] = query match {
    case q if q.hasEmpty =>
      Source.empty
    case _ =>
      val initialRequest = new QueryRequest()
        .withTableName(tableName)
        .withKeyConditions(
          Map(
            "store" -> new Condition()
              .withComparisonOperator(ComparisonOperator.EQ)
              .withAttributeValueList(new AttributeValue().withS(storeName))
          ).asJava
        )

      DynamoDb
        .source(initialRequest)
        .withAttributes(DynamoAttributes.client(client))
        .mapConcat(_.getItems.asScala.toList)
        .map(item => Key(item.get("id").getS) -> DynamoMapper.toJsValue(item.get("value").getM))
        .filter { case (k, _) => Query.keyMatchQuery(k, query) }
  }

  override def deleteAll(query: Query): ZIO[DataStoreContext, IzanamiErrors, Unit] =
    for {
      _       <- Logger.debug(s"Dynamo query on $tableName and store $storeName deleteAll with patterns : $query")
      runtime <- ZIO.runtime[DataStoreContext]
      source  <- findByQuery(query).refineToOrDie[IzanamiErrors]
      res <- IO
              .fromFuture { implicit ec =>
                source
                  .mapAsync(10) {
                    case (id, _) =>
                      runtime.unsafeRunToFuture(delete(id).either)
                  }
                  .runWith(Sink.ignore)
                  .map(_ => ())
              }
              .refineToOrDie[IzanamiErrors]
    } yield res

  override def count(query: Query): RIO[DataStoreContext, Long] =
    Logger.debug(s"Dynamo query on $tableName and store $storeName count with patterns : $query") *>
    Task.fromFuture(_ => countF(query))

  private def countF(query: Query): Future[Long] =
    findKeys(query)
      .runFold(0L) { (acc, _) =>
        acc + 1
      }

}
