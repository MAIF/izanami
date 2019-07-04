package izanami.experiments

import java.time.LocalDateTime

import akka.http.scaladsl.util.FastFuture
import izanami._
import izanami.scaladsl.{ExperimentClient, ExperimentsClient}
import play.api.libs.json.{JsObject, Json}

import scala.concurrent.Future

object FallbackExperimentStrategy {
  def apply(fallback: Experiments): FallbackExperimentStrategy =
    new FallbackExperimentStrategy(fallback)
}

class FallbackExperimentStrategy(fallback: Experiments) extends ExperimentsClient {

  override def experiment(id: String): Future[Option[ExperimentClient]] =
    FastFuture.successful(fallback.experiments.find(_.id == id).map { fb =>
      ExperimentClient(this, fb.experiment)
    })

  override def list(pattern: Seq[String]): Future[Seq[ExperimentClient]] =
    FastFuture.successful(fallback.experiments.map(fb => ExperimentClient(this, fb.experiment)))

  override def tree(pattern: Seq[String], clientId: String): Future[JsObject] =
    FastFuture.successful(
      fallback.experiments
        .filter(_.enabled)
        .map { _.tree }
        .foldLeft(Json.obj())(_ deepMerge _)
    )

  override def getVariantFor(experimentId: String, clientId: String): Future[Option[Variant]] =
    FastFuture.successful(fallback.experiments.find(_.id == experimentId).map(_.variant))

  override def markVariantDisplayed(experimentId: String, clientId: String): Future[ExperimentVariantDisplayed] = {

    val experiment: ExperimentFallback = fallback.experiments
      .find(_.id == experimentId)
      .getOrElse(ExperimentFallback(experimentId, "", "", false, Variant("", "", "")))

    FastFuture.successful(
      ExperimentVariantDisplayed(
        s"${experiment.id}:${experiment.variant.id}:${clientId}:${System.currentTimeMillis()}",
        experiment.id,
        clientId,
        experiment.variant,
        LocalDateTime.now(),
        0,
        experiment.variant.id
      )
    )
  }

  override def markVariantWon(experimentId: String, clientId: String): Future[ExperimentVariantWon] = {

    val experiment: ExperimentFallback = fallback.experiments
      .find(_.id == experimentId)
      .getOrElse(ExperimentFallback(experimentId, "", "", false, Variant("", "", "")))

    FastFuture.successful(
      ExperimentVariantWon(
        s"${experiment.id}:${experiment.variant.id}:${clientId}:${System.currentTimeMillis()}",
        experiment.id,
        clientId,
        experiment.variant,
        LocalDateTime.now(),
        0,
        experiment.variant.id
      )
    )
  }
}
