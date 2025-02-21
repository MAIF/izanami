package fr.maif.izanami.services

import fr.maif.izanami.env.Env
import fr.maif.izanami.errors.IzanamiError
import fr.maif.izanami.models.{LightWeightFeature, LightWeightFeatureWithUsageInformation}

import java.time.{Duration, Instant}
import scala.concurrent.{ExecutionContext, Future}

class FeatureUsageService(env: Env) {

  private implicit val executionContext: ExecutionContext = env.executionContext
  private val featureCalls = env.datastores.featureCalls
  private val staleDelay = Duration.ofHours(env.configuration.get[Long]("app.feature.stale-hours-delay"))

  def reportStaleFeatures(tenant: String, delay: Duration): Future[Either[IzanamiError, List[String]]] = {
    val nowMinusDelay = Instant.now().minus(delay)
    featureCalls.findFeatureWithoutCallSince(tenant, nowMinusDelay)
  }

  def determineStaleStatus(tenant: String, features: Seq[LightWeightFeature]): Future[Either[IzanamiError, Seq[LightWeightFeatureWithUsageInformation]]] = {
    featureCalls.findLastCallAndCreationDate(tenant, features.map(_.id)).map(either => either.map(lastCallAndCreationDateByFeature => {
      features.map(feature => {
        val lastCallAndCreationDate = lastCallAndCreationDateByFeature(feature.id)
        val isStale = Duration.between(lastCallAndCreationDate.lastCallOrCreationDate, Instant.now()).compareTo(staleDelay) > 0
        LightWeightFeatureWithUsageInformation(feature = feature, stale = isStale, creationDate = lastCallAndCreationDate.creationDate, lastCall = lastCallAndCreationDate.lastCall)
      })
    }))
  }

}
