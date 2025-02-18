package fr.maif.izanami.datastores

import fr.maif.izanami.env.Env
import fr.maif.izanami.errors.IzanamiError
import fr.maif.izanami.utils.Datastore
import fr.maif.izanami.utils.syntax.implicits.BetterJsValue
import play.api.libs.json.JsValue

import scala.concurrent.Future

class FeatureCallsDatastore(val env: Env) extends Datastore{
  private type FeatureId = String
  def registerCall(tenant: String, key: String, valuesByFeature: Map[FeatureId, JsValue]): Future[Either[IzanamiError, Unit]] = {
    env.postgresql.queryOne(
      query = s"""
         |INSERT INTO feature_calls(feature, apikey, value) VALUES (unnest($$1::text[]), $$2, unnest($$3::jsonb[]))
         |""".stripMargin,
        params = List(
          valuesByFeature.keys.toArray,
          key,
          valuesByFeature.values.map(json => json.vertxJsValue).toArray
        ),
        schemas = Set(tenant)
    ){r => Some(())}
      .map(_ => Right(()))
      .recover(env.postgresql.pgErrorPartialFunction.andThen(err => Left(err)))
  }
}
