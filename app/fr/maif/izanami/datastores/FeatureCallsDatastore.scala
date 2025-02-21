package fr.maif.izanami.datastores

import fr.maif.izanami.env.Env
import fr.maif.izanami.errors.{InternalServerError, IzanamiError}
import fr.maif.izanami.utils.Datastore
import fr.maif.izanami.utils.syntax.implicits.BetterJsValue
import fr.maif.izanami.env.pgimplicits.EnhancedRow
import fr.maif.izanami.models.LastCallAndCreationDate
import play.api.libs.json.JsValue

import java.time.{Instant, ZoneOffset}
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
   * Find last call date of given features and their creation dates.
   * @param tenant tenant name
   * @param featureIds feature ids
   * @return feature last call and creation dates
   */
  def findLastCallAndCreationDate(tenant: String, featureIds: Seq[String]): Future[Either[IzanamiError, Map[String, LastCallAndCreationDate]]] = {
    env.postgresql
      .queryAll(
        query = s"""
         |SELECT MAX(fc.date) AS last_call, f.created_at, f.id
         |FROM features f
         |LEFT JOIN feature_calls fc ON fc.feature = f.id
         |WHERE f.id = ANY($$1)
         |GROUP BY f.id
         |""".stripMargin,
        params = List(featureIds.toArray),
        schemas = Set(tenant)
      ) { r => {
        val lastCall = r.optOffsetDatetime("last_call").map(_.toInstant)
        for(
          createdAt <- r.optOffsetDatetime("created_at").map(_.toInstant);
          id <- r.optString("id")
        ) yield (id, LastCallAndCreationDate(creationDate = createdAt, lastCall = lastCall))
      }}
      .map(ls => Right(ls.toMap))
      .recover(env.postgresql.pgErrorPartialFunction.andThen(err => Left(err)))
  }
}
