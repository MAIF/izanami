package domains.script

import cats.Applicative
import cats.effect.IO
import com.codahale.metrics.MetricRegistry
import controllers.AssetsComponents
import domains.script.Script.ScriptCache
import env._
import org.scalatest.concurrent.{IntegrationPatience, ScalaFutures}
import org.scalatestplus.play.PlaySpec
import org.scalatestplus.play.components.OneServerPerSuiteWithComponents
import play.api.ApplicationLoader.Context
import play.api.libs.json.Json
import play.api.libs.ws.ahc.AhcWSComponents
import play.api.{BuiltInComponents, BuiltInComponentsFromContext, NoHttpFiltersComponents}
import play.libs.ws.ahc.AhcWSClient
import play.shaded.ahc.org.asynchttpclient.AsyncHttpClient

import scala.concurrent.duration.DurationInt
import scala.reflect.ClassTag

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

    def wsJavaClient: play.libs.ws.WSClient =
      new AhcWSClient(wsClient.underlying[AsyncHttpClient], materializer)

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
    "X-Forwarded-For",
    Default(DefaultFilter(Seq(), "", "", "", ApiKeyHeaders("", ""))),
    DbConfig(""),
    LogoutConfig(""),
    ConfigConfig(dbConfig),
    FeaturesConfig(dbConfig),
    GlobalScriptConfig(dbConfig),
    ExperimentConfig(dbConfig),
    ExperimentEventConfig(dbConfig),
    WebhookConfig(dbConfig, WebhookEventsConfig(5, 1.second, 1, 1.second)),
    UserConfig(dbConfig, InitialUserConfig("", "")),
    ApikeyConfig(dbConfig, InitializeApiKey(None, None, "*")),
    InMemoryEvents(InMemoryEventsConfig()),
    PatchConfig(dbConfig),
    MetricsConfig(
      false,
      MetricsConsoleConfig(false, 1.second),
      MetricsLogConfig(false, 1.second),
      MetricsHttpConfig("json"),
      MetricsKafkaConfig(false, "", "", 1.second),
      MetricsElasticConfig(false, "", 1.second)
    )
  )

  lazy val kotlinScript =
    s"""
       |fun enabled(context: JsonNode, enabled: () -> Unit, disabled: () -> Unit, wsClient: WSClient) {
       |    wsClient.url("http://localhost:$port/surname")
       |      .get()
       |      .whenComplete { wsResponse, e ->
       |    	  if (e != null) {
       |            disabled()
       |        } else {
       |            when (wsResponse.getStatus()) {
       |              200 -> {
       |                val jsonBody = wsResponse.asJson()
       |                if(jsonBody.get("surname").asText() == "Lodbrok" && context.get("name").asText() == "Ragnar") {
       |                  enabled()
       |                } else {
       |                  disabled()
       |                }
       |              }
       |              else -> disabled()
       |            }
       |        }
       |      }
       |}
         """.stripMargin

  "Script" must {

    "a javascript script executed must return true" in {

      import domains.script.ScriptInstances._
      import domains.script.syntax._

      implicit val cache: ScriptCache[IO] = fakeCache[IO]
      implicit val ec: ScriptExecutionContext =
        ScriptExecutionContext(testComponents.actorSystem)

      val theScript: Script = JavascriptScript(script)
      val result: ScriptExecution = theScript
        .run[IO](Json.obj("name" -> "Ragnar"), getEnv)
        .unsafeRunSync()

      result must be(ScriptExecutionSuccess(true))
    }

    "a javascript script executed must return false" in {

      import domains.script.ScriptInstances._
      import domains.script.syntax._

      implicit val cache: ScriptCache[IO] = fakeCache[IO]
      implicit val ec: ScriptExecutionContext =
        ScriptExecutionContext(testComponents.actorSystem)

      val theScript: Script = JavascriptScript(script)
      val result: ScriptExecution = theScript
        .run[IO](Json.obj("name" -> "Floki"), getEnv)
        .unsafeRunSync()

      result must be(ScriptExecutionSuccess(false))
    }

    "a kotlin script executed must return true" in {

      import domains.script.ScriptInstances._
      import domains.script.syntax._

      implicit val cache: ScriptCache[IO] = fakeCache[IO]
      implicit val ec: ScriptExecutionContext =
        ScriptExecutionContext(testComponents.actorSystem)

      val theScript: Script = KotlinScript(kotlinScript)
      val result: ScriptExecution = theScript
        .run[IO](Json.obj("name" -> "Ragnar"), getEnv)
        .unsafeRunSync()

      result must be(ScriptExecutionSuccess(true))
    }

    "a kotlin script executed must return false" in {

      import domains.script.ScriptInstances._
      import domains.script.syntax._

      implicit val cache: ScriptCache[IO] = fakeCache[IO]
      implicit val ec: ScriptExecutionContext =
        ScriptExecutionContext(testComponents.actorSystem)

      val theScript: Script = KotlinScript(kotlinScript)
      val result: ScriptExecution = theScript
        .run[IO](Json.obj("name" -> "Floki"), getEnv)
        .unsafeRunSync()

      result must be(ScriptExecutionSuccess(false))
    }

  }

  private def getEnv =
    Env(
      config,
      testComponents.environment,
      testComponents.actorSystem,
      testComponents.wsClient,
      testComponents.wsJavaClient,
      testComponents.assetsFinder,
      new MetricRegistry()
    )
  def fakeCache[F[_]: Applicative]: ScriptCache[F] = new ScriptCache[F] {
    override def get[T: ClassTag](id: String): F[Option[T]]      = Applicative[F].pure(None)
    override def set[T: ClassTag](id: String, value: T): F[Unit] = Applicative[F].pure(())
  }

}
