package patches

import akka.Done
import akka.actor.ActorSystem
import akka.http.scaladsl.util.FastFuture
import akka.stream.ActorMaterializer
import akka.stream.scaladsl.{Sink, Source}
import cats.effect.Effect
import domains.Key
import libs.logs.IzanamiLogger
import play.api.libs.json.Json
import store.JsonDataStore

import scala.util.{Failure, Success}

trait PatchInstance[F[_]] {

  def patch(): F[Done]
}

object Patchs {

  case class Patch(lastPatch: Int)

  object Patch {
    implicit val format = Json.format[Patch]
  }

}

class Patchs[F[_]: Effect](mayBeJsonStore: Option[JsonDataStore[F]], allpatches: Map[Int, PatchInstance[F]])(
    implicit val system: ActorSystem
) {

  import Patchs._
  import system.dispatcher
  import cats.implicits._
  import libs.effects._
  import libs.streams.syntax._
  implicit val materializer = ActorMaterializer()

  val key = Key("last:patch")

  def run(): F[Done] =
    mayBeJsonStore match {
      case None => Effect[F].pure(Done)
      case Some(store) =>
        store
          .getById(key)
          .map {
            case Some(json) =>
              json
                .validate[Patch]
                .fold(
                  err => {
                    IzanamiLogger.error(s"Error reading json : $err")
                    0
                  },
                  _.lastPatch
                )
            case None => 0
          }
          .flatMap { lastNum =>
            IzanamiLogger.info("Starting to patch Izanami ...")
            Source(allpatches.toList)
              .filter(_._1 > lastNum)
              .mapAsyncF(1) {
                case (num, p) =>
                  IzanamiLogger.info(s"Patch number $num from class ${p.getClass.getSimpleName}")
                  p.patch().flatMap { _ =>
                    store.update(key, key, Json.toJson(Patch(num)))
                  }
              }
              .watchTermination() {
                case (_, done) =>
                  done.onComplete {
                    case Success(_) => IzanamiLogger.info("All patchs done with Success")
                    case Failure(e) => IzanamiLogger.error(s"Error while patching Izanami", e)
                  }
              }
              .runWith(Sink.ignore)
              .toF
          }
    }

}
