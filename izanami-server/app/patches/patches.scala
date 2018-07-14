package patches

import akka.Done
import akka.actor.ActorSystem
import akka.http.scaladsl.util.FastFuture
import akka.stream.ActorMaterializer
import akka.stream.scaladsl.{Sink, Source}
import domains.Key
import play.api.Logger
import play.api.libs.json.Json
import store.JsonDataStore

import scala.concurrent.Future
import scala.util.{Failure, Success}

trait PatchInstance {

  def patch(): Future[Done]
}

object Patchs {

  case class Patch(lastPatch: Int)

  object Patch {
    implicit val format = Json.format[Patch]
  }

}

class Patchs(mayBeJsonStore: Option[JsonDataStore], allpatches: Map[Int, PatchInstance], actorSystem: ActorSystem) {

  import Patchs._
  import actorSystem.dispatcher
  implicit val system: ActorSystem = actorSystem
  implicit val materializer        = ActorMaterializer()

  val key = Key("last:patch")

  def run(): Future[Done] =
    mayBeJsonStore match {
      case None => FastFuture.successful(Done)
      case Some(store) =>
        store
          .getById(key)
          .map {
            case Some(json) =>
              json
                .validate[Patch]
                .fold(
                  err => {
                    Logger.error(s"Error reading json : $err")
                    0
                  },
                  _.lastPatch
                )
            case None => 0
          }
          .flatMap { lastNum =>
            Logger.info("Starting to patch Izanami ...")
            Source(allpatches.toList)
              .filter(_._1 > lastNum)
              .mapAsync(1) {
                case (num, p) =>
                  Logger.info(s"Patch number $num from class ${p.getClass.getSimpleName}")
                  p.patch().flatMap { _ =>
                    store.update(key, key, Json.toJson(Patch(num)))
                  }
              }
              .watchTermination() {
                case (_, done) =>
                  done.onComplete {
                    case Success(_) => Logger.info("All patchs done with Success")
                    case Failure(e) => Logger.error(s"Error while patching Izanami", e)
                  }
              }
              .runWith(Sink.ignore)
          }
    }

}
