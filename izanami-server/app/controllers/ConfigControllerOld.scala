package controllers

import play.api.mvc._

class ConfigControllerOld(val configController: ConfigController)
    extends AbstractController(configController.cc) {
  def list(keyConfig: String, page: Int, nbElementPerPage: Int) =
    configController.list(keyConfig, page, nbElementPerPage)

  def tree(pattern: String) = configController.tree(pattern)

  def create() = configController.create()

  def get(id: String) = configController.get(id)

  def update(id: String) = configController.update(id)

  def delete(id: String) = configController.delete(id)

  def deleteAll(patterns: Option[String]) = configController.deleteAll(patterns)

  def count() = configController.count()
}
