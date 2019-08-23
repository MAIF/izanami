package domains.feature

import java.time.{LocalDateTime, ZoneId}
import java.time.temporal.ChronoUnit

import akka.actor.ActorSystem
import akka.stream.ActorMaterializer
import akka.stream.scaladsl.{Sink, Source}
import domains.apikey.Apikey
import domains.AuthInfo
import domains.AuthInfo
import domains.AuthorizedPattern
import domains.events.Events
import domains.events.Events._
import domains.events.EventStore
import domains.events.EventStore
import domains.Key
import domains.script._
import domains.script.GlobalScript.GlobalScriptKey
import domains.script.Script.ScriptCache
import domains.user.User
import libs.logs.{Logger, ProdLogger}
import libs.logs.Logger
import libs.logs.ProdLogger
import org.scalatest.concurrent.{IntegrationPatience, ScalaFutures}
import play.api.libs.json.{JsSuccess, JsValue, Json}
import scala.collection.concurrent.TrieMap
import scala.collection.mutable
import scala.reflect.ClassTag
import scala.util.Random
import store.JsonDataStore
import store.JsonDataStore
import store._
import store.memory.InMemoryJsonDataStore
import store.Result.{DataShouldExists, IzanamiErrors}
import test.{IzanamiSpec, TestEventStore}
import test.TestEventStore
import zio.{DefaultRuntime, RIO, Task, ZIO}
import zio.blocking.Blocking
import zio.internal.Executor
import zio.internal.PlatformLive
import zio.RIO
import zio.ZIO
import org.scalatest.BeforeAndAfterAll
import akka.testkit.TestKit
import domains.ImportResult
import store.Result.AppErrors
import store.Result.IdMustBeTheSame
import play.api.Environment
import scala.concurrent.ExecutionContext
import play.api.libs.ws.ahc.AhcWSComponents
import test.FakeApplicationLifecycle
import play.api.Configuration
import play.api.libs.json.JsArray

class FeatureSpec extends IzanamiSpec with ScalaFutures with IntegrationPatience with BeforeAndAfterAll {

  implicit val runtime          = new DefaultRuntime {}
  implicit val actorSystem      = ActorSystem()
  implicit val mat              = ActorMaterializer()
  override def afterAll(): Unit = TestKit.shutdownActorSystem(actorSystem)

  "Feature Deserialisation" must {

    "Deserialize DefaultFeature" in {
      import FeatureInstances._
      val json = Json.parse("""
          |{
          |   "id": "id",
          |   "enabled": true,
          |   "activationStrategy": "NO_STRATEGY"
          |}
        """.stripMargin)

      val result = json.validate[Feature]
      result mustBe an[JsSuccess[_]]

      result.get must be(DefaultFeature(Key("id"), true))

    }

    "Deserialize GlobalScriptFeature" in {
      import FeatureInstances._
      val json = Json.parse("""
          |{
          |   "id": "id",
          |   "enabled": true,
          |   "activationStrategy": "GLOBAL_SCRIPT",
          |   "parameters": { "ref": "ref" }
          |}
        """.stripMargin)

      val result = json.validate[Feature]
      result mustBe an[JsSuccess[_]]

      result.get must be(GlobalScriptFeature(Key("id"), true, "ref"))

    }

    "Deserialize ScriptFeature" in {
      import FeatureInstances._
      val json = Json.parse("""
          |{
          |   "id": "id",
          |   "enabled": true,
          |   "activationStrategy": "SCRIPT",
          |   "parameters": { "script": "script" }
          |}
        """.stripMargin)

      val result = json.validate[Feature]
      result mustBe an[JsSuccess[_]]

      result.get must be(ScriptFeature(Key("id"), true, JavascriptScript("script")))

    }

    "Deserialize ReleaseDateFeature" in {
      import FeatureInstances._
      val json =
        Json.parse("""
          |{
          |   "id": "id",
          |   "enabled": true,
          |   "activationStrategy": "RELEASE_DATE",
          |   "parameters": { "releaseDate": "01/01/2017 12:12:12" }
          |}
        """.stripMargin)

      val result = json.validate[Feature]
      result mustBe an[JsSuccess[_]]

      result.get must be(ReleaseDateFeature(Key("id"), true, LocalDateTime.of(2017, 1, 1, 12, 12, 12)))

    }

    "Deserialize ReleaseDateFeature other format" in {
      import FeatureInstances._
      val json =
        Json.parse("""
                     |{
                     |   "id": "id",
                     |   "enabled": true,
                     |   "activationStrategy": "RELEASE_DATE",
                     |   "parameters": { "releaseDate": "01/01/2017 12:12" }
                     |}
                   """.stripMargin)

      val result = json.validate[Feature]
      result mustBe an[JsSuccess[_]]

      result.get must be(ReleaseDateFeature(Key("id"), true, LocalDateTime.of(2017, 1, 1, 12, 12, 0)))

    }
  }

