package store.memorywithdb

import akka.{Done, NotUsed}
import akka.actor.{ActorSystem, Cancellable}
import akka.http.scaladsl.util.FastFuture
import akka.stream.scaladsl.{Flow, RestartSource, Sink, Source}
import akka.stream.{ActorMaterializer, Materializer}
import domains.Key
import domains.events.EventStore
import domains.events.Events._
import env.{DbDomainConfig, InMemoryWithDbConfig}
import play.api.Logger
import play.api.inject.ApplicationLifecycle
import play.api.libs.json.JsValue
import store.Result.Result
import store._
import store.memory.BaseInMemoryJsonDataStore

import scala.concurrent.Future
import scala.concurrent.duration.DurationDouble
import scala.util.Success

sealed trait CacheEvent
case class Create(id: Key, data: JsValue)             extends CacheEvent
case class Update(oldId: Key, id: Key, data: JsValue) extends CacheEvent
case class Delete(id: Key)                            extends CacheEvent
case class DeleteAll(patterns: Seq[String])           extends CacheEvent

object InMemoryWithDbStore {
  import domains.config.Config
  import domains.feature.Feature
  import domains.script.GlobalScript
  import domains.abtesting.Experiment
  import domains.webhook.Webhook
  import domains.user.User
  import domains.abtesting.VariantBinding
  import domains.apikey.Apikey

  def apply(
      dbConfig: InMemoryWithDbConfig,
      dbDomainConfig: DbDomainConfig,
      underlyingDataStore: JsonDataStore,
      eventStore: EventStore,
      eventAdapter: Flow[IzanamiEvent, CacheEvent, NotUsed],
      applicationLifecycle: ApplicationLifecycle
  )(implicit system: ActorSystem): InMemoryWithDbStore = {
    val namespace = dbDomainConfig.conf.namespace
    new InMemoryWithDbStore(dbConfig, namespace, underlyingDataStore, eventStore, eventAdapter, applicationLifecycle)
  }

  lazy val globalScriptEventAdapter: Flow[IzanamiEvent, CacheEvent, NotUsed] = Flow[IzanamiEvent].collect {
    case GlobalScriptCreated(id, script, _, _)       => Create(id, GlobalScript.format.writes(script))
    case GlobalScriptUpdated(id, old, script, _, _)  => Update(old.id, id, GlobalScript.format.writes(script))
    case GlobalScriptDeleted(id, _, _, _)            => Delete(id)
    case GlobalScriptsDeleted(count, patterns, _, _) => DeleteAll(patterns)
  }

  lazy val configEventAdapter: Flow[IzanamiEvent, CacheEvent, NotUsed] = Flow[IzanamiEvent].collect {
    case ConfigCreated(id, value, _, _)        => Create(id, Config.format.writes(value))
    case ConfigUpdated(id, old, value, _, _)   => Update(old.id, id, Config.format.writes(value))
    case ConfigDeleted(id, _, _, _)            => Delete(id)
    case ConfigsDeleted(count, patterns, _, _) => DeleteAll(patterns)
  }

  lazy val featureEventAdapter: Flow[IzanamiEvent, CacheEvent, NotUsed] = Flow[IzanamiEvent].collect {
    case FeatureCreated(id, value, _, _)        => Create(id, Feature.format.writes(value))
    case FeatureUpdated(id, old, value, _, _)   => Update(old.id, id, Feature.format.writes(value))
    case FeatureDeleted(id, _, _, _)            => Delete(id)
    case FeaturesDeleted(count, patterns, _, _) => DeleteAll(patterns)
  }

  lazy val experimentEventAdapter: Flow[IzanamiEvent, CacheEvent, NotUsed] = Flow[IzanamiEvent].collect {
    case ExperimentCreated(id, value, _, _)        => Create(id, Experiment.format.writes(value))
    case ExperimentUpdated(id, old, value, _, _)   => Update(old.id, id, Experiment.format.writes(value))
    case ExperimentDeleted(id, _, _, _)            => Delete(id)
    case ExperimentsDeleted(count, patterns, _, _) => DeleteAll(patterns)
  }

  lazy val variantBindingEventAdapter: Flow[IzanamiEvent, CacheEvent, NotUsed] = Flow[IzanamiEvent].collect {
    case VariantBindingCreated(id, value, _, _) => Create(id.key, VariantBinding.format.writes(value))
  }

