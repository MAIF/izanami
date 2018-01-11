package domains.abtesting.impl

import akka.actor.ActorSystem
import akka.stream.ActorMaterializer
import akka.stream.alpakka.cassandra.scaladsl.CassandraSource
import akka.stream.scaladsl.Source
import akka.{Done, NotUsed}
import com.datastax.driver.core.{Cluster, Session, SimpleStatement}
import domains.abtesting._
import domains.events.EventStore
import env.{CassandraConfig, DbDomainConfig}
import play.api.Logger
import play.api.libs.json._
import store.Result.Result
import store.cassandra.Cassandra
import store.{FindResult, Result, SourceFindResult}

import scala.concurrent.Future

//////////////////////////////////////////////////////////////////////////////////////////
////////////////////////////////////    CASSANDRA     ////////////////////////////////////
//////////////////////////////////////////////////////////////////////////////////////////

object ExperimentVariantEventCassandreStore {
  def apply(maybeCluster: Cluster,
            config: DbDomainConfig,
            cassandraConfig: CassandraConfig,
            eventStore: EventStore,
            actorSystem: ActorSystem): ExperimentVariantEventCassandreStore =
    new ExperimentVariantEventCassandreStore(maybeCluster, config, cassandraConfig, eventStore, actorSystem)
}

class ExperimentVariantEventCassandreStore(cluster: Cluster,
                                           config: DbDomainConfig,
                                           cassandraConfig: CassandraConfig,
                                           eventStore: EventStore,
                                           actorSystem: ActorSystem)
    extends ExperimentVariantEventStore {

  private val namespaceFormatted = config.conf.namespace.replaceAll(":", "_")
  private val keyspace           = cassandraConfig.keyspace
  import Cassandra._
  import domains.events.Events._

  implicit private val s   = actorSystem
  implicit private val mat = ActorMaterializer()
  implicit private val c   = cluster
  implicit private val es  = eventStore

  import actorSystem.dispatcher

  Logger.info(s"Creating table ${keyspace}.$namespaceFormatted if not exists")

  //Events table
  cluster
    .connect()
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
  cluster
    .connect()
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
  cluster
    .connect()
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

  private def incrWon(experimentId: String, variantId: String)(implicit session: Session): Future[Done] =
    executeWithSession(
      s"UPDATE ${keyspace}.${namespaceFormatted}_won SET counter_value = counter_value + 1 WHERE experimentId = ? AND variantId = ? ",
      experimentId,
      variantId
    ).map(_ => Done)

  private def getWon(experimentId: String, variantId: String)(implicit session: Session): Future[Long] =
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

  private def incrAndGetWon(experimentId: String, variantId: String)(implicit session: Session): Future[Long] =
    incrWon(experimentId, variantId).flatMap(_ => getWon(experimentId, variantId))

  private def incrDisplayed(experimentId: String, variantId: String)(implicit session: Session): Future[Done] =
    executeWithSession(
      s"UPDATE ${keyspace}.${namespaceFormatted}_displayed SET counter_value = counter_value + 1 WHERE experimentId = ? AND variantId = ? ",
      experimentId,
      variantId
    ).map(_ => Done)

  private def getDisplayed(experimentId: String, variantId: String)(implicit session: Session): Future[Long] =
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

  private def incrAndGetDisplayed(experimentId: String, variantId: String)(implicit session: Session): Future[Long] =
    incrDisplayed(experimentId, variantId).flatMap(_ => getDisplayed(experimentId, variantId))

  private def saveToCassandra(id: ExperimentVariantEventKey,
                              data: ExperimentVariantEvent)(implicit session: Session) = {
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
      Json.stringify(Json.toJson(data))
    ).map(_ => Result.ok(data))
  }

  override def create(id: ExperimentVariantEventKey,
                      data: ExperimentVariantEvent): Future[Result[ExperimentVariantEvent]] =
    session()
      .flatMap { implicit session =>
        data match {
          case e: ExperimentVariantDisplayed =>
            for {
              displayed <- incrAndGetDisplayed(id.experimentId.key, id.variantId) // increment display counter
              won       <- getWon(id.experimentId.key, id.variantId)              // get won counter
              transformation = if (displayed != 0) (won * 100.0) / displayed
              else 0.0
              toSave = e.copy(transformation = transformation)
              result <- saveToCassandra(id, toSave) // add event
            } yield result
          case e: ExperimentVariantWon =>
            for {
              won       <- incrAndGetWon(id.experimentId.key, id.variantId) // increment won counter
              displayed <- getDisplayed(id.experimentId.key, id.variantId)  // get display counter
              transformation = if (displayed != 0) (won * 100.0) / displayed
              else 0.0
              toSave = e.copy(transformation = transformation)
              result <- saveToCassandra(id, toSave) // add event
            } yield result
        }
      }
      .andPublishEvent(e => ExperimentVariantEventCreated(id, e))

  override def deleteEventsForExperiment(experiment: Experiment): Future[Result[Done]] =
    session()
      .flatMap { implicit session =>
        Future.sequence(experiment.variants.map { variant =>
          executeWithSession(s" DELETE FROM ${keyspace}.$namespaceFormatted  WHERE experimentId = ? AND variantId = ?",
                             experiment.id.key,
                             variant.id)
            .map { r =>
              Result.ok(r.asInstanceOf[Any])
            }
        })
      }
      .map(r => Result.ok(Done))
      .andPublishEvent(e => ExperimentVariantEventsDeleted(experiment))

  def getVariantResult(experimentId: String,
                       variant: Variant)(implicit session: Session): Source[VariantResult, NotUsed] = {
    val variantId: String = variant.id
    val events: Source[Seq[ExperimentVariantEvent], NotUsed] = CassandraSource(
      new SimpleStatement(
        s"SELECT value FROM ${keyspace}.$namespaceFormatted WHERE experimentId = ? and variantId = ? ",
        experimentId,
        variantId
      )
    ).map(r => r.getString("value"))
      .map(Json.parse)
      .mapConcat(_.validate[ExperimentVariantEvent].asOpt.toList)
      .fold(Seq.empty[ExperimentVariantEvent])(_ :+ _)

    val won: Source[Long, NotUsed] =
      Source.fromFuture(getWon(experimentId, variantId))
    val displayed: Source[Long, NotUsed] =
      Source.fromFuture(getDisplayed(experimentId, variantId))

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

  override def findVariantResult(experiment: Experiment): FindResult[VariantResult] =
    SourceFindResult(Source.fromFuture(session()).flatMapConcat { implicit session =>
      Source(experiment.variants.toList)
        .flatMapMerge(4, v => getVariantResult(experiment.id.key, v))
    })

  override def listAll(patterns: Seq[String]) =
    Source
      .fromFuture(session())
      .flatMapConcat { implicit session =>
        CassandraSource(
          new SimpleStatement(
            s"SELECT value FROM ${keyspace}.$namespaceFormatted "
          )
        ).map(r => r.getString("value"))
          .map(Json.parse)
          .mapConcat(_.validate[ExperimentVariantEvent].asOpt.toList)
          .filter(e => e.id.key.matchPatterns(patterns: _*))
      }
}
