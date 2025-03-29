package fr.maif.izanami.datastores

import fr.maif.izanami.env.Env
import fr.maif.izanami.errors.{InternalServerError, IzanamiError}
import fr.maif.izanami.utils.Datastore
import fr.maif.izanami.utils.syntax.implicits.BetterJsValue
import fr.maif.izanami.env.pgimplicits.EnhancedRow
import fr.maif.izanami.models.FeatureUsage
import play.api.libs.json.{JsValue, Json}

import java.time.{Duration, Instant, ZoneOffset}
import scala.concurrent.Future

class FeatureCallsDatastore(val env: Env) extends Datastore {

  private type FeatureId = String

  def registerCall(
      tenant: String,
      key: String,
      valuesByFeature: Map[FeatureId, JsValue]
  ): Future[Either[IzanamiError, Unit]] = {
    env.postgresql
      .queryOne(
        query = s"""
         |INSERT INTO feature_calls(feature, apikey, value) VALUES (unnest($$1::text[]), $$2, unnest($$3::jsonb[]))
         |""".stripMargin,
        params = List(
          valuesByFeature.keys.toArray,
          key,
          valuesByFeature.values.map(json => json.vertxJsValue).toArray
        ),
        schemas = Set(tenant)
      ) { r => Some(()) }
      .map(_ => Right(()))
      .recover(env.postgresql.pgErrorPartialFunction.andThen(err => Left(err)))
  }

  def findFeatureWithoutCallSince(tenant: String, since: Instant): Future[Either[IzanamiError, List[String]]] = {
    env.postgresql
      .queryAll(
        query = s"""
         |SELECT feature, MAX(date) AS last_call
         |FROM feature_calls
         |GROUP BY feature
         |HAVING MAX(date) < $$1
         |""".stripMargin,
        params = List(
          since.atOffset(ZoneOffset.UTC)
        ),
        schemas = Set(tenant)
      ) { r => r.optString("feature") }
      .map(features => Right(features))
      .recover(env.postgresql.pgErrorPartialFunction.andThen(err => Left(err)))
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
         |    WHERE date > $$2
         |    AND fc.feature = ANY($$1)
         |    GROUP BY fc.feature
         |)
         |SELECT
         |    MAX(fc.date) AS last_call,
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
        schemas = Set(tenant)
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
