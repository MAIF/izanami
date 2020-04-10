package store.memorywithdb

import akka.{Done, NotUsed}
import akka.actor.{ActorSystem, Cancellable}
import akka.stream.scaladsl.{Flow, RestartSource, Sink, Source}
import cats.implicits._
import domains.Key
import domains.abtesting.ExperimentInstances
import domains.apikey.ApikeyInstances
import domains.config.ConfigInstances
import domains.configuration.PlayModule
import domains.events.EventStore
import domains.events.Events._
import domains.feature.FeatureInstances
import domains.script.GlobalScriptInstances
import domains.user.UserInstances
import domains.webhook.WebhookInstances
import env.{DbDomainConfig, InMemoryWithDbConfig}
import libs.logs.{IzanamiLogger, ZLogger}
import play.api.libs.json.JsValue
import domains.errors.IzanamiErrors
import store._
import store.datastore._
import store.memory.BaseInMemoryJsonDataStore
import zio.clock.Clock
import zio.duration.Duration
import zio.{Exit, Fiber, Ref, UIO, ZIO, ZLayer}

import scala.collection.concurrent.TrieMap
import scala.concurrent.duration.DurationDouble

sealed trait CacheEvent
case class Create(id: Key, data: JsValue)             extends CacheEvent
case class Update(oldId: Key, id: Key, data: JsValue) extends CacheEvent
case class Delete(id: Key)                            extends CacheEvent
case class DeleteAll(patterns: Seq[String])           extends CacheEvent

object InMemoryWithDbStore {

  def live(
      dbConfig: InMemoryWithDbConfig,
      dbDomainConfig: DbDomainConfig,
      eventAdapter: Flow[IzanamiEvent, CacheEvent, NotUsed]
  ): ZLayer[JsonDataStore with DataStoreLayerContext, Throwable, JsonDataStore] =
    ZLayer.fromFunctionM { mix: JsonDataStore with DataStoreLayerContext =>
      val namespace                 = dbDomainConfig.conf.namespace
      implicit val sys: ActorSystem = mix.get[PlayModule.Service].system
      ZLogger
        .info(
          s"Loading InMemoryWithDbStore for namespace ${dbDomainConfig.conf.namespace} with underlying store"
        )
        .provide(mix) *> Ref.make(Option.empty[OpenResources]).map { r =>
        new InMemoryWithDbStore(dbConfig, namespace, mix.get[JsonDataStore.Service], eventAdapter, r)
      }
    }

  lazy val globalScriptEventAdapter: Flow[IzanamiEvent, CacheEvent, NotUsed] = Flow[IzanamiEvent].collect {
    case GlobalScriptCreated(id, script, _, _, _) => Create(id, GlobalScriptInstances.format.writes(script))
    case GlobalScriptUpdated(id, old, script, _, _, _) =>
      Update(old.id, id, GlobalScriptInstances.format.writes(script))
    case GlobalScriptDeleted(id, _, _, _, _)        => Delete(id)
    case GlobalScriptsDeleted(_, patterns, _, _, _) => DeleteAll(patterns)
  }

  lazy val configEventAdapter: Flow[IzanamiEvent, CacheEvent, NotUsed] = Flow[IzanamiEvent].collect {
    case ConfigCreated(id, value, _, _, _)      => Create(id, ConfigInstances.format.writes(value))
    case ConfigUpdated(id, old, value, _, _, _) => Update(old.id, id, ConfigInstances.format.writes(value))
    case ConfigDeleted(id, _, _, _, _)          => Delete(id)
    case ConfigsDeleted(_, patterns, _, _, _)   => DeleteAll(patterns)
  }

  lazy val featureEventAdapter: Flow[IzanamiEvent, CacheEvent, NotUsed] = Flow[IzanamiEvent].collect {
    case FeatureCreated(id, value, _, _, _)      => Create(id, FeatureInstances.format.writes(value))
    case FeatureUpdated(id, old, value, _, _, _) => Update(old.id, id, FeatureInstances.format.writes(value))
    case FeatureDeleted(id, _, _, _, _)          => Delete(id)
    case FeaturesDeleted(_, patterns, _, _, _)   => DeleteAll(patterns)
  }

  lazy val experimentEventAdapter: Flow[IzanamiEvent, CacheEvent, NotUsed] = Flow[IzanamiEvent].collect {
    case ExperimentCreated(id, value, _, _, _)      => Create(id, ExperimentInstances.format.writes(value))
    case ExperimentUpdated(id, old, value, _, _, _) => Update(old.id, id, ExperimentInstances.format.writes(value))
    case ExperimentDeleted(id, _, _, _, _)          => Delete(id)
    case ExperimentsDeleted(_, patterns, _, _, _)   => DeleteAll(patterns)
  }

  lazy val webhookEventAdapter: Flow[IzanamiEvent, CacheEvent, NotUsed] = Flow[IzanamiEvent].collect {
    case WebhookCreated(id, value, _, _, _)      => Create(id, WebhookInstances.format.writes(value))
    case WebhookUpdated(id, old, value, _, _, _) => Update(old.clientId, id, WebhookInstances.format.writes(value))
    case WebhookDeleted(id, _, _, _, _)          => Delete(id)
    case WebhooksDeleted(_, patterns, _, _, _)   => DeleteAll(patterns)
  }

  lazy val userEventAdapter: Flow[IzanamiEvent, CacheEvent, NotUsed] = Flow[IzanamiEvent].collect {
    case UserCreated(id, value, _, _, _)      => Create(id, UserInstances.format.writes(value))
    case UserUpdated(id, old, value, _, _, _) => Update(Key(old.id), id, UserInstances.format.writes(value))
    case UserDeleted(id, _, _, _, _)          => Delete(id)
    case UsersDeleted(_, patterns, _, _, _)   => DeleteAll(patterns)
  }

