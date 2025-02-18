package fr.maif.izanami.datastores

import akka.actor.Cancellable
import fr.maif.izanami.env.Env
import fr.maif.izanami.errors.{InternalServerError, IzanamiError}
import fr.maif.izanami.utils.Datastore
import fr.maif.izanami.utils.syntax.implicits.BetterJsValue
import fr.maif.izanami.env.pgimplicits.EnhancedRow
import fr.maif.izanami.models.FeatureCallAggregator.FeatureCallRange
import fr.maif.izanami.models.{FeatureCall, FeatureUsage}
import fr.maif.izanami.web.FeatureContextPath
import play.api.libs.json.{JsValue, Json}

import java.time.{Duration, Instant, ZoneId, ZoneOffset}
import scala.concurrent.Future
import scala.concurrent.duration.{DurationInt, DurationLong}

class FeatureCallsDatastore(val env: Env) extends Datastore {
  private val callRetentionDelayInHours: Long = env.configuration.get[Long]("app.feature.call-records.call-retention-time-in-hours")
  private var outDatedCallDeleteCancellation: Cancellable = Cancellable.alreadyCancelled
  override def onStart(): Future[Unit] = {
    outDatedCallDeleteCancellation = env.actorSystem.scheduler.scheduleAtFixedRate(env.houseKeepingStartDelayInSeconds.seconds, env.houseKeepingIntervalInSeconds.seconds)(() => {
      deleteOutDatedCalls(Duration.ofHours(callRetentionDelayInHours))
    })
    Future.successful(())
  }

  override def onStop(): Future[Unit] = {
    outDatedCallDeleteCancellation.cancel()
    Future.successful(())
  }

  def deleteOutDatedCalls(duration: Duration): Future[Unit] = {
    env.datastores.tenants.readTenants().map(tenants => {
      Future.sequence(tenants.map(tenant => deleteOutDatedCallsForTenant(tenant.name, duration)))
    })
  }

  def deleteOutDatedCallsForTenant(tenant: String, duration: Duration): Future[Unit] = {
    env.postgresql.queryRaw(
      s"""
         |DELETE FROM feature_calls WHERE EXTRACT(EPOCH FROM (NOW() - range_stop)) > $$1 returning feature
         |""".stripMargin,
      List(duration.toSeconds.asInstanceOf[Number]),
      schemas=Seq(tenant)
    ){_ => ()}
  }

  def registerCalls(
                     tenant: String,
                     calls: Map[FeatureCallRange, Long],
                     rangeDurationInMinutes: Long
                   ): Future[Unit] = {
    val callSeq = calls.toSeq
    env.postgresql.queryRaw(
      s"""
         |INSERT INTO feature_calls (feature, apikey, context, value, range_start, range_stop, count)
         |VALUES (
         |  UNNEST($$1::TEXT[]),
         |  UNNEST($$2::TEXT[]),
         |  text2ltree(UNNEST($$3::TEXT[])),
         |  UNNEST($$4::JSONB[]),
         |  UNNEST($$5::timestamptz[]),
         |  UNNEST($$6::timestamptz[]),
         |  UNNEST($$7::NUMERIC[])
         |)
         |ON CONFLICT (feature, apikey, context, value, range_start)
         |DO UPDATE SET count = feature_calls.count + excluded.count
         |""".stripMargin,
      List(
        callSeq.map(_._1.feature).toArray,
        callSeq.map(_._1.key).toArray,
        callSeq.map(_._1.context.toDBPath).toArray,
        callSeq.map(_._1.result.vertxJsValue).toArray,
        callSeq.map(_._1.rangeStart).map(s => s.atOffset(ZoneOffset.UTC)).toArray,
        callSeq.map(_._1.rangeStart).map(s => s.atOffset(ZoneOffset.UTC).plusMinutes(rangeDurationInMinutes)).toArray,
        callSeq.map(_._2).map(l => l.asInstanceOf[Number]).toArray
      ),
      schemas=Seq(tenant)
    ){r => ()}
  }

  /**
   * Find last call date of given features, their creation dates and their last values.
   * @param tenant tenant name
   * @param featureIds feature ids
   * @return feature last call, creation date and last values
   */
  def findFeatureUsages(tenant: String, featureIds: Seq[String], valuesSince :Instant ): Future[Either[IzanamiError, Map[String, FeatureUsage]]] = {
    env.postgresql
      .queryAll(
        query = s"""
         |WITH distinct_values_by_feature AS (
         |    SELECT array_agg(distinct fc.value::TEXT) as values, fc.feature
         |    FROM feature_calls fc
         |    WHERE range_stop > $$2
         |    AND fc.feature = ANY($$1)
         |    GROUP BY fc.feature
         |)
         |SELECT
         |    MAX(fc.range_stop) AS last_call,
         |    f.created_at,
         |    f.id,
         |    dv.values
         |FROM features f
         |LEFT JOIN feature_calls fc ON fc.feature = f.id
         |LEFT JOIN distinct_values_by_feature dv ON dv.feature = f.id
         |WHERE f.id = ANY($$1)
         |GROUP BY f.id, dv.values
         |""".stripMargin,
        params = List(featureIds.toArray, valuesSince.atOffset(ZoneOffset.UTC)),
        schemas = Seq(tenant)
      ) { r => {
        val lastCall = r.optOffsetDatetime("last_call").map(_.toInstant)
        val values = r.optStringArray("values").map(vs => vs.map(str => Json.parse(str)).toSet).getOrElse(Set.empty)
        for(
          createdAt <- r.optOffsetDatetime("created_at").map(_.toInstant);
          id <- r.optString("id")
        ) yield (id, FeatureUsage(creationDate = createdAt, lastCall = lastCall, lastValues = values))
      }}
      .map(ls => Right(ls.toMap))
      .recover(env.postgresql.pgErrorPartialFunction.andThen(err => Left(err)))
  }
}
