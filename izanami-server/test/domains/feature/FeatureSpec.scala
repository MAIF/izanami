package domains.feature

import java.time.{LocalDateTime, ZoneId}
import java.time.temporal.ChronoUnit
import akka.actor.ActorSystem
import akka.stream.Materializer
import akka.stream.scaladsl.{Sink, Source}
import domains.apikey.Apikey
import domains.{AuthorizedPatterns, ImportResult, Key, PatternRights}
import domains.auth.AuthInfo
import domains.events.Events
import domains.events.Events._
import domains.events.EventStore
import domains.script.{RunnableScriptModule, Script, _}
import domains.script.GlobalScript.GlobalScriptKey
import libs.logs.ZLogger
import org.scalatest.concurrent.{IntegrationPatience, ScalaFutures}
import play.api.libs.json.{JsSuccess, JsValue, Json}

import scala.collection.concurrent.TrieMap
import scala.collection.mutable
import scala.reflect.ClassTag
import scala.util.Random
import store.datastore.JsonDataStore
import store._
import store.memory.InMemoryJsonDataStore
import domains.errors.{DataShouldExists, IdMustBeTheSame, InvalidCopyKey, IzanamiErrors, Unauthorized, ValidationError}
import test.{FakeApplicationLifecycle, FakeConfig, IzanamiSpec, TestEventStore}
import zio.{RIO, Runtime, Task, ULayer, ZIO, ZLayer}
import zio.blocking.Blocking
import zio.internal.Executor
import org.scalatest.BeforeAndAfterAll
import akka.testkit.TestKit
import play.api.Environment

import scala.concurrent.ExecutionContext
import play.api.libs.ws.ahc.AhcWSComponents
import play.api.Configuration
import play.api.libs.json.JsArray

import java.time.LocalTime
import java.time.Duration
import cats.data.NonEmptyList
import domains.configuration.PlayModule
import domains.configuration.PlayModule.PlayModuleProd
import domains.script.RunnableScriptModule.RunnableScriptModuleProd
import env.IzanamiConfig
import env.configuration.IzanamiConfigModule

import javax.script.{Invocable, ScriptEngine, ScriptEngineManager}
import org.jetbrains.kotlin.script.jsr223.KotlinJsr223JvmLocalScriptEngineFactory
import play.api.inject.ApplicationLifecycle
import play.api.libs.ws.WSClient
import play.libs.ws

class FeatureSpec extends IzanamiSpec with ScalaFutures with IntegrationPatience with BeforeAndAfterAll {

  implicit val runtime     = Runtime.default
  implicit val actorSystem = ActorSystem()

  import IzanamiErrors._

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