  "Feature Serialisation" should {

    "Serialize DefaultFeature" in {
      import FeatureInstances._
      val json = Json.parse("""
          |{
          |   "id": "id",
          |   "enabled": true,
          |   "activationStrategy": "NO_STRATEGY"
          |}
        """.stripMargin)

      Json.toJson(DefaultFeature(Key("id"), true)) must be(json)
    }

    "Deserialize GlobalScriptFeature" in {
      import FeatureInstances._
      val json = Json.parse("""
          |{
          |   "id": "id",
          |   "enabled": true,
          |   "activationStrategy": "GLOBAL_SCRIPT",
          |   "parameters": { "ref": "ref" }
          |}
        """.stripMargin)

      Json.toJson(GlobalScriptFeature(Key("id"), true, "ref")) must be(json)
    }

    "Deserialize ScriptFeature" in {
      import FeatureInstances._
      val json = Json.parse("""
          |{
          |   "id": "id",
          |   "enabled": true,
          |   "activationStrategy": "SCRIPT",
          |   "parameters": { "type": "javascript", "script": "script" }
          |}
        """.stripMargin)
      Json.toJson(ScriptFeature(Key("id"), true, JavascriptScript("script"))) must be(json)
    }

    "Deserialize ReleaseDateFeature" in {
      import FeatureInstances._
      val json =
        Json.parse("""
          |{
          |   "id": "id",
          |   "enabled": true,
          |   "activationStrategy": "RELEASE_DATE",
          |   "parameters": { "releaseDate": "01/01/2017 12:12:12" }
          |}
        """.stripMargin)
      Json.toJson(ReleaseDateFeature(Key("id"), true, LocalDateTime.of(2017, 1, 1, 12, 12, 12))) must be(json)
    }
  }

  "Feature graph" must {
    "Serialization must be ok" in {
      val features = List(DefaultFeature(Key("a"), true),
                          DefaultFeature(Key("a:b"), false),
                          DefaultFeature(Key("a:b:c"), true),
                          DefaultFeature(Key("a:b:d"), false))

      import FeatureInstances._

      val graph = Source(features)
        .via(
          runIsActiveTask(
            Feature.toGraph(Json.obj())
          )
        )
        .runWith(Sink.head)

      graph.futureValue must be(
        Json.obj(
          "a" -> Json.obj(
            "active" -> true,
            "b" -> Json.obj(
              "active" -> false,
              "c"      -> Json.obj("active" -> true),
              "d"      -> Json.obj("active" -> false)
            )
          )
        )
      )
    }
  }

  "Date range feature" must {
    "active" in {
      val from    = LocalDateTime.now(ZoneId.of("Europe/Paris")).minus(1, ChronoUnit.HOURS)
      val to      = LocalDateTime.now(ZoneId.of("Europe/Paris")).plus(1, ChronoUnit.HOURS)
      val feature = DateRangeFeature(Key("key"), true, from = from, to = to)

      import cats._
      runIsActive(DateRangeFeatureInstances.isActive.isActive(feature, Json.obj())) must be(true)
    }

    "inactive" in {
      val from    = LocalDateTime.now(ZoneId.of("Europe/Paris")).plus(1, ChronoUnit.MINUTES)
      val to      = LocalDateTime.now(ZoneId.of("Europe/Paris")).plus(2, ChronoUnit.HOURS)
      val feature = DateRangeFeature(Key("key"), true, from = from, to = to)

      import cats._
      runIsActive(DateRangeFeatureInstances.isActive.isActive(feature, Json.obj())) must be(false)
    }
  }

