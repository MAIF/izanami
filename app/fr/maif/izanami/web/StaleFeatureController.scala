package fr.maif.izanami.web

import fr.maif.izanami.env.Env
import fr.maif.izanami.services.StaleFeatureService
import play.api.libs.json.Json
import play.api.mvc.{Action, AnyContent, BaseController, ControllerComponents}

import java.time.Duration
import scala.concurrent.ExecutionContext

class StaleFeatureController(
    env: Env,
    staleFeatureService: StaleFeatureService,
    val controllerComponents: ControllerComponents
) extends BaseController {

  implicit val ec: ExecutionContext = env.executionContext

  private val defaultDelay =
    env.configuration.getOptional[Duration]("izanami.stale-feature.delay").getOrElse(Duration.ofDays(30L))

  def staleFeatures(tenant: String, delay: Option[Duration]): Action[AnyContent] = Action.async { _ =>
    staleFeatureService
      .reportStaleFeatures(tenant, delay.getOrElse(defaultDelay))
      .map({
        case Left(error)     => InternalServerError(Json.obj("error" -> error.message))
        case Right(features) => Ok(Json.obj("features" -> features))
      })
  }

}
