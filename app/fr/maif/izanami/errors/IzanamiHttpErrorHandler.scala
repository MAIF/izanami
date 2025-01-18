package fr.maif.izanami.errors

import fr.maif.izanami.env.Env
import play.api.{Logger, mvc}
import play.api.http.Status.INTERNAL_SERVER_ERROR
import play.api.libs.json.Json
import play.api.mvc.Result
import play.api.mvc.Results.Status
import play.api.http.HttpErrorHandler

import java.security.SecureRandom
import scala.concurrent.{ExecutionContext, Future}

class IzanamiHttpErrorHandler(env: Env) extends HttpErrorHandler {

  implicit val ec: ExecutionContext = env.executionContext

  lazy val clientErrorLogger = Logger("izanami-client-error-handler")
  lazy val serverErrorLogger = Logger("izanami-seerver-error-handler")

  override def onClientError(request: mvc.RequestHeader, statusCode: Int, message: String): Future[Result] = {
    val uuid =
      java.util.UUID.nameUUIDFromBytes(new SecureRandom().generateSeed(16))
    val msg =
      Option(message).filterNot(_.trim.isEmpty).getOrElse("An error occured")
    val errorMessage =
      s"Client Error [$uuid]: $msg on ${request.uri} ($statusCode)"

    clientErrorLogger.error(errorMessage)
    Future.successful(Status(statusCode)(Json.obj("message" -> msg)))
  }

  override def onServerError(request: mvc.RequestHeader, exception: Throwable): Future[Result] = {
    val uuid =
      java.util.UUID.nameUUIDFromBytes(new SecureRandom().generateSeed(16))

    serverErrorLogger.error(
      s"Server Error [$uuid]: ${exception.getMessage} on ${request.uri}",
      exception)
    Future.successful(Status(INTERNAL_SERVER_ERROR)(Json.obj("message" -> exception.getMessage)).withHeaders(("content-type", "application/json")))
  }
}
