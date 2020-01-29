package controllers

import controllers.actions.AuthContext
import controllers.dto.error.ApiErrors
import domains.GlobalContext
import play.api.libs.json.Json
import play.api.mvc.{AbstractController, ActionBuilder, AnyContent, ControllerComponents}
import store.Healthcheck
import zio.Runtime

class HealthCheckController(
    AuthAction: ActionBuilder[AuthContext, AnyContent],
    cc: ControllerComponents
)(implicit R: Runtime[GlobalContext])
    extends AbstractController(cc) {

  import libs.http._

  def check() = AuthAction.asyncZio[GlobalContext] { _ =>
    Healthcheck
      .check()
      .map(_ => Ok(Json.obj()))
      .mapError { ApiErrors.toHttpResult }
  }

}
