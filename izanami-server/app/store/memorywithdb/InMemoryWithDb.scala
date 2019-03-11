package store.memorywithdb

import akka.{Done, NotUsed}
import akka.actor.{ActorSystem, Cancellable}
import akka.http.scaladsl.util.FastFuture
import akka.stream.scaladsl.{Flow, RestartSource, Sink, Source}
import akka.stream.{ActorMaterializer, Materializer}
import cats.effect.Effect
import domains.Key
import domains.abtesting.{ExperimentInstances}
import domains.apikey.ApikeyInstances
import domains.config.ConfigInstances
import domains.events.EventStore
import domains.events.Events._
import domains.feature.FeatureInstances
import domains.script.GlobalScriptInstances
import domains.user.UserInstances
import domains.webhook.WebhookInstances
import env.{DbDomainConfig, InMemoryWithDbConfig}
import libs.logs.IzanamiLogger
import play.api.inject.ApplicationLifecycle
import play.api.libs.json.JsValue
import store.Result.Result
import store._
import store.memory.BaseInMemoryJsonDataStore

import scala.concurrent.Future
import scala.concurrent.duration.DurationDouble

sealed trait CacheEvent
case class Create(id: Key, data: JsValue)             extends CacheEvent
case class Update(oldId: Key, id: Key, data: JsValue) extends CacheEvent
case class Delete(id: Key)                            extends CacheEvent
case class DeleteAll(patterns: Seq[String])           extends CacheEvent

object InMemoryWithDbStore {

  def apply[F[_]: Effect](
      dbConfig: InMemoryWithDbConfig,
      dbDomainConfig: DbDomainConfig,
      underlyingDataStore: JsonDataStore[F],
      eventStore: EventStore[F],
      eventAdapter: Flow[IzanamiEvent, CacheEvent, NotUsed],
      applicationLifecycle: ApplicationLifecycle
  )(implicit system: ActorSystem): InMemoryWithDbStore[F] = {
    val namespace = dbDomainConfig.conf.namespace
    new InMemoryWithDbStore(dbConfig, namespace, underlyingDataStore, eventStore, eventAdapter, applicationLifecycle)
  }

  lazy val globalScriptEventAdapter: Flow[IzanamiEvent, CacheEvent, NotUsed] = Flow[IzanamiEvent].collect {
    case GlobalScriptCreated(id, script, _, _)       => Create(id, GlobalScriptInstances.format.writes(script))
    case GlobalScriptUpdated(id, old, script, _, _)  => Update(old.id, id, GlobalScriptInstances.format.writes(script))
    case GlobalScriptDeleted(id, _, _, _)            => Delete(id)
    case GlobalScriptsDeleted(count, patterns, _, _) => DeleteAll(patterns)
  }

  lazy val configEventAdapter: Flow[IzanamiEvent, CacheEvent, NotUsed] = Flow[IzanamiEvent].collect {
    case ConfigCreated(id, value, _, _)        => Create(id, ConfigInstances.format.writes(value))
    case ConfigUpdated(id, old, value, _, _)   => Update(old.id, id, ConfigInstances.format.writes(value))
    case ConfigDeleted(id, _, _, _)            => Delete(id)
    case ConfigsDeleted(count, patterns, _, _) => DeleteAll(patterns)
  }

  lazy val featureEventAdapter: Flow[IzanamiEvent, CacheEvent, NotUsed] = Flow[IzanamiEvent].collect {
    case FeatureCreated(id, value, _, _)        => Create(id, FeatureInstances.format.writes(value))
    case FeatureUpdated(id, old, value, _, _)   => Update(old.id, id, FeatureInstances.format.writes(value))
    case FeatureDeleted(id, _, _, _)            => Delete(id)
    case FeaturesDeleted(count, patterns, _, _) => DeleteAll(patterns)
  }

  lazy val experimentEventAdapter: Flow[IzanamiEvent, CacheEvent, NotUsed] = Flow[IzanamiEvent].collect {
    case ExperimentCreated(id, value, _, _)        => Create(id, ExperimentInstances.format.writes(value))
    case ExperimentUpdated(id, old, value, _, _)   => Update(old.id, id, ExperimentInstances.format.writes(value))
    case ExperimentDeleted(id, _, _, _)            => Delete(id)
    case ExperimentsDeleted(count, patterns, _, _) => DeleteAll(patterns)
  }

