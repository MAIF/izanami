package controllers

import akka.actor.ActorSystem
import env.Env
import izanami.scaladsl.Proxy
import play.api.mvc.{AbstractController, ControllerComponents}

class IzanamiController(env: Env, proxy: Proxy, val cc: ControllerComponents)(implicit system: ActorSystem)
    extends AbstractController(cc) {

  import system.dispatcher

  def izanamiProxy() = Action.async { _ =>
    proxy.statusAndJsonResponse(None, None).map {
      case (_, response) =>
        Ok(response)
    }
  }

}
