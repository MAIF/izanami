package fr.maif.izanami.services

import fr.maif.izanami.env.Env
import fr.maif.izanami.errors.IzanamiError
import fr.maif.izanami.models.{FeatureUsage, LightWeightFeature, LightWeightFeatureWithUsageInformation, NeverCalled, NoCall, NoValueChange}

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
    val valueSearchStateDate = Instant.now().minus(staleDelay)
    featureCalls.findFeatureUsages(tenant, features.map(_.id), valueSearchStateDate).map(either => either.map(lastCallAndCreationDateByFeature => {
      features.map(feature => {
        val lastCallAndCreationDate = lastCallAndCreationDateByFeature(feature.id)
        val staleStatus = lastCallAndCreationDate match {
          case FeatureUsage(Some(lastCall), _, _) if isTooOld(lastCall) => Some(NoCall(since = lastCall))
          case FeatureUsage(None, creationDate, _) if isTooOld(creationDate) => Some(NeverCalled(since = creationDate))
          case FeatureUsage(_, creationDate, values) if values.size == 1 && isTooOld(creationDate) => Some(NoValueChange(since = valueSearchStateDate, value = values.head))
          case _ => None
        }
        LightWeightFeatureWithUsageInformation(feature = feature, staleStatus = staleStatus)
      })
    }))
  }

  private def isTooOld(date: Instant): Boolean = Duration.between(date, Instant.now()).compareTo(staleDelay) > 0

}
