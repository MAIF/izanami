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
import store.Result.Result
import store.postgresql.{PgData, PostgresqlClient}
import cats.effect.implicits._
import cats.implicits._
import doobie.implicits._
import fs2.Stream
import libs.logs.IzanamiLogger
import play.api.libs.json.JsValue
import store.Result

object ExperimentVariantEventPostgresqlService {
  def apply[F[_]: ContextShift: ConcurrentEffect](
      config: PostgresqlConfig,
      client: PostgresqlClient[F],
      domainConfig: DbDomainConfig,
      eventStore: EventStore[F]
  ): ExperimentVariantEventPostgresqlService[F] =
    new ExperimentVariantEventPostgresqlService[F](client, config, domainConfig, eventStore)
}

class ExperimentVariantEventPostgresqlService[F[_]: ContextShift: ConcurrentEffect](client: PostgresqlClient[F],
                                                                                    config: PostgresqlConfig,
                                                                                    domainConfig: DbDomainConfig,
                                                                                    eventStore: EventStore[F])
    extends ExperimentVariantEventService[F] {

  import PgData._
  private val xa            = client.transactor
  private val tableName     = domainConfig.conf.namespace.replaceAll(":", "_")
  private val fragTableName = Fragment.const(tableName)

  {
    val createTableScript = {
      val frag = (sql"create table if not exists " ++ fragTableName ++ sql""" (
         id varchar(500) primary key,
         created timestamp not null default now(),
         experiment_id varchar(500) not null,
         variant_id varchar(500) not null,
         payload jsonb not null
       )""")
      IzanamiLogger.debug(s"Applying script $frag")
      frag
    }.update.run

    val experimentIdScript = {
      val frag = (sql"CREATE INDEX IF NOT EXISTS " ++
      Fragment.const(s"${tableName}_experiment_id_idx ") ++ fr" ON " ++ fragTableName ++ fr" (experiment_id)")
      IzanamiLogger.debug(s"Applying script $frag")
      frag
    }.update.run

    val variantIdScript = {
      val frag = (sql"CREATE INDEX IF NOT EXISTS " ++
      Fragment.const(s"${tableName}_variant_id_idx ") ++ fr" ON " ++ fragTableName ++ fr" (variant_id)")
      IzanamiLogger.debug(s"Applying script $frag")
      frag
    }.update.run

    val createdScript = {
      val frag = (sql"CREATE INDEX IF NOT EXISTS " ++
      Fragment.const(s"${tableName}_created_idx ") ++ fr" ON " ++ fragTableName ++ fr" (created)")
      IzanamiLogger.debug(s"Applying script $frag")
      frag
    }.update.run

    import cats.effect.implicits._
    (createTableScript *> experimentIdScript *> variantIdScript *> createdScript)
      .transact(xa)
      .toIO
      .unsafeRunSync()
  }

  override def create(
      id: ExperimentVariantEventKey,
      data: ExperimentVariantEvent
  ): F[Result[ExperimentVariantEvent]] = {
    val json = ExperimentVariantEventInstances.format.writes(data)
    (sql"insert into " ++ fragTableName ++ fr" (id, experiment_id, variant_id, payload) values (${id.key}, ${id.experimentId},  ${id.variantId}, $json)").update.run
      .transact(xa)
      .map { _ =>
        Result.ok(data)
      }
  }
  override def deleteEventsForExperiment(
      experiment: Experiment
  ): F[Result[Done]] =
    (sql"delete from " ++ fragTableName ++ fr" where experiment_id = ${experiment.id}").update.run
      .transact(xa)
      .map(_ => Result.ok(Done))

  override def findVariantResult(experiment: Experiment): Source[VariantResult, NotUsed] = {
    import streamz.converter._
    Source(experiment.variants.toList)
      .flatMapMerge(
        4, { v =>
          Source
            .fromFuture(firstEvent(experiment.id.key, v.id).toIO.unsafeToFuture())
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
  private def findEvents(experimentId: String, variant: Variant): Stream[F, ExperimentVariantEvent] =
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

  private def firstEvent(experimentId: String, variant: String): F[Option[ExperimentVariantEvent]] =
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

  override def listAll(patterns: Seq[String]): Source[ExperimentVariantEvent, NotUsed] = {

    import streamz.converter._
    Source.fromGraph(
      (sql"select payload from " ++ fragTableName)
        .query[JsValue]
        .stream
        .transact(xa)
        .map { ExperimentVariantEventInstances.format.reads }
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

  override def check(): F[Unit] =
    (sql"select id from " ++ fragTableName ++ fr" where id = 'test' LIMIT 1")
      .query[String]
      .option
      .transact(xa)
      .map(_.fold(())(_ => ()))
}
