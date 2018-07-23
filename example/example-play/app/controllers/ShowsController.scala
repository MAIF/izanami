package controllers

import akka.actor.ActorSystem
import domains.shows.Shows
import env.Env
import play.api.libs.json.Json
import play.api.mvc.{AbstractController, ControllerComponents}

import scala.concurrent.Future

class ShowsController(env: Env, shows: Shows[Future], val cc: ControllerComponents)(implicit system: ActorSystem)
    extends AbstractController(cc) {

  import system.dispatcher
  import domains.shows.Show._

  def search(name: String) = Action.async { _ =>
    shows.search(name).map { res =>
      Ok(Json.toJson(res))
    }
  }

}
