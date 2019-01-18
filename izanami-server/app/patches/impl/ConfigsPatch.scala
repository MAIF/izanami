package patches.impl

import akka.Done
import akka.actor.ActorSystem
import akka.stream.scaladsl.{Flow, Sink}
import akka.stream.{ActorMaterializer, Materializer}
import cats.effect.{ConcurrentEffect, ContextShift, Effect}
import domains.config.Config.ConfigKey
import domains.config.{Config, ConfigService}
import domains.events.EventStore
import domains.events.Events.IzanamiEvent
import env._
import libs.database.Drivers
import patches.PatchInstance
import play.api.Logger
import play.api.inject.ApplicationLifecycle
import play.api.libs.json.Json
import store.{JsonDataStore, Result}
import store.leveldb.DbStores
import store.memorywithdb.CacheEvent

private[impl] case class OldConfig(id: ConfigKey, value: String)

private[impl] object OldConfig {
  val format = Json.format[OldConfig]
}

class ConfigsPatch[F[_]: ConcurrentEffect: ContextShift](
    izanamiConfig: IzanamiConfig,
    configStore: => ConfigService[F],
    drivers: Drivers[F],
    eventStore: EventStore[F],
    applicationLifecycle: ApplicationLifecycle
)(implicit store: DbStores[F], system: ActorSystem)
    extends PatchInstance[F] {

  import libs.effects._
  import cats.implicits._
  implicit val materializer: Materializer = ActorMaterializer()

  import system.dispatcher

  override def patch(): F[Done] = {

    import libs.streams.syntax._
    val conf: DbDomainConfig = izanamiConfig.config.db
    Logger.info(s"Patch for configs starting for DB ${conf.`type`}")

    // format: off
    lazy val jsonDataStore = JsonDataStore[F](drivers, izanamiConfig, conf, eventStore, Flow[IzanamiEvent].mapConcat(_ => List.empty[CacheEvent]), applicationLifecycle)
    // format: on
    Logger.info(s"Patch for configs starting for DB ${conf.`type`} with ${jsonDataStore.getClass.getSimpleName}")
    jsonDataStore
      .getByIdLike(Seq("*"))
      .map(_._2)
      .mapAsyncF(2) { l =>
        val config: OldConfig = OldConfig.format.reads(l).get
        configStore.update(config.id, config.id, Config(config.id, Json.parse(config.value)))
      //Result.ok(()).pure[F]
      }
      .runWith(Sink.foreach {
        case Right(e) => Logger.debug(s"Config updated with success => $e")
        case Left(e)  => Logger.debug(s"Config update failure $e")
      })
      .toF

  }
}
