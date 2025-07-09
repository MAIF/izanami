package fr.maif.izanami.api

import fr.maif.izanami.api.BaseAPISpec.TestSituationBuilder
import BaseAPISpec._
import com.github.tomakehurst.wiremock.http.{HttpHeaders, Request}
import org.awaitility.Awaitility.await
import play.api.http.Status.{BAD_REQUEST, CREATED, FORBIDDEN, INTERNAL_SERVER_ERROR, NO_CONTENT, OK, UNAUTHORIZED}
import play.api.libs.json.{JsArray, JsObject, JsString, JsUndefined, JsValue, Json}

import java.util.UUID
import scala.collection.mutable
import scala.concurrent.duration.SECONDS

class WebhookAPISpec extends BaseAPISpec {
  "webhook POST endpoint" should {
    "prevent webhook creation one field is too long" in {
      val situation = TestSituationBuilder()
        .loggedInWithAdminRights()
        .withTenants(
          TestTenant("tenant")
            .withProjects(
              TestProject("project").withFeatures(
                TestFeature("f1", enabled = false)
              )
            )
        )
        .build()

      var response = situation.createWebhook(
        "tenant",
        TestWebhook(
          name = "abcdefghij" * 21,
          url = "http://localhost:9999",
          features = Set(situation.findFeatureId("tenant", "project", "f1").get)
        )
      )
      response.status mustBe BAD_REQUEST

      response = situation.createWebhook(
        "tenant",
        TestWebhook(
          name = "abcdefghij",
          description = "abcdefghij" * 51,
          url = "http://localhost:9999",
          features = Set(situation.findFeatureId("tenant", "project", "f1").get)
        )
      )
      response.status mustBe BAD_REQUEST

      response = situation.createWebhook(
        "tenant",
        TestWebhook(
          name = "abcdefghij",
          url = s"http://${"abcdefghij" * 204}:9000",
          features = Set(situation.findFeatureId("tenant", "project", "f1").get)
        )
      )
      response.status mustBe BAD_REQUEST

      val body = s"{\n${(1 to 100_000).map(idx => s""""active$idx": {{payload.active}}""").mkString(",\n")}\n}"
      response = situation.createWebhook(
        "tenant",
        TestWebhook(
          name = "abcdefghij",
          url = s"http://localhost:9000",
          features = Set(situation.findFeatureId("tenant", "project", "f1").get),
          bodyTemplate = Some(body)
        )
      )
      response.status mustBe BAD_REQUEST

      val headers = (1 to 10_000).map(idx => (s"foo$idx", "bar")).toMap
      response = situation.createWebhook(
        "tenant",
        TestWebhook(
          name = "abcdefghij",
          url = s"http://localhost:9000",
          features = Set(situation.findFeatureId("tenant", "project", "f1").get),
          headers = headers
        )
      )
      response.status mustBe BAD_REQUEST
    }

    "prevent webhook creation if template is not valid" in {
      val situation = TestSituationBuilder()
        .loggedInWithAdminRights()
        .withTenants(
          TestTenant("tenant")
            .withProjects(
              TestProject("project").withFeatures(
                TestFeature("f1", enabled = false)
              )
            )
        )
        .build()

      val response = situation.createWebhook(
        "tenant",
        TestWebhook(
          name = "my-hook",
          url = "http://localhost:9999",
          features = Set(situation.findFeatureId("tenant", "project", "f1").get),
          bodyTemplate = Some("""{"active": {{}""")
        )
      )

      response.status mustBe BAD_REQUEST
    }

    "prevent webhook creation if url is not valid" in {
      val situation = TestSituationBuilder()
        .loggedInWithAdminRights()
        .withTenants(
          TestTenant("tenant")
            .withProjects(
              TestProject("project").withFeatures(
                TestFeature("f1", enabled = false)
              )
            )
        )
        .build()

      val response = situation.createWebhook(
        "tenant",
        TestWebhook(
          name = "my-hook",
          url = "foobar",
          features = Set(situation.findFeatureId("tenant", "project", "f1").get)
        )
      )

      response.status mustBe BAD_REQUEST
    }

    "prevent webhook creation if both features and projects are missing for non global hook" in {
      val situation = TestSituationBuilder()
        .loggedInWithAdminRights()
        .withTenants(
          TestTenant("tenant")
            .withProjects(
              TestProject("project").withFeatures(
                TestFeature("f1", enabled = false)
              )
            )
        )
        .build()

      val response = situation.createWebhook(
        "tenant",
        TestWebhook(name = "my-hook", url = "http://localhost:3000")
      )

      response.status mustBe BAD_REQUEST
    }

    "prevent webhook creation if there are features or projects on global hook" in {
      val situation = TestSituationBuilder()
        .loggedInWithAdminRights()
        .withTenants(
          TestTenant("tenant")
            .withProjects(
              TestProject("project").withFeatures(
                TestFeature("f1", enabled = false)
              )
            )
        )
        .build()

      val response = situation.createWebhook(
        "tenant",
        TestWebhook(
          name = "my-hook",
          url = "http://localhost:3000",
          features = Set(situation.findFeatureId("tenant", "project", "f1").get),
          global = true
        )
      )

      response.status mustBe BAD_REQUEST
    }

    "allow to create webhook" in {
      val situation = TestSituationBuilder()
        .loggedInWithAdminRights()
        .withTenants(
          TestTenant("tenant")
            .withProjects(
              TestProject("project").withFeatures(
                TestFeature("f1", enabled = false)
              )
            )
        )
        .build()

      val response = situation.createWebhook(
        "tenant",
        TestWebhook(
          name = "my-hook",
          url = "http://localhost:3000",
          features = Set(situation.findFeatureId("tenant", "project", "f1").get)
        )
      )

      response.status mustBe CREATED
    }
  }

  "webhook GET endpoint" should {
    "allow to retrieve all webhooks for tenant if user has default read webhook rights" in {
      val situation = TestSituationBuilder()
        .withTenants(
          TestTenant("tenant")
            .withProjects(
              TestProject("project").withFeatures(
                TestFeature("f1", enabled = false)
              ),
              TestProject("project2").withFeatures(
                TestFeature("ff1", enabled = true)
              )
            )
            .withWebhooks(TestWebhookByName(
              name = "my-hook",
              url = "http://localhost:3000",
              features = Set(("tenant", "project", "f1"))
            ), TestWebhookByName(
              name = "my-hook2",
              url = "http://localhost:4000",
              projects = Set(("tenant", "project2"))
            ))
        ).withUsers(TestUser("testu").withTenantReadRight("tenant").withDefaultReadWebhookRight("tenant"))
        .loggedAs("testu")
        .build()

      val response = situation.listWebhook("tenant")
      response.status mustBe OK
      val json     = response.json.get.as[JsArray]
      json.value must have size 2

      (json \\ "name").map(js => js.as[String]).toSeq must contain allElementsOf Seq("my-hook", "my-hook2")
      (json \\ "url").map(js => js.as[String]).toSeq must contain allElementsOf Seq(
        "http://localhost:3000",
        "http://localhost:4000"
      )
    }

    "allow to retrieve all webhooks for tenant" in {
      val situation = TestSituationBuilder()
        .loggedInWithAdminRights()
        .withTenants(
          TestTenant("tenant")
            .withProjects(
              TestProject("project").withFeatures(
                TestFeature("f1", enabled = false)
              ),
              TestProject("project2").withFeatures(
                TestFeature("ff1", enabled = true)
              )
            )
        )
        .build()

      situation.createWebhook(
        "tenant",
        TestWebhook(
          name = "my-hook",
          url = "http://localhost:3000",
          features = Set(situation.findFeatureId("tenant", "project", "f1").get)
        )
      )

      situation.createWebhook(
        "tenant",
        TestWebhook(
          name = "my-hook2",
          url = "http://localhost:4000",
          projects = Set(situation.findProjectId("tenant", "project2").get)
        )
      )

      val response = situation.listWebhook("tenant")
      response.status mustBe OK
      val json     = response.json.get.as[JsArray]
      json.value must have size 2

      (json \\ "name").map(js => js.as[String]).toSeq must contain allElementsOf Seq("my-hook", "my-hook2")
      (json \\ "url").map(js => js.as[String]).toSeq must contain allElementsOf Seq(
        "http://localhost:3000",
        "http://localhost:4000"
      )
    }

    "Retrieve only webhook on which user has rights" in {
      val situation = TestSituationBuilder()
        .loggedInWithAdminRights()
        .withTenants(
          TestTenant("tenant")
            .withProjects(
              TestProject("project").withFeatures(
                TestFeature("f1", enabled = false)
              ),
              TestProject("project2").withFeatures(
                TestFeature("ff1", enabled = true)
              )
            )
        )
        .withUsers(
          TestUser(
            "testu",
            password = "testutestu",
            admin = false,
            rights = TestRights(Map("tenant" -> TestTenantRight(name = "tenant", level = "Write")))
          )
        )
        .build()

      var response = situation.createWebhook(
        "tenant",
        TestWebhook(
          name = "my-hook",
          url = "http://localhost:3000",
          features = Set(situation.findFeatureId("tenant", "project", "f1").get)
        )
      )
      response.status mustBe CREATED

      situation.logout()
      val newSituation = situation.loggedAs("testu", "testutestu")

      response = newSituation.createWebhook(
        "tenant",
        TestWebhook(
          name = "my-hook2",
          url = "http://localhost:3000",
          features = Set(newSituation.findFeatureId("tenant", "project2", "ff1").get)
        )
      )
      response.status mustBe CREATED

      response = newSituation.listWebhook("tenant")
      response.status mustBe OK
      val json = response.json.get.as[JsArray]
      json.value must have size 1

      (json \\ "name").map(js => js.as[String]).toSeq must contain oneElementOf Seq("my-hook2")
      (json \\ "url").map(js => js.as[String]).toSeq must contain allElementsOf Seq("http://localhost:3000")
    }
  }

  "Webhook DELETE endpoint" should {
    "delete webhook" in {
      val situation = TestSituationBuilder()
        .withTenants(
          TestTenant("tenant")
            .withProjects(
              TestProject("project").withFeatures(
                TestFeature("f1", enabled = false)
              )
            )
        )
        .withUsers(TestUser(username = "admin", admin = true, password = "barfoofoo"))
        .loggedAs("admin")
        .build()

      val response = situation.createWebhook(
        "tenant",
        TestWebhook(
          name = "my-hook",
          url = "http://localhost:3000",
          features = Set(situation.findFeatureId("tenant", "project", "f1").get)
        )
      )

      val id = (response.json.get \ "id").as[String]

      val deleteResponse = situation.deleteWebhook("tenant", id)
      deleteResponse.status mustBe NO_CONTENT

      situation.listWebhook("tenant").json.get.as[JsArray].value mustBe empty
    }

    "prevent deleting webhook if user has not enough rights" in {
      val situation = TestSituationBuilder()
        .loggedInWithAdminRights()
        .withTenants(
          TestTenant("tenant")
            .withProjects(
              TestProject("project").withFeatures(
                TestFeature("f1", enabled = false)
              )
            )
        )
        .withUsers(
          TestUser(
            "testuser",
            password = "foofoo123",
            rights = TestRights(Map("tenant" -> TestTenantRight(name = "tenant", level = "Write")))
          )
        )
        .build()

      val response = situation.createWebhook(
        "tenant",
        TestWebhook(
          name = "my-hook",
          url = "http://localhost:3000",
          features = Set(situation.findFeatureId("tenant", "project", "f1").get)
        )
      )

      val id = (response.json.get \ "id").as[String]

      situation.logout()
      val newSituation = situation.loggedAs("testu", "foofoo123")

      val deleteResponse = newSituation.deleteWebhook("tenant", id)

      deleteResponse.status mustBe UNAUTHORIZED
    }
  }

  "webhook call" should {

    def awaitRequests(port: Int, eventType: String, count: Int = 1): mutable.Seq[(Request, HttpHeaders)] = {
      val requests = getWebhookServerRequests(port)
      await atMost (10, SECONDS) until {
        requests.count { case (request, _) =>
          request.getBodyAsString.contains(eventType)
        } >= count
      }
      requests
    }

    "trigger when a targeted feature is updated" in {
      val tenant    = s"tenant${UUID.randomUUID().toString.replace("-", "")}"
      val situation = TestSituationBuilder()
        .loggedInWithAdminRights()
        .withTenants(
          TestTenant(tenant)
            .withProjects(
              TestProject("project").withFeatures(
                TestFeature("f1", enabled = false)
              )
            )
            .withWebhooks(
              TestWebhookByName(
                name = "test-hook",
                url = "http://localhost:8087/",
                features = Set((tenant, "project", "f1"))
              )
            )
        )
        .withWebhookServer(port = 8087, responseCode = OK)
        .build()

      situation.updateFeatureByName(tenant, "project", "f1", f => f ++ Json.obj("enabled" -> true))

      val requests = awaitRequests(8087, "FEATURE_UPDATED")

      requests.toSeq.exists { case (r, _) =>
        r.getBodyAsString.contains("FEATURE_UPDATED")
      } mustBe true

      Thread.sleep(1000)
    }

    "trigger when a targeted feature name is updated" in {
      val tenant    = s"tenant${UUID.randomUUID().toString.replace("-", "")}"
      val situation = TestSituationBuilder()
        .loggedInWithAdminRights()
        .withTenants(
          TestTenant(tenant)
            .withProjects(
              TestProject("project").withFeatures(
                TestFeature("f1", enabled = false)
              )
            )
            .withWebhooks(
              TestWebhookByName(
                name = "test-hook",
                url = "http://localhost:8087/",
                features = Set((tenant, "project", "f1"))
              )
            )
        )
        .withWebhookServer(port = 8087, responseCode = OK)
        .build()

      situation.updateFeatureByName(tenant, "project", "f1", f => f ++ Json.obj("name" -> "f2"))

      val requests = awaitRequests(8087, "FEATURE_UPDATED")

      requests.toSeq.exists { case (r, _) =>
        r.getBodyAsString.contains("FEATURE_UPDATED")
      } mustBe true

      Thread.sleep(1000)
    }

    "triggers if feature overload is created" in {
      val tenant    = s"tenant${UUID.randomUUID().toString.replace("-", "")}"
      val situation = TestSituationBuilder()
        .loggedInWithAdminRights()
        .withTenants(
          TestTenant(tenant)
            .withProjects(
              TestProject("project").withFeatures(
                TestFeature("f1", enabled = false)
              )
            )
            .withWebhooks(
              TestWebhookByName(
                name = "test-hook",
                url = "http://localhost:8091/",
                features = Set((tenant, "project", "f1"))
              )
            )
            .withGlobalContext(TestFeatureContext("prod"))
        )
        .withWebhookServer(port = 8091, responseCode = OK)
        .build()

      val response = situation.changeFeatureStrategyForContext(
        tenant = tenant,
        project = "project",
        contextPath = "prod",
        enabled = true,
        feature = "f1"
      )

      response.status mustEqual NO_CONTENT

      val requests = awaitRequests(8091, "FEATURE_UPDATED")

      requests.toSeq.exists { case (r, _) =>
        r.getBodyAsString.contains("FEATURE_UPDATED")
      } mustBe true

      Thread.sleep(1000)
    }

    "triggers if feature overload is updated" in {
      val tenant    = s"tenant${UUID.randomUUID().toString.replace("-", "")}"
      val situation = TestSituationBuilder()
        .loggedInWithAdminRights()
        .withTenants(
          TestTenant(tenant)
            .withProjects(
              TestProject("project").withFeatures(
                TestFeature("f1", enabled = false)
              )
            )
            .withWebhooks(
              TestWebhookByName(
                name = "test-hook",
                url = "http://localhost:8092/",
                features = Set((tenant, "project", "f1"))
              )
            )
            .withGlobalContext(TestFeatureContext("prod"))
        )
        .withWebhookServer(port = 8092, responseCode = OK)
        .build()

      val response = situation.changeFeatureStrategyForContext(
        tenant = tenant,
        project = "project",
        contextPath = "prod",
        enabled = false,
        feature = "f1"
      )

      val response2 = situation.changeFeatureStrategyForContext(
        tenant = tenant,
        project = "project",
        contextPath = "prod",
        enabled = true,
        feature = "f1"
      )

      response.status mustEqual NO_CONTENT
      response2.status mustEqual NO_CONTENT

      val requests = awaitRequests(8092, "FEATURE_UPDATED", 2)

      requests.toSeq.exists { case (r, _) =>
        r.getBodyAsString.contains("FEATURE_UPDATED")
      } mustBe true

      Thread.sleep(1000)
    }

    "triggers if feature overload is deleted" in {
      val tenant    = s"tenant${UUID.randomUUID().toString.replace("-", "")}"
      val situation = TestSituationBuilder()
        .loggedInWithAdminRights()
        .withTenants(
          TestTenant(tenant)
            .withProjects(
              TestProject("project").withFeatures(
                TestFeature("f1", enabled = false)
              )
            )
            .withWebhooks(
              TestWebhookByName(
                name = "test-hook",
                url = "http://localhost:8093/",
                features = Set((tenant, "project", "f1"))
              )
            )
            .withGlobalContext(TestFeatureContext("prod"))
        )
        .withWebhookServer(port = 8093, responseCode = OK)
        .build()

      val response = situation.changeFeatureStrategyForContext(
        tenant = tenant,
        project = "project",
        contextPath = "prod",
        enabled = true,
        feature = "f1"
      )

      val deleteResponse =
        situation.deleteFeatureOverload(tenant = tenant, project = "project", path = "prod", feature = "f1")

      response.status mustEqual NO_CONTENT
      deleteResponse.status mustEqual NO_CONTENT

      val requests = awaitRequests(8093, "FEATURE_UPDATED", 2)

      requests.toSeq.exists { case (r, _) =>
        r.getBodyAsString.contains("FEATURE_UPDATED")
      } mustBe true

      Thread.sleep(1000)
    }

    "trigger when a feature is updated is hook is global" in {
      val tenant    = s"tenant${UUID.randomUUID().toString.replace("-", "")}"
      val situation = TestSituationBuilder()
        .loggedInWithAdminRights()
        .withTenants(
          TestTenant(tenant)
            .withProjects(
              TestProject("project").withFeatures(
                TestFeature("f1", enabled = false)
              )
            )
            .withWebhooks(
              TestWebhookByName(
                name = "test-hook",
                url = "http://localhost:8087/",
                global = true
              )
            )
        )
        .withWebhookServer(port = 8087, responseCode = OK)
        .build()

      situation.updateFeatureByName(tenant, "project", "f1", f => f ++ Json.obj("enabled" -> true))

      val requests = awaitRequests(8087, "FEATURE_UPDATED")

      requests.toSeq.exists { case (r, _) =>
        r.getBodyAsString.contains("FEATURE_UPDATED")
      } mustBe true

      Thread.sleep(1000)
    }

    "trigger when a feature from targeted project is updated" in {
      val tenant    = s"tenant${UUID.randomUUID().toString.replace("-", "")}"
      val situation = TestSituationBuilder()
        .loggedInWithAdminRights()
        .withTenants(
          TestTenant(tenant)
            .withProjects(
              TestProject("project").withFeatures(
                TestFeature("f1", enabled = false)
              )
            )
            .withWebhooks(
              TestWebhookByName(name = "test-hook", url = "http://localhost:9996", projects = Set((tenant, "project")))
            )
        )
        .withWebhookServer(port = 9996, responseCode = OK)
        .build()

      situation.updateFeatureByName(tenant, "project", "f1", f => f ++ Json.obj("enabled" -> true))

      val requests = awaitRequests(9996, "FEATURE_UPDATED")

      requests.toSeq.exists { case (r, _) =>
        r.getBodyAsString.contains("FEATURE_UPDATED")
      } mustBe true
    }

    "call all concerned webhooks when feature is updated" in {
      val tenant    = s"tenant${UUID.randomUUID().toString.replace("-", "")}"
      val situation = TestSituationBuilder()
        .loggedInWithAdminRights()
        .withTenants(
          TestTenant(tenant)
            .withProjects(
              TestProject("project").withFeatures(
                TestFeature("f1", enabled = false)
              )
            )
            .withWebhooks(
              TestWebhookByName(name = "test-hook", url = "http://localhost:9996", projects = Set((tenant, "project")))
            )
            .withWebhooks(
              TestWebhookByName(
                name = "test-hook2",
                url = "http://localhost:9995",
                features = Set((tenant, "project", "f1"))
              )
            )
        )
        .withWebhookServer(port = 9996, responseCode = OK)
        .withWebhookServer(port = 9995, responseCode = OK)
        .build()

      situation.updateFeatureByName(tenant, "project", "f1", f => f ++ Json.obj("enabled" -> true))

      val requests1 = awaitRequests(9996, "FEATURE_UPDATED")
      val requests2 = awaitRequests(9995, "FEATURE_UPDATED")

      requests1.toSeq.exists { case (r, _) =>
        r.getBodyAsString.contains("FEATURE_UPDATED")
      } mustBe true

      requests2.toSeq.exists { case (r, _) =>
        r.getBodyAsString.contains("FEATURE_UPDATED")
      } mustBe true
    }

    "recompute activation for user" in {
      val tenant    = s"tenant${UUID.randomUUID().toString.replace("-", "")}"
      val situation = TestSituationBuilder()
        .loggedInWithAdminRights()
        .withTenants(
          TestTenant(tenant)
            .withProjects(
              TestProject("project").withFeatures(
                TestFeature("f1", enabled = false, conditions = Set(TestCondition(rule = TestUserListRule(Set("foo")))))
              )
            )
            .withWebhooks(
              TestWebhookByName(name = "test-hook", url = "http://localhost:9996", projects = Set((tenant, "project")))
            )
            .withWebhooks(
              TestWebhookByName(
                name = "test-hook2",
                url = "http://localhost:9995",
                projects = Set((tenant, "project")),
                user = "foo"
              )
            )
        )
        .withWebhookServer(port = 9996, responseCode = OK)
        .withWebhookServer(port = 9995, responseCode = OK)
        .build()

      situation.updateFeatureByName(tenant, "project", "f1", f => f ++ Json.obj("enabled" -> true))

      val requests = awaitRequests(9996, "FEATURE_UPDATED")

      val request = requests.toSeq.find { case (r, _) =>
        r.getBodyAsString.contains("FEATURE_UPDATED")
      }

      val requests2 = awaitRequests(9995, "FEATURE_UPDATED")

      val request2 = requests2.toSeq.find { case (r, _) =>
        r.getBodyAsString.contains("FEATURE_UPDATED")
      }

      (Json.parse(request.get._1.getBodyAsString) \ "payload" \ "active").as[Boolean] mustBe false
      (Json.parse(request2.get._1.getBodyAsString) \ "payload" \ "active").as[Boolean] mustBe true
    }

    "recompute activation for context" in {
      val tenant    = s"tenant${UUID.randomUUID().toString.replace("-", "")}"
      val situation = TestSituationBuilder()
        .loggedInWithAdminRights()
        .withTenants(
          TestTenant(tenant)
            .withProjects(
              TestProject("project").withFeatures(
                TestFeature("f1", enabled = false, conditions = Set(TestCondition(rule = TestUserListRule(Set("foo")))))
              )
            )
            .withWebhooks(
              TestWebhookByName(name = "test-hook", url = "http://localhost:9996", projects = Set((tenant, "project")))
            )
            .withWebhooks(
              TestWebhookByName(
                name = "test-hook2",
                url = "http://localhost:9995",
                projects = Set((tenant, "project")),
                context = "foo"
              )
            )
            .withGlobalContext(TestFeatureContext("foo"))
        )
        .withWebhookServer(port = 9996, responseCode = OK)
        .withWebhookServer(port = 9995, responseCode = OK)
        .build()

      val response = situation.changeFeatureStrategyForContext(
        tenant = tenant,
        project = "project",
        contextPath = "foo",
        feature = "f1",
        enabled = true
      )

      val requests = awaitRequests(9996, "FEATURE_UPDATED")
      val request  = requests.toSeq.find { case (r, _) =>
        r.getBodyAsString.contains("FEATURE_UPDATED")
      }

      val requests2 = awaitRequests(9995, "FEATURE_UPDATED")
      val request2  = requests2.toSeq.find { case (r, _) =>
        r.getBodyAsString.contains("FEATURE_UPDATED")
      }

      (Json.parse(request.get._1.getBodyAsString) \ "payload" \ "active").as[Boolean] mustBe false
      (Json.parse(request2.get._1.getBodyAsString) \ "payload" \ "active").as[Boolean] mustBe true
    }

    "pass authoring user" in {
      val tenant    = s"tenant${UUID.randomUUID().toString.replace("-", "")}"
      val situation = TestSituationBuilder()
        .loggedInWithAdminRights()
        .withTenants(
          TestTenant(tenant)
            .withProjects(
              TestProject("project").withFeatures(
                TestFeature("f1", enabled = false)
              )
            )
            .withWebhooks(
              TestWebhookByName(
                name = "test-hook",
                url = "http://localhost:9996",
                features = Set((tenant, "project", "f1"))
              )
            )
        )
        .withWebhookServer(port = 9996, responseCode = OK)
        .build()

      situation.updateFeatureByName(tenant, "project", "f1", f => f ++ Json.obj("enabled" -> true))

      val requests = awaitRequests(9996, "FEATURE_UPDATED")

      val request = requests.toSeq.find { case (r, _) =>
        r.getBodyAsString.contains("FEATURE_UPDATED")
      }.get
      val body    = Json.parse(request._1.getBodyAsString)
      (body \ "metadata" \ "user").as[String] mustEqual "RESERVED_ADMIN_USER"
    }

    "pass given headers correctly" in {
      val tenant    = s"tenant${UUID.randomUUID().toString.replace("-", "")}"
      val situation = TestSituationBuilder()
        .loggedInWithAdminRights()
        .withTenants(
          TestTenant(tenant)
            .withProjects(
              TestProject("project").withFeatures(
                TestFeature("f1", enabled = false)
              )
            )
            .withWebhooks(
              TestWebhookByName(
                name = "test-hook",
                url = "http://localhost:9996",
                features = Set((tenant, "project", "f1")),
                headers = Map("x-foo" -> "bar", "authorization" -> "my-token")
              )
            )
        )
        .withWebhookServer(port = 9996, responseCode = OK)
        .build()

      situation.updateFeatureByName(tenant, "project", "f1", f => f ++ Json.obj("enabled" -> true))

      val requests = awaitRequests(9996, "FEATURE_UPDATED")

      val request = requests.toSeq.find { case (r, _) =>
        r.getBodyAsString.contains("FEATURE_UPDATED")
      }.get
      val headers = request._2
      headers.getHeader("x-foo").values().get(0) mustEqual "bar"
      headers.getHeader("authorization").values().get(0) mustEqual "my-token"

    }

    "pass activation conditions" in {
      val tenant    = s"tenant${UUID.randomUUID().toString.replace("-", "")}"
      val situation = TestSituationBuilder()
        .loggedInWithAdminRights()
        .withTenants(
          TestTenant(tenant)
            .withProjects(
              TestProject("project").withFeatures(
                TestFeature("f1", enabled = false, conditions = Set(TestCondition(rule = TestUserListRule(Set("foo")))))
              )
            )
            .withWebhooks(
              TestWebhookByName(
                name = "test-hook",
                url = "http://localhost:9996",
                features = Set((tenant, "project", "f1")),
                headers = Map("x-foo" -> "bar", "authorization" -> "my-token")
              )
            )
        )
        .withWebhookServer(port = 9996, responseCode = OK)
        .build()

      situation.updateFeatureByName(tenant, "project", "f1", f => f ++ Json.obj("enabled" -> true))

      val requests = awaitRequests(9996, "FEATURE_UPDATED")

      val request = requests.toSeq.find { case (r, _) =>
        r.getBodyAsString.contains("FEATURE_UPDATED")
      }.get

      val body = Json.parse(request._1.getBodyAsString)
      val user = body \ "payload" \ "conditions" \ "" \ "conditions" \ 0 \ "rule" \ "users" \ 0
      user.as[String] mustEqual "foo"
    }

    "retry if first call failed" in {
      val tenant    = s"""tenant${UUID.randomUUID().toString.replace("-", "")}"""
      val situation = TestSituationBuilder()
        .loggedInWithAdminRights()
        .withTenants(
          TestTenant(tenant)
            .withProjects(
              TestProject("project").withFeatures(
                TestFeature("f1", enabled = false, conditions = Set(TestCondition(rule = TestUserListRule(Set("foo")))))
              )
            )
            .withWebhooks(
              TestWebhookByName(
                name = "test-hook",
                url = "http://localhost:9988",
                features = Set((tenant, "project", "f1"))
              )
            )
        )
        .withCustomConfiguration(
          Map(
            "app.webhooks.retry.intial-delay" -> java.lang.Integer.valueOf(1),
            "app.webhooks.retry.multiplier"   -> java.lang.Integer.valueOf(1),
            "app.webhooks.retry.check-interval"   -> java.lang.Integer.valueOf(1)
          )
        )
        .withWebhookServer(port = 9988, responseCode = INTERNAL_SERVER_ERROR)
        .build()

      situation.updateFeatureByName(tenant = tenant, "project", "f1", f => f ++ Json.obj("enabled" -> true))

      await atMost (20, SECONDS) until {
        getWebhookServerRequests(9988).count { case (req, _) =>
          req.getBodyAsString.contains("FEATURE_UPDATED")
        } >= 2
      }
    }

    "Stop retrying after sucess" in {
      val tenant    = s"""tenant${UUID.randomUUID().toString.replace("-", "")}"""
      val situation = TestSituationBuilder()
        .loggedInWithAdminRights()
        .withTenants(
          TestTenant(tenant)
            .withProjects(
              TestProject("project").withFeatures(
                TestFeature("f1", enabled = false, conditions = Set(TestCondition(rule = TestUserListRule(Set("foo")))))
              )
            )
            .withWebhooks(
              TestWebhookByName(
                name = "test-hook",
                url = "http://localhost:9996",
                features = Set((tenant, "project", "f1"))
              )
            )
        )
        .withCustomConfiguration(
          Map(
            "app.webhooks.retry.intial-delay" -> java.lang.Integer.valueOf(1),
            "app.webhooks.retry.multiplier"   -> java.lang.Integer.valueOf(1),
            "app.webhooks.retry.check-interval"   -> java.lang.Integer.valueOf(1)
          )
        )
        .withWebhookServer(port = 9996, responseCode = INTERNAL_SERVER_ERROR)
        .build()

      situation.updateFeatureByName(tenant = tenant, "project", "f1", f => f ++ Json.obj("enabled" -> true))

      Thread.sleep(500)

      BaseAPISpec.setupWebhookServer(9996, OK)

      await atMost (20, SECONDS) until {
        getWebhookServerRequests(9996).count { case (req, _) =>
          req.getBodyAsString.contains("FEATURE_UPDATED")
        } == 2
      }
    }

    "Respect given template" in {
      val tenant    = s"""tenant${UUID.randomUUID().toString.replace("-", "")}"""
      val situation = TestSituationBuilder()
        .loggedInWithAdminRights()
        .withTenants(
          TestTenant(tenant)
            .withProjects(
              TestProject("project").withFeatures(
                TestFeature("f1", enabled = false)
              )
            )
            .withWebhooks(
              TestWebhookByName(
                name = "test-hook",
                url = "http://localhost:9994",
                features = Set((tenant, "project", "f1")),
                bodyTemplate = Some(s"""{"type": "{{type}}", "active": {{payload.active}} }""")
              )
            )
        )
        .withWebhookServer(port = 9994, responseCode = OK)
        .build()

      situation.updateFeatureByName(tenant, "project", "f1", f => f ++ Json.obj("enabled" -> true))

      val requests = awaitRequests(9994, "FEATURE_UPDATED")
      val body     = requests.map(r => r._1.getBodyAsString).filter(str => str.contains("FEATURE_UPDATED")).head

      body mustEqual s"""{"type": "FEATURE_UPDATED", "active": true }"""
    }
  }

  "webhook PUT endpoint" should {
    "prevent to update webhook if one field is too long" in {
      val situation = TestSituationBuilder()
        .loggedInWithAdminRights()
        .withTenants(
          TestTenant("tenant")
            .withProjects(
              TestProject("project").withFeatures(
                TestFeature("f1", enabled = false),
                TestFeature("f2", enabled = false)
              ),
              TestProject("project2")
            )
            .withWebhooks(
              TestWebhookByName(
                name = "my-hook",
                url = "http://localhost:3000",
                features = Set(("tenant", "project", "f1"))
              )
            )
        )
        .build()

      val webhookId = situation.findWebhookId(tenant = "tenant", webhook = "my-hook").get

      var response = situation.updateWebhook(
        "tenant",
        id = webhookId,
        transformer = json => {
          val result: JsObject = json +
            ("name"        -> Json.toJson("abcdefghij" * 21)) +
            ("features"    -> JsArray(Seq(JsString(situation.findFeatureId("tenant", "project", "f2").get)))) +
            ("projects"    -> JsArray(Seq(JsString(situation.findProjectId("tenant", "project2").get)))) +
            ("headers"     -> Json.obj("foo" -> "bar")) +
            ("url"         -> Json.toJson("http://foo.bar")) +
            ("description" -> Json.toJson("My new description")) +
            ("context"     -> Json.toJson("My new context")) +
            ("user"        -> Json.toJson("My new user"))
          result
        }
      )
      response.status mustBe BAD_REQUEST

      response = situation.updateWebhook(
        "tenant",
        id = webhookId,
        transformer = json => {
          val result: JsObject = json +
            ("name"        -> Json.toJson("abcdefghij")) +
            ("features"    -> JsArray(Seq(JsString(situation.findFeatureId("tenant", "project", "f2").get)))) +
            ("projects"    -> JsArray(Seq(JsString(situation.findProjectId("tenant", "project2").get)))) +
            ("headers"     -> Json.obj("foo" -> "bar")) +
            ("url"         -> Json.toJson("http://foo.bar")) +
            ("description" -> Json.toJson("abcdefghij" * 51)) +
            ("context"     -> Json.toJson("My new context")) +
            ("user"        -> Json.toJson("My new user"))
          result
        }
      )
      response.status mustBe BAD_REQUEST

      response = situation.updateWebhook(
        "tenant",
        id = webhookId,
        transformer = json => {
          val result: JsObject = json +
            ("name"        -> Json.toJson("abcdefghij")) +
            ("features"    -> JsArray(Seq(JsString(situation.findFeatureId("tenant", "project", "f2").get)))) +
            ("projects"    -> JsArray(Seq(JsString(situation.findProjectId("tenant", "project2").get)))) +
            ("headers"     -> Json.obj("foo" -> "bar")) +
            ("url"         -> Json.toJson(s"http://${"abcdefghij" * 204}:9000")) +
            ("description" -> Json.toJson("abcdefghij")) +
            ("context"     -> Json.toJson("My new context")) +
            ("user"        -> Json.toJson("My new user"))
          result
        }
      )
      response.status mustBe BAD_REQUEST

      val body = s"{\n${(1 to 100_000).map(idx => s""""active$idx": {{payload.active}}""").mkString(",\n")}\n}"
      response = situation.updateWebhook(
        "tenant",
        id = webhookId,
        transformer = json => {
          val result: JsObject = json +
            ("name"         -> Json.toJson("abcdefghij")) +
            ("features"     -> JsArray(Seq(JsString(situation.findFeatureId("tenant", "project", "f2").get)))) +
            ("projects"     -> JsArray(Seq(JsString(situation.findProjectId("tenant", "project2").get)))) +
            ("headers"      -> Json.obj("foo" -> "bar")) +
            ("url"          -> Json.toJson(s"http://localhost:9000")) +
            ("description"  -> Json.toJson("abcdefghij")) +
            ("context"      -> Json.toJson("My new context")) +
            ("bodyTemplate" -> Json.toJson(body)) +
            ("user"         -> Json.toJson("My new user"))
          result
        }
      )
      response.status mustBe BAD_REQUEST

      val headers = (1 to 10_000).map(idx => (s"foo$idx", "bar")).toMap
      response = situation.updateWebhook(
        "tenant",
        id = webhookId,
        transformer = json => {
          val result: JsObject = json +
            ("name"        -> Json.toJson("abcdefghij")) +
            ("features"    -> JsArray(Seq(JsString(situation.findFeatureId("tenant", "project", "f2").get)))) +
            ("projects"    -> JsArray(Seq(JsString(situation.findProjectId("tenant", "project2").get)))) +
            ("headers"     -> Json.toJson(headers)) +
            ("url"         -> Json.toJson(s"http://localhost:9000")) +
            ("description" -> Json.toJson("abcdefghij")) +
            ("context"     -> Json.toJson("My new context")) +
            ("user"        -> Json.toJson("My new user"))
          result
        }
      )
      response.status mustBe BAD_REQUEST
    }

    "allow to update webhook" in {
      val situation = TestSituationBuilder()
        .loggedInWithAdminRights()
        .withTenants(
          TestTenant("tenant")
            .withProjects(
              TestProject("project").withFeatures(
                TestFeature("f1", enabled = false),
                TestFeature("f2", enabled = false)
              ),
              TestProject("project2")
            )
            .withWebhooks(
              TestWebhookByName(
                name = "my-hook",
                url = "http://localhost:3000",
                features = Set(("tenant", "project", "f1"))
              )
            )
        )
        .build()

      val webhookId = situation.findWebhookId(tenant = "tenant", webhook = "my-hook").get

      val response = situation.updateWebhook(
        "tenant",
        id = webhookId,
        transformer = json => {
          val result: JsObject = json +
            ("name"        -> Json.toJson("my-super-hook")) +
            ("features"    -> JsArray(Seq(JsString(situation.findFeatureId("tenant", "project", "f2").get)))) +
            ("projects"    -> JsArray(Seq(JsString(situation.findProjectId("tenant", "project2").get)))) +
            ("headers"     -> Json.obj("foo" -> "bar")) +
            ("url"         -> Json.toJson("http://foo.bar")) +
            ("description" -> Json.toJson("My new description")) +
            ("context"     -> Json.toJson("My new context")) +
            ("user"        -> Json.toJson("My new user"))
          result
        }
      )

      response.status mustBe NO_CONTENT

      val hooks = situation.listWebhook("tenant").json.get.as[JsArray].value
      hooks must have size 1
      val head  = hooks.head
      (head \ "name").as[String] mustEqual "my-super-hook"
      (head \ "url").as[String] mustEqual "http://foo.bar"
      (head \ "description").as[String] mustEqual "My new description"
      (head \ "headers").as[JsObject] mustEqual Json.obj("foo" -> "bar")
      (head \ "features" \\ "id").map(v => v.as[String]) must contain theSameElementsAs Seq(
        situation.findFeatureId("tenant", "project", "f2").get
      )
      (head \ "projects" \\ "id").map(v => v.as[String]) must contain theSameElementsAs Seq(
        situation.findProjectId("tenant", "project2").get
      )
    }

    "prevent webhook update if user does not have write right" in {
      val situation = TestSituationBuilder()
        .loggedInWithAdminRights()
        .withTenants(
          TestTenant("tenant")
            .withProjects(
              TestProject("project").withFeatures(
                TestFeature("f1", enabled = false),
                TestFeature("f2", enabled = false)
              ),
              TestProject("project2")
            )
            .withWebhooks(
              TestWebhookByName(
                name = "my-hook",
                url = "http://localhost:3000",
                features = Set(("tenant", "project", "f1"))
              )
            )
        )
        .withUsers(
          TestUser(
            "testu",
            rights = TestRights(Map("tenant" -> TestTenantRight("tenant").addWebhookRight("my-hook", "Read")))
          )
        )
        .loggedAs("testu")
        .build()

      val webhookId = situation.findWebhookId(tenant = "tenant", webhook = "my-hook").get

      val response = situation.updateWebhook(
        "tenant",
        id = webhookId,
        transformer = json => {
          val result: JsObject = json +
            ("name" -> Json.toJson("my-super-hook"))
          result
        }
      )

      response.status mustBe FORBIDDEN
    }

    "prevent webhook update if there is no features or projects for non global hooks" in {
      val situation = TestSituationBuilder()
        .loggedInWithAdminRights()
        .withTenants(
          TestTenant("tenant")
            .withProjects(
              TestProject("project").withFeatures(
                TestFeature("f1", enabled = false),
                TestFeature("f2", enabled = false)
              ),
              TestProject("project2")
            )
            .withWebhooks(
              TestWebhookByName(
                name = "my-hook",
                url = "http://localhost:3000",
                features = Set(("tenant", "project", "f1"))
              )
            )
        )
        .build()

      val webhookId = situation.findWebhookId(tenant = "tenant", webhook = "my-hook").get

      val response = situation.updateWebhook(
        "tenant",
        id = webhookId,
        transformer = json => {
          val result: JsObject = json +
            ("name"        -> Json.toJson("my-super-hook")) +
            ("features"    -> JsArray()) +
            ("projects"    -> JsArray()) +
            ("headers"     -> Json.obj("foo" -> "bar")) +
            ("url"         -> Json.toJson("http://foo.bar")) +
            ("description" -> Json.toJson("My new description")) +
            ("context"     -> Json.toJson("My new context")) +
            ("user"        -> Json.toJson("My new user"))
          result
        }
      )

      response.status mustBe BAD_REQUEST
    }

    "prevent webhook update if there is features or projects for global hooks" in {}
  }

  "webhook right endpoint" should {
    "return accredited users with correct right levels" in {
      val situation = TestSituationBuilder()
        .loggedInWithAdminRights()
        .withTenants(
          TestTenant("tenant")
            .withProjects(
              TestProject("project").withFeatures(
                TestFeature("f1", enabled = false)
              )
            )
            .withWebhooks(
              TestWebhookByName(
                name = "my-hook",
                url = "http://localhost:3000",
                features = Set(("tenant", "project", "f1"))
              )
            )
        )
        .withUsers(
          TestUser(
            "testuHookRead",
            rights = TestRights(Map("tenant" -> TestTenantRight("tenant").addWebhookRight("my-hook", "Read")))
          )
        )
        .withUsers(
          TestUser(
            "testuHookWrite",
            rights = TestRights(Map("tenant" -> TestTenantRight("tenant").addWebhookRight("my-hook", "Write")))
          )
        )
        .withUsers(
          TestUser(
            "testuHookAdmin",
            rights = TestRights(Map("tenant" -> TestTenantRight("tenant").addWebhookRight("my-hook", "Admin")))
          )
        )
        .withUsers(
          TestUser("testuTenantAdmin", rights = TestRights(Map("tenant" -> TestTenantRight("tenant", level = "Admin"))))
        )
        .withUsers(TestUser("testuAdmin", admin = true))
        .build()

      val response = situation.fetchWebhookUser(
        "tenant",
        webhook = situation.findWebhookId(tenant = "tenant", webhook = "my-hook").get
      )

      response.status mustBe OK

      val array = response.json.get.as[JsArray].value
      array must have length 6

      def findUser(username: String): JsObject =
        array.find(js => (js \ "username").as[String] == username).get.as[JsObject]

      val defaultUser = findUser("RESERVED_ADMIN_USER")
      (defaultUser \ "right").as[String] mustEqual "Admin"
      (defaultUser \ "admin").as[Boolean] mustBe true
      (defaultUser \ "tenantAdmin").as[Boolean] mustBe true

      val testuAdmin = findUser("testuAdmin")
      (testuAdmin \ "right").isEmpty mustBe true
      (testuAdmin \ "admin").as[Boolean] mustBe true
      (testuAdmin \ "tenantAdmin").as[Boolean] mustBe false

      val testuHookRead = findUser("testuHookRead")
      (testuHookRead \ "right").as[String] mustEqual "Read"
      (testuHookRead \ "admin").as[Boolean] mustBe false
      (testuHookRead \ "tenantAdmin").as[Boolean] mustBe false

      val testuHookWrite = findUser("testuHookWrite")
      (testuHookWrite \ "right").as[String] mustEqual "Write"
      (testuHookWrite \ "admin").as[Boolean] mustBe false
      (testuHookWrite \ "tenantAdmin").as[Boolean] mustBe false

      val testuHookAdmin = findUser("testuHookAdmin")
      (testuHookAdmin \ "right").as[String] mustEqual "Admin"
      (testuHookAdmin \ "admin").as[Boolean] mustBe false
      (testuHookAdmin \ "tenantAdmin").as[Boolean] mustBe false

      val testuTenantAdmin = findUser("testuTenantAdmin")
      (testuTenantAdmin \ "right").isEmpty mustBe true
      (testuTenantAdmin \ "admin").as[Boolean] mustBe false
      (testuTenantAdmin \ "tenantAdmin").as[Boolean] mustBe true
    }

    "return an error if user is not webhook admin" in {
      val situation = TestSituationBuilder()
        .loggedInWithAdminRights()
        .withTenants(
          TestTenant("tenant")
            .withProjects(
              TestProject("project").withFeatures(
                TestFeature("f1", enabled = false)
              )
            )
            .withWebhooks(
              TestWebhookByName(
                name = "my-hook",
                url = "http://localhost:3000",
                features = Set(("tenant", "project", "f1"))
              )
            )
        )
        .withUsers(
          TestUser(
            "testu",
            rights = TestRights(Map("tenant" -> TestTenantRight("tenant").addWebhookRight("my-hook", "Write")))
          )
        )
        .loggedAs("testu")
        .build()

      val response = situation.fetchWebhookUser("tenant", situation.findWebhookId("tenant", "my-hook").get)
      response.status mustBe FORBIDDEN
    }
  }

  "webhook right update endpoint" should {
    "allow to update user right for webhook" in {
      val situation = TestSituationBuilder()
        .loggedInWithAdminRights()
        .withTenants(
          TestTenant("tenant")
            .withProjects(
              TestProject("project").withFeatures(
                TestFeature("f1", enabled = false)
              )
            )
            .withWebhooks(
              TestWebhookByName(
                name = "my-hook",
                url = "http://localhost:3000",
                features = Set(("tenant", "project", "f1"))
              )
            )
        )
        .withUsers(
          TestUser(
            "testu",
            rights = TestRights(Map("tenant" -> TestTenantRight("tenant").addWebhookRight("my-hook", "Read")))
          )
        )
        .build()

      val response = situation.updateUserRightForWebhook(
        tenant = "tenant",
        webhook = situation.findWebhookId("tenant", "my-hook").get,
        user = "testu",
        right = Some("Admin")
      )

      response.status mustBe NO_CONTENT

      val users = situation.fetchWebhookUser("tenant", situation.findWebhookId("tenant", "my-hook").get)

      val testu = users.json.get.as[JsArray].value.find(js => (js \ "username").as[String] == "testu").get.as[JsObject]
      (testu \ "right").as[String] mustEqual "Admin"
    }

    "allow to add right for a non accredited user" in {
      val situation = TestSituationBuilder()
        .loggedInWithAdminRights()
        .withTenants(
          TestTenant("tenant")
            .withProjects(
              TestProject("project").withFeatures(
                TestFeature("f1", enabled = false)
              )
            )
            .withWebhooks(
              TestWebhookByName(
                name = "my-hook",
                url = "http://localhost:3000",
                features = Set(("tenant", "project", "f1"))
              )
            )
        )
        .withUsers(TestUser("testu"))
        .build()

      val response = situation.updateUserRightForWebhook(
        tenant = "tenant",
        webhook = situation.findWebhookId("tenant", "my-hook").get,
        user = "testu",
        right = Some("Admin")
      )

      response.status mustBe NO_CONTENT

      val users = situation.fetchWebhookUser("tenant", situation.findWebhookId("tenant", "my-hook").get)

      val testu = users.json.get.as[JsArray].value.find(js => (js \ "username").as[String] == "testu").get.as[JsObject]
      (testu \ "right").as[String] mustEqual "Admin"
    }

    "return an error when logged in user is not admin" in {
      val situation = TestSituationBuilder()
        .loggedAs("testu")
        .withTenants(
          TestTenant("tenant")
            .withProjects(
              TestProject("project").withFeatures(
                TestFeature("f1", enabled = false)
              )
            )
            .withWebhooks(
              TestWebhookByName(
                name = "my-hook",
                url = "http://localhost:3000",
                features = Set(("tenant", "project", "f1"))
              )
            )
        )
        .withUsers(
          TestUser(
            "testu",
            rights = TestRights(Map("tenant" -> TestTenantRight("tenant").addWebhookRight("my-hook", "Write")))
          )
        )
        .withUsers(TestUser("testu2"))
        .build()

      val response = situation.updateUserRightForWebhook(
        tenant = "tenant",
        webhook = situation.findWebhookId("tenant", "my-hook").get,
        user = "testu2",
        right = Some("Read")
      )

      response.status mustBe FORBIDDEN
    }
  }
}