      result.get must be(DefaultFeature(Key("id"), true, None))

    }

    "Deserialize DefaultFeature without enabled" in {
      import FeatureInstances._
      val json = Json.parse("""
          |{
          |   "id": "id",
          |   "activationStrategy": "NO_STRATEGY"
          |}
        """.stripMargin)

      val result = json.validate[Feature]
      result mustBe an[JsSuccess[_]]

      result.get must be(DefaultFeature(Key("id"), false, None))
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

      result.get must be(GlobalScriptFeature(Key("id"), true, None, "ref"))

    }

    "Deserialize GlobalScriptFeature  without enabled" in {
      import FeatureInstances._
      val json = Json.parse("""
          |{
          |   "id": "id",
          |   "activationStrategy": "GLOBAL_SCRIPT",
          |   "parameters": { "ref": "ref" }
          |}
        """.stripMargin)

      val result = json.validate[Feature]
      result mustBe an[JsSuccess[_]]

      result.get must be(GlobalScriptFeature(Key("id"), false, None, "ref"))

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

      result.get must be(ScriptFeature(Key("id"), true, None, JavascriptScript("script")))

    }

    "Deserialize ScriptFeature without enabled" in {
      import FeatureInstances._
      val json = Json.parse("""
          |{
          |   "id": "id",
          |   "activationStrategy": "SCRIPT",
          |   "parameters": { "script": "script" }
          |}
        """.stripMargin)

      val result = json.validate[Feature]
      result mustBe an[JsSuccess[_]]

      result.get must be(ScriptFeature(Key("id"), false, None, JavascriptScript("script")))

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

      result.get must be(ReleaseDateFeature(Key("id"), true, None, LocalDateTime.of(2017, 1, 1, 12, 12, 12)))

    }

    "Deserialize ReleaseDateFeature without enabled" in {
      import FeatureInstances._
      val json =
        Json.parse("""
          |{
          |   "id": "id",
          |   "activationStrategy": "RELEASE_DATE",
          |   "parameters": { "releaseDate": "01/01/2017 12:12:12" }
          |}
        """.stripMargin)

      val result = json.validate[Feature]
      result mustBe an[JsSuccess[_]]

      result.get must be(ReleaseDateFeature(Key("id"), false, None, LocalDateTime.of(2017, 1, 1, 12, 12, 12)))

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

      result.get must be(ReleaseDateFeature(Key("id"), true, None, LocalDateTime.of(2017, 1, 1, 12, 12, 0)))

    }

    "Deserialize HourRangeFeature" in {
      import FeatureInstances._
      val json =
        Json.parse("""
                |{
                |   "id": "id",
                |   "enabled": true,
                |   "activationStrategy": "HOUR_RANGE",
                |   "parameters": { 
                |     "startAt": "02:15",
                |     "endAt": "17:30" 
                |   }
                |}""".stripMargin)

      val result = json.validate[Feature]
      result mustBe an[JsSuccess[_]]

      result.get must be(HourRangeFeature(Key("id"), true, None, LocalTime.of(2, 15), LocalTime.of(17, 30)))

    }

    "Deserialize HourRangeFeature 2" in {
      import FeatureInstances._
      val json =
        Json.parse("""{
                     |"id":"test",
                     |"enabled":true,
                     |"parameters":{
                     |   "endAt":"13:06",
                     |   "startAt":"1:06"
                     |},
                     |"activationStrategy":"HOUR_RANGE"
                     |}""".stripMargin)

      val result = json.validate[Feature]
      result mustBe an[JsSuccess[_]]

      result.get must be(HourRangeFeature(Key("test"), true, None, LocalTime.of(1, 6), LocalTime.of(13, 6)))

    }

    "Deserialize HourRangeFeature  without enabled" in {
      import FeatureInstances._
      val json =
        Json.parse("""
                     |{
                     |   "id": "id",
                     |   "activationStrategy": "HOUR_RANGE",
                     |   "parameters": { 
                     |     "startAt": "02:15",
                     |     "endAt": "17:30" 
                     |   }
                     |}""".stripMargin)

      val result = json.validate[Feature]
      result mustBe an[JsSuccess[_]]

      result.get must be(HourRangeFeature(Key("id"), false, None, LocalTime.of(2, 15), LocalTime.of(17, 30)))
    }

    "Deserialize CustomersFeature" in {
      import FeatureInstances._
      val json =
        Json.parse("""{
                     |"id":"test",
                     |"enabled":true,
                     |"parameters":{
                     |   "customers":["id1", "id2"]
                     |},
                     |"activationStrategy":"CUSTOMERS_LIST"
                     |}""".stripMargin)

      val result = json.validate[Feature]
      result mustBe an[JsSuccess[_]]

      result.get must be(CustomersFeature(Key("test"), true, None, List("id1", "id2")))

    }

    "Deserialize CustomersFeature without enabled" in {
      import FeatureInstances._
      val json =
        Json.parse("""{
                     |"id":"test",
                     |"parameters":{
                     |   "customers":["id3", "id4"]
                     |},
                     |"activationStrategy":"CUSTOMERS_LIST"
                     |}""".stripMargin)

      val result = json.validate[Feature]
      result mustBe an[JsSuccess[_]]

      result.get must be(CustomersFeature(Key("test"), false, None, List("id3", "id4")))
    }

  }

  "Feature Serialisation" should {

    "Serialize DefaultFeature" in {
      val json = Json.parse("""
          |{
          |   "id": "id",
          |   "enabled": true,
          |   "activationStrategy": "NO_STRATEGY"
          |}
        """.stripMargin)

      FeatureInstances.format.writes(DefaultFeature(Key("id"), true, None)) must be(json)
    }

    "Serialize GlobalScriptFeature" in {
      val json = Json.parse("""
          |{
          |   "id": "id",
          |   "enabled": true,
          |   "activationStrategy": "GLOBAL_SCRIPT",
          |   "parameters": { "ref": "ref" }
          |}
        """.stripMargin)

      FeatureInstances.format.writes(GlobalScriptFeature(Key("id"), true, None, "ref")) must be(json)
    }

    "Serialize ScriptFeature" in {
      val json = Json.parse("""
          |{
          |   "id": "id",
          |   "enabled": true,
          |   "activationStrategy": "SCRIPT",
          |   "parameters": { "type": "javascript", "script": "script" }
          |}
        """.stripMargin)
      FeatureInstances.format.writes(ScriptFeature(Key("id"), true, None, JavascriptScript("script"))) must be(json)
    }

    "Serialize ReleaseDateFeature" in {
      val json =
        Json.parse("""
          |{
          |   "id": "id",
          |   "enabled": true,
          |   "activationStrategy": "RELEASE_DATE",
          |   "parameters": { "releaseDate": "01/01/2017 12:12:12" }
          |}
        """.stripMargin)
      FeatureInstances.format.writes(
        ReleaseDateFeature(Key("id"), true, None, LocalDateTime.of(2017, 1, 1, 12, 12, 12))
      ) must be(json)
    }

    "Serialize HourRangeFeature" in {
      val json =
        Json.parse("""
                     |{
                     |   "id": "id",
                     |   "enabled": true,
                     |   "activationStrategy": "HOUR_RANGE",
                     |   "parameters": { 
                     |     "startAt": "02:30",
                     |     "endAt": "17:15" 
                     |   }
                     |}
        """.stripMargin)
      FeatureInstances.format.writes(HourRangeFeature(Key("id"), true, None, LocalTime.of(2, 30), LocalTime.of(17, 15))) must be(
        json
      )
    }

    "Serialize CustomersFeature" in {
      val json =
        Json.parse("""
                     |{
                     |   "id": "id",
                     |   "enabled": true,
                     |   "activationStrategy": "CUSTOMERS_LIST",
                     |   "parameters": { 
                     |     "customers": ["id5", "id6"]
                     |   }
                     |}
        """.stripMargin)
      FeatureInstances.format.writes(CustomersFeature(Key("id"), true, None, List("id5", "id6"))) must be(
        json
      )
    }
  }

  "Feature graph" must {
    "Serialization must be ok" in {
      val features = List(
        DefaultFeature(Key("a"), true, None),
        DefaultFeature(Key("a:b"), false, None),
        DefaultFeature(Key("a:b:c"), true, None),
        DefaultFeature(Key("a:b:d"), false, None)
      )

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
      val feature = DateRangeFeature(Key("key"), true, None, from = from, to = to)

      runIsActive(DateRangeFeatureInstances.isActive.isActive(feature, Json.obj())) must be(true)
    }

    "inactive" in {
      val from    = LocalDateTime.now(ZoneId.of("Europe/Paris")).plus(1, ChronoUnit.MINUTES)
      val to      = LocalDateTime.now(ZoneId.of("Europe/Paris")).plus(2, ChronoUnit.HOURS)
      val feature = DateRangeFeature(Key("key"), true, None, from = from, to = to)

      runIsActive(DateRangeFeatureInstances.isActive.isActive(feature, Json.obj())) must be(false)
    }
  }

  "Release date feature" must {
    "active" in {
      val date    = LocalDateTime.now(ZoneId.of("Europe/Paris")).minus(1, ChronoUnit.HOURS)
      val feature = ReleaseDateFeature(Key("key"), true, None, date = date)

      runIsActive(ReleaseDateFeatureInstances.isActive.isActive(feature, Json.obj())) must be(true)
    }

    "inactive" in {
      val date    = LocalDateTime.now(ZoneId.of("Europe/Paris")).plus(1, ChronoUnit.MINUTES)
      val feature = ReleaseDateFeature(Key("key"), true, None, date = date)

      runIsActive(ReleaseDateFeatureInstances.isActive.isActive(feature, Json.obj())) must be(false)
    }
  }

  "Percentage feature" must {
    "Calc ratio" in {
      val feature = PercentageFeature(Key("key"), true, None, 60)

      val count: Int = calcPercentage(feature)(i => s"string-number-$i")

      count must (be > 55 and be < 65)

      val count2: Int = calcPercentage(feature)(i => Random.nextString(50))

      count2 must (be > 55 and be < 65)

    }
  }

  "Customers feature" must {
    val feature = CustomersFeature(Key("key"), true, None, List("id1", "id2"))

    "active" in {
      runIsActive(CustomersFeatureInstances.isActive.isActive(feature, Json.obj("id" -> "id1"))) must be(true)
    }

    "inactive" in {
      runIsActive(CustomersFeatureInstances.isActive.isActive(feature, Json.obj("id" -> "id3"))) must be(false)
    }
  }

  "Hour range feature" must {
    "active" in {

      val startAt = LocalTime.now().minus(Duration.ofHours(1))
      val endAt   = LocalTime.now().plus(Duration.ofMinutes(30))
      val feature = HourRangeFeature(Key("key"), true, None, startAt, endAt)

      runIsActive(HourRangeFeatureInstances.isActive.isActive(feature, Json.obj())) must be(true)

    }

    "inactive" in {

      val startAt = LocalTime.now().plus(Duration.ofMinutes(1))
      val endAt   = LocalTime.now().plus(Duration.ofMinutes(30))
      val feature = HourRangeFeature(Key("key"), true, None, startAt, endAt)

      runIsActive(HourRangeFeatureInstances.isActive.isActive(feature, Json.obj())) must be(false)

    }
  }

  val authInfo = Some(Apikey("1", "name", "****", AuthorizedPatterns.All))

  def authInfo(patterns: AuthorizedPatterns = AuthorizedPatterns.All, admin: Boolean = false) =
    Some(Apikey("1", "name", "****", patterns, admin = admin))

  "FeatureService" must {

    "create" in {
      val id               = Key("test")
      val events           = mutable.ArrayBuffer.empty[Events.IzanamiEvent]
      val featureDataStore = new InMemoryJsonDataStore("Feature-test")
      val ctx              = testFeatureContext(featureDataStore = featureDataStore, events = events)
      val feature          = DefaultFeature(id, true, None)

      val created = run(ctx)(FeatureService.create(id, feature))
      created must be(feature)
      featureDataStore.inMemoryStore.contains(id) must be(true)
      events must have size 1
      inside(events.head) {
        case FeatureCreated(i, k, _, _, auth) =>
          i must be(id)
          k must be(feature)
          auth must be(authInfo)
      }
    }

    "create forbidden" in {
      val id = Key("test")

      val events           = mutable.ArrayBuffer.empty[Events.IzanamiEvent]
      val featureDataStore = new InMemoryJsonDataStore("Feature-test")
      val ctx = testFeatureContext(
        featureDataStore = featureDataStore,
        events = events,
        user = authInfo(patterns = AuthorizedPatterns.of("*" -> PatternRights.R))
      )
      val feature = DefaultFeature(id, true, None)

      val value = run(ctx)(FeatureService.create(id, feature).either)
      value mustBe Left(NonEmptyList.of(Unauthorized(Some(Key("test")))))
      featureDataStore.inMemoryStore.contains(id) must be(false)
    }

    "create id not equal" in {
      val id               = Key("test")
      val events           = mutable.ArrayBuffer.empty[Events.IzanamiEvent]
      val featureDataStore = new InMemoryJsonDataStore("Feature-test")
      val ctx              = testFeatureContext(featureDataStore = featureDataStore, events = events)
      val feature          = DefaultFeature(id, true, None)

      val created = run(ctx)(FeatureService.create(Key("other"), feature).either)
      created must be(Left(IdMustBeTheSame(feature.id, Key("other")).toErrors))
      featureDataStore.inMemoryStore.contains(id) must be(false)
      events must have size 0
    }

    "update if data not exists" in {
      val id      = Key("test")
      val ctx     = testFeatureContext()
      val feature = DefaultFeature(id, true, None)

      val updated = run(ctx)(FeatureService.update(id, id, feature).either)
      updated must be(Left(DataShouldExists(id).toErrors))
    }

    "update" in {
      val id               = Key("test")
      val events           = mutable.ArrayBuffer.empty[Events.IzanamiEvent]
      val featureDataStore = new InMemoryJsonDataStore("Feature-test")
      val ctx              = testFeatureContext(featureDataStore = featureDataStore, events = events)
      val feature          = DefaultFeature(id, true, None)

      val test = for {
        _       <- FeatureService.create(id, feature)
        updated <- FeatureService.update(id, id, feature)
      } yield updated

      val updated = run(ctx)(test)
      updated must be(feature)
      featureDataStore.inMemoryStore.contains(id) must be(true)
      events must have size 2
      inside(events.last) {
        case FeatureUpdated(i, oldValue, newValue, _, _, auth) =>
          i must be(id)
          oldValue must be(feature)
          newValue must be(feature)
          auth must be(authInfo)
      }
    }

    "update forbidden" in {
      val id               = Key("test")
      val events           = mutable.ArrayBuffer.empty[Events.IzanamiEvent]
      val featureDataStore = new InMemoryJsonDataStore("Feature-test")
      val ctx = testFeatureContext(
        featureDataStore = featureDataStore,
        events = events,
        user = authInfo(patterns = AuthorizedPatterns.of("*" -> PatternRights.C))
      )
      val feature = DefaultFeature(id, true, None)

      val value = run(ctx)(FeatureService.update(id, id, feature).either)
      value mustBe Left(NonEmptyList.of(Unauthorized(Some(Key("test")))))
      featureDataStore.inMemoryStore.contains(id) must be(false)
    }

    "update changing id" in {
      val id               = Key("test")
      val newId            = Key("test2")
      val events           = mutable.ArrayBuffer.empty[Events.IzanamiEvent]
      val featureDataStore = new InMemoryJsonDataStore("Feature-test")
      val ctx              = testFeatureContext(featureDataStore = featureDataStore, events = events)
      val feature          = DefaultFeature(id, true, None)

      val test = for {
        _       <- FeatureService.create(id, feature)
        updated <- FeatureService.update(id, newId, feature)
      } yield updated

      run(ctx)(test)
      featureDataStore.inMemoryStore.contains(id) must be(false)
      featureDataStore.inMemoryStore.contains(newId) must be(true)
      events must have size 2
      inside(events.last) {
        case FeatureUpdated(i, oldValue, newValue, _, _, auth) =>
          i must be(newId)
          oldValue must be(feature)
          newValue must be(feature)
          auth must be(authInfo)
      }
    }

    "copy node" in {
      val id1              = Key("my:awesome:feature:to:copy1")
      val id2              = Key("my:awesome:feature:to:copy2")
      val copiedId1        = Key("my:awesome:other:feature:to:copy1")
      val copiedId2        = Key("my:awesome:other:feature:to:copy2")
      val events           = mutable.ArrayBuffer.empty[Events.IzanamiEvent]
      val featureDataStore = new InMemoryJsonDataStore("Feature-test")
      val ctx              = testFeatureContext(featureDataStore = featureDataStore, events = events)
      val feature1         = DefaultFeature(id1, true, None)
      val feature2         = DefaultFeature(id2, true, None)

      val test = for {
        _      <- FeatureService.create(id1, feature1)
        _      <- FeatureService.create(id2, feature2)
        copied <- FeatureService.copyNode(Key("my:awesome"), Key("my:awesome:other"), false)
      } yield copied

      run(ctx)(test)
      featureDataStore.inMemoryStore.contains(id1) must be(true)
      featureDataStore.inMemoryStore.contains(id2) must be(true)
      featureDataStore.inMemoryStore.contains(copiedId1) must be(true)
      featureDataStore.inMemoryStore.contains(copiedId2) must be(true)

      inside(events.last) {
        case FeatureCreated(i, newValue, _, _, auth) =>
          i must (be(copiedId2) or be(copiedId1))
          newValue must (be(feature1.copy(id = copiedId1, enabled = false)) or be(
            feature2.copy(id = copiedId2, enabled = false)
          ))
          auth must be(authInfo)
      }
    }

    "copy node forbidden" in {
      val id               = Key("test")
      val events           = mutable.ArrayBuffer.empty[Events.IzanamiEvent]
      val featureDataStore = new InMemoryJsonDataStore("Feature-test")
      val ctx = testFeatureContext(
        featureDataStore = featureDataStore,
        events = events,
        user = authInfo(patterns = AuthorizedPatterns.of("test" -> PatternRights.R))
      )

      val value = run(ctx)(FeatureService.copyNode(Key("my:awesome"), Key("my:awesome:other"), false).either)
      value mustBe Left(
        NonEmptyList.of(
          Unauthorized(Some(Key("my:awesome"))),
          Unauthorized(Some(Key("my:awesome:other")))
        )
      )
      featureDataStore.inMemoryStore.contains(id) must be(false)
    }

    "copy existing node" in {
      val id1              = Key("my:awesome:feature:to:copy1")
      val id2              = Key("my:awesome:feature:to:copy2")
      val events           = mutable.ArrayBuffer.empty[Events.IzanamiEvent]
      val featureDataStore = new InMemoryJsonDataStore("Feature-test")
      val ctx              = testFeatureContext(featureDataStore = featureDataStore, events = events)
      val feature1         = DefaultFeature(id1, true, None)
      val feature2         = DefaultFeature(id2, true, None)

      val test = for {
        _      <- FeatureService.create(id1, feature1)
        _      <- FeatureService.create(id2, feature2)
        copied <- FeatureService.copyNode(Key("my:awesome"), Key("my:awesome"), false)
      } yield copied

      run(ctx)(test)
      featureDataStore.inMemoryStore.contains(id1) must be(true)
      featureDataStore.inMemoryStore.contains(id2) must be(true)
      events.size must be(2)
    }

    "invalid key" in {
      val id1              = Key("my:awesome:feature:to:copy1")
      val id2              = Key("my:awesome:feature:to:copy2")
      val copiedId1        = Key("my:awesome:other:feature:to:copy1")
      val copiedId2        = Key("my:awesome:other:feature:to:copy2")
      val events           = mutable.ArrayBuffer.empty[Events.IzanamiEvent]
      val featureDataStore = new InMemoryJsonDataStore("Feature-test")
      val ctx              = testFeatureContext(featureDataStore = featureDataStore, events = events)
      val feature1         = DefaultFeature(id1, true, None)
      val feature2         = DefaultFeature(id2, true, None)
      val from             = Key("my:*:awesome")
      val to               = Key("my:awesome:*:other")

      val test = for {
        _      <- FeatureService.create(id1, feature1)
        _      <- FeatureService.create(id2, feature2)
        copied <- FeatureService.copyNode(from, to, false)
      } yield copied

      val copied = run(ctx)(test.either)
      copied must be(Left(NonEmptyList.of(InvalidCopyKey(from), InvalidCopyKey(to))))
      featureDataStore.inMemoryStore.contains(id1) must be(true)
      featureDataStore.inMemoryStore.contains(id2) must be(true)
      featureDataStore.inMemoryStore.contains(copiedId1) must be(false)
      featureDataStore.inMemoryStore.contains(copiedId2) must be(false)
    }

    "delete" in {
      val id               = Key("test")
      val events           = mutable.ArrayBuffer.empty[Events.IzanamiEvent]
      val featureDataStore = new InMemoryJsonDataStore("Feature-test")
      val ctx              = testFeatureContext(featureDataStore = featureDataStore, events = events)
      val feature          = DefaultFeature(id, true, None)

      val test = for {
        _       <- FeatureService.create(id, feature)
        deleted <- FeatureService.delete(id)
      } yield deleted

      val deleted = run(ctx)(test)
      featureDataStore.inMemoryStore.contains(id) must be(false)
      events must have size 2
      inside(events.last) {
        case FeatureDeleted(i, oldValue, _, _, auth) =>
          i must be(id)
          oldValue must be(feature)
          auth must be(authInfo)
      }
    }

    "delete forbidden" in {
      val id               = Key("test")
      val events           = mutable.ArrayBuffer.empty[Events.IzanamiEvent]
      val featureDataStore = new InMemoryJsonDataStore("Feature-test")
      val ctx = testFeatureContext(
        featureDataStore = featureDataStore,
        events = events,
        user = authInfo(patterns = AuthorizedPatterns.of("*" -> PatternRights.C))
      )

      val value = run(ctx)(FeatureService.delete(id).either)
      value mustBe Left(NonEmptyList.of(Unauthorized(Some(Key("test")))))
      featureDataStore.inMemoryStore.contains(id) must be(false)
    }

    "delete empty data" in {
      val id               = Key("test")
      val events           = mutable.ArrayBuffer.empty[Events.IzanamiEvent]
      val featureDataStore = new InMemoryJsonDataStore("Feature-test")
      val ctx              = testFeatureContext(featureDataStore = featureDataStore, events = events)
      val feature          = DefaultFeature(id, true, None)

      val deleted = run(ctx)(FeatureService.delete(id).either)
      deleted must be(Left(DataShouldExists(id).toErrors))
      featureDataStore.inMemoryStore.contains(id) must be(false)
      events must have size 0
    }

    "get by id when active" in {
      val id      = Key("test")
      val date    = LocalDateTime.now(ZoneId.of("Europe/Paris")).minus(1, ChronoUnit.HOURS)
      val feature = ReleaseDateFeature(id, true, None, date = date)
      val ctx     = testFeatureContext()

      val test = for {
        _           <- FeatureService.create(id, feature)
        mayBeFature <- FeatureService.getByIdActive(Json.obj(), id)
      } yield mayBeFature

      val mayBeFeature = run(ctx)(test)
      inside(mayBeFeature) {
        case Some((ReleaseDateFeature(ident, enabled, None, _), isActive)) =>
          ident must be(id)
          enabled must be(true)
          isActive must be(true)
      }
    }

    "get by id when not active" in {
      val id      = Key("test")
      val date    = LocalDateTime.now(ZoneId.of("Europe/Paris")).plus(1, ChronoUnit.HOURS)
      val feature = ReleaseDateFeature(id, true, None, date = date)
      val ctx     = testFeatureContext()

      val test = for {
        _           <- FeatureService.create(id, feature)
        mayBeFature <- FeatureService.getByIdActive(Json.obj(), id)
      } yield mayBeFature

      val mayBeFeature = run(ctx)(test)
      inside(mayBeFeature) {
        case Some((ReleaseDateFeature(ident, enabled, None, _), isActive)) =>
          ident must be(id)
          enabled must be(true)
          isActive must be(false)
      }
    }

    "find when not active" in {
      val id      = Key("test")
      val date    = LocalDateTime.now(ZoneId.of("Europe/Paris")).plus(1, ChronoUnit.HOURS)
      val feature = ReleaseDateFeature(id, true, None, date = date)
      val ctx     = testFeatureContext()

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
            case (ReleaseDateFeature(ident, enabled, None, _), isActive) =>
              ident must be(id)
              enabled must be(true)
              isActive must be(false)
          }
      }
    }

    "get by id forbidden" in {
      val id               = Key("test")
      val events           = mutable.ArrayBuffer.empty[Events.IzanamiEvent]
      val featureDataStore = new InMemoryJsonDataStore("Feature-test")
      val ctx = testFeatureContext(
        featureDataStore = featureDataStore,
        events = events,
        user = authInfo(patterns = AuthorizedPatterns.of("other" -> PatternRights.C))
      )

      val value = run(ctx)(FeatureService.getById(id).either)
      value mustBe Left(NonEmptyList.of(Unauthorized(Some(Key("test")))))
      featureDataStore.inMemoryStore.contains(id) must be(false)
    }

    "get by id active forbidden" in {
      val id               = Key("test")
      val events           = mutable.ArrayBuffer.empty[Events.IzanamiEvent]
      val featureDataStore = new InMemoryJsonDataStore("Feature-test")
      val ctx = testFeatureContext(
        featureDataStore = featureDataStore,
        events = events,
        user = authInfo(patterns = AuthorizedPatterns.of("other" -> PatternRights.C))
      )

      val value = run(ctx)(FeatureService.getByIdActive(Json.obj(), id).either)
      value mustBe Left(NonEmptyList.of(Unauthorized(Some(Key("test")))))
      featureDataStore.inMemoryStore.contains(id) must be(false)
    }

    "find stream when not active" in {
      val id      = Key("test")
      val date    = LocalDateTime.now(ZoneId.of("Europe/Paris")).plus(1, ChronoUnit.HOURS)
      val feature = ReleaseDateFeature(id, true, None, date = date)
      val ctx     = testFeatureContext()

      val test = for {
        _      <- FeatureService.create(id, feature)
        source <- FeatureService.findByQueryActive(Json.obj(), Query.oneOf("*"))
        res    <- ZIO.fromFuture(_ => source.runWith(Sink.seq))
      } yield res

      val mayBeFeature = run(ctx)(test)
      inside(mayBeFeature.head) {
        case (key, ReleaseDateFeature(ident, enabled, None, _), isActive) =>
          key must be(id)
          ident must be(id)
          enabled must be(true)
          isActive must be(false)
      }
    }

    "feature tree flat" in {
      val id1      = Key("test")
      val feature1 = DefaultFeature(id1, true, None)
      val id2      = Key("test:other")
      val feature2 = DefaultFeature(id2, false, None)

      val ctx = testFeatureContext()

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
      val feature1 = DefaultFeature(id1, true, None)
      val id2      = Key("test:other")
      val feature2 = DefaultFeature(id2, false, None)

      val ctx = testFeatureContext()

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
      val ctx     = testFeatureContext()
      val feature = DefaultFeature(id, true, None)

      val res = run(ctx)(FeatureService.importData().flatMap { flow =>
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
      val ctx     = testFeatureContext()
      val feature = DefaultFeature(id, true, None)

      val res = run(ctx)(FeatureService.importData().flatMap { flow =>
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
      val id      = Key("test")
      val ctx     = testFeatureContext()
      val feature = DefaultFeature(id, true, None)

      val test = for {
        _ <- FeatureService.create(id, feature)
        res <- FeatureService.importData().flatMap { flow =>
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
      res must contain only (ImportResult())
    }
  }

  val env: Environment                     = Environment.simple()
  val playModule: ULayer[PlayModule]       = FakeConfig.playModule(actorSystem, env)
  val testScript: RunnableScriptModuleProd = RunnableScriptModuleProd(env.classLoader)

  val runnableScriptModule: ZLayer[Any, Nothing, RunnableScriptModule] = RunnableScriptModule.value(testScript)

  def testFeatureContext(
      events: mutable.ArrayBuffer[Events.IzanamiEvent] = mutable.ArrayBuffer.empty,
      user: Option[AuthInfo.Service] = authInfo,
      featureDataStore: InMemoryJsonDataStore = new InMemoryJsonDataStore("Feature-test"),
      globalScriptDataStore: InMemoryJsonDataStore = new InMemoryJsonDataStore("Feature-test"),
      scriptCache: CacheService[String] = fakeCache
  ): ZLayer[Any, Throwable, FeatureContext] =
    playModule ++ ZLogger.live ++ Blocking.live ++
    ScriptCache.value(scriptCache) ++ EventStore.value(new TestEventStore(events)) ++ AuthInfo
      .optValue(user) ++ GlobalScriptDataStore.value(globalScriptDataStore) ++ FeatureDataStore.value(featureDataStore) ++
    runnableScriptModule >+> IzanamiConfigModule.value(FakeConfig.config)

  private def runIsActive[T](t: ZIO[IsActiveContext, IzanamiErrors, T]): T =
    runtime.unsafeRun(t.provideLayer(isActiveContext))

  private def runIsActiveTask[T](t: RIO[IsActiveContext, T]): T =
    runtime.unsafeRun(t.provideLayer(isActiveContext))

  private def isActiveContext: ZLayer[Any, Throwable, IsActiveContext] =
    playModule ++ ScriptCache.value(fakeCache) ++ EventStore.value(new TestEventStore()) ++ AuthInfo
      .optValue(Some(Apikey("1", "key", "secret", AuthorizedPatterns.All, true))) ++ Blocking.live ++ GlobalScriptDataStore
      .value(
        new InMemoryJsonDataStore("script", TrieMap.empty[GlobalScriptKey, JsValue])
      ) ++ runnableScriptModule ++ ZLogger.live >+> IzanamiConfigModule.value(FakeConfig.config)

  def fakeCache: CacheService[String] = new CacheService[String] {
    override def get[T: ClassTag](id: String): Task[Option[T]]      = Task.succeed(None)
    override def set[T: ClassTag](id: String, value: T): Task[Unit] = Task.succeed(())
  }

  private def calcPercentage(feature: PercentageFeature)(mkString: Int => String) = {
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
