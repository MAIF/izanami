import akka.actor.ActorSystem
import akka.{Done, NotUsed}
import akka.stream.scaladsl.Flow
import com.softwaremill.macwire.wire
import controllers._
import controllers.actions.{AuthAction, AuthContext, SecuredAction, SecuredAuthContext}
import domains.abtesting.impl._
import domains.abtesting.{ExperimentVariantEventStore, _}
import domains.apikey.ApikeyStore
import domains.config.ConfigStore
import domains.events.Events.{GlobalScriptCreated, IzanamiEvent}
import domains.events._
import domains.events.impl.{BasicEventStore, DistributedPubSubEventStore, KafkaEventStore, RedisEventStore}
import domains.feature.FeatureStore
import domains.script.GlobalScriptStore
import domains.user.UserStore
import domains.webhook.WebhookStore
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
import store.JsonDataStore
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

    lazy val izanamiConfig: IzanamiConfig = IzanamiConfig(configuration)

    implicit val system: ActorSystem = actorSystem

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

    lazy val authController: AuthController = wire[AuthController]

    lazy val drivers: Drivers = Drivers(izanamiConfig, configuration, applicationLifecycle)

    /* Event store */
    // format: off
    lazy val eventStore: EventStore = izanamiConfig.events match {
      case InMemoryEvents(_) => new BasicEventStore(actorSystem)
      case KafkaEvents(c) => new KafkaEventStore(environment, actorSystem, izanamiConfig.db.kafka.get, c)
      case RedisEvents(c) => new RedisEventStore(drivers.redisClient.get, c, actorSystem)
      case DistributedEvents(c) => new DistributedPubSubEventStore(configuration.underlying, c, applicationLifecycle)
      case other => throw new IllegalArgumentException(s"Unknown event store $other")
    }
    // format: on

    /* Global script */
    lazy val globalScriptStore: GlobalScriptStore = {
      val conf                        = izanamiConfig.globalScript.db
      lazy val eventAdapter           = InMemoryWithDbStore.globalScriptEventAdapter
      lazy val dbStore: JsonDataStore = wire[JsonDataStore]
      wire[GlobalScriptStore]
    }

    lazy val globalScripController: GlobalScriptController =
      wire[GlobalScriptController]

    /* Config */
    lazy val configStore: ConfigStore = {
      val conf                        = izanamiConfig.config.db
      lazy val eventAdapter           = InMemoryWithDbStore.configEventAdapter
      lazy val dbStore: JsonDataStore = wire[JsonDataStore]
      wire[ConfigStore]
    }

    lazy val configController: ConfigController = wire[ConfigController]

    /* Feature */
    lazy val featureStore: FeatureStore = {
      val conf                        = izanamiConfig.features.db
      lazy val eventAdapter           = InMemoryWithDbStore.featureEventAdapter
      lazy val dbStore: JsonDataStore = wire[JsonDataStore]
      wire[FeatureStore]
    }

    lazy val featureController: FeatureController = wire[FeatureController]

    /* Experiment */
    lazy val experimentStore: ExperimentStore = {
      val conf                        = izanamiConfig.experiment.db
      lazy val eventAdapter           = InMemoryWithDbStore.experimentEventAdapter
      lazy val dbStore: JsonDataStore = wire[JsonDataStore]
      wire[ExperimentStore]
    }

    lazy val variantBindingStore: VariantBindingStore = {
      val conf                        = izanamiConfig.variantBinding.db
      lazy val eventAdapter           = InMemoryWithDbStore.variantBindingEventAdapter
      lazy val dbStore: JsonDataStore = wire[JsonDataStore]
      wire[VariantBindingStore]
    }

    lazy val experimentVariantEventStore: ExperimentVariantEventStore = {
      val conf = izanamiConfig.experimentEvent.db
      // format: off

      def getExperimentVariantEventStore(dbType: DbType) = dbType match {
        case InMemory  => ExperimentVariantEventInMemoryStore(conf, actorSystem)
        case Redis     => ExperimentVariantEventRedisStore(drivers.redisClient, eventStore, actorSystem)
        case LevelDB   => ExperimentVariantEventLevelDBStore(izanamiConfig.db.leveldb.get, conf, eventStore, actorSystem, applicationLifecycle)
        case Cassandra => ExperimentVariantEventCassandreStore(drivers.cassandraClient.get._2, conf, izanamiConfig.db.cassandra.get, eventStore, actorSystem)
        case Elastic   => ExperimentVariantEventElasticStore(drivers.elasticClient.get, izanamiConfig.db.elastic.get, conf, eventStore, actorSystem)
        case Mongo    =>  ExperimentVariantEventMongoStore(conf, drivers.mongoApi.get, eventStore, actorSystem)
        case _ => throw new IllegalArgumentException("Unsupported store type ")
      }

      conf.`type` match {
        case InMemoryWithDb => getExperimentVariantEventStore(izanamiConfig.db.inMemoryWithDb.get.db)
        case other => getExperimentVariantEventStore(other)
      }
      // format: on
    }

    lazy val experimentController: ExperimentController =
      wire[ExperimentController]

    /* Webhook */
    lazy val webhookStore: WebhookStore = {
      lazy val webhookConfig          = izanamiConfig.webhook
      lazy val conf                   = webhookConfig.db
      lazy val eventAdapter           = InMemoryWithDbStore.webhookEventAdapter
      lazy val dbStore: JsonDataStore = wire[JsonDataStore]
      wire[WebhookStore]
    }

    lazy val webhookController: WebhookController = wire[WebhookController]

    /* User */
    lazy val userStore: UserStore = {
      val conf                        = izanamiConfig.user.db
      lazy val eventAdapter           = InMemoryWithDbStore.userEventAdapter
      lazy val dbStore: JsonDataStore = wire[JsonDataStore]
      wire[UserStore]
    }

    lazy val userController: UserController = wire[UserController]

    /* Apikey */
    lazy val apikeyStore: ApikeyStore = {
      val conf                        = izanamiConfig.apikey.db
      lazy val eventAdapter           = InMemoryWithDbStore.apikeyEventAdapter
      lazy val dbStore: JsonDataStore = wire[JsonDataStore]
      wire[ApikeyStore]
    }

    lazy val eventsController: EventsController = wire[EventsController]

    lazy val apikeyController: ApikeyController = wire[ApikeyController]

    val patchResult: Future[Done] = {
      lazy val conf: DbDomainConfig = izanamiConfig.patch.db
      lazy val eventAdapter: Flow[IzanamiEvent, CacheEvent, NotUsed] =
        Flow[IzanamiEvent].mapConcat(_ => List.empty[CacheEvent])
      lazy val jsonStore: JsonDataStore            = wire[JsonDataStore]
      lazy val jsonStoreOpt: Option[JsonDataStore] = Some(jsonStore)
      lazy val configsPatch: ConfigsPatch          = wire[ConfigsPatch]

      lazy val allPatchs = Map(1 -> configsPatch)

      lazy val patchs: Patchs = new Patchs(jsonStoreOpt, allPatchs, actorSystem)
      patchs.run()
    }

    lazy val searchController: SearchController = wire[SearchController]

    lazy val backOfficeController: BackOfficeController =
      wire[BackOfficeController]

    lazy val swaggerController: SwaggerController = wire[SwaggerController]

    lazy val httpFilters: Seq[EssentialFilter] = izanamiConfig.filter match {
      case env.Otoroshi(config) =>
        Logger.info("Using otoroshi filter")
        Seq(new OtoroshiFilter(_env, config))
      case env.Default(config) =>
        Logger.info("Using default filter")
        Seq(new IzanamiDefaultFilter(_env, izanamiConfig, config, izanamiConfig.apikey, apikeyStore))
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
