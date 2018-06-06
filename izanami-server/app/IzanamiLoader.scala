import com.datastax.driver.core.{Cluster, Session}
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
import play.modules.reactivemongo.{DefaultReactiveMongoApi, ReactiveMongoApi}
import reactivemongo.api.MongoConnection
import redis.RedisClientMasterSlaves
import router.Routes
import store.JsonDataStore
import store.cassandra.{CassandraClient, CassandraJsonDataStore}
import store.elastic.{ElasticClient, ElasticJsonDataStore}
import store.leveldb.LevelDBJsonDataStore
import store.memory.InMemoryJsonDataStore
import store.mongo.MongoJsonDataStore
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

    lazy val redisClient: Option[RedisClientMasterSlaves] =
      RedisClient.redisClient(izanamiConfig.db.redis, actorSystem)

    lazy val cassandraClient: Option[(Cluster, Session)] =
      CassandraClient.cassandraClient(izanamiConfig.db.cassandra)

    lazy val elasticClient: Option[Elastic[JsValue]] =
      izanamiConfig.db.elastic.map(c => ElasticClient(c, actorSystem))

    lazy val mongoApi: Option[ReactiveMongoApi] = izanamiConfig.db.mongo.map { c =>
      val name      = c.name.getOrElse("default")
      val parsedUri = MongoConnection.parseURI(c.url).get
      val dbName    = parsedUri.db.orElse(c.database).getOrElse("default")
      Logger.info(s"Creating mongo api driver with name:$name, dbName:$dbName, uri:$parsedUri")
      new DefaultReactiveMongoApi(
        name,
        parsedUri,
        dbName,
        false,
        configuration,
        applicationLifecycle
      )
    }

    /* Event store */
    // format: off
    lazy val eventStore: EventStore = izanamiConfig.events match {
      case InMemoryEvents(_) => new BasicEventStore(actorSystem)
      case KafkaEvents(c) => new KafkaEventStore(environment, actorSystem, izanamiConfig.db.kafka.get, c)
      case RedisEvents(c) => new RedisEventStore(redisClient.get, c, actorSystem)
      case DistributedEvents(c) => new DistributedPubSubEventStore(configuration.underlying, c, applicationLifecycle)
      case other => throw new IllegalArgumentException(s"Unknown event store $other")
    }
    // format: on

    /* Global script */
    lazy val globalScriptStore: GlobalScriptStore = {
      val conf = izanamiConfig.globalScript.db
      // format: off
      conf.`type` match {
        case InMemory  => GlobalScriptStore(InMemoryJsonDataStore(conf, actorSystem), eventStore, actorSystem)
        case Redis     => GlobalScriptStore(RedisJsonDataStore(redisClient.get, conf, actorSystem), eventStore, actorSystem)
        case LevelDB   => GlobalScriptStore(LevelDBJsonDataStore(izanamiConfig.db.leveldb.get, conf, actorSystem, applicationLifecycle), eventStore, actorSystem)
        case Cassandra => GlobalScriptStore(CassandraJsonDataStore(cassandraClient.get._2, izanamiConfig.db.cassandra.get, conf, actorSystem), eventStore, actorSystem)
        case Elastic   => GlobalScriptStore(ElasticJsonDataStore(elasticClient.get, izanamiConfig.db.elastic.get, conf, actorSystem), eventStore, actorSystem)
        case Mongo     => GlobalScriptStore(MongoJsonDataStore(mongoApi.get,conf, actorSystem), eventStore, actorSystem)
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
        case InMemory  => ConfigStore(InMemoryJsonDataStore(conf, actorSystem), eventStore, actorSystem)
        case Redis     => ConfigStore(RedisJsonDataStore(redisClient.get, conf, actorSystem), eventStore, actorSystem)
        case LevelDB   => ConfigStore(LevelDBJsonDataStore(izanamiConfig.db.leveldb.get, conf, actorSystem, applicationLifecycle), eventStore, actorSystem)
        case Cassandra => ConfigStore(CassandraJsonDataStore(cassandraClient.get._2, izanamiConfig.db.cassandra.get, conf, actorSystem), eventStore, actorSystem)
        case Elastic   => ConfigStore(ElasticJsonDataStore(elasticClient.get, izanamiConfig.db.elastic.get, conf, actorSystem), eventStore, actorSystem)
        case Mongo     => ConfigStore(MongoJsonDataStore(mongoApi.get,conf, actorSystem), eventStore, actorSystem)
      }
      // format: on
    }

    lazy val configController: ConfigController = wire[ConfigController]

    /* Feature */
    lazy val featureStore: FeatureStore = {
      val conf = izanamiConfig.features.db
      // format: off
      conf.`type` match {
        case InMemory  => FeatureStore(InMemoryJsonDataStore(conf, actorSystem), eventStore, actorSystem)
        case Redis     => FeatureStore(RedisJsonDataStore(redisClient.get, conf, actorSystem), eventStore, actorSystem)
        case LevelDB   => FeatureStore(LevelDBJsonDataStore(izanamiConfig.db.leveldb.get, conf, actorSystem, applicationLifecycle), eventStore, actorSystem)
        case Cassandra => FeatureStore(CassandraJsonDataStore(cassandraClient.get._2, izanamiConfig.db.cassandra.get, conf, actorSystem), eventStore, actorSystem)
        case Elastic   => FeatureStore(ElasticJsonDataStore(elasticClient.get, izanamiConfig.db.elastic.get, conf, actorSystem), eventStore, actorSystem)
        case Mongo     => FeatureStore(MongoJsonDataStore(mongoApi.get,conf, actorSystem), eventStore, actorSystem)
      }
      // format: on
    }

    lazy val featureController: FeatureController = wire[FeatureController]

    /* Experiment */
    lazy val experimentStore: ExperimentStore = {
      val conf = izanamiConfig.experiment.db
      // format: off
      conf.`type` match {
        case InMemory  => ExperimentStore(InMemoryJsonDataStore(conf, actorSystem), eventStore, actorSystem)
        case Redis     => ExperimentStore(RedisJsonDataStore(redisClient.get, conf, actorSystem), eventStore, actorSystem)
        case LevelDB   => ExperimentStore(LevelDBJsonDataStore(izanamiConfig.db.leveldb.get, conf, actorSystem, applicationLifecycle), eventStore, actorSystem)
        case Cassandra => ExperimentStore(CassandraJsonDataStore(cassandraClient.get._2, izanamiConfig.db.cassandra.get, conf, actorSystem), eventStore, actorSystem)
        case Elastic   => ExperimentStore(ElasticJsonDataStore(elasticClient.get, izanamiConfig.db.elastic.get, conf, actorSystem), eventStore, actorSystem)
        case Mongo     => ExperimentStore(MongoJsonDataStore(mongoApi.get,conf, actorSystem), eventStore, actorSystem)
      }
      // format: on
    }

    lazy val variantBindingStore: VariantBindingStore = {
      val conf = izanamiConfig.variantBinding.db

      // format: off
      conf.`type` match {
        case InMemory  => VariantBindingStore(InMemoryJsonDataStore(conf, actorSystem), eventStore, actorSystem)
        case Redis     => VariantBindingStore(RedisJsonDataStore(redisClient.get, conf, actorSystem), eventStore, actorSystem)
        case LevelDB   => VariantBindingStore(LevelDBJsonDataStore(izanamiConfig.db.leveldb.get, conf, actorSystem, applicationLifecycle), eventStore, actorSystem)
        case Cassandra => VariantBindingStore(CassandraJsonDataStore(cassandraClient.get._2, izanamiConfig.db.cassandra.get, conf, actorSystem), eventStore, actorSystem)
        case Elastic   => VariantBindingStore(ElasticJsonDataStore(elasticClient.get, izanamiConfig.db.elastic.get, conf, actorSystem), eventStore, actorSystem)
        case Mongo     => VariantBindingStore(MongoJsonDataStore(mongoApi.get,conf, actorSystem), eventStore, actorSystem)
      }
      // format: on
    }

    lazy val experimentVariantEventStore: ExperimentVariantEventStore = {
      val conf = izanamiConfig.experimentEvent.db
      // format: off
      conf.`type` match {
        case InMemory  => ExperimentVariantEventInMemoryStore(conf, actorSystem)
        case Redis     => ExperimentVariantEventRedisStore(redisClient, eventStore, actorSystem)
        case LevelDB   => ExperimentVariantEventLevelDBStore(izanamiConfig.db.leveldb.get, conf, eventStore, actorSystem, applicationLifecycle)
        case Cassandra => ExperimentVariantEventCassandreStore(cassandraClient.get._2, conf, izanamiConfig.db.cassandra.get, eventStore, actorSystem)
        case Elastic   => ExperimentVariantEventElasticStore(elasticClient.get, izanamiConfig.db.elastic.get, conf, eventStore, actorSystem)
        case Mongo    =>  ExperimentVariantEventMongoStore(conf, mongoApi.get, eventStore, actorSystem)
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
        case InMemory  => WebhookStore(InMemoryJsonDataStore(conf, actorSystem), eventStore, conf, izanamiConfig.webhook, wsClient, actorSystem)
        case Redis     => WebhookStore(RedisJsonDataStore(redisClient.get, conf, actorSystem), eventStore, conf, izanamiConfig.webhook, wsClient, actorSystem)
        case LevelDB   => WebhookStore(LevelDBJsonDataStore(izanamiConfig.db.leveldb.get, conf, actorSystem, applicationLifecycle), eventStore, conf, izanamiConfig.webhook, wsClient, actorSystem)
        case Cassandra => WebhookStore(CassandraJsonDataStore(cassandraClient.get._2, izanamiConfig.db.cassandra.get, conf, actorSystem), eventStore, conf, izanamiConfig.webhook, wsClient, actorSystem)
        case Elastic   => WebhookStore(ElasticJsonDataStore(elasticClient.get, izanamiConfig.db.elastic.get, conf, actorSystem), eventStore, conf, izanamiConfig.webhook, wsClient, actorSystem)
        case Mongo     => WebhookStore(MongoJsonDataStore(mongoApi.get,conf, actorSystem), eventStore, conf, izanamiConfig.webhook, wsClient, actorSystem)
      }
      // format: on
    }

    lazy val webhookController: WebhookController = wire[WebhookController]

    /* User */
    lazy val userStore: UserStore = {
      val conf = izanamiConfig.user.db
      // format: off
      conf.`type` match {
        case InMemory  => UserStore(InMemoryJsonDataStore(conf, actorSystem), eventStore, actorSystem)
        case Redis     => UserStore(RedisJsonDataStore(redisClient.get, conf, actorSystem), eventStore, actorSystem)
        case LevelDB   => UserStore(LevelDBJsonDataStore(izanamiConfig.db.leveldb.get, conf, actorSystem, applicationLifecycle), eventStore, actorSystem)
        case Cassandra => UserStore(CassandraJsonDataStore(cassandraClient.get._2, izanamiConfig.db.cassandra.get, conf, actorSystem), eventStore, actorSystem)
        case Elastic   => UserStore(ElasticJsonDataStore(elasticClient.get, izanamiConfig.db.elastic.get, conf, actorSystem), eventStore, actorSystem)
        case Mongo     => UserStore(MongoJsonDataStore(mongoApi.get,conf, actorSystem), eventStore, actorSystem)
      }
      // format: on
    }

    lazy val userController: UserController = wire[UserController]

    /* Apikey */
    lazy val apikeyStore: ApikeyStore = {
      val conf = izanamiConfig.apikey.db
      // format: off
      conf.`type` match {
        case InMemory  => ApikeyStore(InMemoryJsonDataStore(conf, actorSystem), eventStore, actorSystem)
        case Redis     => ApikeyStore(RedisJsonDataStore(redisClient.get, conf, actorSystem), eventStore, actorSystem)
        case LevelDB   => ApikeyStore(LevelDBJsonDataStore(izanamiConfig.db.leveldb.get, conf, actorSystem, applicationLifecycle), eventStore, actorSystem)
        case Cassandra => ApikeyStore(CassandraJsonDataStore(cassandraClient.get._2, izanamiConfig.db.cassandra.get, conf, actorSystem), eventStore, actorSystem)
        case Elastic   => ApikeyStore(ElasticJsonDataStore(elasticClient.get, izanamiConfig.db.elastic.get, conf, actorSystem), eventStore, actorSystem)
        case Mongo     => ApikeyStore(MongoJsonDataStore(mongoApi.get,conf, actorSystem), eventStore, actorSystem)
      }
      // format: on
    }

    lazy val eventsController: EventsController = wire[EventsController]

    lazy val apikeyController: ApikeyController = wire[ApikeyController]

    val patchResult = {
      val conf: DbDomainConfig = izanamiConfig.patch.db
      // format: off
      lazy val jsonStore: Option[JsonDataStore] = conf.`type` match {
        case InMemory  => None
        case Redis     => redisClient.map(c => RedisJsonDataStore(c, conf, actorSystem))
        case LevelDB   => izanamiConfig.db.leveldb.map(leveldb => LevelDBJsonDataStore(leveldb, conf, actorSystem, applicationLifecycle))
        case Cassandra => cassandraClient.map(cClient => CassandraJsonDataStore(cClient._2, izanamiConfig.db.cassandra.get, conf, actorSystem))
        case Elastic   => elasticClient.map(es => ElasticJsonDataStore(es, izanamiConfig.db.elastic.get, conf, actorSystem))
        case Mongo     =>  mongoApi.map(mongo => MongoJsonDataStore(mongo, conf, actorSystem))
      }
      // format: on

      lazy val configsPatch: ConfigsPatch = wire[ConfigsPatch]
      lazy val allPatchs = Map(
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
