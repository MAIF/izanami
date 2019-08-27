package domains.abtesting.impl
import java.time.LocalDateTime
import java.time.temporal.ChronoUnit

import akka.{Done, NotUsed}
import akka.stream.scaladsl.Source
import cats.effect.{ConcurrentEffect, ContextShift}
import domains.abtesting._
import domains.events.EventStore
import doobie.util.fragment.Fragment
import env.{DbDomainConfig, PostgresqlConfig}
import store.Result.{IzanamiErrors, Result}
import store.postgresql.{PgData, PostgresqlClient}
import cats.effect.implicits._
import cats.implicits._
import doobie.implicits._
import fs2.Stream
import libs.logs.IzanamiLogger
import play.api.libs.json.JsValue
import store.Result
import zio.{RIO, Task, ZIO}
import libs.logs.Logger
import domains.AuthInfo
import domains.events.Events.ExperimentVariantEventCreated
import domains.events.Events.ExperimentVariantEventsDeleted

object ExperimentVariantEventPostgresqlService {
  def apply(
      config: PostgresqlConfig,
      client: PostgresqlClient,
      domainConfig: DbDomainConfig,
  ): ExperimentVariantEventPostgresqlService =
    new ExperimentVariantEventPostgresqlService(client, config, domainConfig)
}

class ExperimentVariantEventPostgresqlService(client: PostgresqlClient,
                                              config: PostgresqlConfig,
                                              domainConfig: DbDomainConfig)
    extends ExperimentVariantEventService {

  import zio.interop.catz._
  import PgData._
  private val xa            = client.transactor
  private val tableName     = domainConfig.conf.namespace.replaceAll(":", "_")
  private val fragTableName = Fragment.const(tableName)

  override def start: RIO[ExperimentVariantEventServiceModule, Unit] = {
    val createTableScript = (sql"create table if not exists " ++ fragTableName ++ sql""" (
         id varchar(500) primary key,
         created timestamp not null default now(),
         experiment_id varchar(500) not null,
         variant_id varchar(500) not null,
         payload jsonb not null
       )""")

    val experimentIdScript = (sql"CREATE INDEX IF NOT EXISTS " ++
    Fragment.const(s"${tableName}_experiment_id_idx ") ++ fr" ON " ++ fragTableName ++ fr" (experiment_id)")

    val variantIdScript = (sql"CREATE INDEX IF NOT EXISTS " ++
    Fragment.const(s"${tableName}_variant_id_idx ") ++ fr" ON " ++ fragTableName ++ fr" (variant_id)")

    val createdScript = (sql"CREATE INDEX IF NOT EXISTS " ++
    Fragment.const(s"${tableName}_created_idx ") ++ fr" ON " ++ fragTableName ++ fr" (created)")

    import cats.effect.implicits._
    Logger.debug(s"Applying script $createTableScript") *>
    Logger.debug(s"Applying script $experimentIdScript") *>
    Logger.debug(s"Applying script $variantIdScript") *>
    Logger.debug(s"Applying script $createdScript") *>
    (createTableScript.update.run *>
    experimentIdScript.update.run *>
    variantIdScript.update.run *>
    createdScript.update.run)
      .transact(xa)
      .unit
  }

  override def create(
      id: ExperimentVariantEventKey,
      data: ExperimentVariantEvent
  ): ZIO[ExperimentVariantEventServiceModule, IzanamiErrors, ExperimentVariantEvent] = {
    val json = ExperimentVariantEventInstances.format.writes(data)
    (sql"insert into " ++ fragTableName ++ fr" (id, experiment_id, variant_id, payload) values (${id.key}, ${id.experimentId},  ${id.variantId}, $json)").update.run
      .transact(xa)
      .refineToOrDie[IzanamiErrors]
      .map { _ =>
        data
      } <* (AuthInfo.authInfo flatMap (
        authInfo =>
          EventStore.publish(
            ExperimentVariantEventCreated(id, data, authInfo = authInfo)
          )
    ))
  }
  override def deleteEventsForExperiment(
      experiment: Experiment
  ): ZIO[ExperimentVariantEventServiceModule, IzanamiErrors, Unit] =
    (sql"delete from " ++ fragTableName ++ fr" where experiment_id = ${experiment.id}").update.run
      .transact(xa)
      .refineToOrDie[IzanamiErrors]
      .unit <* (AuthInfo.authInfo flatMap (
        authInfo => EventStore.publish(ExperimentVariantEventsDeleted(experiment, authInfo = authInfo))
    ))

  override def findVariantResult(
      experiment: Experiment
  ): RIO[ExperimentVariantEventServiceModule, Source[VariantResult, NotUsed]] =
    ZIO.runtime[ExperimentVariantEventServiceModule].map { implicit runtime =>
      import streamz.converter._
      import zio.interop.catz._
      Source(experiment.variants.toList)
        .flatMapMerge(
          4, { v =>
            Source
              .fromFuture(runtime.unsafeRunToFuture(firstEvent(experiment.id.key, v.id)))
              .flatMapConcat { mayBeEvent =>
                val interval = mayBeEvent
                  .map(e => ExperimentVariantEvent.calcInterval(e.date, LocalDateTime.now()))
                  .getOrElse(ChronoUnit.HOURS)
                Source
                  .fromGraph(findEvents(experiment.id.key, v).toSource)
                  .via(ExperimentVariantEvent.eventAggregation(experiment.id.key, experiment.variants.size, interval))
              }

          }
        )
    }
  private def findEvents(experimentId: String, variant: Variant): Stream[Task, ExperimentVariantEvent] =
    (sql"select payload from " ++ fragTableName ++ fr" where variant_id = ${variant.id} and experiment_id = $experimentId order by created")
      .query[JsValue]
      .stream
      .transact(xa)
      .flatMap { json =>
        ExperimentVariantEventInstances.format
          .reads(json)
          .fold(
            { err =>
              IzanamiLogger.error(s"Error reading json $json : $err")
              Stream.empty
            }, { ok =>
              Stream(ok)
            }
          )
      }

  private def firstEvent(experimentId: String, variant: String): Task[Option[ExperimentVariantEvent]] =
    (sql"select payload from " ++ fragTableName ++
    fr" where variant_id = ${variant} and experiment_id = $experimentId order by created asc limit 1")
      .query[JsValue]
      .option
      .map(
        _.flatMap(
          json =>
            ExperimentVariantEventInstances.format
              .reads(json)
              .fold(
                { err =>
                  IzanamiLogger.error(s"Error reading json $json : $err")
                  None
                }, { ok =>
                  Some(ok)
                }
            )
        )
      )
      .transact(xa)

  override def listAll(
      patterns: Seq[String]
  ): RIO[ExperimentVariantEventServiceModule, Source[ExperimentVariantEvent, NotUsed]] =
    ZIO.runtime[ExperimentVariantEventServiceModule].map { implicit runtime =>
      import streamz.converter._
      import zio.interop.catz._
      Source.fromGraph(
        (sql"select payload from " ++ fragTableName)
          .query[JsValue]
          .stream
          .transact(xa)
          .map {
            ExperimentVariantEventInstances.format.reads
          }
          .flatMap { json =>
            json.fold(
              { err =>
                IzanamiLogger.error(s"Error reading json $json : $err")
                Stream.empty
              }, { ok =>
                Stream(ok)
              }
            )
          }
          .toSource
      )
    }

  override def check(): Task[Unit] =
    (sql"select id from " ++ fragTableName ++ fr" where id = 'test' LIMIT 1")
      .query[String]
      .option
      .transact(xa)
      .map(_.fold(())(_ => ()))
}
