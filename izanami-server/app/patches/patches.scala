package patches

import akka.Done
import akka.actor.ActorSystem
import akka.stream.ActorMaterializer
import akka.stream.scaladsl.{Sink, Source}
import domains.configuration.{GlobalContext, PlayModule}
import domains.Key
import libs.logs.IzanamiLogger
import play.api.libs.json.Json
import store.datastore.JsonDataStore
import zio.{Has, Task, ZIO, ZLayer}

import scala.util.{Failure, Success}
import env.IzanamiConfig
import env.DbDomainConfig
import akka.stream.scaladsl.Flow
import domains.abtesting.events.ExperimentVariantEventServiceModule
import domains.events.Events.IzanamiEvent
import env.configuration.IzanamiConfigModule
import libs.database.Drivers
import play.api.inject.ApplicationLifecycle
import store.memorywithdb.CacheEvent
import patches.impl.ConfigsPatch

trait PatchInstance {

  def patch(): zio.RIO[GlobalContext, Done]
}

object Patchs {

  case class Patch(lastPatch: Int)

  object Patch {
    implicit val format = Json.format[Patch]
  }

  def start: zio.RIO[GlobalContext, Unit] = ZIO.accessM(_.get[Patchs].run().ignore)

  val live: ZLayer[PlayModule with Drivers with IzanamiConfigModule, Nothing, Has[Patchs]] =
    ZLayer.fromFunction { mix =>
      val playModule: PlayModule.Service    = mix.get[PlayModule.Service]
      implicit val actorSystem: ActorSystem = playModule.system
      val izanamiConfig: IzanamiConfig      = mix.get[IzanamiConfigModule.Service].izanamiConfig
      val drivers: Drivers.Service          = mix.get[Drivers.Service]
      Patchs(izanamiConfig, drivers, playModule.applicationLifecycle)
    }

  def apply(izanamiConfig: IzanamiConfig, drivers: Drivers.Service, applicationLifecycle: ApplicationLifecycle)(
      implicit system: ActorSystem
  ): Patchs = {
    import com.softwaremill.macwire._
    lazy val conf: DbDomainConfig = izanamiConfig.patch.db
    lazy val eventAdapter         = Flow[IzanamiEvent].mapConcat(_ => List.empty[CacheEvent])
    lazy val jsonStore: JsonDataStore.Service =
      JsonDataStore(drivers, izanamiConfig, conf, eventAdapter, applicationLifecycle)
    lazy val jsonStoreOpt: Option[JsonDataStore.Service] = Some(jsonStore)
    lazy val configsPatch: ConfigsPatch                  = wire[ConfigsPatch]

    lazy val allPatchs: Map[Int, PatchInstance] = Map(1 -> configsPatch)

    new Patchs(izanamiConfig, jsonStoreOpt, allPatchs)
  }

}

class Patchs(izanamiConfig: IzanamiConfig,
             mayBeJsonStore: Option[JsonDataStore.Service],
             allpatches: Map[Int, PatchInstance])(
    implicit val system: ActorSystem
) {

  import Patchs._

  val key = Key("last:patch")

  def run(): zio.RIO[GlobalContext, Done] =
    if (izanamiConfig.patchEnabled) {
      for {
        runtime <- ZIO.runtime[GlobalContext]
        res <- mayBeJsonStore match {
                case None => Task.succeed(Done)
                case Some(store) =>
                  store.start *>
                  store
                    .getById(key)
                    .either
                    .map {
                      case Right(Some(json)) =>
                        json
                          .validate[Patch]
                          .fold(
                            err => {
                              IzanamiLogger.error(s"Error reading json : $err")
                              0
                            },
                            _.lastPatch
                          )
                      case _ => 0
                    }
                    .flatMap { lastNum =>
                      IzanamiLogger.info("Starting to patch Izanami ...")
                      Task.fromFuture { implicit ec =>
                        Source(allpatches.toList)
                          .filter(_._1 > lastNum)
                          .mapAsync(1) {
                            case (num, p) =>
                              IzanamiLogger.info(s"Patch number $num from class ${p.getClass.getSimpleName}")
                              runtime.unsafeRunToFuture(p.patch().flatMap { _ =>
                                store.update(key, key, Json.toJson(Patch(num))).either
                              })
                          }
                          .watchTermination() {
                            case (_, done) =>
                              done.onComplete {
                                case Success(_) => IzanamiLogger.info("All patchs done with Success")
                                case Failure(e) => IzanamiLogger.error(s"Error while patching Izanami", e)
                              }
                          }
                          .runWith(Sink.ignore)
                      }
                    }
              }
      } yield res
    } else {
      ZIO.succeed(Done)
    }

}
