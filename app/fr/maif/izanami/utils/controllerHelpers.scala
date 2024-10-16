package fr.maif.izanami.utils
import play.api.libs.json.{JsValue, Json}
import play.api.mvc.Results.BadRequest

import scala.concurrent.Future

object ControllerHelpers {
   def checkPassword(body: JsValue): Future[Either[play.api.mvc.Result, String]] = {
    (body \ "password").asOpt[String] match {
      case None =>
        Future.successful(Left(BadRequest(Json.obj("message" -> "Missing password"))))
      case Some(password) =>
        Future.successful(Right(password))
    }
  }

}