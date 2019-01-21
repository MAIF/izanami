package controllers
import controllers.actions.AuthContext
import metrics.Metrics
import play.api.mvc._

class MetricController[F[_]](metrics: Metrics[F],
                             AuthAction: ActionBuilder[AuthContext, AnyContent],
                             cc: ControllerComponents)
    extends AbstractController(cc) {

  def metricsEndpoint() = AuthAction { req =>
    req match {
      case Accepts.Json =>
        Ok(metrics.jsonExport).withHeaders("Content-Type" -> "application/json")
      case Prometheus =>
        Ok(metrics.prometheusExport)
      case _ =>
        Ok(metrics.defaultHttpFormat)
    }
  }

  val Prometheus = Accepting("application/prometheus")

}
