import akka.actor.ActorSystem
import akka.{Done, NotUsed}
import akka.stream.scaladsl.Flow
import cats.effect.IO
import com.softwaremill.macwire._
import controllers._
import controllers.actions.{AuthAction, AuthContext, SecuredAction, SecuredAuthContext}
import domains.config.{Config, ConfigStore, ConfigStoreImpl}
import domains.Import
import domains.abtesting.impl._
import domains.abtesting.{ExperimentVariantEventStore, _}
import domains.apikey.{Apikey, ApikeyStore, ApikeyStoreImpl}
import domains.events.Events.IzanamiEvent
import domains.events._
import domains.events.impl.{BasicEventStore, DistributedPubSubEventStore, KafkaEventStore, RedisEventStore}
import domains.feature.{Feature, FeatureService, FeatureServiceImpl}
import domains.script.{GlobalScript, GlobalScriptStore, GlobalScriptStoreImpl}
import domains.user.{User, UserStore, UserStoreImpl}
import domains.webhook.{Webhook, WebhookStore, WebhookStoreImpl}
import libs.database.Drivers
import env._
import filters.{IzanamiDefaultFilter, OtoroshiFilter}
import handlers.ErrorHandler
import patches.Patchs
import patches.impl.ConfigsPatch
import play.api.ApplicationLoader.Context
import play.api._
import play.api.http.HttpErrorHandler
import play.api.libs.ws.ahc.AhcWSComponents
import play.api.mvc.{ActionBuilder, AnyContent, EssentialFilter}
import play.api.routing.Router
import router.Routes
import store.leveldb.DbStores
import store.{Healthcheck, JsonDataStore}
import store.memorywithdb.{CacheEvent, InMemoryWithDbStore}

import scala.concurrent.Future

class IzanamiLoader extends ApplicationLoader {
  def load(context: Context) = {
    LoggerConfigurator(context.environment.classLoader).foreach {
      _.configure(context.environment, context.initialConfiguration, Map.empty)
    }
    new modules.IzanamiComponentsInstances(context).application
  }
}

