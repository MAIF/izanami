package fr.maif.izanami.services

import akka.actor.Cancellable
import fr.maif.izanami.env.Env
import fr.maif.izanami.errors.IzanamiError
import fr.maif.izanami.models.FeatureCall.FeatureCallOrigin
import fr.maif.izanami.models._
import fr.maif.izanami.web.FeatureContextPath

import java.time.{Duration, Instant}
import scala.concurrent.duration.{DurationInt, DurationLong}
import scala.concurrent.{ExecutionContext, Future}

class FeatureUsageService(env: Env) {

  private implicit val executionContext: ExecutionContext      = env.executionContext
  private val featureCalls                                     = env.datastores.featureCalls
  private val staleDelay                                       = Duration.ofHours(env.configuration.get[Long]("app.feature.stale-hours-delay"))
  private val isStatusTrackingActive: Boolean                  = env.configuration.get[Boolean]("app.experimental.stale-tracking.enabled")
  private val callRegistrationIntervalInSeconds: Long          = env.configuration.get[Long]("app.feature.call-records.call-register-interval-in-seconds")
  private val callAggregator: FeatureCallAggregator            = FeatureCallAggregator()
  private var callAggregationRegisterCancellation: Cancellable = Cancellable.alreadyCancelled
  private val rangeDurationInMinutes                           = 60

  def onStart(): Future[Unit] = {
    callAggregationRegisterCancellation = env.actorSystem.scheduler.scheduleAtFixedRate(callRegistrationIntervalInSeconds.seconds, callRegistrationIntervalInSeconds.seconds)(() => {
      val callByTenants = callAggregator.calls.groupBy { case (range, _) => range.tenant };
      Future.sequence(callByTenants.map { case (tenant, map) =>
        featureCalls.registerCalls(tenant, map.readOnlySnapshot().view.mapValues(al => al.get()).toMap, rangeDurationInMinutes)
      }).map(_ => ()).map(_ => callAggregator.clearCalls())
    })

    Future.successful()
  }

  def onStop(): Future[Unit] = {
    callAggregationRegisterCancellation.cancel()

    Future.successful()
  }

  def determineStaleStatus(
      tenant: String,
      features: Seq[LightWeightFeature]
  ): Future[Either[IzanamiError, Seq[LightWeightFeatureWithUsageInformation]]] = {
    //Future.successful(Right(features.map(f => LightWeightFeatureWithUsageInformation(feature = f, staleStatus = None))))
    val valueSearchStateDate = Instant.now().minus(staleDelay)
    featureCalls
      .findFeatureUsages(tenant, features.map(_.id), valueSearchStateDate)
      .map(either =>
        either.map(lastCallAndCreationDateByFeature => {
          features.map(feature => {
            val lastCallAndCreationDate = lastCallAndCreationDateByFeature(feature.id)
            val staleStatus             = lastCallAndCreationDate match {
              case _ if !isStatusTrackingActive                                                        => None
              case FeatureUsage(Some(lastCall), _, _) if isTooOld(lastCall)                            => Some(NoCall(since = lastCall))
              case FeatureUsage(None, creationDate, _) if isTooOld(creationDate)                       =>
                Some(NeverCalled(since = creationDate))
              case FeatureUsage(_, creationDate, values) if values.size == 1 && isTooOld(creationDate) =>
                Some(NoValueChange(since = valueSearchStateDate, value = values.head))
              case _                                                                                   => None
            }
            LightWeightFeatureWithUsageInformation(feature = feature, staleStatus = staleStatus)
          })
        })
      )
  }

  private def isTooOld(date: Instant): Boolean = Duration.between(date, Instant.now()).compareTo(staleDelay) > 0

  def registerCalls(
      tenant: String,
      key: String,
      evaluatedCompleteFeatures: Seq[EvaluatedCompleteFeature],
      context: FeatureContextPath,
      origin: FeatureCallOrigin
  ): Unit = {
    if (isStatusTrackingActive) {
      evaluatedCompleteFeatures
        .map(evaluated => {
          FeatureCall(
            tenant = tenant,
            feature = evaluated.baseFeature.id,
            result = evaluated.result,
            context = context,
            time = Instant.now(),
            origin = origin,
            key = key
          )
        })
        .foreach(fc => callAggregator.addCall(fc))
    } else {
      ()
    }
  }
}
