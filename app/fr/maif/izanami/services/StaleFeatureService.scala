package fr.maif.izanami.services

import fr.maif.izanami.env.Env
import fr.maif.izanami.errors.IzanamiError

import java.time.{Duration, Instant}
import scala.concurrent.Future

class StaleFeatureService(env: Env) {

  private val featureCalls = env.datastores.featureCalls

  def reportStaleFeatures(tenant: String, delay: Duration): Future[Either[IzanamiError, List[String]]] = {
    val nowMinusDelay = Instant.now().minus(delay)
    featureCalls.findFeatureWithoutCallSince(tenant, nowMinusDelay)
  }

}