  lazy val webhookEventAdapter: Flow[IzanamiEvent, CacheEvent, NotUsed] = Flow[IzanamiEvent].collect {
    case WebhookCreated(id, value, _, _)        => Create(id, Webhook.format.writes(value))
    case WebhookUpdated(id, old, value, _, _)   => Update(old.clientId, id, Webhook.format.writes(value))
    case WebhookDeleted(id, _, _, _)            => Delete(id)
    case WebhooksDeleted(count, patterns, _, _) => DeleteAll(patterns)
  }

  lazy val userEventAdapter: Flow[IzanamiEvent, CacheEvent, NotUsed] = Flow[IzanamiEvent].collect {
    case UserCreated(id, value, _, _)        => Create(id, User.format.writes(value))
    case UserUpdated(id, old, value, _, _)   => Update(Key(old.id), id, User.format.writes(value))
    case UserDeleted(id, _, _, _)            => Delete(id)
    case UsersDeleted(count, patterns, _, _) => DeleteAll(patterns)
  }

  lazy val apikeyEventAdapter: Flow[IzanamiEvent, CacheEvent, NotUsed] = Flow[IzanamiEvent].collect {
    case ApikeyCreated(id, value, _, _)        => Create(id, Apikey.format.writes(value))
    case ApikeyUpdated(id, old, value, _, _)   => Update(Key(old.clientId), id, Apikey.format.writes(value))
    case ApikeyDeleted(id, _, _, _)            => Delete(id)
    case ApikeysDeleted(count, patterns, _, _) => DeleteAll(patterns)
  }

}

class InMemoryWithDbStore(dbConfig: InMemoryWithDbConfig,
                          name: String,
                          underlyingDataStore: JsonDataStore,
                          eventStore: EventStore,
                          eventAdapter: Flow[IzanamiEvent, CacheEvent, NotUsed],
                          applicationLifecycle: ApplicationLifecycle)(implicit system: ActorSystem)
    extends BaseInMemoryJsonDataStore(name)
    with JsonDataStore {

  import system.dispatcher
  private implicit val materializer: Materializer = ActorMaterializer()

  private val cancellable: Option[Cancellable] = dbConfig.pollingInterval.map { interval =>
    system.scheduler.schedule(interval, interval, () => {
      Logger.debug(s"Reloading data from db for $name")
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
      eventStore
        .events()
        .via(eventAdapter)
        .map {
          case e @ Create(id, data) =>
            Logger.debug(s"Applying create event $e")
            createSync(id, data)
            Done
          case e @ Update(oldId, id, data) =>
            Logger.debug(s"Applying update event $e")
            updateSync(oldId, id, data)
            Done
          case e @ Delete(id) =>
            Logger.debug(s"Applying delete event $e")
            deleteSync(id)
            Done
          case e @ DeleteAll(patterns) =>
            Logger.debug(s"Applying delete all event $e")
            deleteAllSync(patterns)
            Done
        }
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

  override def create(id: Key, data: JsValue): Future[Result[JsValue]] = {
    val res = underlyingDataStore.create(id, data)
    res.onComplete {
      case Success(Right(_)) => createSync(id, data)
      case _                 =>
    }
    res
  }

  override def update(oldId: Key, id: Key, data: JsValue): Future[Result[JsValue]] = {
    val res = underlyingDataStore.update(oldId, id, data)
    res.onComplete {
      case Success(Right(_)) => updateSync(oldId, id, data)
      case _                 =>
    }
    res
  }

  override def delete(id: Key): Future[Result[JsValue]] = {
    val res = underlyingDataStore.delete(id)
    res.onComplete {
      case Success(Right(_)) => deleteSync(id)
      case _                 =>
    }
    res
  }

  override def deleteAll(patterns: Seq[String]): Future[Result[Done]] = {
    val res = underlyingDataStore.deleteAll(patterns)
    res.onComplete {
      case Success(Right(_)) => deleteAllSync(patterns)
      case _                 =>
    }
    res
  }

  override def getById(id: Key): FindResult[JsValue] =
    SimpleFindResult(FastFuture.successful(getByIdSync(id).toList))

  override def getByIdLike(patterns: Seq[String], page: Int, nbElementPerPage: Int): Future[PagingResult[JsValue]] =
    FastFuture.successful(getByIdLikeSync(patterns, page, nbElementPerPage))

  override def getByIdLike(patterns: Seq[String]): Source[(Key, JsValue), NotUsed] =
    Source(getByIdLikeSync(patterns))

  override def count(patterns: Seq[String]): Future[Long] =
    FastFuture.successful(countSync(patterns))

}
