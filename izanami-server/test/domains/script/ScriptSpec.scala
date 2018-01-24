package domains.script

import com.typesafe.config.ConfigFactory
import controllers.AssetsComponents
import env._
import org.scalatest.concurrent.{IntegrationPatience, ScalaFutures}
import org.scalatestplus.play.PlaySpec
import org.scalatestplus.play.components.OneServerPerSuiteWithComponents
import play.api.ApplicationLoader.Context
import play.api.libs.json.Json
import play.api.libs.ws.ahc.AhcWSComponents
import play.api.{
  BuiltInComponents,
  BuiltInComponentsFromContext,
  Configuration,
  NoHttpFiltersComponents
}

import scala.concurrent.duration.DurationInt

/**
  * Created by adelegue on 18/07/2017.
  */
class ScriptSpec
    extends PlaySpec
    with OneServerPerSuiteWithComponents
    with ScalaFutures
    with IntegrationPatience {

  case class TestComponent(context: Context)
      extends BuiltInComponentsFromContext(context)
      with NoHttpFiltersComponents
      with AhcWSComponents
      with AssetsComponents {
    import play.api.mvc.Results
    import play.api.routing.Router
    import play.api.routing.sird._

    def globalScripStore: GlobalScriptStore = null

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

  val config = IzanamiConfig(
    Some("dev"),
    Default(DefaultFilter(Seq(), "", "", "", ApiKey("", ""))),
    DbConfig("", None, None, None, None, None),
    LogoutConfig(""),
    ConfigConfig(DbDomainConfig("", DbDomainConfigDetails(""))),
    FeaturesConfig(DbDomainConfig("", DbDomainConfigDetails(""))),
    GlobalScriptConfig(DbDomainConfig("", DbDomainConfigDetails(""))),
    ExperimentConfig(DbDomainConfig("", DbDomainConfigDetails(""))),
    VariantBindingConfig(DbDomainConfig("", DbDomainConfigDetails(""))),
    ExperimentEventConfig(DbDomainConfig("", DbDomainConfigDetails(""))),
    WebhookConfig(DbDomainConfig("", DbDomainConfigDetails("")),
                  WebhookEventsConfig(5, 1.second, 1, 1.second)),
    UserConfig(DbDomainConfig("", DbDomainConfigDetails("")),
               InitialUserConfig("", "")),
    ApikeyConfig(DbDomainConfig("", DbDomainConfigDetails(""))),
    InMemoryEvents(InMemoryEventsConfig()),
    PatchConfig(DbDomainConfig("", DbDomainConfigDetails("")))
  )

  "Script" must {

    "a script executed must return true" in {

      implicit val ec: ScriptExecutionContext =
        ScriptExecutionContext(testComponents.actorSystem)

      val result: Boolean = Script
        .executeScript(
          script,
          Json.obj("name" -> "Ragnar"),
          Env(
            config,
            testComponents.environment,
            testComponents.actorSystem,
            testComponents.wsClient,
            testComponents.globalScripStore,
            testComponents.assetsFinder
          )
        )
        .futureValue

      result must be(true)
    }
    "a script executed must return false" in {

      implicit val ec: ScriptExecutionContext =
        ScriptExecutionContext(testComponents.actorSystem)

      val result: Boolean = Script
        .executeScript(
          script,
          Json.obj("name" -> "Floki"),
          Env(
            config,
            testComponents.environment,
            testComponents.actorSystem,
            testComponents.wsClient,
            testComponents.globalScripStore,
            testComponents.assetsFinder
          )
        )
        .futureValue

      result must be(false)
    }

  }

}
