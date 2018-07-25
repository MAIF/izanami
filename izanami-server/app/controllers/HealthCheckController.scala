package controllers

import akka.actor.ActorSystem
import controllers.actions.SecuredAuthContext
import env.Env
import play.api.libs.json.Json
import play.api.mvc.{AbstractController, ActionBuilder, AnyContent, ControllerComponents}
import store.Healthcheck

class HealthCheckController(env: Env,
                            healthcheck: Healthcheck,
                            system: ActorSystem,
                            AuthAction: ActionBuilder[SecuredAuthContext, AnyContent],
                            cc: ControllerComponents)
    extends AbstractController(cc) {

  import system.dispatcher

  def check() = AuthAction.async { req =>
    healthcheck.check().map(_ => Ok(Json.obj()))
  }

}