  "Release date feature" must {
    "active" in {
      val date    = LocalDateTime.now(ZoneId.of("Europe/Paris")).minus(1, ChronoUnit.HOURS)
      val feature = ReleaseDateFeature(Key("key"), true, date = date)

      import cats._
      runIsActive(ReleaseDateFeatureInstances.isActive.isActive(feature, Json.obj())) must be(true)
    }

    "inactive" in {
      val date    = LocalDateTime.now(ZoneId.of("Europe/Paris")).plus(1, ChronoUnit.MINUTES)
      val feature = ReleaseDateFeature(Key("key"), true, date = date)

      import cats._
      runIsActive(ReleaseDateFeatureInstances.isActive.isActive(feature, Json.obj())) must be(false)
    }
  }

  "Percentage feature" must {
    "Calc ratio" in {
      val feature = PercentageFeature(Key("key"), true, 60)

      val count: Int = calcPercentage(feature) { i =>
        s"string-number-$i"
      }

      count must (be > 55 and be < 65)

      val count2: Int = calcPercentage(feature) { i =>
        Random.nextString(50)
      }

      count2 must (be > 55 and be < 65)

    }
  }

  val authInfo = Some(Apikey("1", "name", "****", AuthorizedPattern("pattern")))

  "FeatureService" must {

    "create" in {
      val id      = Key("test")
      val ctx     = TestFeatureContext()
      val feature = DefaultFeature(id, true)

      val created = run(ctx)(FeatureService.create(id, feature))
      created must be(feature)
      ctx.featureDataStore.inMemoryStore.contains(id) must be(true)
      ctx.events must have size 1
      inside(ctx.events.head) {
        case FeatureCreated(i, k, _, _, auth) =>
          i must be(id)
          k must be(feature)
          auth must be(authInfo)
      }
    }

    "create id not equal" in {
      val id      = Key("test")
      val ctx     = TestFeatureContext()
      val feature = DefaultFeature(id, true)

      val created = run(ctx)(FeatureService.create(Key("other"), feature).either)
      created must be(Left(IdMustBeTheSame(feature.id, Key("other"))))
      ctx.featureDataStore.inMemoryStore.contains(id) must be(false)
      ctx.events must have size 0
    }

    "update if data not exists" in {
      val id      = Key("test")
      val ctx     = TestFeatureContext()
      val feature = DefaultFeature(id, true)

      val updated = run(ctx)(FeatureService.update(id, id, feature).either)
      updated must be(Left(DataShouldExists(id)))
    }

    "update" in {
      val id      = Key("test")
      val ctx     = TestFeatureContext()
      val feature = DefaultFeature(id, true)

      val test = for {
        _       <- FeatureService.create(id, feature)
        updated <- FeatureService.update(id, id, feature)
      } yield updated

      val updated = run(ctx)(test)
      updated must be(feature)
      ctx.featureDataStore.inMemoryStore.contains(id) must be(true)
      ctx.events must have size 2
      inside(ctx.events.last) {
        case FeatureUpdated(i, oldValue, newValue, _, _, auth) =>
          i must be(id)
          oldValue must be(feature)
          newValue must be(feature)
          auth must be(authInfo)
      }
    }

    "update changing id" in {
      val id      = Key("test")
      val newId   = Key("test2")
      val ctx     = TestFeatureContext()
      val feature = DefaultFeature(id, true)

      val test = for {
        _       <- FeatureService.create(id, feature)
        updated <- FeatureService.update(id, newId, feature)
      } yield updated

      val updated = run(ctx)(test)
      ctx.featureDataStore.inMemoryStore.contains(id) must be(false)
      ctx.featureDataStore.inMemoryStore.contains(newId) must be(true)
      ctx.events must have size 2
      inside(ctx.events.last) {
        case FeatureUpdated(i, oldValue, newValue, _, _, auth) =>
          i must be(newId)
          oldValue must be(feature)
          newValue must be(feature)
          auth must be(authInfo)
      }
    }

    "delete" in {
      val id      = Key("test")
      val ctx     = TestFeatureContext()
      val feature = DefaultFeature(id, true)

      val test = for {
        _       <- FeatureService.create(id, feature)
        deleted <- FeatureService.delete(id)
      } yield deleted

      val deleted = run(ctx)(test)
      ctx.featureDataStore.inMemoryStore.contains(id) must be(false)
      ctx.events must have size 2
      inside(ctx.events.last) {
        case FeatureDeleted(i, oldValue, _, _, auth) =>
          i must be(id)
          oldValue must be(feature)
          auth must be(authInfo)
      }
    }

    "delete empty data" in {
      val id      = Key("test")
      val ctx     = TestFeatureContext()
      val feature = DefaultFeature(id, true)

      val deleted = run(ctx)(FeatureService.delete(id).either)
      deleted must be(Left(DataShouldExists(id)))
      ctx.featureDataStore.inMemoryStore.contains(id) must be(false)
      ctx.events must have size 0
    }

    "get by id when active" in {
      val id      = Key("test")
      val date    = LocalDateTime.now(ZoneId.of("Europe/Paris")).minus(1, ChronoUnit.HOURS)
      val feature = ReleaseDateFeature(id, true, date = date)
      val ctx     = TestFeatureContext()

      val test = for {
        _           <- FeatureService.create(id, feature)
        mayBeFature <- FeatureService.getByIdActive(Json.obj(), id)
      } yield mayBeFature

      val mayBeFeature = run(ctx)(test)
      inside(mayBeFeature) {
        case Some((ReleaseDateFeature(ident, enabled, _), isActive)) =>
          ident must be(id)
          enabled must be(true)
          isActive must be(true)
      }
    }

    "get by id when not active" in {
      val id      = Key("test")
      val date    = LocalDateTime.now(ZoneId.of("Europe/Paris")).plus(1, ChronoUnit.HOURS)
      val feature = ReleaseDateFeature(id, true, date = date)
      val ctx     = TestFeatureContext()

      val test = for {
        _           <- FeatureService.create(id, feature)
        mayBeFature <- FeatureService.getByIdActive(Json.obj(), id)
      } yield mayBeFature

      val mayBeFeature = run(ctx)(test)
      inside(mayBeFeature) {
        case Some((ReleaseDateFeature(ident, enabled, _), isActive)) =>
          ident must be(id)
          enabled must be(true)
          isActive must be(false)
      }
    }

    "find when not active" in {
      val id      = Key("test")
      val date    = LocalDateTime.now(ZoneId.of("Europe/Paris")).plus(1, ChronoUnit.HOURS)
      val feature = ReleaseDateFeature(id, true, date = date)
      val ctx     = TestFeatureContext()

      val test = for {
        _           <- FeatureService.create(id, feature)
        mayBeFature <- FeatureService.findByQueryActive(Json.obj(), Query.oneOf("*"), 1, 20)
      } yield mayBeFature

      val mayBeFeature = run(ctx)(test)
      inside(mayBeFeature) {
        case DefaultPagingResult(pages, num, size, nbElt) =>
          pages must have size 1
          num must be(1)
          size must be(20)
          nbElt must be(1)
          inside(pages.head) {
            case (ReleaseDateFeature(ident, enabled, _), isActive) =>
              ident must be(id)
              enabled must be(true)
              isActive must be(false)
          }
      }
    }

    "find stream when not active" in {
      val id      = Key("test")
      val date    = LocalDateTime.now(ZoneId.of("Europe/Paris")).plus(1, ChronoUnit.HOURS)
      val feature = ReleaseDateFeature(id, true, date = date)
      val ctx     = TestFeatureContext()

      val test = for {
        _      <- FeatureService.create(id, feature)
        source <- FeatureService.findByQueryActive(Json.obj(), Query.oneOf("*"))
        res    <- ZIO.fromFuture(_ => source.runWith(Sink.seq))
      } yield res

      val mayBeFeature = run(ctx)(test)
      inside(mayBeFeature.head) {
        case (key, ReleaseDateFeature(ident, enabled, _), isActive) =>
          key must be(id)
          ident must be(id)
          enabled must be(true)
          isActive must be(false)
      }
    }

    "feature tree flat" in {
      val id1      = Key("test")
      val feature1 = DefaultFeature(id1, true)
      val id2      = Key("test:other")
      val feature2 = DefaultFeature(id2, false)

      val ctx = TestFeatureContext()

      val test = for {
        _      <- FeatureService.create(id1, feature1)
        _      <- FeatureService.create(id2, feature2)
        source <- FeatureService.getFeatureTree(Query.oneOf("*"), true, Json.obj())
        res    <- ZIO.fromFuture(_ => source.runWith(Sink.head))
      } yield res

      val tree = run(ctx)(test)
      tree.asInstanceOf[JsArray].value must contain theSameElementsAs (
        Seq(
          Json.obj("id" -> "test", "enabled"       -> true, "activationStrategy"  -> "NO_STRATEGY", "active" -> true),
          Json.obj("id" -> "test:other", "enabled" -> false, "activationStrategy" -> "NO_STRATEGY", "active" -> false)
        )
      )
    }

    "feature tree tree" in {
      val id1      = Key("test")
      val feature1 = DefaultFeature(id1, true)
      val id2      = Key("test:other")
      val feature2 = DefaultFeature(id2, false)

      val ctx = TestFeatureContext()

      val test = for {
        _      <- FeatureService.create(id1, feature1)
        _      <- FeatureService.create(id2, feature2)
        source <- FeatureService.getFeatureTree(Query.oneOf("*"), false, Json.obj())
        res    <- ZIO.fromFuture(_ => source.runWith(Sink.head))
      } yield res

      val tree = run(ctx)(test)
      tree must be(
        Json.obj(
          "test" -> Json.obj(
            "other" -> Json.obj(
              "active" -> false
            ),
            "active" -> true
          )
        )
      )
    }

    "import data" in {
      val id      = Key("test")
      val ctx     = TestFeatureContext()
      val feature = DefaultFeature(id, true)

      val res = run(ctx)(FeatureService.importData.flatMap { flow =>
        Task.fromFuture { implicit ec =>
          Source(List((id.key, FeatureInstances.format.writes(feature))))
            .via(flow)
            .runWith(Sink.seq)
        }
      })
      res must contain only (ImportResult(success = 1))
    }

    "import data invalid format" in {
      val id      = Key("test")
      val ctx     = TestFeatureContext()
      val feature = DefaultFeature(id, true)

      val res = run(ctx)(FeatureService.importData.flatMap { flow =>
        Task.fromFuture { implicit ec =>
          Source(
            List(
              (id.key, Json.obj())
            )
          ).via(flow)
            .runWith(Sink.seq)
        }
      })
      res must contain only (ImportResult(errors = AppErrors.error("json.parse.error", id.key)))
    }

    "import data data exist" in {
      val id      = Key("test")
      val ctx     = TestFeatureContext()
      val feature = DefaultFeature(id, true)

      val test = for {
        _ <- FeatureService.create(id, feature)
        res <- FeatureService.importData.flatMap { flow =>
                Task.fromFuture { implicit ec =>
                  Source(
                    List(
                      (id.key, FeatureInstances.format.writes(feature))
                    )
                  ).via(flow)
                    .runWith(Sink.seq)
                }
              }
      } yield res

      val res = run(ctx)(test)
      res must contain only (ImportResult(errors = AppErrors.error("error.data.exists", id.key)))
    }
  }

