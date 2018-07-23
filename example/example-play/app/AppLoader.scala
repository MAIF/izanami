import com.softwaremill.macwire.wire
import controllers._
import domains.shows.{BetaSerieShows, Shows}
import env.{AppConfig, Env}
import izanami.Strategy.{CacheWithSseStrategy, DevStrategy, FetchStrategy}
import izanami.{ClientConfig, Experiments, IzanamiDispatcher, Strategy}
import izanami.scaladsl._
import play.api.ApplicationLoader.Context
import play.api.Mode.Dev
import play.api.libs.ws.ahc.AhcWSComponents
import play.api.mvc.EssentialFilter
import play.api.routing.Router
import play.api.{ApplicationLoader, BuiltInComponentsFromContext, LoggerConfigurator, Mode}
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

    lazy val betaSerie: Shows[Future] = {
      val betaConfig = appConfig.betaSerie
      wire[BetaSerieShows]
    }

    lazy val izanamiController: IzanamiController = wire[IzanamiController]
    lazy val meController: MeController           = wire[MeController]
    lazy val showsController: ShowsController     = wire[ShowsController]
    lazy val homeController: HomeController       = wire[HomeController]

    override def router: Router = {
      lazy val prefix: String = "/"
      wire[Routes]
    }

    override def httpFilters: Seq[EssentialFilter] = Seq.empty[EssentialFilter]
  }
}
