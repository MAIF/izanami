package controllers

import akka.actor.ActorSystem
import env.Env
import play.api.libs.json.Json
import play.api.mvc.{AbstractController, ControllerComponents}

class MeController(env: Env, val cc: ControllerComponents)(implicit system: ActorSystem)
    extends AbstractController(cc) {

  import system.dispatcher

  def me() = Action { _ =>
    Ok(Json.obj())
  }

}