  val blockingInstance: Blocking.Service[Any] = new Blocking.Service[Any] {
    def blockingExecutor: ZIO[Any, Nothing, Executor] =
      ZIO.succeed(
        Executor
          .fromExecutionContext(20)(actorSystem.dispatchers.lookup("izanami.blocking-dispatcher"))
      )
  }

  val fakeEnv = Environment.simple()

  val fakeAhcComponent = new AhcWSComponents {
    override def environment: Environment                                   = fakeEnv
    override val applicationLifecycle: play.api.inject.ApplicationLifecycle = FakeApplicationLifecycle()
    override val configuration: play.api.Configuration                      = Configuration.load(fakeEnv)
    override val executionContext: scala.concurrent.ExecutionContext        = actorSystem.dispatcher
    override val materializer: akka.stream.Materializer                     = mat
  }

  val wsClient  = fakeAhcComponent.wsClient
  val jWsClient = play.test.WSTestClient.newClient(-1)

  case class TestFeatureContext(
      events: mutable.ArrayBuffer[Events.IzanamiEvent] = mutable.ArrayBuffer.empty,
      user: Option[AuthInfo] = None,
      featureDataStore: InMemoryJsonDataStore = new InMemoryJsonDataStore("Feature-test"),
      globalScriptDataStore: InMemoryJsonDataStore = new InMemoryJsonDataStore("Feature-test"),
      scriptCache: ScriptCache = fakeCache,
      logger: Logger = new ProdLogger,
      authInfo: Option[AuthInfo] = authInfo,
      blocking: Blocking.Service[Any] = blockingInstance
  ) extends FeatureContext {
    override def eventStore: EventStore                               = new TestEventStore(events)
    override def withAuthInfo(user: Option[AuthInfo]): FeatureContext = this.copy(user = user)
    override def environment: Environment                             = fakeEnv
    override def ec: ExecutionContext                                 = actorSystem.dispatcher
    override val javaWsClient: play.libs.ws.WSClient                  = jWsClient
    override val wSClient: play.api.libs.ws.WSClient                  = wsClient
  }

