package controllers
import controllers.actions.AuthContext
import metrics.{MetricsContext, MetricsService}
import play.api.mvc._
import zio._
import libs.http.HttpContext

class MetricController(AuthAction: ActionBuilder[AuthContext, AnyContent], cc: ControllerComponents)(
    implicit R: HttpContext[MetricsContext]
) extends AbstractController(cc) {

  import libs.http._

  def metricsEndpoint() = AuthAction.asyncTask[MetricsContext] { req =>
    MetricsService.metrics.map { metrics =>
      req match {
        case Accepts.Json =>
          Ok(metrics.jsonExport).withHeaders("Content-Type" -> "application/json")
        case Prometheus() =>
          Ok(metrics.prometheusExport)
        case _ =>
          Ok(metrics.defaultHttpFormat)
      }
    }
  }

  val Prometheus = Accepting("application/prometheus")

}
