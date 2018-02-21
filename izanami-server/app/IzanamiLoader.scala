import com.datastax.driver.core.Cluster
import com.softwaremill.macwire.wire
import controllers._
import controllers.actions.{AuthAction, AuthContext, SecuredAction, SecuredAuthContext}
import domains.abtesting.impl._
import domains.abtesting.{ExperimentVariantEventStore, _}
import domains.apikey.ApikeyStore
import domains.config.ConfigStore
import domains.events._
import domains.events.impl.{BasicEventStore, DistributedPubSubEventStore, KafkaEventStore, RedisEventStore}
import domains.feature.FeatureStore
import domains.script.GlobalScriptStore
import domains.user.UserStore
import domains.webhook.WebhookStore
import elastic.api.Elastic
import env._
import filters.{IzanamiDefaultFilter, OtoroshiFilter}
import handlers.ErrorHandler
import patches.Patchs
import patches.impl.ConfigsPatch
import play.api.ApplicationLoader.Context
import play.api._
import play.api.http.HttpErrorHandler
import play.api.libs.json.JsValue
import play.api.libs.ws.ahc.AhcWSComponents
import play.api.mvc.{ActionBuilder, AnyContent, EssentialFilter}
import play.api.routing.Router
import redis.RedisClientMasterSlaves
import router.Routes
import store.JsonDataStore
import store.cassandra.{CassandraClient, CassandraJsonDataStore}
import store.elastic.{ElasticClient, ElasticJsonDataStore}
import store.leveldb.LevelDBJsonDataStore
import store.memory.InMemoryJsonDataStore
import store.redis.{RedisClient, RedisJsonDataStore}

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

    Logger.info(s"Configuration: \n$izanamiConfig")

    lazy val _env: Env = wire[Env]

    def authAction: ActionBuilder[AuthContext, AnyContent]                       = wire[AuthAction]
    def securedSecuredAuthContext: ActionBuilder[SecuredAuthContext, AnyContent] = wire[SecuredAction]

    lazy val homeController: HomeController = wire[HomeController]

    lazy val authController: AuthController = wire[AuthController]

    lazy val redisClient: Option[RedisClientMasterSlaves] =
      RedisClient.redisClient(izanamiConfig.db.redis, actorSystem)

    lazy val cassandraClient: Option[Cluster] =
      CassandraClient.cassandraClient(izanamiConfig.db.cassandra)

    lazy val elasticClient: Option[Elastic[JsValue]] =
      izanamiConfig.db.elastic.map(c => ElasticClient(c, actorSystem))

    /* Event store */
    // format: off
    lazy val eventStore: EventStore = izanamiConfig.events match {
      case InMemoryEvents(_) => new BasicEventStore(actorSystem)
      case KafkaEvents(c) => new KafkaEventStore(environment, actorSystem, izanamiConfig.db.kafka.get, c)
      case RedisEvents(c) => new RedisEventStore(redisClient.get, c, actorSystem)
      case DistributedEvents(c) => new DistributedPubSubEventStore(configuration.underlying, c, applicationLifecycle)
      case other => throw new IllegalArgumentException(s"Unknow event store $other")
    }
    // format: on

    /* Global script */
    lazy val globalScriptStore: GlobalScriptStore = {
      val conf = izanamiConfig.globalScript.db
      // format: off
      conf.`type` match {
        case DbType.inMemory  => GlobalScriptStore(InMemoryJsonDataStore(conf, actorSystem), eventStore, actorSystem)
        case DbType.redis     => GlobalScriptStore(RedisJsonDataStore(redisClient.get, conf, actorSystem), eventStore, actorSystem)
        case DbType.levelDB   => GlobalScriptStore(LevelDBJsonDataStore(izanamiConfig.db.leveldb.get, conf, actorSystem, applicationLifecycle), eventStore, actorSystem)
        case DbType.cassandra => GlobalScriptStore(CassandraJsonDataStore(cassandraClient.get, izanamiConfig.db.cassandra.get, conf, actorSystem), eventStore, actorSystem)
        case DbType.elastic   => GlobalScriptStore(ElasticJsonDataStore(elasticClient.get, izanamiConfig.db.elastic.get, conf, actorSystem), eventStore, actorSystem)
      }
      // format: on
    }

    lazy val globalScripController: GlobalScriptController =
      wire[GlobalScriptController]

    /* Config */
    lazy val configStore: ConfigStore = {
      val conf = izanamiConfig.config.db
      // format: off
      conf.`type` match {
        case DbType.inMemory  => ConfigStore(InMemoryJsonDataStore(conf, actorSystem), eventStore, actorSystem)
        case DbType.redis     => ConfigStore(RedisJsonDataStore(redisClient.get, conf, actorSystem), eventStore, actorSystem)
        case DbType.levelDB   => ConfigStore(LevelDBJsonDataStore(izanamiConfig.db.leveldb.get, conf, actorSystem, applicationLifecycle), eventStore, actorSystem)
        case DbType.cassandra => ConfigStore(CassandraJsonDataStore(cassandraClient.get, izanamiConfig.db.cassandra.get, conf, actorSystem), eventStore, actorSystem)
        case DbType.elastic   => ConfigStore(ElasticJsonDataStore(elasticClient.get, izanamiConfig.db.elastic.get, conf, actorSystem), eventStore, actorSystem)
      }
      // format: on
    }

    lazy val configController: ConfigController = wire[ConfigController]

    /* Feature */
    lazy val featureStore: FeatureStore = {
      val conf = izanamiConfig.features.db
      // format: off
      conf.`type` match {
        case DbType.inMemory  => FeatureStore(InMemoryJsonDataStore(conf, actorSystem), eventStore, actorSystem)
        case DbType.redis     => FeatureStore(RedisJsonDataStore(redisClient.get, conf, actorSystem), eventStore, actorSystem)
        case DbType.levelDB   => FeatureStore(LevelDBJsonDataStore(izanamiConfig.db.leveldb.get, conf, actorSystem, applicationLifecycle), eventStore, actorSystem)
        case DbType.cassandra => FeatureStore(CassandraJsonDataStore(cassandraClient.get, izanamiConfig.db.cassandra.get, conf, actorSystem), eventStore, actorSystem)
        case DbType.elastic   => FeatureStore(ElasticJsonDataStore(elasticClient.get, izanamiConfig.db.elastic.get, conf, actorSystem), eventStore, actorSystem)
      }
      // format: on
    }

    lazy val featureController: FeatureController = wire[FeatureController]

    /* Experiment */
    lazy val experimentStore: ExperimentStore = {
      val conf = izanamiConfig.experiment.db
      // format: off
      conf.`type` match {
        case DbType.inMemory  => ExperimentStore(InMemoryJsonDataStore(conf, actorSystem), eventStore, actorSystem)
        case DbType.redis     => ExperimentStore(RedisJsonDataStore(redisClient.get, conf, actorSystem), eventStore, actorSystem)
        case DbType.levelDB   => ExperimentStore(LevelDBJsonDataStore(izanamiConfig.db.leveldb.get, conf, actorSystem, applicationLifecycle), eventStore, actorSystem)
        case DbType.cassandra => ExperimentStore(CassandraJsonDataStore(cassandraClient.get, izanamiConfig.db.cassandra.get, conf, actorSystem), eventStore, actorSystem)
        case DbType.elastic   => ExperimentStore(ElasticJsonDataStore(elasticClient.get, izanamiConfig.db.elastic.get, conf, actorSystem), eventStore, actorSystem)
      }
      // format: on
    }

    lazy val variantBindingStore: VariantBindingStore = {
      val conf = izanamiConfig.variantBinding.db

      // format: off
      conf.`type` match {
        case DbType.inMemory  => VariantBindingStore(InMemoryJsonDataStore(conf, actorSystem), eventStore, actorSystem)
        case DbType.redis     => VariantBindingStore(RedisJsonDataStore(redisClient.get, conf, actorSystem), eventStore, actorSystem)
        case DbType.levelDB   => VariantBindingStore(LevelDBJsonDataStore(izanamiConfig.db.leveldb.get, conf, actorSystem, applicationLifecycle), eventStore, actorSystem)
        case DbType.cassandra => VariantBindingStore(CassandraJsonDataStore(cassandraClient.get, izanamiConfig.db.cassandra.get, conf, actorSystem), eventStore, actorSystem)
        case DbType.elastic   => VariantBindingStore(ElasticJsonDataStore(elasticClient.get, izanamiConfig.db.elastic.get, conf, actorSystem), eventStore, actorSystem)
      }
      // format: on
    }

    lazy val experimentVariantEventStore: ExperimentVariantEventStore = {
      val conf = izanamiConfig.experimentEvent.db
      // format: off
      conf.`type` match {
        case DbType.inMemory  => ExperimentVariantEventInMemoryStore(conf, actorSystem)
        case DbType.redis     => ExperimentVariantEventRedisStore(redisClient, eventStore, actorSystem)
        case DbType.levelDB   => ExperimentVariantEventLevelDBStore(izanamiConfig.db.leveldb.get, conf, eventStore, actorSystem, applicationLifecycle)
        case DbType.cassandra => ExperimentVariantEventCassandreStore(cassandraClient.get, conf, izanamiConfig.db.cassandra.get, eventStore, actorSystem)
        case DbType.elastic   => ExperimentVariantEventElasticStore(elasticClient.get, izanamiConfig.db.elastic.get, conf, eventStore, actorSystem)
      }
      // format: on
    }

    lazy val experimentController: ExperimentController =
      wire[ExperimentController]

    /* Webhook */
    lazy val webhookStore: WebhookStore = {
      val conf = izanamiConfig.webhook.db
      // format: off
      conf.`type` match {
        case DbType.inMemory  => WebhookStore(InMemoryJsonDataStore(conf, actorSystem), eventStore, conf, izanamiConfig.webhook, wsClient, actorSystem)
        case DbType.redis     => WebhookStore(RedisJsonDataStore(redisClient.get, conf, actorSystem), eventStore, conf, izanamiConfig.webhook, wsClient, actorSystem)
        case DbType.levelDB   => WebhookStore(LevelDBJsonDataStore(izanamiConfig.db.leveldb.get, conf, actorSystem, applicationLifecycle), eventStore, conf, izanamiConfig.webhook, wsClient, actorSystem)
        case DbType.cassandra => WebhookStore(CassandraJsonDataStore(cassandraClient.get, izanamiConfig.db.cassandra.get, conf, actorSystem), eventStore, conf, izanamiConfig.webhook, wsClient, actorSystem)
        case DbType.elastic   => WebhookStore(ElasticJsonDataStore(elasticClient.get, izanamiConfig.db.elastic.get, conf, actorSystem), eventStore, conf, izanamiConfig.webhook, wsClient, actorSystem)
      }
      // format: on
    }

    lazy val webhookController: WebhookController = wire[WebhookController]

    /* User */
    lazy val userStore: UserStore = {
      val conf = izanamiConfig.user.db
      // format: off
      conf.`type` match {
        case DbType.inMemory  => UserStore(InMemoryJsonDataStore(conf, actorSystem), eventStore, actorSystem)
        case DbType.redis     => UserStore(RedisJsonDataStore(redisClient.get, conf, actorSystem), eventStore, actorSystem)
        case DbType.levelDB   => UserStore(LevelDBJsonDataStore(izanamiConfig.db.leveldb.get, conf, actorSystem, applicationLifecycle), eventStore, actorSystem)
        case DbType.cassandra => UserStore(CassandraJsonDataStore(cassandraClient.get, izanamiConfig.db.cassandra.get, conf, actorSystem), eventStore, actorSystem)
        case DbType.elastic   => UserStore(ElasticJsonDataStore(elasticClient.get, izanamiConfig.db.elastic.get, conf, actorSystem), eventStore, actorSystem)
      }
      // format: on
    }

    lazy val userController: UserController = wire[UserController]

    /* Apikey */
    lazy val apikeyStore: ApikeyStore = {
      val conf = izanamiConfig.apikey.db
      // format: off
      conf.`type` match {
        case DbType.inMemory  => ApikeyStore(InMemoryJsonDataStore(conf, actorSystem), eventStore, actorSystem)
        case DbType.redis     => ApikeyStore(RedisJsonDataStore(redisClient.get, conf, actorSystem), eventStore, actorSystem)
        case DbType.levelDB   => ApikeyStore(LevelDBJsonDataStore(izanamiConfig.db.leveldb.get, conf, actorSystem, applicationLifecycle), eventStore, actorSystem)
        case DbType.cassandra => ApikeyStore(CassandraJsonDataStore(cassandraClient.get, izanamiConfig.db.cassandra.get, conf, actorSystem), eventStore, actorSystem)
        case DbType.elastic   => ApikeyStore(ElasticJsonDataStore(elasticClient.get, izanamiConfig.db.elastic.get, conf, actorSystem), eventStore, actorSystem)
      }
      // format: on
    }

    lazy val eventsController: EventsController = wire[EventsController]

    lazy val apikeyController: ApikeyController = wire[ApikeyController]

    val patchResult = {
      val conf: DbDomainConfig = izanamiConfig.patch.db
      // format: off
      lazy val jsonStore: Option[JsonDataStore] = conf.`type` match {
        case DbType.inMemory  => None
        case DbType.redis     => redisClient.map(c => RedisJsonDataStore(c, conf, actorSystem))
        case DbType.levelDB   => izanamiConfig.db.leveldb.map(leveldb => LevelDBJsonDataStore(leveldb, conf, actorSystem, applicationLifecycle))
        case DbType.cassandra => cassandraClient.map(cClient => CassandraJsonDataStore(cClient, izanamiConfig.db.cassandra.get, conf, actorSystem))
        case DbType.elastic   => elasticClient.map(es => ElasticJsonDataStore(es, izanamiConfig.db.elastic.get, conf, actorSystem))
      }
      // format: on

      lazy val configsPatch: ConfigsPatch = wire[ConfigsPatch]
      val allPatchs = Map(
        1 -> configsPatch
      )
      val patchs: Patchs = new Patchs(jsonStore, allPatchs, actorSystem)
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
        Seq(new IzanamiDefaultFilter(_env, config, izanamiConfig.apikey, apikeyStore))
    }

    lazy val router: Router = {
      lazy val prefix: String = "/"
      wire[Routes]
    }

    override lazy val httpErrorHandler: HttpErrorHandler =
      new ErrorHandler(environment, configuration, None, Some(router))
  }

}