  lazy val apikeyEventAdapter: Flow[IzanamiEvent, CacheEvent, NotUsed] = Flow[IzanamiEvent].collect {
    case ApikeyCreated(id, value, _, _, _)      => Create(id, ApikeyInstances.format.writes(value))
    case ApikeyUpdated(id, old, value, _, _, _) => Update(Key(old.clientId), id, ApikeyInstances.format.writes(value))
    case ApikeyDeleted(id, _, _, _, _)          => Delete(id)
    case ApikeysDeleted(_, patterns, _, _, _)   => DeleteAll(patterns)
  }
}

case class OpenResources(fiber: Fiber[Throwable, Unit], c: Option[Cancellable]) {
  def stop: ZIO[Any, Nothing, Unit] =
    (fiber.interrupt *> c.fold(ZIO.unit)(c => UIO(c.cancel()))).unit
}

class InMemoryWithDbStore(
    dbConfig: InMemoryWithDbConfig,
    name: String,
    underlyingDataStore: JsonDataStore.Service,
    eventAdapter: Flow[IzanamiEvent, CacheEvent, NotUsed],
    toClose: Ref[Option[OpenResources]],
    override val inMemoryStore: TrieMap[Key, JsValue] = TrieMap.empty[Key, JsValue]
)(implicit system: ActorSystem)
    extends BaseInMemoryJsonDataStore()
    with JsonDataStore.Service {

  import zio._
  import system.dispatcher

  override def start: RIO[DataStoreContext with Clock, Unit] =
    for {
      _            <- ZLogger.info(s"Initializing in memory DB")
      _            <- refreshCacheFromDb
      fiberEvents  <- listenEvents.unit.fork
      fiberPolling <- polling
      _            <- toClose.set(Some(OpenResources(fiberEvents, fiberPolling)))
    } yield ()

  private val refreshCacheFromDb: RIO[DataStoreContext, Unit] =
    for {
      refreshCacheProcess <- underlyingDataStore
                              .findByQuery(Query.oneOf("*"))
                              .map {
                                _.map {
                                  case (k, v) =>
                                    inMemoryStore.put(k, v)
                                    Done
                                }
                              }
      _ <- ZIO.fromFuture(_ => refreshCacheProcess.runWith(Sink.ignore))
    } yield ()

  private val polling: ZIO[DataStoreContext with Clock, Throwable, Option[Cancellable]] =
    dbConfig.pollingInterval match {
      case Some(interval) =>
        ZIO.runtime[DataStoreContext].map { runtime =>
          system.scheduler
            .scheduleWithFixedDelay(interval, interval)(new Runnable {
              override def run(): Unit = {
                IzanamiLogger.debug(s"Reloading data from db for $name")
                runtime.unsafeRunToFuture(refreshCacheFromDb)
                ()
              }
            })
            .some
        }
      case None => ZIO(none[Cancellable])
    }

  override def close: RIO[DataStoreContext, Unit] =
    toClose.get.flatMap {
      case Some(r) => r.stop.unit
      case _       => ZIO.unit
    }

  private val listenEvents: ZIO[DataStoreContext, Throwable, Unit] =
    for {
      events <- EventStore.events()
      res <- IO.fromFuture { _ =>
              RestartSource
                .onFailuresWithBackoff(1.second, 20.second, 1)(
                  () =>
                    events
                      .via(eventAdapter)
                      .map {
                        case e @ Create(id, data) =>
                          IzanamiLogger.debug(s"Applying create event $e")
                          createSync(id, data)
                          Done
                        case e @ Update(oldId, id, data) =>
                          IzanamiLogger.debug(s"Applying update event $e")
                          updateSync(oldId, id, data)
                          Done
                        case e @ Delete(id) =>
                          IzanamiLogger.debug(s"Applying delete event $e")
                          deleteSync(id)
                          Done
                        case e @ DeleteAll(patterns) =>
                          IzanamiLogger.debug(s"Applying delete all event $e")
                          deleteAllSync(Query.oneOf(patterns))
                          Done
                    }
                )
                .runWith(Sink.ignore)
            }.unit
    } yield res

  override def create(id: Key, data: JsValue): ZIO[DataStoreContext, IzanamiErrors, JsValue] =
    for {
      res <- underlyingDataStore.create(id, data)
      _   <- IO.fromEither(createSync(id, data))
    } yield res

  override def update(oldId: Key, id: Key, data: JsValue): ZIO[DataStoreContext, IzanamiErrors, JsValue] =
    for {
      res <- underlyingDataStore.update(oldId, id, data)
      _   <- IO.fromEither(updateSync(oldId, id, data))
    } yield res

  override def delete(id: Key): ZIO[DataStoreContext, IzanamiErrors, JsValue] =
    for {
      res <- underlyingDataStore.delete(id)
      _   <- IO.fromEither(deleteSync(id))
    } yield res

  override def deleteAll(query: Query): ZIO[DataStoreContext, IzanamiErrors, Unit] =
    for {
      res <- underlyingDataStore.deleteAll(query)
      _   <- IO.fromEither(deleteAllSync(query))
    } yield res

  override def getById(id: Key): RIO[DataStoreContext, Option[JsValue]] =
    Task(getByIdSync(id))

  override def findByQuery(query: Query,
                           page: Int,
                           nbElementPerPage: Int): RIO[DataStoreContext, PagingResult[JsValue]] =
    Task(findByQuerySync(query, page, nbElementPerPage))

  override def findByQuery(query: Query): RIO[DataStoreContext, Source[(Key, JsValue), NotUsed]] =
    Task(Source(findByQuerySync(query)))

  override def count(query: Query): RIO[DataStoreContext, Long] =
    Task(countSync(query))

}
