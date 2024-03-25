package fr.maif.izanami.env

import fr.maif.izanami.utils.syntax.implicits._

import scala.concurrent.Future

class Jobs(env: Env) {

  implicit val ec = env.executionContext

  def onStart(): Future[Unit] = {
    Future.successful(())
  }

  def onStop(): Future[Unit] = {
    ().vfuture
  }
}
