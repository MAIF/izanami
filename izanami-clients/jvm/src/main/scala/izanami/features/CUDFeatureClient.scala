package izanami.features
import akka.actor.ActorSystem
import akka.event.Logging
import akka.http.scaladsl.model.StatusCodes
import akka.http.scaladsl.util.FastFuture
import izanami.commons.{HttpClient, IzanamiException}
import izanami.{Feature, FeatureType, IzanamiDispatcher}
import play.api.libs.json.{JsObject, Json}

import scala.concurrent.Future
import akka.http.scaladsl.model.HttpEntity
import akka.http.scaladsl.model.MediaType
import akka.http.scaladsl.model.HttpCharsets

object CUDFeatureClient {
  def apply(client: HttpClient)(implicit izanamiDispatcher: IzanamiDispatcher, actorSystem: ActorSystem) =
    new CUDFeatureClient(client)
}

class CUDFeatureClient(client: HttpClient)(implicit val izanamiDispatcher: IzanamiDispatcher,
                                           actorSystem: ActorSystem) {

  import izanamiDispatcher.ec
  private val logger = Logging(actorSystem, this.getClass.getName)

  def createFeature(feature: Feature): Future[Feature] = {
    import Feature._

    val payload = Json.toJsObject(feature)

    client
      .post("/api/features", payload)
      .flatMap {
        case (status, json) if status == StatusCodes.Created =>
          FastFuture.successful(Json.parse(json).as[Feature](Feature.reads))
        case (status, body) => {
          val message = s"Error creating feature ${feature.id} : status=$status, body=$payload response=$body"
          logger.error(message)
          FastFuture.failed(IzanamiException(message))
        }
      }
  }

  def createJsonFeature(id: String,
                        enabled: Boolean = true,
                        activationStrategy: FeatureType = FeatureType.NO_STRATEGY,
                        parameters: Option[JsObject] = None): Future[Feature] = {
    val payload = Json.obj("id" -> id, "enabled" -> enabled, "activationStrategy" -> activationStrategy.name) ++ parameters
      .map(value => Json.obj("parameters" -> value))
      .getOrElse(Json.obj())
    client
      .post("/api/features", payload)
      .flatMap {
        case (status, json) if status == StatusCodes.Created =>
          FastFuture.successful(Json.parse(json).as[Feature](Feature.reads))
        case (status, body) => {
          val message = s"Error creating feature $id : status=$status, body=$payload response=$body"
          logger.error(message)
          FastFuture.failed(IzanamiException(message))
        }
      }
  }

  def importFeature(features: Seq[Feature]): Future[Unit] = {
    val payload = features.map(f => Json.toJsObject(f)).map(Json.stringify).mkString("\n")
    client
      .rawPost("/api/features.ndjson",
               HttpEntity(MediaType.applicationWithFixedCharset("nd-json", HttpCharsets.`UTF-8`), payload))
      .map {
        case (status, body) =>
          if (status != StatusCodes.OK) {
            logger.debug(s"Fail to import feature $body")
          }
          ()
      }
  }

  def updateFeature(id: String, feature: Feature): Future[Feature] = {
    import Feature._
    val payload = Json.toJsObject(feature)
    client
      .put(s"/api/features/$id", payload)
      .flatMap {
        case (status, json) if status == StatusCodes.OK =>
          FastFuture.successful(Json.parse(json).as[Feature](Feature.reads))
        case (status, body) => {
          val message = s"Error updating feature $id / ${feature.id} : status=$status, body=$payload response=$body"
          logger.error(message)
          FastFuture.failed(IzanamiException(message))
        }
      }
  }

  def switchFeature(id: String, enabled: Boolean): Future[Feature] = {
    val patch = Json.arr(
      Json.obj(
        "op"    -> "replace",
        "path"  -> "/enabled",
        "value" -> enabled
      )
    )
    client
      .patch(s"/api/features/$id", patch)
      .flatMap {
        case (status, json) if status == StatusCodes.OK =>
          FastFuture.successful(Json.parse(json).as[Feature](Feature.reads))
        case (status, body) => {
          val message = s"Error patching feature ${id} : status=$status, body=$patch response=$body"
          logger.error(message)
          FastFuture.failed(IzanamiException(message))
        }
      }
  }

  def deleteFeature(id: String): Future[Unit] = {
    client
      .delete(s"/api/features/$id")
      .flatMap {
        case (status, json) if status == StatusCodes.OK || status == StatusCodes.NoContent =>
          FastFuture.successful(())
        case (status, body) => {
          val message = s"Error deleting feature ${id} : status=$status, response=$body"
          logger.error(message)
          FastFuture.failed(IzanamiException(message))
        }
      }
  }

}
