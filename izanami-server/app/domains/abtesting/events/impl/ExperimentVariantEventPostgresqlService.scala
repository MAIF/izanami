package domains.abtesting.events.impl

import akka.NotUsed
import akka.actor.ActorSystem
import akka.stream.scaladsl.Source
import cats.implicits._
import domains.abtesting._
import domains.abtesting.events._
import domains.auth.AuthInfo
import domains.configuration.PlayModule
import domains.errors.IzanamiErrors
import domains.events.EventStore
import domains.events.Events.{ExperimentVariantEventCreated, ExperimentVariantEventsDeleted}
import doobie.implicits._
import doobie.util.fragment.Fragment
import env.DbDomainConfig
import env.configuration.IzanamiConfigModule
import fs2.Stream
import libs.database.Drivers.PostgresDriver
import libs.logs.{IzanamiLogger, ZLogger}
import play.api.libs.json.JsValue
import store.datastore.DataStoreLayerContext
import store.postgresql.{PgData, PostgresqlClient}
import zio.{RIO, Task, ZIO, ZLayer}

import java.time.LocalDateTime
import java.time.temporal.ChronoUnit

object ExperimentVariantEventPostgresqlService {

  val live: ZLayer[PostgresDriver with DataStoreLayerContext, Throwable, ExperimentVariantEventService] =
    ZLayer.fromFunction { mix =>
      implicit val sys: ActorSystem = mix.get[PlayModule.Service].system
      val izanamiConfig             = mix.get[IzanamiConfigModule.Service].izanamiConfig
      val Some(client)              = mix.get[Option[PostgresqlClient]]
      ExperimentVariantEventPostgresqlService(client, izanamiConfig.experimentEvent.db)
    }

  def apply(
      client: PostgresqlClient,
      domainConfig: DbDomainConfig
  ): ExperimentVariantEventPostgresqlService =
    new ExperimentVariantEventPostgresqlService(client, domainConfig)
}

