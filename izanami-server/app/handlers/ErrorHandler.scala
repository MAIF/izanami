package handlers

import play.api._
import play.api.http.DefaultHttpErrorHandler
import play.api.mvc.Results._
import play.api.mvc._
import play.api.routing.Router
import play.core.SourceMapper

import scala.concurrent._

class ErrorHandler(
    env: Environment,
    config: Configuration,
    sourceMapper: Option[SourceMapper],
    router: => Option[Router]
) extends DefaultHttpErrorHandler(env, config, sourceMapper, router) {

  override def onProdServerError(request: RequestHeader, exception: UsefulException) = {
    Logger.error(s"Error serving request ${request.method} ${request.uri}", exception)
    Future.successful(
      InternalServerError
    )
  }

}
