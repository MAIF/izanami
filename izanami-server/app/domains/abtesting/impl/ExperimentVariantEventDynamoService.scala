package domains.abtesting.impl

import java.time.LocalDateTime
import java.time.temporal.ChronoUnit

import akka.stream.ActorMaterializer
import akka.actor.ActorSystem
import akka.stream.scaladsl.{Flow, Sink, Source}
import akka.stream.alpakka.dynamodb._
import akka.stream.alpakka.dynamodb.AwsOp._
import akka.stream.alpakka.dynamodb.scaladsl.DynamoDb
import akka.{NotUsed}
import domains.abtesting._
import domains.events.EventStore
import env.DynamoConfig
import store.Result.IzanamiErrors

import com.amazonaws.services.dynamodbv2.model.{AttributeValue, _}
import domains.Key
import domains.abtesting.Experiment.ExperimentKey
import domains.abtesting.ExperimentVariantEvent.eventAggregation
import domains.events.Events.{ExperimentVariantEventCreated, ExperimentVariantEventsDeleted}
import libs.dynamo.DynamoMapper
import libs.logs.IzanamiLogger
import zio.{RIO, Task, ZIO}

import scala.jdk.CollectionConverters._
import libs.logs.Logger
import domains.AuthInfo

object ExperimentVariantEventDynamoService {
  def apply(config: DynamoConfig, client: DynamoClient)(
      implicit system: ActorSystem
  ): ExperimentVariantEventDynamoService =
    new ExperimentVariantEventDynamoService(client, config.eventsTableName)
}

class ExperimentVariantEventDynamoService(client: DynamoClient, tableName: String)(
    implicit actorSystem: ActorSystem
) extends ExperimentVariantEventService {
  import ExperimentVariantEventInstances._

  actorSystem.dispatcher
  private implicit val mat: ActorMaterializer = ActorMaterializer()(actorSystem)

  override def create(
      id: ExperimentVariantEventKey,
      data: ExperimentVariantEvent
  ): ZIO[ExperimentVariantEventServiceModule, IzanamiErrors, ExperimentVariantEvent] = {
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
      _ <- Logger.debug(s"Dynamo create on $tableName with id : $id and data : $data")
      res <- ZIO
              .fromFuture { _ =>
                DynamoDb
                  .source(request)
                  .withAttributes(DynamoAttributes.client(client))
                  .runWith(Sink.head)
              }
              .map(_ => data)
              .refineToOrDie[IzanamiErrors]
      authInfo <- AuthInfo.authInfo
      _        <- EventStore.publish(ExperimentVariantEventCreated(id, data, authInfo = authInfo))
    } yield res

  }

  override def deleteEventsForExperiment(
      experiment: Experiment
  ): ZIO[ExperimentVariantEventServiceModule, IzanamiErrors, Unit] = {

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
      .via(DynamoDb.flow[DeleteItem].withAttributes(DynamoAttributes.client(client)))

    val deletes = ZIO
      .fromFuture { _ =>
        findExperimentVariantEvents(experiment)
          .map(_._2)
          .via(delete)
          .runWith(Sink.ignore)
      }
      .refineToOrDie[IzanamiErrors]
      .unit

    for {
      _        <- Logger.debug(s"Dynamo delete events on $tableName with experiment $experiment")
      r        <- deletes
      authInfo <- AuthInfo.authInfo
      _        <- EventStore.publish(ExperimentVariantEventsDeleted(experiment, authInfo = authInfo))
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
      .withAttributes(DynamoAttributes.client(client))
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
  ): RIO[ExperimentVariantEventServiceModule, Source[VariantResult, NotUsed]] =
    Logger.debug(s"Dynamo find variant result on $tableName with experiment $experiment") *>
    Task(
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
    )

  override def listAll(
      patterns: Seq[String]
  ): RIO[ExperimentVariantEventServiceModule, Source[ExperimentVariantEvent, NotUsed]] = {

    val request = new ScanRequest()
      .withTableName(tableName)

    Logger.debug(s"Dynamo listAll on $tableName with patterns $patterns") *>
    Task(
      DynamoDb
        .source(request)
        .withAttributes(DynamoAttributes.client(client))
        .mapConcat(_.getItems.asScala.toList)
        .map(item => Key(item.get("variantId").getS) -> item.get("events").getL.asScala.map(DynamoMapper.toJsValue))
        .filter(_._1.matchAllPatterns(patterns: _*))
        .mapConcat(_._2.toList)
        .map(_.validate[ExperimentVariantEvent].get)
    )
  }

  override def check(): RIO[ExperimentVariantEventServiceModule, Unit] = {
    val request = new QueryRequest()
      .withTableName(tableName)
      .withKeyConditions(
        Map(
          "experimentId" -> new Condition()
            .withComparisonOperator(ComparisonOperator.EQ)
            .withAttributeValueList(new AttributeValue().withS("dummyvalue"))
        ).asJava
      )
      .withLimit(1)

    Logger.debug(s"Dynamo check on $tableName") *>
    ZIO.fromFuture { _ =>
      DynamoDb
        .source(request)
        .withAttributes(DynamoAttributes.client(client))
        .runWith(Sink.head)
    }.unit
  }
}
