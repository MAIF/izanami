package domains.script

import com.codahale.metrics.MetricRegistry
import controllers.AssetsComponents
import env._
import org.scalatest.concurrent.{IntegrationPatience, ScalaFutures}
import org.scalatestplus.play.PlaySpec
import org.scalatestplus.play.components.OneServerPerSuiteWithComponents
import play.api.ApplicationLoader.Context
import play.api.libs.json.{JsValue, Json}
import play.api.libs.ws.ahc.AhcWSComponents
import play.api.{
  BuiltInComponents,
  BuiltInComponentsFromContext,
  Configuration,
  Environment,
  Mode,
  NoHttpFiltersComponents
}
import play.libs.ws.ahc.AhcWSClient
import play.shaded.ahc.org.asynchttpclient.AsyncHttpClient
import zio.blocking.Blocking
import zio.{RIO, Runtime, Task, ULayer, ZLayer}

import scala.concurrent.duration.DurationInt
import scala.reflect.ClassTag
import libs.logs.ZLogger
import org.scalatest.BeforeAndAfterAll
import akka.actor.ActorSystem
import akka.stream.{ActorMaterializer, Materializer}
import akka.testkit.TestKit
import domains.events.EventStore
import test.{FakeApplicationLifecycle, FakeConfig, IzanamiSpec, TestEventStore}
import domains.{AuthorizedPatterns, ImportResult, Key, PatternRights}
import domains.auth.AuthInfo
import store.memory.InMemoryJsonDataStore

import scala.collection.mutable
import domains.events.Events
import domains.events.Events._
import akka.stream.scaladsl.Source
import akka.stream.scaladsl.Sink
import cats.data.NonEmptyList
import domains.apikey.Apikey
import domains.configuration.PlayModule
import domains.configuration.PlayModule.PlayModuleProd
import domains.errors.{DataShouldExists, IdMustBeTheSame, Unauthorized, ValidationError}
import domains.script.RunnableScriptModule.RunnableScriptModuleProd

/**
 * Created by adelegue on 18/07/2017.
 */
