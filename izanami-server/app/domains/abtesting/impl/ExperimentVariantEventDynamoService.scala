package domains.abtesting.impl

import java.time.LocalDateTime
import java.time.temporal.ChronoUnit

import akka.stream.ActorMaterializer
import akka.actor.ActorSystem
import akka.stream.scaladsl.{Flow, Sink, Source}
import akka.stream.alpakka.dynamodb._
import akka.stream.alpakka.dynamodb.AwsOp._
import akka.stream.alpakka.dynamodb.scaladsl.DynamoDb
import akka.{Done, NotUsed}
import cats.effect.Effect
import domains.abtesting._
import domains.events.EventStore
import env.DynamoConfig
import store.Result.Result

import scala.concurrent.ExecutionContext
import com.amazonaws.services.dynamodbv2.model.{AttributeValue, _}
import domains.Key
import domains.abtesting.Experiment.ExperimentKey
import domains.abtesting.ExperimentVariantEvent.eventAggregation
import domains.events.Events.{ExperimentVariantEventCreated, ExperimentVariantEventsDeleted}
import libs.dynamo.DynamoMapper
import libs.logs.IzanamiLogger
import store.Result

import scala.collection.JavaConverters._
import scala.collection.immutable

object ExperimentVariantEventDynamoService {
  def apply[F[_]: Effect](config: DynamoConfig, client: DynamoClient, eventStore: EventStore[F])(
      implicit system: ActorSystem
  ): ExperimentVariantEventDynamoService[F] =
    new ExperimentVariantEventDynamoService(config.eventsTableName, eventStore)
}

class ExperimentVariantEventDynamoService[F[_]: Effect](tableName: String, eventStore: EventStore[F])(
    implicit actorSystem: ActorSystem
) extends ExperimentVariantEventService[F] {
  import cats.implicits._
  import libs.effects._
  import ExperimentVariantEventInstances._

  private implicit val ec: ExecutionContext   = actorSystem.dispatcher
  private implicit val mat: ActorMaterializer = ActorMaterializer()(actorSystem)

  override def create(
      id: ExperimentVariantEventKey,
      data: ExperimentVariantEvent
  ): F[Result[ExperimentVariantEvent]] = {
    IzanamiLogger.debug(s"Dynamo create on $tableName with id : $id and data : $data")
    val key: String =
      s"${id.experimentId.key}:${id.variantId}"

    val jsValue = ExperimentVariantEventInstances.format.writes(data)

    val request = new UpdateItemRequest()
      .withTableName(tableName)
      .withKey(
        Map(
          "experimentId" -> new AttributeValue().withS(id.experimentId.key),
          "variantId"    -> new AttributeValue().withS(key)
        ).asJava
      )
      .withUpdateExpression("SET #events = list_append(if_not_exists(#events, :empty), :event)")
      .withExpressionAttributeNames(Map("#events" -> "events").asJava)
      .withExpressionAttributeValues(
        Map(
          ":event" -> new AttributeValue().withL(DynamoMapper.fromJsValue(jsValue)),
          ":empty" -> new AttributeValue().withL()
        ).asJava
      )

    for {
      res <- DynamoDb
              .single(request)
              .map(_ => Result.ok(data))
              .toF
      _ <- eventStore.publish(ExperimentVariantEventCreated(id, data))
    } yield res

  }

  override def deleteEventsForExperiment(
      experiment: Experiment
  ): F[Result[Done]] = {
    IzanamiLogger.debug(s"Dynamo delete events on $tableName with experiment $experiment")

    val delete = Flow[ExperimentVariantEventKey]
      .map(variantId => {
        new DeleteItemRequest()
          .withTableName(tableName)
          .withKey(
            Map(
              "experimentId" -> new AttributeValue().withS(experiment.id.key),
              "variantId"    -> new AttributeValue().withS(variantId.variantId)
            ).asJava
          )
      })
      .map(DeleteItem)
      .via(DynamoDb.flow[DeleteItem])

    val deletes = findExperimentVariantEvents(experiment)
      .map(_._2)
      .via(delete)
      .runWith(Sink.ignore)
      .toF
      .map(_ => Result.ok(Done))

    for {
      r <- deletes
      _ <- eventStore.publish(ExperimentVariantEventsDeleted(experiment))
    } yield r
  }

  def findExperimentVariantEvents(
      experiment: Experiment
  ): Source[(ExperimentKey, ExperimentVariantEventKey, List[ExperimentVariantEvent]), NotUsed] = {
    IzanamiLogger.debug(s"Dynamo find events on $tableName with experiment $experiment")

    val request = new QueryRequest()
      .withTableName(tableName)
      .withKeyConditions(
        Map(
          "experimentId" -> new Condition()
            .withComparisonOperator(ComparisonOperator.EQ)
            .withAttributeValueList(new AttributeValue().withS(experiment.id.key))
        ).asJava
      )

    DynamoDb
      .source(request)
      .mapConcat(_.getItems.asScala.toList)
      .map(item => {
        val variantId: ExperimentVariantEventKey = ExperimentVariantEventKey(Key(item.get("variantId").getS))
        val events: List[ExperimentVariantEvent] = item
          .get("events")
          .getL
          .asScala
          .map(DynamoMapper.toJsValue)
          .toList
          .map(_.validate[ExperimentVariantEvent].asOpt)
          .collect { case Some(e) => e }
        (variantId.experimentId, variantId, events)
      })
  }

  override def findVariantResult(
      experiment: Experiment
  ): Source[VariantResult, NotUsed] = {
    IzanamiLogger.debug(s"Dynamo find variant result on $tableName with experiment $experiment")

    findExperimentVariantEvents(experiment)
      .flatMapMerge(
        4, {
          case (_, _, evts) =>
            val first = evts.headOption
            val interval = first
              .map(e => ExperimentVariantEvent.calcInterval(e.date, LocalDateTime.now()))
              .getOrElse(ChronoUnit.HOURS)
            Source(evts)
              .via(eventAggregation(experiment.id.key, experiment.variants.size, interval))
        }
      )
  }

  override def listAll(
      patterns: Seq[String]
  ): Source[ExperimentVariantEvent, NotUsed] = {
    IzanamiLogger.debug(s"Dynamo listAll on $tableName with patterns $patterns")

    val request = new ScanRequest()
      .withTableName(tableName)

    DynamoDb
      .source(request)
      .mapConcat(_.getItems.asScala.toList)
      .map(item => Key(item.get("variantId").getS) -> item.get("events").getL.asScala.map(DynamoMapper.toJsValue))
      .filter(_._1.matchPatterns(patterns: _*))
      .mapConcat(_._2.toList)
      .map(_.validate[ExperimentVariantEvent].get)
  }

  override def check(): F[Unit] = {
    IzanamiLogger.debug(s"Dynamo check on $tableName")

    val request = new QueryRequest()
      .withTableName(tableName)
      .withLimit(0)

    DynamoDb
      .source(request)
      .runWith(Sink.head)
      .map(_ => ())
      .toF
  }
}