  lazy val webhookEventAdapter: Flow[IzanamiEvent, CacheEvent, NotUsed] = Flow[IzanamiEvent].collect {
    case WebhookCreated(id, value, _, _)        => Create(id, WebhookInstances.format.writes(value))
    case WebhookUpdated(id, old, value, _, _)   => Update(old.clientId, id, WebhookInstances.format.writes(value))
    case WebhookDeleted(id, _, _, _)            => Delete(id)
    case WebhooksDeleted(count, patterns, _, _) => DeleteAll(patterns)
  }

  lazy val userEventAdapter: Flow[IzanamiEvent, CacheEvent, NotUsed] = Flow[IzanamiEvent].collect {
    case UserCreated(id, value, _, _)        => Create(id, UserInstances.format.writes(value))
    case UserUpdated(id, old, value, _, _)   => Update(Key(old.id), id, UserInstances.format.writes(value))
    case UserDeleted(id, _, _, _)            => Delete(id)
    case UsersDeleted(count, patterns, _, _) => DeleteAll(patterns)
  }

  lazy val apikeyEventAdapter: Flow[IzanamiEvent, CacheEvent, NotUsed] = Flow[IzanamiEvent].collect {
    case ApikeyCreated(id, value, _, _)        => Create(id, ApikeyInstances.format.writes(value))
    case ApikeyUpdated(id, old, value, _, _)   => Update(Key(old.clientId), id, ApikeyInstances.format.writes(value))
    case ApikeyDeleted(id, _, _, _)            => Delete(id)
    case ApikeysDeleted(count, patterns, _, _) => DeleteAll(patterns)
  }

}

class InMemoryWithDbStore[F[_]: Effect](dbConfig: InMemoryWithDbConfig,
                                        name: String,
                                        underlyingDataStore: JsonDataStore[F],
                                        eventStore: EventStore[F],
                                        eventAdapter: Flow[IzanamiEvent, CacheEvent, NotUsed],
                                        applicationLifecycle: ApplicationLifecycle)(implicit system: ActorSystem)
    extends BaseInMemoryJsonDataStore(name)
    with JsonDataStore[F] {

  import cats.implicits._
  import system.dispatcher
  private implicit val materializer: Materializer = ActorMaterializer()

  private val cancellable: Option[Cancellable] = dbConfig.pollingInterval.map { interval =>
    system.scheduler.schedule(interval, interval, () => {
      IzanamiLogger.debug(s"Reloading data from db for $name")
      loadCacheFromDb.runWith(Sink.ignore)
      ()
    })
  }

  applicationLifecycle.addStopHook(() => {
    FastFuture.successful(cancellable.foreach(_.cancel()))
  })

  init()

  private def init(): Future[Done] = {
    val value: Source[Done.type, NotUsed] =
      loadCacheFromDb.concat(
        eventStore
          .events()
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
              deleteAllSync(patterns)
              Done
          }
      )
    RestartSource
      .onFailuresWithBackoff(1.second, 20.second, 1)(() => value)
      .runWith(Sink.ignore)
  }

  private def loadCacheFromDb =
    underlyingDataStore
      .getByIdLike(Seq("*"))
      .map {
        case (k, v) =>
          inMemoryStore.put(k, v)
          Done
      }

  override def create(id: Key, data: JsValue): F[Result[JsValue]] =
    for {
      res <- underlyingDataStore.create(id, data)
      _   <- createSync(id, data).pure[F]
    } yield res

  override def update(oldId: Key, id: Key, data: JsValue): F[Result[JsValue]] =
    for {
      res <- underlyingDataStore.update(oldId, id, data)
      _   <- updateSync(oldId, id, data).pure[F]
    } yield res

  override def delete(id: Key): F[Result[JsValue]] =
    for {
      res <- underlyingDataStore.delete(id)
      _   <- deleteSync(id).pure[F]
    } yield res

  override def deleteAll(patterns: Seq[String]): F[Result[Done]] =
    for {
      res <- underlyingDataStore.deleteAll(patterns)
      _   <- deleteAllSync(patterns).pure[F]
    } yield res

  override def getById(id: Key): F[Option[JsValue]] =
    getByIdSync(id).pure[F]

  override def getByIdLike(patterns: Seq[String], page: Int, nbElementPerPage: Int): F[PagingResult[JsValue]] =
    getByIdLikeSync(patterns, page, nbElementPerPage).pure[F]

  override def getByIdLike(patterns: Seq[String]): Source[(Key, JsValue), NotUsed] =
    Source(getByIdLikeSync(patterns))

  override def count(patterns: Seq[String]): F[Long] =
    countSync(patterns).pure[F]

}
