package domains.abtesting.impl

import akka.actor.ActorSystem
import akka.stream.ActorMaterializer
import akka.stream.alpakka.cassandra.scaladsl.CassandraSource
import akka.stream.scaladsl.Source
import akka.{Done, NotUsed}
import cats.effect.Effect
import com.datastax.driver.core.{Cluster, Session, SimpleStatement}
import domains.abtesting._
import domains.events.EventStore
import env.{CassandraConfig, DbDomainConfig}
import play.api.Logger
import play.api.libs.json._
import store.Result.Result
import store.cassandra.Cassandra
import store.Result

import scala.concurrent.Future

//////////////////////////////////////////////////////////////////////////////////////////
////////////////////////////////////    CASSANDRA     ////////////////////////////////////
//////////////////////////////////////////////////////////////////////////////////////////

object ExperimentVariantEventCassandreStore {
  def apply[F[_]: Effect](session: Session,
                          config: DbDomainConfig,
                          cassandraConfig: CassandraConfig,
                          eventStore: EventStore[F],
                          actorSystem: ActorSystem): ExperimentVariantEventCassandreStore[F] =
    new ExperimentVariantEventCassandreStore(session, config, cassandraConfig, eventStore, actorSystem)
}

class ExperimentVariantEventCassandreStore[F[_]: Effect](session: Session,
                                                         config: DbDomainConfig,
                                                         cassandraConfig: CassandraConfig,
                                                         eventStore: EventStore[F],
                                                         actorSystem: ActorSystem)
    extends ExperimentVariantEventStore[F] {

  private val namespaceFormatted = config.conf.namespace.replaceAll(":", "_")
  private val keyspace           = cassandraConfig.keyspace
  import Cassandra._
  import cats.implicits._
  import cats.effect.implicits._
  import domains.events.Events._

  implicit private val s    = actorSystem
  implicit private val mat  = ActorMaterializer()
  implicit private val sess = session
  implicit private val es   = eventStore

  import actorSystem.dispatcher

  Logger.info(s"Creating table ${keyspace}.$namespaceFormatted if not exists")

  //Events table
  session
    .execute(
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

  //Won table
  session
    .execute(
      s"""
         | CREATE TABLE IF NOT EXISTS ${keyspace}.${namespaceFormatted}_won
         |  (counter_value counter,
         |  experimentId text,
         |  variantId text,
         |  PRIMARY KEY (experimentId, variantId)
         |)
         | """.stripMargin
    )

  //Displayed table
  session
    .execute(
      s"""
         | CREATE TABLE IF NOT EXISTS ${keyspace}.${namespaceFormatted}_displayed
         |  (counter_value counter,
         |  experimentId text,
         |  variantId text,
         |  PRIMARY KEY (experimentId, variantId)
         |)
         | """.stripMargin
    )

  private def incrWon(experimentId: String, variantId: String): F[Done] =
    executeWithSession(
      s"UPDATE ${keyspace}.${namespaceFormatted}_won SET counter_value = counter_value + 1 WHERE experimentId = ? AND variantId = ? ",
      experimentId,
      variantId
    ).map(_ => Done)

  private def getWon(experimentId: String, variantId: String): F[Long] =
    executeWithSession(
      s"SELECT counter_value FROM ${keyspace}.${namespaceFormatted}_won WHERE experimentId = ? AND variantId = ? ",
      experimentId,
      variantId
    ).map(
      rs =>
        Option(rs.one())
          .flatMap(o => Option(o.getLong("counter_value")))
          .getOrElse(0)
    )

  private def incrAndGetWon(experimentId: String, variantId: String): F[Long] =
    incrWon(experimentId, variantId).flatMap(_ => getWon(experimentId, variantId))

  private def incrDisplayed(experimentId: String, variantId: String): F[Done] =
    executeWithSession(
      s"UPDATE ${keyspace}.${namespaceFormatted}_displayed SET counter_value = counter_value + 1 WHERE experimentId = ? AND variantId = ? ",
      experimentId,
      variantId
    ).map(_ => Done)

  private def getDisplayed(experimentId: String, variantId: String): F[Long] =
    executeWithSession(
      s"SELECT counter_value FROM ${keyspace}.${namespaceFormatted}_displayed WHERE experimentId = ? AND variantId = ? ",
      experimentId,
      variantId
    ).map(
      rs =>
        Option(rs.one())
          .flatMap(o => Option(o.getLong("counter_value")))
          .getOrElse(0)
    )

  private def incrAndGetDisplayed(experimentId: String, variantId: String): F[Long] =
    incrDisplayed(experimentId, variantId).flatMap(_ => getDisplayed(experimentId, variantId))

  private def saveToCassandra(id: ExperimentVariantEventKey, data: ExperimentVariantEvent) = {
    val query =
      s"INSERT INTO ${keyspace}.$namespaceFormatted (experimentId, variantId, clientId, namespace, id, value) values (?, ?, ?, ?, ?, ?) IF NOT EXISTS "
    Logger.debug(s"Running query $query")
    executeWithSession(
      query,
      id.experimentId.key,
      id.variantId,
      id.clientId,
      id.namespace,
      id.id,
      Json.stringify(ExperimentVariantEventInstances.format.writes(data))
    ).map(_ => Result.ok(data))
  }

  override def create(id: ExperimentVariantEventKey, data: ExperimentVariantEvent): F[Result[ExperimentVariantEvent]] =
    data match {
      case e: ExperimentVariantDisplayed =>
        for {
          displayed <- incrAndGetDisplayed(id.experimentId.key, id.variantId) // increment display counter
          won       <- getWon(id.experimentId.key, id.variantId)              // get won counter
          transformation = if (displayed != 0) (won * 100.0) / displayed
          else 0.0
          toSave = e.copy(transformation = transformation)
          result <- saveToCassandra(id, toSave) // add event
          _      <- result.traverse(e => eventStore.publish(ExperimentVariantEventCreated(id, e)))
        } yield result
      case e: ExperimentVariantWon =>
        for {
          won       <- incrAndGetWon(id.experimentId.key, id.variantId) // increment won counter
          displayed <- getDisplayed(id.experimentId.key, id.variantId)  // get display counter
          transformation = if (displayed != 0) (won * 100.0) / displayed
          else 0.0
          toSave = e.copy(transformation = transformation)
          result <- saveToCassandra(id, toSave) // add event
          _      <- result.traverse(e => eventStore.publish(ExperimentVariantEventCreated(id, e)))
        } yield result
    }

  override def deleteEventsForExperiment(experiment: Experiment): F[Result[Done]] =
    experiment.variants.toList
      .traverse { variant =>
        executeWithSession(s" DELETE FROM ${keyspace}.$namespaceFormatted  WHERE experimentId = ? AND variantId = ?",
                           experiment.id.key,
                           variant.id)
          .map { r =>
            Result.ok(r.asInstanceOf[Any])
          }
      }
      .map(r => Result.ok(Done))
      .flatMap { r =>
        r.traverse(e => eventStore.publish(ExperimentVariantEventsDeleted(experiment)))
      }

  def getVariantResult(experimentId: String, variant: Variant): Source[VariantResult, NotUsed] = {
    val variantId: String = variant.id
    val events: Source[Seq[ExperimentVariantEvent], NotUsed] = CassandraSource(
      new SimpleStatement(
        s"SELECT value FROM ${keyspace}.$namespaceFormatted WHERE experimentId = ? and variantId = ? ",
        experimentId,
        variantId
      )
    ).map(r => r.getString("value"))
      .map(Json.parse)
      .mapConcat(ExperimentVariantEventInstances.format.reads(_).asOpt.toList)
      .fold(Seq.empty[ExperimentVariantEvent])(_ :+ _)

    val won: Source[Long, NotUsed] =
      Source.fromFuture(getWon(experimentId, variantId).toIO.unsafeToFuture())
    val displayed: Source[Long, NotUsed] =
      Source.fromFuture(getDisplayed(experimentId, variantId).toIO.unsafeToFuture())

    events.zip(won).zip(displayed).map {
      case ((e, w), d) =>
        VariantResult(
          variant = Some(variant),
          displayed = d,
          won = w,
          transformation = if (d != 0) (w * 100.0) / d else 0.0,
          events = e
        )
    }
  }

  override def findVariantResult(experiment: Experiment): Source[VariantResult, NotUsed] =
    Source(experiment.variants.toList)
      .flatMapMerge(4, v => getVariantResult(experiment.id.key, v))

  override def listAll(patterns: Seq[String]) =
    CassandraSource(
      new SimpleStatement(
        s"SELECT value FROM ${keyspace}.$namespaceFormatted "
      )
    ).map(r => r.getString("value"))
      .map(Json.parse)
      .mapConcat(ExperimentVariantEventInstances.format.reads(_).asOpt.toList)
      .filter(e => e.id.key.matchPatterns(patterns: _*))

  override def check(): F[Unit] = executeWithSession("SELECT now() FROM system.local").map(_ => ())

}
