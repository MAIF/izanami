import com.softwaremill.macwire.wire
import controllers._
import controllers.actions.SecuredAction
import domains.me.{LevelDbMeRepository, MeRepository, MeService, MeServiceImpl}
import domains.shows.{AllShows, BetaSerieShows, Shows, TvdbShows}
import env.{AppConfig, Env}
import izanami.Strategy.{CacheWithSseStrategy, DevStrategy, FetchStrategy}
import filter.OtoroshiFilter
import izanami.scaladsl._
import izanami.{ClientConfig, Experiments, IzanamiDispatcher}
import play.api.ApplicationLoader.Context
import play.api.Mode.Dev
import play.api.libs.ws.ahc.AhcWSComponents
import play.api.mvc.EssentialFilter
import play.api.routing.Router
import play.api._
import router.Routes

import scala.concurrent.Future

class AppLoader extends ApplicationLoader {
  def load(context: Context) = {
    LoggerConfigurator(context.environment.classLoader).foreach {
      _.configure(context.environment, context.initialConfiguration, Map.empty)
    }
    new modules.AppComponentsInstances(context).application
  }
}

object modules {
  class AppComponentsInstances(context: Context)
      extends BuiltInComponentsFromContext(context)
      with AssetsComponents
      with AhcWSComponents {

    private implicit val system            = actorSystem
    private implicit val izanamiDispatcher = IzanamiDispatcher(system = system)

    private val mode: Mode = environment.mode

    private val _env: Env = wire[Env]

    private val appConfig: AppConfig = AppConfig(configuration)

    private val clientConfig: ClientConfig = env.Izanami.izanamiConfig(appConfig.izanami)

    private lazy val izanamiClient: IzanamiClient = wire[IzanamiClient]

    private lazy val featureClient: FeatureClient = mode match {
      case Dev =>
        izanamiClient.featureClient(
          DevStrategy,
          Features.parseJson(appConfig.izanami.fallback.features)
        )
      case _ =>
        izanamiClient.featureClient(
          CacheWithSseStrategy(patterns = Seq("mytvshows:*")),
          Features.parseJson(appConfig.izanami.fallback.features)
        )
    }

    private lazy val configClient: ConfigClient = mode match {
      case Dev =>
        izanamiClient.configClient(
          DevStrategy,
          Configs.parseJson(appConfig.izanami.fallback.configs)
        )
      case _ =>
        izanamiClient.configClient(
          CacheWithSseStrategy(patterns = Seq("mytvshows:*")),
          Configs.parseJson(appConfig.izanami.fallback.configs)
        )
    }

    private lazy val experimentClient: ExperimentsClient = mode match {
      case Dev =>
        izanamiClient.experimentClient(
          DevStrategy,
          Experiments.parseJson(appConfig.izanami.fallback.experiments)
        )
      case _ =>
        izanamiClient.experimentClient(
          FetchStrategy(),
          Experiments.parseJson(appConfig.izanami.fallback.experiments)
        )
    }

    private lazy val proxy: Proxy = Proxy(
      featureClient = Some(featureClient),
      configClient = Some(configClient),
      experimentClient = Some(experimentClient)
    )

    lazy val shows: Shows[Future] = {
      val betaConfig                     = appConfig.betaSerie
      val betaSerieShows: BetaSerieShows = wire[BetaSerieShows]
      val tvdbConfig                     = appConfig.tvdb
      val tvdbShows: TvdbShows           = wire[TvdbShows]

      wire[AllShows]
    }

    lazy val meRepository: MeRepository[Future] = {
      val path: String = appConfig.dbpath
      wire[LevelDbMeRepository]
    }
    lazy val meService: MeService[Future] = wire[MeServiceImpl[Future]]
    def authAction: SecuredAction         = wire[SecuredAction]

    lazy val izanamiController: IzanamiController = wire[IzanamiController]
    lazy val meController: MeController           = wire[MeController]
    lazy val showsController: ShowsController     = wire[ShowsController]
    lazy val homeController: HomeController       = wire[HomeController]

    override def router: Router = {
      lazy val prefix: String = "/"
      wire[Routes]
    }

    override def httpFilters: Seq[EssentialFilter] =
      if (appConfig.otoroshi.enabled) {
        Seq(new OtoroshiFilter(_env, appConfig.otoroshi))
      } else {
        Seq.empty[EssentialFilter]
      }
  }
}