package object modules {

  class IzanamiComponentsInstances(context: Context)
      extends BuiltInComponentsFromContext(context)
      with IzanamiMainComponents {}

  trait IzanamiMainComponents extends BuiltInComponentsFromContext with AssetsComponents with AhcWSComponents {

    import cats.effect.implicits._

    lazy val izanamiConfig: IzanamiConfig = IzanamiConfig(configuration)

    implicit val system: ActorSystem     = actorSystem
    implicit val lDbStores: DbStores[IO] = new DbStores[IO]

    Logger.info(s"Configuration: \n$izanamiConfig")

    lazy val _env: Env = izanamiConfig.baseURL match {
      case "/" => wire[Env]
      case c =>
        val aFinder: AssetsFinder = assetsFinder
          .withUrlPrefix(s"$c/assets")
        wire[Env].copy(assetsFinder = aFinder)
    }

    def authAction: ActionBuilder[AuthContext, AnyContent]                       = wire[AuthAction]
    def securedSecuredAuthContext: ActionBuilder[SecuredAuthContext, AnyContent] = wire[SecuredAction]

    lazy val homeController: HomeController = wire[HomeController]

    lazy val authController: effect.AuthControllerEff = wire[AuthController[IO]]

    lazy val drivers: Drivers = Drivers(izanamiConfig, configuration, applicationLifecycle)

    /* Event store */
    // format: off
    lazy val eventStore: EventStore[IO] = izanamiConfig.events match {
      case InMemoryEvents(_) => new BasicEventStore[IO]
      case KafkaEvents(c) => new KafkaEventStore[IO](environment, actorSystem, izanamiConfig.db.kafka.get, c)
      case RedisEvents(c) => new RedisEventStore[IO](drivers.redisClient.get, c, actorSystem)
      case DistributedEvents(c) => new DistributedPubSubEventStore[IO](configuration.underlying, c, applicationLifecycle)
      case other => throw new IllegalArgumentException(s"Unknown event store $other")
    }
    // format: on

    /* Global script */
    lazy val globalScriptStore: GlobalScriptStore[IO] = {
      val conf              = izanamiConfig.globalScript.db
      lazy val eventAdapter = InMemoryWithDbStore.globalScriptEventAdapter
      lazy val dbStore: JsonDataStore[IO] =
        JsonDataStore[IO](drivers, izanamiConfig, conf, eventStore, eventAdapter, applicationLifecycle)
      val store: GlobalScriptStore[IO] = wire[GlobalScriptStoreImpl[IO]]
      Import.importFile(conf, GlobalScript.importData(store))
      store
    }

    lazy val globalScripController: effect.GlobalScriptControllerEff = wire[GlobalScriptController[IO]]

    /* Config */
    lazy val configStore: ConfigStore[IO] = {
      val conf              = izanamiConfig.config.db
      lazy val eventAdapter = InMemoryWithDbStore.configEventAdapter
      lazy val dbStore: JsonDataStore[IO] =
        JsonDataStore[IO](drivers, izanamiConfig, conf, eventStore, eventAdapter, applicationLifecycle)
      val store: ConfigStore[IO] = wire[ConfigStoreImpl[IO]]
      Import.importFile(conf, Config.importData(store))
      store
    }

    lazy val configController: effect.ConfigControllerEff = wire[ConfigController[IO]]

    /* Feature */
    lazy val featureStore: FeatureService[IO] = {
      val conf              = izanamiConfig.features.db
      lazy val eventAdapter = InMemoryWithDbStore.featureEventAdapter
      lazy val dbStore: JsonDataStore[IO] =
        JsonDataStore[IO](drivers, izanamiConfig, conf, eventStore, eventAdapter, applicationLifecycle)
      val store: FeatureService[IO] = wire[FeatureServiceImpl[IO]]
      Import.importFile(conf, Feature.importData(store))
      store
    }

    lazy val featureController: effect.FeatureControllerEff = wire[FeatureController[IO]]

    /* Experiment */
    lazy val experimentStore: ExperimentStore[IO] = {
      val conf              = izanamiConfig.experiment.db
      lazy val eventAdapter = InMemoryWithDbStore.experimentEventAdapter
      lazy val dbStore: JsonDataStore[IO] =
        JsonDataStore[IO](drivers, izanamiConfig, conf, eventStore, eventAdapter, applicationLifecycle)
      val store: ExperimentStore[IO] = wire[ExperimentStoreImpl[IO]]
      Import.importFile(conf, Experiment.importData(store))
      store
    }

    lazy val variantBindingStore: VariantBindingStore[IO] = {
      val conf              = izanamiConfig.variantBinding.db
      lazy val eventAdapter = InMemoryWithDbStore.variantBindingEventAdapter
      lazy val dbStore: JsonDataStore[IO] =
        JsonDataStore[IO](drivers, izanamiConfig, conf, eventStore, eventAdapter, applicationLifecycle)
      lazy val store: VariantBindingStore[IO] = wire[VariantBindingStoreImpl[IO]]
      Import.importFile(conf, VariantBinding.importData(store))
      store
    }

    lazy val experimentVariantEventStore: ExperimentVariantEventStore[IO] = {
      val conf = izanamiConfig.experimentEvent.db
      // format: off

      def getExperimentVariantEventStore(dbType: DbType): ExperimentVariantEventStore[IO] = dbType match {
        case InMemory  => ExperimentVariantEventInMemoryStore(conf, actorSystem)
        case Redis     => ExperimentVariantEventRedisStore(drivers.redisClient, eventStore, actorSystem)
        case LevelDB   => ExperimentVariantEventLevelDBStore(izanamiConfig.db.leveldb.get, conf, eventStore, actorSystem, applicationLifecycle)
        case Cassandra => ExperimentVariantEventCassandreStore(drivers.cassandraClient.get._2, conf, izanamiConfig.db.cassandra.get, eventStore, actorSystem)
        case Elastic   => ExperimentVariantEventElasticStore(drivers.elasticClient.get, izanamiConfig.db.elastic.get, conf, eventStore, actorSystem)
        case Mongo    =>  ExperimentVariantEventMongoStore(conf, drivers.mongoApi.get, eventStore)
        case _ => throw new IllegalArgumentException("Unsupported store type ")
      }

      val store = conf.`type` match {
        case InMemoryWithDb => getExperimentVariantEventStore(izanamiConfig.db.inMemoryWithDb.get.db)
        case other => getExperimentVariantEventStore(other)
      }
      Import.importFile(conf, ExperimentVariantEvent.importData(store))
      store
      // format: on
    }

    lazy val experimentController: effect.ExperimentControllerEff = wire[ExperimentController[IO]]

    /* Webhook */
    lazy val webhookStore: WebhookStore[IO] = {
      lazy val webhookConfig = izanamiConfig.webhook
      lazy val conf          = webhookConfig.db
      lazy val eventAdapter  = InMemoryWithDbStore.webhookEventAdapter
      lazy val dbStore: JsonDataStore[IO] =
        JsonDataStore[IO](drivers, izanamiConfig, conf, eventStore, eventAdapter, applicationLifecycle)
      lazy val store: WebhookStore[IO] = wire[WebhookStoreImpl[IO]]
      Import.importFile(conf, Webhook.importData(store))
      store
    }

    lazy val webhookController: effect.WebhookControllerEff = wire[WebhookController[IO]]

    /* User */
    lazy val userStore: UserStore[IO] = {
      val conf              = izanamiConfig.user.db
      lazy val eventAdapter = InMemoryWithDbStore.userEventAdapter
      lazy val dbStore: JsonDataStore[IO] =
        JsonDataStore[IO](drivers, izanamiConfig, conf, eventStore, eventAdapter, applicationLifecycle)
      lazy val store: UserStore[IO] = wire[UserStoreImpl[IO]]
      Import.importFile(conf, User.importData(store))
      store
    }

    lazy val userController: effect.UserControllerEff = wire[UserController[IO]]

    /* Apikey */
    lazy val apikeyStore: ApikeyStore[IO] = {
      val conf              = izanamiConfig.apikey.db
      lazy val eventAdapter = InMemoryWithDbStore.apikeyEventAdapter
      lazy val dbStore: JsonDataStore[IO] =
        JsonDataStore[IO](drivers, izanamiConfig, conf, eventStore, eventAdapter, applicationLifecycle)
      lazy val store: ApikeyStore[IO] = wire[ApikeyStoreImpl[IO]]
      Import.importFile(conf, Apikey.importData(store))
      store
    }
    lazy val apikeyController: effect.ApikeyControllerEff = wire[ApikeyController[IO]]

    lazy val healthCheck: Healthcheck[IO]                           = wire[Healthcheck[IO]]
    lazy val healthCheckController: effect.HealthCheckControllerEff = wire[HealthCheckController[IO]]

    lazy val eventsController: effect.EventsControllerEff = wire[EventsController[IO]]

    val patchResult: Future[Done] = {
      lazy val conf: DbDomainConfig = izanamiConfig.patch.db
      lazy val eventAdapter: Flow[IzanamiEvent, CacheEvent, NotUsed] =
        Flow[IzanamiEvent].mapConcat(_ => List.empty[CacheEvent])
      lazy val jsonStore: JsonDataStore[IO] =
        JsonDataStore[IO](drivers, izanamiConfig, conf, eventStore, eventAdapter, applicationLifecycle)
      lazy val jsonStoreOpt: Option[JsonDataStore[IO]] = Some(jsonStore)
      lazy val configsPatch: ConfigsPatch[IO]          = wire[ConfigsPatch[IO]]

      lazy val allPatchs = Map(1 -> configsPatch)

      lazy val patchs: Patchs[IO] = new Patchs[IO](jsonStoreOpt, allPatchs, actorSystem)
      patchs.run().unsafeToFuture()
    }

    lazy val searchController: effect.SearchControllerEff = wire[SearchController[IO]]

    lazy val backOfficeController: BackOfficeController =
      wire[BackOfficeController]

    lazy val swaggerController: SwaggerController = wire[SwaggerController]

    lazy val httpFilters: Seq[EssentialFilter] = izanamiConfig.filter match {
      case env.Otoroshi(config) =>
        Logger.info("Using otoroshi filter")
        Seq(new OtoroshiFilter[IO](_env, config))
      case env.Default(config) =>
        Logger.info("Using default filter")
        Seq(new IzanamiDefaultFilter[IO](_env, izanamiConfig, config, izanamiConfig.apikey, apikeyStore))
    }

    lazy val router: Router = {
      lazy val prefix: String = izanamiConfig.contextPath
      Logger.info(s"Initializing play router with prefix $prefix")
      wire[Routes].withPrefix(prefix)
    }

    override lazy val httpErrorHandler: HttpErrorHandler =
      new ErrorHandler(environment, configuration, None, Some(router))
  }

}