class ExperimentVariantEventPostgresqlService(client: PostgresqlClient, domainConfig: DbDomainConfig)
    extends ExperimentVariantEventService.Service {

  import PgData._
  import zio.interop.catz._
  private val xa            = client.transactor
  private val tableName     = domainConfig.conf.namespace.replaceAll(":", "_")
  private val fragTableName = Fragment.const(tableName)

  override def start: RIO[ExperimentVariantEventServiceContext, Unit] = {
    val createTableScript = (sql"create table if not exists " ++ fragTableName ++
      sql""" (
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

    ZLogger.debug(s"Applying script $createTableScript") *>
    ZLogger.debug(s"Applying script $experimentIdScript") *>
    ZLogger.debug(s"Applying script $variantIdScript") *>
    ZLogger.debug(s"Applying script $createdScript") *>
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
  ): ZIO[ExperimentVariantEventServiceContext, IzanamiErrors, ExperimentVariantEvent] = {
    val json = ExperimentVariantEventInstances.format.writes(data)
    (sql"insert into " ++ fragTableName ++ fr" (id, experiment_id, variant_id, payload) values (${id.key}, ${id.experimentId},  ${id.variantId}, $json)").update.run
      .transact(xa)
      .orDie
      .map { _ =>
        data
      } <* (AuthInfo.authInfo flatMap (authInfo =>
      EventStore.publish(
        ExperimentVariantEventCreated(id, data, authInfo = authInfo)
      )
    ))
  }

  override def deleteEventsForExperiment(
      experiment: Experiment
  ): ZIO[ExperimentVariantEventServiceContext, IzanamiErrors, Unit] =
    (sql"delete from " ++ fragTableName ++ fr" where experiment_id = ${experiment.id}").update.run
      .transact(xa)
      .orDie
      .unit <* (AuthInfo.authInfo flatMap (authInfo =>
      EventStore.publish(ExperimentVariantEventsDeleted(experiment, authInfo = authInfo))
    ))

  override def findVariantResult(
      experiment: Experiment
  ): RIO[ExperimentVariantEventServiceContext, Source[VariantResult, NotUsed]] =
    ZIO.runtime[ExperimentVariantEventServiceContext].map { implicit runtime =>
      import streamz.converter._
      import zio.interop.catz._

      Source
        .future(runtime.unsafeRunToFuture(firstEvent(experiment.id.key)))
        .flatMapConcat { mayBeEvent =>
          val interval = mayBeEvent
            .map(e => ExperimentVariantEvent.calcInterval(e.date, LocalDateTime.now()))
            .getOrElse(ChronoUnit.HOURS)
          Source.fromGraph(getExperimentResult(experiment.id.key, interval).toSource)
        }
    }

  private def getExperimentResult(experimentId: String, interval: ChronoUnit) = {
    val secInterval = interval.getDuration.toSeconds
    (sql"""with experiments_events as (select id, experiment_id, variant_id,
                                              payload ->> '@type' as event_type,
                                              (payload ->> 'date')::timestamp as event_date,
                                              payload ->> 'clientId' as client_id
                                       from """ ++ fragTableName ++ fr"""
                                       where experiment_id = $experimentId ),
                range_timestamp as (select min(event_date) as first_date,
                                           max(event_date) as last_date
                                    from experiments_events),
                series as (select generate_series(first_date,
                                  last_date + make_interval(secs => ${secInterval}),
                                  make_interval(secs => ${secInterval}))::timestamp as date
                           from range_timestamp),
                count_by_type as (select events.experiment_id, events.variant_id, date,
                                         (count(*) filter (where events.event_type = 'VariantWonEvent'))::double precision as count_won,
                                         (count(*) filter (where events.event_type = 'VariantDisplayedEvent'))::double precision as count_displayed,
                                         (min(events.id)) as id
                                  from series, experiments_events events
                                  where event_date between series.date and (series.date + make_interval(secs => ${secInterval}))
                                  group by series.date, events.experiment_id, events.variant_id),
                sum_by_type as (select id, experiment_id, variant_id,
                                       sum(count_won) over (PARTITION BY variant_id ORDER BY date) as won,
                                       sum(count_displayed) over (PARTITION BY variant_id ORDER BY date) as displayed
                                from count_by_type),
                variants_settings as (select id, variant_id,
                                             events.payload -> 'variant' as variant,
                                             events.payload -> 'date' as date
                                      from """ ++ fragTableName ++ fr""" as events
                                      where events.id IN (select distinct id from sum_by_type)),
                transformations as (select sum_by_type.id, experiment_id, sum_by_type.variant_id, date,
                                           case when displayed = 0 then 0 else won * 100 / displayed end as transformation,
                                           variant
                                    from sum_by_type, variants_settings
                                    where sum_by_type.id = variants_settings.id),
                series_result_json as (select variant_id,
                                              json_agg(json_build_object('experimentId', t.experiment_id,
                                                                         'variantId', t.variant_id,
                                                                         'date', t.date,
                                                                         'transformation', t.transformation,
                                                                         'variant', variant)) as events
                                       from transformations as t
                                       group by variant_id),
                variants_count as (select variant_id,
                                          count(distinct client_id) as users,
                                          (count(*) filter (where event_type = 'VariantWonEvent')) as won,
                                          (count(*) filter (where event_type = 'VariantDisplayedEvent')) as displayed
                                   from experiments_events
                                   group by variant_id),
                variants_result as (select users as users,
                                           won as won,
                                           displayed as displayed,
                                           (won::double precision * 100 / coalesce(nullif(displayed,0),1)::double precision) as transformation,
                                           json.events as events,
                                           (select variant from variants_settings
                                            where variants_settings.variant_id = variants_count.variant_id
                                            order by date desc limit 1) as variant
                                    from
                                           series_result_json json,
                                           variants_count
                                    where json.variant_id = variants_count.variant_id
                                    order by json.variant_id)

               select row_to_json(variants_result) from variants_result""")
      .query[JsValue]
      .stream
      .transact(xa)
      .map(json => ExperimentInstances.variantResultFormat.reads(json))
      .flatMap { json =>
        json.fold(
          { err =>
            IzanamiLogger.error(s"Error reading json $json : $err")
            Stream.empty
          },
          ok => Stream(ok)
        )
      }
  }

  private def firstEvent(experimentId: String): Task[Option[ExperimentVariantEvent]] =
    (sql"select payload from " ++ fragTableName ++ fr" where experiment_id = $experimentId order by created asc limit 1")
      .query[JsValue]
      .option
      .map(
        _.flatMap(json =>
          ExperimentVariantEventInstances.format
            .reads(json)
            .fold(
              { err =>
                IzanamiLogger.error(s"Error reading json $json : $err")
                None
              },
              ok => Some(ok)
            )
        )
      )
      .transact(xa)

  override def listAll(
      patterns: Seq[String]
  ): RIO[ExperimentVariantEventServiceContext, Source[ExperimentVariantEvent, NotUsed]] =
    ZIO.runtime[ExperimentVariantEventServiceContext].map { implicit runtime =>
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
              },
              ok => Stream(ok)
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
