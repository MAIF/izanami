package fr.maif.izanami.utils

import org.apache.pekko.http.scaladsl.util.FastFuture
import fr.maif.izanami.env.Env
import play.api.Logger

import scala.concurrent.ExecutionContext
import scala.concurrent.Future

trait Datastore {
  implicit val ec: ExecutionContext = env.executionContext
  protected val logger: Logger              = Logger("izanami-datastore")

  def env: Env

  def onStart(): Future[Unit] = {
    FastFuture.successful(())
  }

  def onStop(): Future[Unit] = {
    FastFuture.successful(())
  }
}
