package fr.maif.izanami.api

import akka.http.scaladsl.model.sse.ServerSentEvent
import fr.maif.izanami.api.BaseAPISpec.{
  TestApiKey,
  TestCondition,
  TestDateTimePeriod,
  TestDayPeriod,
  TestFeature,
  TestFeatureContext,
  TestHourPeriod,
  TestPercentageRule,
  TestProject,
  TestSituationBuilder,
  TestTenant,
  TestUserListRule
}
import org.awaitility.Awaitility.await
import play.api.libs.json.{JsArray, JsObject, Json}

import java.time._
import scala.collection.mutable.ArrayBuffer
import scala.concurrent.duration.SECONDS

class EventsAPISpec extends BaseAPISpec {

  "event endpoint" should {
    "should send initial event" in {
      val situation = TestSituationBuilder()
        .loggedInWithAdminRights()
        .withTenants(
          TestTenant("test")
            .withApiKeys(TestApiKey("mykey", enabled = true, admin = true))
            .withProjects(
              TestProject("project")
                .withFeatureNames("f1", "f2")
            )
        )
        .build()

      val evts = ArrayBuffer[ServerSentEvent]()
      situation.listenEvents(
        key = "mykey",
        features = Seq(),
        projects = Seq(situation.findProjectId("test", "project").get),
        consumer = evt => {
          evts.addOne(evt);
        }
      )

      await atMost (10, SECONDS) until {
        evts.exists(e => e.eventType.get == "FEATURE_STATES")
      }

      val evt     = evts.findLast(e => e.eventType.get == "FEATURE_STATES")
      val f1Id    = situation.findFeatureId(tenant = "test", project = "project", feature = "f1").get
      val maybeF1 = (Json.parse(evt.get.data) \ "payload" \ f1Id).as[JsObject]
      (maybeF1 \ "name").as[String] mustEqual "f1"
      (maybeF1 \ "active").as[Boolean] mustEqual true
      (maybeF1 \ "conditions" \ "" \ "enabled").as[Boolean] mustEqual true
    }

    "should send initial event without condition if requested" in {
      val situation = TestSituationBuilder()
        .loggedInWithAdminRights()
        .withTenants(
          TestTenant("test")
            .withApiKeys(TestApiKey("mykey", enabled = true, admin = true))
            .withProjects(
              TestProject("project")
                .withFeatureNames("f1", "f2")
            )
        )
        .build()

      val evts = ArrayBuffer[ServerSentEvent]()
      situation.listenEvents(
        key = "mykey",
        features = Seq(),
        projects = Seq(situation.findProjectId("test", "project").get),
        consumer = evt => {
          evts.addOne(evt);
        },
        conditions = false
      )

      await atMost (10, SECONDS) until {
        evts.exists(e => e.eventType.get == "FEATURE_STATES")
      }

      val evt     = evts.findLast(e => e.eventType.get == "FEATURE_STATES")
      val f1Id    = situation.findFeatureId(tenant = "test", project = "project", feature = "f1").get
      val maybeF1 = (Json.parse(evt.get.data) \ "payload" \ f1Id).as[JsObject]
      (maybeF1 \ "name").as[String] mustEqual "f1"
      (maybeF1 \ "active").as[Boolean] mustEqual true
      (maybeF1 \ "conditions").asOpt[JsObject] mustBe None
    }

    "should send initial event periodically if asked" in {
      val situation = TestSituationBuilder()
        .loggedInWithAdminRights()
        .withTenants(
          TestTenant("test")
            .withApiKeys(TestApiKey("mykey", enabled = true, admin = true))
            .withProjects(
              TestProject("project")
                .withFeatureNames("f1", "f2")
            )
        )
        .build()

      val evts = ArrayBuffer[ServerSentEvent]()
      situation.listenEvents(
        key = "mykey",
        features = Seq(),
        projects = Seq(situation.findProjectId("test", "project").get),
        consumer = evt => {
          evts.addOne(evt);
        },
        refreshInterval = Duration.ofSeconds(1L)
      )

      await atMost (10, SECONDS) until { evts.size >= 5 }

      evts
        .map(_.getEventType)
        .filter(_.isPresent)
        .map(_.get)
        .filter("FEATURE_STATES".equals(_))
        .toSeq
        .size must be > 2
    }

    "should send keepAlive events when nothing happens" in {
      val situation = TestSituationBuilder()
        .loggedInWithAdminRights()
        .withTenants(
          TestTenant("test")
            .withApiKeys(TestApiKey("mykey", enabled = true, admin = true))
            .withProjects(
              TestProject("project")
                .withFeatureNames("f1", "f2")
            )
        )
        .build()

      val evts = ArrayBuffer[ServerSentEvent]()
      situation.listenEvents(
        key = "mykey",
        features = Seq(),
        projects = Seq(situation.findProjectId("test", "project").get),
        consumer = evt => {
          evts.addOne(evt);
        },
        keepAliveInterval = Duration.ofSeconds(2)
      )

      await atMost (35, SECONDS) until {
        evts.exists(s => s.eventType.get === "KEEP_ALIVE")
      }
    }

    "should send feature_created events when feature is created" in {
      val situation = TestSituationBuilder()
        .loggedInWithAdminRights()
        .withTenants(
          TestTenant("test")
            .withApiKeys(TestApiKey("mykey", enabled = true, admin = true))
            .withProjects(
              TestProject("project")
            )
        )
        .build()

      val evts = ArrayBuffer[ServerSentEvent]()
      situation.listenEvents(
        key = "mykey",
        features = Seq(),
        projects = Seq(situation.findProjectId("test", "project").get),
        consumer = evt => {
          evts.addOne(evt);
        },
        conditions = true
      )
      Thread.sleep(2000)

      situation.createFeature(
        "f3",
        project = "project",
        tenant = "test",
        enabled = false,
        conditions = Set(
          TestCondition(
            rule = TestUserListRule(users = Set("foo", "bar")),
            period = TestDateTimePeriod(
              begin = LocalDateTime.of(2020, 1, 1, 1, 0, 0),
              end = LocalDateTime.of(2120, 1, 1, 1, 0, 0),
              hourPeriods = Seq(
                TestHourPeriod(startTime = LocalTime.of(9, 0, 0), endTime = LocalTime.of(12, 0, 0)),
                TestHourPeriod(startTime = LocalTime.of(14, 0, 0), endTime = LocalTime.of(18, 0, 0))
              ),
              days = TestDayPeriod(days = Set(DayOfWeek.MONDAY, DayOfWeek.TUESDAY)),
              timezone = ZoneId.of("Europe/Paris")
            )
          ),
          TestCondition(
            rule = TestPercentageRule(percentage = 60)
          )
        )
      )
      await atMost (10, SECONDS) until {
        evts.exists(s => s.eventType.get == "FEATURE_CREATED")
      }

      val evt         = evts.findLast(e => e.eventType.get == "FEATURE_CREATED").get
      val jsonData    = Json.parse(evt.data)
      val jsonFeature = (jsonData \ "payload").get
      jsonFeature.toString mustEqual """{"name":"f3","active":false,"project":"project","conditions":{"":{"enabled":false,"conditions":[{"period":{"begin":"2020-01-01T01:00:00Z","end":"2120-01-01T01:00:00Z","hourPeriods":[{"startTime":"09:00:00","endTime":"12:00:00"},{"startTime":"14:00:00","endTime":"18:00:00"}],"activationDays":{"days":["MONDAY","TUESDAY"]},"timezone":"Europe/Paris"},"rule":{"users":["foo","bar"]}},{"period":null,"rule":{"percentage":60}}]}}}"""

    }

    "should send feature_created events when feature is created without condition if not required" in {
      val situation = TestSituationBuilder()
        .loggedInWithAdminRights()
        .withTenants(
          TestTenant("test")
            .withApiKeys(TestApiKey("mykey", enabled = true, admin = true))
            .withProjects(
              TestProject("project")
            )
        )
        .build()

      val evts = ArrayBuffer[ServerSentEvent]()
      situation.listenEvents(
        key = "mykey",
        features = Seq(),
        projects = Seq(situation.findProjectId("test", "project").get),
        consumer = evt => {
          evts.addOne(evt);
        },
        conditions = false
      )
      Thread.sleep(2000)

      situation.createFeature(
        "f3",
        project = "project",
        tenant = "test",
        enabled = false,
        conditions = Set(
          TestCondition(
            rule = TestUserListRule(users = Set("foo", "bar")),
            period = TestDateTimePeriod(
              begin = LocalDateTime.of(2020, 1, 1, 1, 0, 0),
              end = LocalDateTime.of(2120, 1, 1, 1, 0, 0),
              hourPeriods = Seq(
                TestHourPeriod(startTime = LocalTime.of(9, 0, 0), endTime = LocalTime.of(12, 0, 0)),
                TestHourPeriod(startTime = LocalTime.of(14, 0, 0), endTime = LocalTime.of(18, 0, 0))
              ),
              days = TestDayPeriod(days = Set(DayOfWeek.MONDAY, DayOfWeek.TUESDAY)),
              timezone = ZoneId.of("Europe/Paris")
            )
          ),
          TestCondition(
            rule = TestPercentageRule(percentage = 60)
          )
        )
      )
      await atMost (10, SECONDS) until {
        evts.exists(s => s.eventType.get == "FEATURE_CREATED")
      }

      val evt         = evts.findLast(e => e.eventType.get == "FEATURE_CREATED").get
      val jsonData    = Json.parse(evt.data)
      val jsonFeature = (jsonData \ "payload").get
      jsonFeature.toString mustEqual """{"name":"f3","active":false,"project":"project"}"""

    }

    "should send feature_updated event when feature is updated" in {
      val situation = TestSituationBuilder()
        .loggedInWithAdminRights()
        .withTenants(
          TestTenant("test")
            .withApiKeys(TestApiKey("mykey", enabled = true, admin = true))
            .withProjects(
              TestProject("project").withFeatures(TestFeature(name = "f1", enabled = true))
            )
        )
        .build()

      val evts = ArrayBuffer[ServerSentEvent]()
      situation.listenEvents(
        key = "mykey",
        features = Seq(),
        projects = Seq(situation.findProjectId("test", "project").get),
        consumer = evt => {
          evts.addOne(evt);
        },
        conditions = true
      )
      Thread.sleep(2000)

      val projectResult = situation.fetchProject(tenant = "test", projectId = "project")
      val feature       = (projectResult.json.get \ "features").get.as[JsArray].head.get.as[JsObject]
      val id            = (feature \ "id").as[String]

      situation.updateFeature(tenant = "test", id = id, json = feature ++ Json.obj("enabled" -> false))

      await atMost (10, SECONDS) until {
        evts.exists(s => s.eventType.get == "FEATURE_UPDATED")
      }

      val evt         = evts.findLast(e => e.eventType.get == "FEATURE_UPDATED").get
      val jsonData    = Json.parse(evt.data)
      val jsonFeature = (jsonData \ "payload" \ "conditions" \ "").get

      (jsonFeature \ "enabled").get.as[Boolean] mustBe false
    }

    "should send feature_updated event when an overload is defined" in {
      val situation = TestSituationBuilder()
        .loggedInWithAdminRights()
        .withTenants(
          TestTenant("test")
            .withGlobalContext(TestFeatureContext(name = "prod"))
            .withApiKeys(TestApiKey("mykey", enabled = true, admin = true))
            .withProjects(
              TestProject("project").withFeatures(TestFeature(name = "f1", enabled = true))
            )
        )
        .build()

      val evts = ArrayBuffer[ServerSentEvent]()
      situation.listenEvents(
        key = "mykey",
        features = Seq(),
        projects = Seq(situation.findProjectId("test", "project").get),
        consumer = evt => {
          evts.addOne(evt);
        },
        conditions = true
      )
      Thread.sleep(2000)

      situation.changeFeatureStrategyForContext(
        tenant = "test",
        project = "project",
        contextPath = "prod",
        feature = "f1",
        enabled = false
      )

      await atMost (10, SECONDS) until {
        evts.exists(s => s.eventType.get == "FEATURE_UPDATED")
      }

      val evt        = evts.findLast(e => e.eventType.get == "FEATURE_UPDATED").get
      val jsonData   = Json.parse(evt.data)
      val conditions = jsonData \ "payload" \ "conditions"

      (conditions \ "prod" \ "enabled").get.as[Boolean] mustBe false
      (conditions \ "" \ "enabled").get.as[Boolean] mustBe true
    }

    "should send feature_updated event when an overload is updated" in {
      val situation = TestSituationBuilder()
        .loggedInWithAdminRights()
        .withTenants(
          TestTenant("test")
            .withGlobalContext(TestFeatureContext(name = "prod"))
            .withApiKeys(TestApiKey("mykey", enabled = true, admin = true))
            .withProjects(
              TestProject("project").withFeatures(TestFeature(name = "f1", enabled = true))
            )
        )
        .build()

      val evts = ArrayBuffer[ServerSentEvent]()
      situation.listenEvents(
        key = "mykey",
        features = Seq(),
        projects = Seq(situation.findProjectId("test", "project").get),
        consumer = evt => {
          evts.addOne(evt);
        },
        conditions = true
      )
      Thread.sleep(2000)

      situation.changeFeatureStrategyForContext(
        tenant = "test",
        project = "project",
        contextPath = "prod",
        feature = "f1",
        enabled = false
      )

      situation.changeFeatureStrategyForContext(
        tenant = "test",
        project = "project",
        contextPath = "prod",
        feature = "f1",
        enabled = true
      )

      await atMost (10, SECONDS) until {
        evts.count(s => s.eventType.get == "FEATURE_UPDATED") == 2
      }

      val evt        = evts.findLast(e => e.eventType.get == "FEATURE_UPDATED").get
      val jsonData   = Json.parse(evt.data)
      val conditions = jsonData \ "payload" \ "conditions"

      (conditions \ "prod" \ "enabled").get.as[Boolean] mustBe true
      (conditions \ "" \ "enabled").get.as[Boolean] mustBe true
    }

    "should send feature_updated event when an overload is deleted" in {
      val situation = TestSituationBuilder()
        .loggedInWithAdminRights()
        .withTenants(
          TestTenant("test")
            .withGlobalContext(TestFeatureContext(name = "prod"))
            .withApiKeys(TestApiKey("mykey", enabled = true, admin = true))
            .withProjects(
              TestProject("project").withFeatures(TestFeature(name = "f1", enabled = true))
            )
        )
        .build()

      val evts = ArrayBuffer[ServerSentEvent]()
      situation.listenEvents(
        key = "mykey",
        features = Seq(),
        projects = Seq(situation.findProjectId("test", "project").get),
        consumer = evt => {
          evts.addOne(evt);
        },
        conditions = true
      )
      Thread.sleep(2000)

      situation.changeFeatureStrategyForContext(
        tenant = "test",
        project = "project",
        contextPath = "prod",
        feature = "f1",
        enabled = false
      )

      val resp = situation.deleteFeatureOverload(
        tenant = "test",
        project = "project",
        path = "prod",
        feature = "f1"
      )

      await atMost (10, SECONDS) until {
        evts.count(s => s.eventType.get == "FEATURE_UPDATED") == 2
      }

      val evt        = evts.findLast(e => e.eventType.get == "FEATURE_UPDATED").get
      val jsonData   = Json.parse(evt.data)
      val conditions = jsonData \ "payload" \ "conditions"

      (conditions \ "prod").toOption mustBe None
      (conditions \ "" \ "enabled").get.as[Boolean] mustBe true
    }

    "should send feature_deleted event when a feature is deleted" in {
      val situation = TestSituationBuilder()
        .loggedInWithAdminRights()
        .withTenants(
          TestTenant("test")
            .withGlobalContext(TestFeatureContext(name = "prod"))
            .withApiKeys(TestApiKey("mykey", enabled = true, admin = true))
            .withProjects(
              TestProject("project").withFeatures(TestFeature(name = "f1", enabled = true))
            )
        )
        .build()

      val evts = ArrayBuffer[ServerSentEvent]()
      situation.listenEvents(
        key = "mykey",
        features = Seq(),
        projects = Seq(situation.findProjectId("test", "project").get),
        consumer = evt => {
          evts.addOne(evt);
        },
        conditions = true
      )
      Thread.sleep(2000)

      val fid = situation.findFeatureId(tenant = "test", project = "project", feature = "f1").get

      situation.deleteFeature(
        tenant = "test",
        id = situation.findFeatureId(tenant = "test", project = "project", feature = "f1").get
      )

      await atMost (10, SECONDS) until {
        evts.exists(s => s.eventType.get == "FEATURE_DELETED")
      }

      val evt = evts.findLast(e => e.eventType.get == "FEATURE_DELETED")

      (Json.parse(evt.get.data) \ "payload").as[String] mustEqual fid

    }

    "should send feature_deleted events if project is deleted" in {
      val situation = TestSituationBuilder()
        .loggedInWithAdminRights()
        .withTenants(
          TestTenant("test")
            .withGlobalContext(TestFeatureContext(name = "prod"))
            .withApiKeys(TestApiKey("mykey", enabled = true, admin = true))
            .withProjects(
              TestProject("project").withFeatures(TestFeature(name = "f1", enabled = true))
            )
        )
        .build()

      val evts = ArrayBuffer[ServerSentEvent]()
      situation.listenEvents(
        key = "mykey",
        features = Seq(),
        projects = Seq(situation.findProjectId("test", "project").get),
        consumer = evt => {
          evts.addOne(evt);
        },
        conditions = true
      )
      Thread.sleep(2000)

      situation.deleteProject(project = "project", tenant = "test")
      await atMost (10, SECONDS) until {
        evts.exists(s => s.eventType.get == "FEATURE_DELETED")
      }

      val f1Id = situation.findFeatureId(tenant = "test", project = "project", feature = "f1").get
      val evt  = evts.findLast(s => s.eventType.get == "FEATURE_DELETED")
      (Json.parse(evt.get.data) \ "payload").as[String] mustEqual f1Id
    }
  }
}
