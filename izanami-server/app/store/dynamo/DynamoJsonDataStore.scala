package store.dynamo

import akka.actor.ActorSystem
import akka.stream.ActorMaterializer
import akka.stream.alpakka.dynamodb.{DynamoClient => AlpakkaClient, _}
import akka.stream.alpakka.dynamodb.AwsOp._
import akka.stream.alpakka.dynamodb.scaladsl.DynamoDb
import akka.stream.scaladsl.{Flow, Sink, Source}
import akka.{Done, NotUsed}
import cats.effect.Effect
import com.amazonaws.services.dynamodbv2.model.{ConditionalCheckFailedException, _}
import domains.Key
import env.{DbDomainConfig, DynamoConfig}
import libs.streams.Flows
import libs.logs.IzanamiLogger
import play.api.libs.json.JsValue
import store.Result.{ErrorMessage, Result}
import libs.dynamo.DynamoMapper
import store.{DefaultPagingResult, JsonDataStore, PagingResult, Result}

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
    new DynamoJsonDataStore(tableName, storeName)
}

class DynamoJsonDataStore[F[_]: Effect](tableName: String, storeName: String)(
    implicit actorSystem: ActorSystem
) extends JsonDataStore[F] {

  import cats.implicits._
  import libs.effects._

  private implicit val ec: ExecutionContext   = actorSystem.dispatcher
  private implicit val mat: ActorMaterializer = ActorMaterializer()(actorSystem)

  override def update(oldId: Key, id: Key, data: JsValue): F[Result[JsValue]] = {
    IzanamiLogger.debug(s"Dynamo update on $tableName and store $storeName update with id : $id and data : $data")
    if (oldId == id) put(id, data, createOnly = false)
    else {
      for {
        _      <- delete(oldId)
        create <- put(id, data, createOnly = false)
      } yield create
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
          "store" -> new AttributeValue().withS(storeName),
          "value" -> DynamoMapper.fromJsValue(data)
        ).asJava
      )

    DynamoDb
      .single(request)
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
      .single(request)
      .map(_.getAttributes.get("value"))
      .map(DynamoMapper.toJsValue)
      .map(Result.ok)
      .recover { case _: ConditionalCheckFailedException => Result.error[JsValue](s"error.data.missing") }
  }

  override def deleteAll(patterns: Seq[String]): F[Result[Done]] = {
    IzanamiLogger.debug(s"Dynamo query on $tableName and store $storeName deleteAll with patterns : $patterns")

    getByIdLike(patterns)
      .mapAsync(10) { case (id, _) => deleteF(id) }
      .runWith(Sink.ignore)
      .toF
      .map(_ => Result.ok(Done.done()))
  }

  override def getByIdLike(patterns: Seq[String]): Source[(Key, JsValue), NotUsed] = {
    IzanamiLogger.debug(s"Dynamo query on $tableName and store $storeName getByIdLike with patterns : $patterns")
    findKeys(patterns)
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
      .single(request)
      .toF
      .map(r => Option(r.getItem).map(_.get("value").getM).map(DynamoMapper.toJsValue))
  }

  override def getByIdLike(patterns: Seq[String], page: Int, nbElementPerPage: Int): F[PagingResult[JsValue]] = {
    IzanamiLogger.debug(
      s"Dynamo query on $tableName and store $storeName getByIdLike with patterns : $patterns, page $page, $nbElementPerPage elements by page"
    )

    val position = (page - 1) * nbElementPerPage
    findKeys(patterns)
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

  private def findKeys(patterns: Seq[String]): Source[(Key, JsValue), NotUsed] = patterns match {
    case p if p.isEmpty || p.contains("") => Source.empty
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
        .mapConcat(_.getItems.asScala.toList)
        .map(item => Key(item.get("id").getS) -> DynamoMapper.toJsValue(item.get("value").getM))
        .filter(_._1.matchPatterns(patterns: _*))
  }

  override def count(patterns: Seq[String]): F[Long] =
    countF(patterns).toF

  private def countF(patterns: Seq[String]): Future[Long] = {
    IzanamiLogger.debug(s"Dynamo query on $tableName and store $storeName count with patterns : $patterns")
    findKeys(patterns)
      .runFold(0L) { (acc, _) =>
        acc + 1
      }
  }
}
