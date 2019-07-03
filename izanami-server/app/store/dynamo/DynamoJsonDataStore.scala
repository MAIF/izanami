package store.dynamo

import akka.actor.ActorSystem
import akka.stream.ActorMaterializer
import akka.stream.alpakka.dynamodb.{DynamoClient => AlpakkaClient, _}
import akka.stream.alpakka.dynamodb.AwsOp._
import akka.stream.alpakka.dynamodb.scaladsl.DynamoDb
import akka.stream.scaladsl.{Flow, Sink, Source}
import akka.{Done, NotUsed}
import cats.data.EitherT
import cats.effect.Effect
import com.amazonaws.services.dynamodbv2.model.{ConditionalCheckFailedException, _}
import domains.Key
import env.{DbDomainConfig, DynamoConfig}
import libs.streams.Flows
import libs.logs.IzanamiLogger
import play.api.libs.json.JsValue
import store.Result.{AppErrors, ErrorMessage, Result}
import libs.dynamo.DynamoMapper
import libs.functional.EitherTSyntax
import store.{DefaultPagingResult, JsonDataStore, PagingResult, Query, Result}

import scala.collection.JavaConverters._
import scala.concurrent.{ExecutionContext, Future}

object DynamoJsonDataStore {
  def apply[F[_]: Effect](client: AlpakkaClient, dynamoConfig: DynamoConfig, config: DbDomainConfig)(
      implicit system: ActorSystem
  ): DynamoJsonDataStore[F] = {
    val namespace = config.conf.namespace
    IzanamiLogger.info(s"Load store Dynamo for namespace $namespace")
    DynamoJsonDataStore(client, dynamoConfig.tableName, namespace)
  }

  def apply[F[_]: Effect](client: AlpakkaClient, tableName: String, storeName: String)(
      implicit system: ActorSystem
  ): DynamoJsonDataStore[F] =
    new DynamoJsonDataStore(client, tableName, storeName)
}

class DynamoJsonDataStore[F[_]: Effect](client: AlpakkaClient, rawTableName: String, rawStoreName: String)(
    implicit actorSystem: ActorSystem
) extends JsonDataStore[F]
    with EitherTSyntax[F] {

  import cats.implicits._
  import libs.effects._
  import libs.functional.syntax._

  private val tableName = rawTableName.replaceAll(":", "_")
  private val storeName = rawStoreName.replaceAll(":", "_")

  private implicit val ec: ExecutionContext   = actorSystem.dispatcher
  private implicit val mat: ActorMaterializer = ActorMaterializer()(actorSystem)

  override def update(oldId: Key, id: Key, data: JsValue): F[Result[JsValue]] = {
    IzanamiLogger.debug(s"Dynamo update on $tableName and store $storeName update with id : $id and data : $data")
    if (oldId == id) {
      val res = for {
        _ <- getById(oldId) |> liftFOption[AppErrors, JsValue] {
              AppErrors.error(s"error.data.missing", id.key)
            }
        _ <- put(id, data, createOnly = false) |> liftFEither[AppErrors, JsValue]
      } yield data
      res.value
    } else {
      val res: EitherT[F, AppErrors, JsValue] = for {
        _ <- getById(oldId) |> liftFOption[AppErrors, JsValue] {
              AppErrors.error(s"error.data.missing", id.key)
            }
        _ <- delete(oldId) |> liftFEither[AppErrors, JsValue]
        _ <- put(id, data, createOnly = true) |> liftFEither[AppErrors, JsValue]
      } yield data
      res.value
    }
  }

  override def create(id: Key, data: JsValue): F[Result[JsValue]] = {
    IzanamiLogger.debug(s"Dynamo query on $tableName and store $storeName create with id : $id and data : $data")

    put(id, data, createOnly = true)
  }

  private def put(id: Key, data: JsValue, createOnly: Boolean): F[Result[JsValue]] = {
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

    DynamoDb
      .source(request)
      .withAttributes(DynamoAttributes.client(client))
      .runWith(Sink.head)
      .map(_ => Result.ok(data))
      .toF
      .recover {
        case _: ConditionalCheckFailedException => Result.errors[JsValue](ErrorMessage("error.data.exists", id.key))
      }
  }

  override def delete(id: Key): F[Result[JsValue]] =
    deleteF(id).toF

  def deleteF(id: Key): Future[Result[JsValue]] = {
    IzanamiLogger.debug(s"Dynamo delete on $tableName and store $storeName delete with id : $id")

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

    DynamoDb
      .source(request)
      .withAttributes(DynamoAttributes.client(client))
      .runWith(Sink.head)
      .map(_.getAttributes.get("value"))
      .map(DynamoMapper.toJsValue)
      .map(Result.ok)
      .recover { case _: ConditionalCheckFailedException => Result.error[JsValue](s"error.data.missing") }
  }

  override def getById(id: Key): F[Option[JsValue]] = {
    IzanamiLogger.debug(s"Dynamo query on $tableName and store $storeName getById with id : $id")

    val request = new GetItemRequest()
      .withTableName(tableName)
      .withKey(
        Map(
          "id"    -> new AttributeValue().withS(id.key),
          "store" -> new AttributeValue().withS(storeName)
        ).asJava
      )

    DynamoDb
      .source(request)
      .withAttributes(DynamoAttributes.client(client))
      .runWith(Sink.head)
      .toF
      .map(r => Option(r.getItem).map(_.get("value").getM).map(DynamoMapper.toJsValue))
  }

  override def findByQuery(query: Query, page: Int, nbElementPerPage: Int): F[PagingResult[JsValue]] = {
    IzanamiLogger.debug(
      s"Dynamo query on $tableName and store $storeName findByQuery with patterns : $query, page $page, $nbElementPerPage elements by page"
    )

    val position = (page - 1) * nbElementPerPage
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
      .toF
      .map {
        case (results, count) =>
          DefaultPagingResult(results, page, nbElementPerPage, count)
      }
  }

  override def findByQuery(query: Query): Source[(Key, JsValue), NotUsed] = {
    IzanamiLogger.debug(s"Dynamo query on $tableName and store $storeName findByQuery with patterns : $query")
    findKeys(query)
  }

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

  override def deleteAll(query: Query): F[Result[Done]] = {
    IzanamiLogger.debug(s"Dynamo query on $tableName and store $storeName deleteAll with patterns : $query")

    findByQuery(query)
      .mapAsync(10) { case (id, _) => deleteF(id) }
      .runWith(Sink.ignore)
      .toF
      .map(_ => Result.ok(Done.done()))
  }

  override def count(query: Query): F[Long] =
    countF(query).toF

  private def countF(query: Query): Future[Long] = {
    IzanamiLogger.debug(s"Dynamo query on $tableName and store $storeName count with patterns : $query")
    findKeys(query)
      .runFold(0L) { (acc, _) =>
        acc + 1
      }
  }

}
