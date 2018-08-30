package controllers

import akka.actor.ActorSystem
import cats.effect.Effect
import controllers.actions.{AuthContext}
import play.api.libs.json.Json
import play.api.mvc.{AbstractController, ActionBuilder, AnyContent, ControllerComponents}
import store.Healthcheck

class HealthCheckController[F[_]: Effect](healthcheck: Healthcheck[F],
                                          system: ActorSystem,
                                          AuthAction: ActionBuilder[AuthContext, AnyContent],
                                          cc: ControllerComponents)
    extends AbstractController(cc) {

  import cats.implicits._
  import libs.http._

  def check() = AuthAction.asyncF { req =>
    healthcheck.check().map(_ => Ok(Json.obj()))
  }

}
