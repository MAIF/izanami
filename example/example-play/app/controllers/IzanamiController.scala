package controllers

import akka.actor.ActorSystem
import controllers.actions.SecuredAction
import env.Env
import izanami.scaladsl.Proxy
import play.api.libs.json.Json
import play.api.mvc.{AbstractController, ControllerComponents}

class IzanamiController(env: Env, proxy: Proxy, SecuredAction: SecuredAction, val cc: ControllerComponents)(
    implicit system: ActorSystem
) extends AbstractController(cc) {

  import cats._
  import cats.implicits._
  import system.dispatcher

  def izanamiProxy() = SecuredAction.async { req =>
    proxy
      .statusAndJsonResponse(
        Json.obj("userId" -> req.userId).some,
        req.userId.some
      )
      .map {
        case (status, response) => Status(status)(response)
      }
  }

  def markDisplayed(experiment: String) = SecuredAction.async { req =>
    proxy.markVariantDisplayed(experiment, req.userId).map {
      case (status, response) => Status(status)(response)
    }
  }

  def markWon(experiment: String) = SecuredAction.async { req =>
    proxy.markVariantWon(experiment, req.userId).map {
      case (status, response) => Status(status)(response)
    }
  }

}
