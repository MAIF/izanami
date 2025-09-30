package fr.maif.izanami.env

import fr.maif.izanami.utils.syntax.implicits.*

import scala.concurrent.{ExecutionContext, Future}

class Jobs(env: Env) {

  implicit val ec: ExecutionContext = env.executionContext

  def onStart(): Future[Unit] = {
    Future.successful(())
  }

  def onStop(): Future[Unit] = {
    ().vfuture
  }
}
