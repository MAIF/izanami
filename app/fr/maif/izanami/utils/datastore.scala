package fr.maif.izanami.utils

import org.apache.pekko.http.scaladsl.util.FastFuture
import play.api.Logger

import scala.concurrent.ExecutionContext
import scala.concurrent.Future

// FIXME is it still used ?
trait Datastore {
  protected val logger: Logger = Logger("izanami-datastore")

  def onStart(): Future[Unit] = {
    FastFuture.successful(())
  }

  def onStop(): Future[Unit] = {
    FastFuture.successful(())
  }
}