  private def runIsActive[T](t: ZIO[IsActiveContext, IzanamiErrors, T]): T =
    runtime.unsafeRun(ZIO.provide(isActiveContext)(t))

  private def runIsActiveTask[T](t: RIO[IsActiveContext, T]): T =
    runtime.unsafeRun(ZIO.provide(isActiveContext)(t))

  private def isActiveContext: IsActiveContext = new IsActiveContext {
    import zio.interop.catz._
    override val blocking: Blocking.Service[Any] = blockingInstance
    override val logger: Logger                  = new ProdLogger
    override val scriptCache: ScriptCache        = fakeCache
    override val globalScriptDataStore: JsonDataStore =
      new InMemoryJsonDataStore("script", TrieMap.empty[GlobalScriptKey, JsValue])
    override val eventStore: EventStore                                    = new TestEventStore()
    override def withAuthInfo(authInfo: Option[AuthInfo]): IsActiveContext = this
    override def authInfo: Option[AuthInfo]                                = None
    override val environment: Environment                                  = fakeEnv
    override val ec: ExecutionContext                                      = actorSystem.dispatcher
    override val javaWsClient: play.libs.ws.WSClient                       = jWsClient
    override val wSClient: play.api.libs.ws.WSClient                       = wsClient
  }

  def fakeCache: ScriptCache = new ScriptCache {
    override def get[T: ClassTag](id: String): Task[Option[T]]      = Task.succeed(None)
    override def set[T: ClassTag](id: String, value: T): Task[Unit] = Task.succeed(())
  }

  private def calcPercentage(feature: PercentageFeature)(mkString: Int => String) = {
    import cats._
    val count = (0 to 1000)
      .map { i =>
        val isActive =
          runIsActive(
            PercentageFeatureInstances.isActive
              .isActive(feature, Json.obj("id" -> mkString(i)))
          )
        isActive
      }
      .count(identity) / 10
    count
  }
}
