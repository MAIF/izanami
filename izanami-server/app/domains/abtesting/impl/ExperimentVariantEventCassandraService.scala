package domains.abtesting.impl

import java.time.LocalDateTime

import akka.actor.ActorSystem
import akka.stream.ActorMaterializer
import akka.stream.alpakka.cassandra.scaladsl.CassandraSource
import akka.stream.scaladsl.{Flow, Source}
import akka.NotUsed
import com.datastax.driver.core.{Row, Session, SimpleStatement}
import domains.abtesting.ExperimentVariantEvent.eventAggregation
import domains.abtesting._
import domains.events.EventStore
import env.{CassandraConfig, DbDomainConfig}
import libs.logs.IzanamiLogger
import play.api.libs.json._
import store.Result.IzanamiErrors
import store.cassandra.Cassandra
import zio.{RIO, Task, ZIO}
import libs.logs.Logger
import domains.AuthInfo

//////////////////////////////////////////////////////////////////////////////////////////
////////////////////////////////////    CASSANDRA     ////////////////////////////////////
//////////////////////////////////////////////////////////////////////////////////////////

object ExperimentVariantEventCassandraService {
  def apply(
      session: Session,
      config: DbDomainConfig,
      cassandraConfig: CassandraConfig
  )(implicit actorSystem: ActorSystem): ExperimentVariantEventCassandraService =
    new ExperimentVariantEventCassandraService(session, config, cassandraConfig)
}

class ExperimentVariantEventCassandraService(session: Session, config: DbDomainConfig, cassandraConfig: CassandraConfig)(
    implicit actorSystem: ActorSystem
) extends ExperimentVariantEventService {

  private val namespaceFormatted = config.conf.namespace.replaceAll(":", "_")
  private val keyspace           = cassandraConfig.keyspace
  import Cassandra._
  import cats.implicits._
  import domains.events.Events._

  ActorMaterializer()
  implicit private val sess = session

  //Events table
  override def start: RIO[ExperimentVariantEventServiceModule, Unit] =
    Logger.info(s"Creating table ${keyspace}.$namespaceFormatted if not exists") *>
    Task(
      session.execute(
        s"""
        | CREATE TABLE IF NOT EXISTS ${keyspace}.$namespaceFormatted (
        |   experimentId text,
        |   variantId text,
        |   clientId text,
        |   namespace text,
        |   id text,
        |   value text,
        |   PRIMARY KEY ((experimentId, variantId), clientId, namespace, id)
        | )
        | """.stripMargin
      )
    )

  private def saveToCassandra(
      id: ExperimentVariantEventKey,
      data: ExperimentVariantEvent
  ): ZIO[ExperimentVariantEventServiceModule, IzanamiErrors, ExperimentVariantEvent] = {
    val query =
      s"INSERT INTO ${keyspace}.$namespaceFormatted (experimentId, variantId, clientId, namespace, id, value) values (?, ?, ?, ?, ?, ?) IF NOT EXISTS "

    executeWithSessionT(
      query,
      id.experimentId.key,
      id.variantId,
      id.clientId,
      id.namespace,
      id.id,
      Json.stringify(ExperimentVariantEventInstances.format.writes(data))
    ).map(_ => data)
      .refineToOrDie[IzanamiErrors]
  }

  override def create(
      id: ExperimentVariantEventKey,
      data: ExperimentVariantEvent
  ): ZIO[ExperimentVariantEventServiceModule, IzanamiErrors, ExperimentVariantEvent] =
    for {
      result   <- saveToCassandra(id, data) // add event
      authInfo <- AuthInfo.authInfo
      _        <- EventStore.publish(ExperimentVariantEventCreated(id, result, authInfo = authInfo))
      _        <- Logger.debug(s"Result $result")
    } yield result

  override def deleteEventsForExperiment(
      experiment: Experiment
  ): ZIO[ExperimentVariantEventServiceModule, IzanamiErrors, Unit] =
    for {
      runtime <- ZIO.runtime[ExperimentVariantEventServiceModule]
      res <- {
        implicit val r = runtime
        import zio.interop.catz._
        experiment.variants.toList
          .traverse { variant =>
            executeWithSessionT(
              s" DELETE FROM ${keyspace}.$namespaceFormatted  WHERE experimentId = ? AND variantId = ?",
              experiment.id.key,
              variant.id
            )
          }
          .unit
          .refineToOrDie[IzanamiErrors]
      }
      authInfo <- AuthInfo.authInfo
      _        <- EventStore.publish(ExperimentVariantEventsDeleted(experiment, authInfo = authInfo))
    } yield res

  def getVariantResult(experiment: Experiment, variant: Variant): Source[VariantResult, NotUsed] = {
    val variantId: String = variant.id
    firstEvent(experiment, variantId)
      .flatMapConcat { first =>
        val interval = ExperimentVariantEvent.calcInterval(first.date, LocalDateTime.now())
        CassandraSource(
          new SimpleStatement(
            s"SELECT value FROM ${keyspace}.$namespaceFormatted WHERE experimentId = ? and variantId = ? ",
            experiment.id.key,
            variantId
          )
        ).via(readValue)
          .via(eventAggregation(experiment.id.key, experiment.variants.size, interval))
      }
  }

  private def firstEvent(experiment: Experiment, variantId: String): Source[ExperimentVariantEvent, NotUsed] =
    CassandraSource(
      new SimpleStatement(
        s"SELECT value FROM ${keyspace}.$namespaceFormatted WHERE experimentId = ? and variantId = ? limit 1",
        experiment.id.key,
        variantId
      )
    ).take(1)
      .via(readValue)

  override def findVariantResult(
      experiment: Experiment
  ): RIO[ExperimentVariantEventServiceModule, Source[VariantResult, NotUsed]] =
    Task(
      Source(experiment.variants.toList).flatMapMerge(4, v => getVariantResult(experiment, v))
    )

  override def listAll(
      patterns: Seq[String]
  ): RIO[ExperimentVariantEventServiceModule, Source[ExperimentVariantEvent, NotUsed]] = {
    val query = s"SELECT value FROM ${keyspace}.$namespaceFormatted "
    Logger.debug(s"Running query $query") *>
    Task(
      CassandraSource(new SimpleStatement(query).setFetchSize(200))
        .via(readValue)
        .filter(e => e.id.key.matchAllPatterns(patterns: _*))
    )
  }

  override def check(): RIO[ExperimentVariantEventServiceModule, Unit] =
    executeWithSessionT("SELECT now() FROM system.local").map(_ => ())

  private val readValue: Flow[Row, ExperimentVariantEvent, NotUsed] =
    Flow[Row]
      .map(r => r.getString("value"))
      .map(Json.parse)
      .mapConcat { json =>
        ExperimentVariantEventInstances.format
          .reads(json)
          .fold(
            { err =>
              IzanamiLogger.error(s"Error parsing json $json: $err")
              List.empty
            }, { ok =>
              List(ok)
            }
          )
      }

}
