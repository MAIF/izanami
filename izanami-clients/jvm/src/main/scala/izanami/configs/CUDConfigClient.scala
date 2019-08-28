package izanami.configs
import akka.actor.ActorSystem
import akka.event.Logging
import akka.http.scaladsl.model.StatusCodes
import akka.http.scaladsl.util.FastFuture
import izanami.commons.{HttpClient, IzanamiException}
import izanami.IzanamiDispatcher
import izanami.scaladsl.Config
import izanami.scaladsl.Config._
import play.api.libs.json.{JsObject, Json}

import scala.concurrent.Future
import play.api.libs.json.JsValue
import scala.concurrent.ExecutionContext

object CUDConfigClient {
  def apply(client: HttpClient)(implicit izanamiDispatcher: IzanamiDispatcher, actorSystem: ActorSystem) =
    new CUDConfigClientImpl(client)
}

trait CUDConfigClient {

  implicit def ec: ExecutionContext

  def createConfig(id: String, config: Config): Future[Config] =
    createRawConfig(id, Json.toJson(config))
      .flatMap { json =>
        json
          .validate[Config]
          .fold(
            { err =>
              val message = s"Error reading config $id, response=$err"
              FastFuture.failed(IzanamiException(message))
            }, { FastFuture.successful }
          )
      }

  def createRawConfig(id: String, config: JsValue): Future[JsValue]
}

class CUDConfigClientImpl(client: HttpClient)(implicit val izanamiDispatcher: IzanamiDispatcher,
                                              actorSystem: ActorSystem)
    extends CUDConfigClient {

  import izanamiDispatcher.ec
  private val logger = Logging(actorSystem, this.getClass.getSimpleName)

  override implicit val ec: ExecutionContext = izanamiDispatcher.ec

  def createRawConfig(id: String, config: JsValue): Future[JsValue] =
    client
      .post("/api/configs", config)
      .flatMap {
        case (status, json) if status == StatusCodes.Created =>
          FastFuture.successful(Json.parse(json))
        case (status, body) => {
          val message = s"Error creating config $id : status=$status, response=$body"
          logger.error(message)
          FastFuture.failed(IzanamiException(message))
        }
      }(izanamiDispatcher.ec)
}
