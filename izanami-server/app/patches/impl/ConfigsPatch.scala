package patches.impl

import akka.Done
import akka.actor.ActorSystem
import akka.stream.scaladsl.{Flow, Sink}
import akka.stream.{ActorMaterializer, Materializer}
import domains.config.ConfigStore.ConfigKey
import domains.config.{Config, ConfigStore}
import domains.events.EventStore
import domains.events.Events.IzanamiEvent
import env._
import libs.database.Drivers
import patches.PatchInstance
import play.api.Logger
import play.api.inject.ApplicationLifecycle
import play.api.libs.json.Json
import store.JsonDataStore
import store.memorywithdb.CacheEvent

import scala.concurrent.Future

private[impl] case class OldConfig(id: ConfigKey, value: String)

private[impl] object OldConfig {
  val format = Json.format[OldConfig]
}

class ConfigsPatch(
    izanamiConfig: IzanamiConfig,
    configStore: => ConfigStore,
    drivers: Drivers,
    eventStore: EventStore,
    applicationLifecycle: ApplicationLifecycle,
    actorSystem: ActorSystem
) extends PatchInstance {

  implicit val system: ActorSystem        = actorSystem
  implicit val materializer: Materializer = ActorMaterializer()

  override def patch(): Future[Done] = {

    val conf: DbDomainConfig = izanamiConfig.config.db
    Logger.info(s"Patch for configs starting for DB ${conf.`type`}")

    // format: off
    lazy val jsonDataStore = JsonDataStore(drivers, izanamiConfig, conf, eventStore, Flow[IzanamiEvent].mapConcat(_ => List.empty[CacheEvent]), applicationLifecycle)
    // format: on
    Logger.info(s"Patch for configs starting for DB ${conf.`type`} with ${jsonDataStore.getClass.getSimpleName}")
    jsonDataStore
      .getByIdLike(Seq("*"))
      .map(_._2)
      .mapAsync(2) { l =>
        val config: OldConfig = OldConfig.format.reads(l).get
        configStore.update(config.id, config.id, Config(config.id, Json.parse(config.value)))
      }
      .runWith(Sink.foreach {
        case Right(e) => Logger.debug(s"Config updated with success => $e")
        case Left(e)  => Logger.debug(s"Config update failure $e")
      })

  }
}