class ScriptSpec
    extends PlaySpec
    with IzanamiSpec
    with OneServerPerSuiteWithComponents
    with ScalaFutures
    with IntegrationPatience
    with BeforeAndAfterAll {

  implicit val system           = ActorSystem("test")
  override def afterAll(): Unit = TestKit.shutdownActorSystem(system)

  import domains.errors.IzanamiErrors._

  implicit val runtime = Runtime.default

  "Script" must {

    "a javascript script executed must return true" in {

      import domains.script.ScriptInstances._
      import domains.script.syntax._

      val theScript: Script = JavascriptScript(script)
      val result: ScriptExecution = runScript(
        theScript
          .run(Json.obj("name" -> "Ragnar"))
      )

      result must be(ScriptExecutionSuccess(true))
    }

    "a javascript script executed must return false" in {

      import domains.script.ScriptInstances._
      import domains.script.syntax._

      val theScript: Script = JavascriptScript(script)
      val result: ScriptExecution = runScript(
        theScript
          .run(Json.obj("name" -> "Floki"))
      )

      result must be(ScriptExecutionSuccess(false))
    }

    "a kotlin script executed must return true" in {

      import domains.script.ScriptInstances._
      import domains.script.syntax._

      val theScript: Script = KotlinScript(kotlinScript)
      val result: ScriptExecution = runScript(
        theScript
          .run(Json.obj("name" -> "Ragnar"))
      )

      result must be(ScriptExecutionSuccess(true))
    }

    "a kotlin script executed must return false" in {

      import domains.script.ScriptInstances._
      import domains.script.syntax._

      val theScript: Script = KotlinScript(kotlinScript)
      val result: ScriptExecution = runScript(
        theScript
          .run(Json.obj("name" -> "Floki"))
      )

      result must be(ScriptExecutionSuccess(false))
    }

  }

  val authInfo = Some(Apikey("1", "name", "****", AuthorizedPatterns.All))

  def authInfo(patterns: AuthorizedPatterns = AuthorizedPatterns.All, admin: Boolean = false) =
    Some(Apikey("1", "name", "****", patterns, admin = admin))

  "ScriptService" must {

    "create" in {
      val id                    = Key("test")
      val globalScript          = GlobalScript(id, "name", "description", JavascriptScript(script))
      val events                = mutable.ArrayBuffer.empty[Events.IzanamiEvent]
      val globalScriptDataStore = new InMemoryJsonDataStore("globalScript-test")
      val ctx                   = testGlobalScriptContext(events, globalScriptDataStore)

      val created = run(ctx)(GlobalScriptService.create(id, globalScript))
      created must be(globalScript)
      globalScriptDataStore.inMemoryStore.contains(id) must be(true)
      events must have size 1
      inside(events.head) {
        case GlobalScriptCreated(i, k, _, _, auth) =>
          i must be(id)
          k must be(globalScript)
          auth must be(authInfo)
      }
    }

    "create forbidden" in {
      val id                    = Key("test")
      val globalScript          = GlobalScript(id, "name", "description", JavascriptScript(script))
      val events                = mutable.ArrayBuffer.empty[Events.IzanamiEvent]
      val globalScriptDataStore = new InMemoryJsonDataStore("globalScript-test")
      val ctx = testGlobalScriptContext(
        events,
        globalScriptDataStore,
        authInfo(patterns = AuthorizedPatterns.of("*" -> PatternRights.R))
      )

      val value = run(ctx)(GlobalScriptService.create(id, globalScript).either)
      value mustBe Left(NonEmptyList.of(Unauthorized(Some(Key("test")))))
      globalScriptDataStore.inMemoryStore.contains(id) must be(false)
    }

    "create id not equal" in {
      val id                    = Key("test")
      val events                = mutable.ArrayBuffer.empty[Events.IzanamiEvent]
      val globalScriptDataStore = new InMemoryJsonDataStore("globalScript-test")
      val ctx                   = testGlobalScriptContext(events, globalScriptDataStore)
      val globalScript          = GlobalScript(Key("other"), "name", "description", JavascriptScript(script))

      val created = run(ctx)(GlobalScriptService.create(id, globalScript).either)
      created must be(Left(IdMustBeTheSame(globalScript.id, id).toErrors))
      globalScriptDataStore.inMemoryStore.contains(id) must be(false)
      events must have size 0
    }

    "update if data not exists" in {
      val id                    = Key("test")
      val events                = mutable.ArrayBuffer.empty[Events.IzanamiEvent]
      val globalScriptDataStore = new InMemoryJsonDataStore("globalScript-test")
      val ctx                   = testGlobalScriptContext(events, globalScriptDataStore)
      val globalScript          = GlobalScript(id, "name", "description", JavascriptScript(script))

      val updated = run(ctx)(GlobalScriptService.update(id, id, globalScript).either)
      updated must be(Left(DataShouldExists(id).toErrors))
    }

    "update" in {
      val id                    = Key("test")
      val events                = mutable.ArrayBuffer.empty[Events.IzanamiEvent]
      val globalScriptDataStore = new InMemoryJsonDataStore("globalScript-test")
      val ctx                   = testGlobalScriptContext(events, globalScriptDataStore)
      val globalScript          = GlobalScript(id, "name", "description", JavascriptScript(script))

      val test = for {
        _       <- GlobalScriptService.create(id, globalScript)
        updated <- GlobalScriptService.update(id, id, globalScript)
      } yield updated

      val updated = run(ctx)(test)
      updated must be(globalScript)
      globalScriptDataStore.inMemoryStore.contains(id) must be(true)
      events must have size 2
      inside(events.last) {
        case GlobalScriptUpdated(i, oldValue, newValue, _, _, auth) =>
          i must be(id)
          oldValue must be(globalScript)
          newValue must be(globalScript)
          auth must be(authInfo)
      }
    }

    "update forbidden" in {
      val id                    = Key("test")
      val globalScript          = GlobalScript(id, "name", "description", JavascriptScript(script))
      val events                = mutable.ArrayBuffer.empty[Events.IzanamiEvent]
      val globalScriptDataStore = new InMemoryJsonDataStore("globalScript-test")
      val ctx = testGlobalScriptContext(
        events,
        globalScriptDataStore,
        authInfo(patterns = AuthorizedPatterns.of("*" -> PatternRights.R))
      )

      val value = run(ctx)(GlobalScriptService.update(id, id, globalScript).either)
      value mustBe Left(NonEmptyList.of(Unauthorized(Some(Key("test")))))
      globalScriptDataStore.inMemoryStore.contains(id) must be(false)
    }

    "update changing id" in {
      val id                    = Key("test")
      val newId                 = Key("test2")
      val events                = mutable.ArrayBuffer.empty[Events.IzanamiEvent]
      val globalScriptDataStore = new InMemoryJsonDataStore("globalScript-test")
      val ctx                   = testGlobalScriptContext(events, globalScriptDataStore)
      val globalScript          = GlobalScript(id, "name", "description", JavascriptScript(script))

      val test = for {
        _       <- GlobalScriptService.create(id, globalScript)
        updated <- GlobalScriptService.update(id, newId, globalScript)
      } yield updated

      val updated = run(ctx)(test)
      globalScriptDataStore.inMemoryStore.contains(id) must be(false)
      globalScriptDataStore.inMemoryStore.contains(newId) must be(true)
      events must have size 2
      inside(events.last) {
        case GlobalScriptUpdated(i, oldValue, newValue, _, _, auth) =>
          i must be(newId)
          oldValue must be(globalScript)
          newValue must be(globalScript)
          auth must be(authInfo)
      }
    }

    "delete" in {
      val id                    = Key("test")
      val events                = mutable.ArrayBuffer.empty[Events.IzanamiEvent]
      val globalScriptDataStore = new InMemoryJsonDataStore("globalScript-test")
      val ctx                   = testGlobalScriptContext(events, globalScriptDataStore)
      val globalScript          = GlobalScript(id, "name", "description", JavascriptScript(script))

      val test = for {
        _       <- GlobalScriptService.create(id, globalScript)
        deleted <- GlobalScriptService.delete(id)
      } yield deleted

      val deleted = run(ctx)(test)
      globalScriptDataStore.inMemoryStore.contains(id) must be(false)
      events must have size 2
      inside(events.last) {
        case GlobalScriptDeleted(i, oldValue, _, _, auth) =>
          i must be(id)
          oldValue must be(globalScript)
          auth must be(authInfo)
      }
    }

    "delete forbidden" in {
      val id                    = Key("test")
      val events                = mutable.ArrayBuffer.empty[Events.IzanamiEvent]
      val globalScriptDataStore = new InMemoryJsonDataStore("globalScript-test")
      val ctx = testGlobalScriptContext(
        events,
        globalScriptDataStore,
        authInfo(patterns = AuthorizedPatterns.of("*" -> PatternRights.R))
      )

      val value = run(ctx)(GlobalScriptService.delete(id).either)
      value mustBe Left(NonEmptyList.of(Unauthorized(Some(Key("test")))))
      globalScriptDataStore.inMemoryStore.contains(id) must be(false)
    }

    "delete empty data" in {
      val id                    = Key("test")
      val events                = mutable.ArrayBuffer.empty[Events.IzanamiEvent]
      val globalScriptDataStore = new InMemoryJsonDataStore("globalScript-test")
      val ctx                   = testGlobalScriptContext(events, globalScriptDataStore)

      val deleted = run(ctx)(GlobalScriptService.delete(id).either)
      deleted must be(Left(DataShouldExists(id).toErrors))
      globalScriptDataStore.inMemoryStore.contains(id) must be(false)
      events must have size 0
    }

    "import data" in {
      val id                    = Key("test")
      val events                = mutable.ArrayBuffer.empty[Events.IzanamiEvent]
      val globalScriptDataStore = new InMemoryJsonDataStore("globalScript-test")
      val ctx                   = testGlobalScriptContext(events, globalScriptDataStore)
      val globalScript          = GlobalScript(id, "name", "description", JavascriptScript(script))

      val res = run(ctx)(GlobalScriptService.importData().flatMap { flow =>
        Task.fromFuture { implicit ec =>
          Source(List((id.key, GlobalScriptInstances.format.writes(globalScript))))
            .via(flow)
            .runWith(Sink.seq)
        }
      })
      res must contain only (ImportResult(success = 1))
    }

    "import data invalid format" in {
      val id                    = Key("test")
      val events                = mutable.ArrayBuffer.empty[Events.IzanamiEvent]
      val globalScriptDataStore = new InMemoryJsonDataStore("globalScript-test")
      val ctx                   = testGlobalScriptContext(events, globalScriptDataStore)

      val res = run(ctx)(GlobalScriptService.importData().flatMap { flow =>
        Task.fromFuture { implicit ec =>
          Source(
            List(
              (id.key, Json.obj())
            )
          ).via(flow)
            .runWith(Sink.seq)
        }
      })
      res must contain only (ImportResult(errors = List(ValidationError.error("json.parse.error", id.key))))
    }

    "import data data exist" in {
      val id                    = Key("test")
      val events                = mutable.ArrayBuffer.empty[Events.IzanamiEvent]
      val globalScriptDataStore = new InMemoryJsonDataStore("globalScript-test")
      val ctx                   = testGlobalScriptContext(events, globalScriptDataStore)
      val globalScript          = GlobalScript(id, "name", "description", JavascriptScript(script))

      val test = for {
        _ <- GlobalScriptService.create(id, globalScript)
        res <- GlobalScriptService.importData().flatMap { flow =>
                Task.fromFuture { implicit ec =>
                  Source(
                    List(
                      (id.key, GlobalScriptInstances.format.writes(globalScript))
                    )
                  ).via(flow)
                    .runWith(Sink.seq)
                }
              }
      } yield res

      val res = run(ctx)(test)
      res must contain only (ImportResult())
    }

  }

  def testGlobalScriptContext(
      events: mutable.ArrayBuffer[Events.IzanamiEvent] = mutable.ArrayBuffer.empty,
      globalScriptDataStore: InMemoryJsonDataStore = new InMemoryJsonDataStore("globalScript-test"),
      user: Option[AuthInfo.Service] = authInfo
  ): ZLayer[Any, Throwable, GlobalScriptContext] =
    playModule ++
    ZLogger.live ++
    Blocking.live ++
    ScriptCache.value(fakeCache) ++
    EventStore.value(new TestEventStore(events)) ++
    AuthInfo.optValue(user) ++
    GlobalScriptDataStore.value(globalScriptDataStore) ++
    runnableScriptContext

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

    lazy val router: Router = Router.from({
      case GET(p"/surname") =>
        defaultActionBuilder {
          Results.Ok(Json.obj("surname" -> "Lodbrok"))
        }
    })
  }

  lazy val testComponents: TestComponent = TestComponent(context)

  override def components: BuiltInComponents = testComponents

  lazy val script: String =
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

  lazy val kotlinScript: String =
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

  private def runScript[T](t: RIO[RunnableScriptContext, T]): T =
    runtime.unsafeRun(t.provideLayer(runnableScriptContext))

  private val environment: Environment = Environment.simple()
  val playModule: ULayer[PlayModule] = PlayModule.live(
    PlayModuleProd(
      testComponents.actorSystem,
      Materializer(testComponents.actorSystem),
      null,
      Configuration.empty,
      environment,
      testComponents.wsClient,
      testComponents.wsJavaClient,
      testComponents.actorSystem.dispatcher,
      FakeApplicationLifecycle()
    )
  )

  val testScript: RunnableScriptModuleProd                             = RunnableScriptModuleProd(environment.classLoader, Mode.Test)
  val runnableScriptModule: ZLayer[Any, Nothing, RunnableScriptModule] = RunnableScriptModule.value(testScript)

  val runnableScriptContext: ZLayer[Any, Nothing, RunnableScriptContext] =
    playModule ++ runnableScriptModule ++ ZLogger.live ++ Blocking.live ++ ScriptCache
      .value(fakeCache)

  def fakeCache: CacheService[String] = new CacheService[String] {
    override def get[T: ClassTag](id: String): Task[Option[T]]      = Task.succeed(None)
    override def set[T: ClassTag](id: String, value: T): Task[Unit] = Task.succeed(())
  }
}
