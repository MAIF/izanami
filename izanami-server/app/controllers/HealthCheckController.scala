package controllers

import akka.actor.ActorSystem
import controllers.actions.AuthContext
import domains.GlobalContext
import play.api.libs.json.Json
import play.api.mvc.{AbstractController, ActionBuilder, AnyContent, ControllerComponents}
import store.Healthcheck
import store.Result.IzanamiErrors
import zio.Runtime

class HealthCheckController(
    system: ActorSystem,
    AuthAction: ActionBuilder[AuthContext, AnyContent],
    cc: ControllerComponents
)(implicit R: Runtime[GlobalContext])
    extends AbstractController(cc) {

  import libs.http._

  def check() = AuthAction.asyncZio[GlobalContext] { req =>
    Healthcheck
      .check()
      .map(_ => Ok(Json.obj()))
      .mapError(e => InternalServerError)
  }

}
