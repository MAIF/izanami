package domains.script

import cats.effect.IO
import com.codahale.metrics.MetricRegistry
import com.typesafe.config.ConfigFactory
import controllers.AssetsComponents
import env._
import org.scalatest.concurrent.{IntegrationPatience, ScalaFutures}
import org.scalatestplus.play.PlaySpec
import org.scalatestplus.play.components.OneServerPerSuiteWithComponents
import play.api.ApplicationLoader.Context
import play.api.libs.json.Json
import play.api.libs.ws.ahc.AhcWSComponents
import play.api.{BuiltInComponents, BuiltInComponentsFromContext, Configuration, NoHttpFiltersComponents}

import scala.concurrent.duration.DurationInt

/**
 * Created by adelegue on 18/07/2017.
 */
class ScriptSpec extends PlaySpec with OneServerPerSuiteWithComponents with ScalaFutures with IntegrationPatience {

  case class TestComponent(context: Context)
      extends BuiltInComponentsFromContext(context)
      with NoHttpFiltersComponents
      with AhcWSComponents
      with AssetsComponents {
    import play.api.mvc.Results
    import play.api.routing.Router
    import play.api.routing.sird._

    def globalScripStore: GlobalScriptService[IO] = null

    lazy val router: Router = Router.from({
      case GET(p"/surname") =>
        defaultActionBuilder {
          Results.Ok(Json.obj("surname" -> "Lodbrok"))
        }
    })
  }

  lazy val testComponents = TestComponent(context)

  override def components: BuiltInComponents = testComponents

  lazy val script =
    s"""
       |function enabled(context, enabled, disabled, httpClient) {
       |
       |    httpClient.call({method: "get", url: "http://localhost:$port/surname"}, function(error, body){
       |      if(error) {
       |        disabled();
       |      } else {
       |        var jsonBody = JSON.parse(body);
       |        if(jsonBody.surname === "Lodbrok" && context.name === "Ragnar") {
       |          enabled();
       |        } else {
       |          disabled();
       |        }
       |      }
       |    })
       |
       |}
         """.stripMargin

  private val dbConfig = DbDomainConfig(InMemory, DbDomainConfigDetails("", None), None)
  val config = IzanamiConfig(
    Some("dev"),
    "/",
    "/",
    false,
    Default(DefaultFilter(Seq(), "", "", "", ApiKeyHeaders("", ""))),
    DbConfig("", None, None, None, None, None, None, None),
    LogoutConfig(""),
    ConfigConfig(dbConfig),
    FeaturesConfig(dbConfig),
    GlobalScriptConfig(dbConfig),
    ExperimentConfig(dbConfig),
    VariantBindingConfig(dbConfig),
    ExperimentEventConfig(dbConfig),
    WebhookConfig(dbConfig, WebhookEventsConfig(5, 1.second, 1, 1.second)),
    UserConfig(dbConfig, InitialUserConfig("", "")),
    ApikeyConfig(dbConfig, InitializeApiKey(None, None, "*")),
    InMemoryEvents(InMemoryEventsConfig()),
    PatchConfig(dbConfig),
    MetricsConfig(false,
                  false,
                  false,
                  MetricsHttpConfig("json"),
                  MetricsKafkaConfig(false, "topic", "json", 1.second),
                  MetricsElasticConfig(false, "topic", 1.second))
  )

  "Script" must {

    "a script executed must return true" in {

      import domains.script.syntax._
      import domains.script.ScriptInstances._

      implicit val ec: ScriptExecutionContext =
        ScriptExecutionContext(testComponents.actorSystem)

      val result: Boolean = Script(script)
        .run[IO](
          Json.obj("name" -> "Ragnar"),
          Env(
            config,
            testComponents.environment,
            testComponents.actorSystem,
            testComponents.wsClient,
            testComponents.assetsFinder,
            new MetricRegistry()
          )
        )
        .unsafeRunSync()

      result must be(true)
    }
    "a script executed must return false" in {

      import domains.script.syntax._
      import domains.script.ScriptInstances._

      implicit val ec: ScriptExecutionContext =
        ScriptExecutionContext(testComponents.actorSystem)

      val result: Boolean = Script(script)
        .run[IO](
          Json.obj("name" -> "Floki"),
          Env(
            config,
            testComponents.environment,
            testComponents.actorSystem,
            testComponents.wsClient,
            testComponents.assetsFinder,
            new MetricRegistry()
          )
        )
        .unsafeRunSync()

      result must be(false)
    }

  }

}
