package controllers

import env.Env
import play.api.mvc.{AbstractController, ControllerComponents}

class HomeController(env: Env, val cc: ControllerComponents) extends AbstractController(cc) {

  def index() = Action {
    Ok(views.html.index(env))
  }

  def indexOtherRoutes(path: String) = Action {
    Ok(views.html.index(env))
  }

}
