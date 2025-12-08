package fr.maif.izanami.api

import fr.maif.izanami.api.BaseAPISpec.{
  TestApiKey,
  TestFeature,
  TestProject,
  TestSituationBuilder,
  TestTenant,
  startServer,
  stopServer,
  ws
}
import play.api.http.Status.{NOT_FOUND, OK}
import play.api.test.Helpers.await

class LeaderWorkerAPISpec extends BaseAPISpec {
  "Leader mode" should {
    "allow to call admin endpoints" in {
      val testSitutation = TestSituationBuilder()
        .withTenants(
          TestTenant("tenant")
            .withProjects(
              TestProject("project").withFeatures(TestFeature("f1"))
            )
        )
        .withCustomConfiguration(Map("app.mode" -> "leader"))
        .loggedInWithAdminRights()
        .build()

      val response = testSitutation.fetchTenants()
      response.status mustBe OK
    }

    "prevent to call client endpoints" in {
      val testSitutation = TestSituationBuilder()
        .withTenants(
          TestTenant("tenant")
            .withApiKeys(TestApiKey(name = "my-key", admin = true))
            .withProjects(
              TestProject("project").withFeatures(TestFeature("f1"))
            )
        )
        .loggedInWithAdminRights()
        .withCustomConfiguration(Map("app.mode" -> "leader"))
        .build()

      val featureId = testSitutation
        .findFeatureId(tenant = "tenant", project = "project", feature = "f1")
        .get

      val checkResponse =
        testSitutation.checkFeature(featureId, key = "my-key")

      checkResponse.status mustBe NOT_FOUND
    }

    "serve frontend" in {
      var testSitutation = TestSituationBuilder()
        .withTenants(
          TestTenant("tenant")
        )
        .loggedInWithAdminRights()
        .build()
      val response = await(ws.url("http://localhost:9000/login").get())
      (response.json \ "message")
        .as[String] mustEqual "Resource not found by Assets controller"
    }
  }

  "Worker mode" should {
    "allow to call client endpoints" in {
      var testSitutation = TestSituationBuilder()
        .withTenants(
          TestTenant("tenant")
            .withApiKeys(TestApiKey(name = "my-key", admin = true))
            .withProjects(
              TestProject("project").withFeatures(TestFeature("f1"))
            )
        )
        .loggedInWithAdminRights()
        .build()

      testSitutation =
        testSitutation.restartServerWithConf(Map("app.mode" -> "worker"))

      val featureId = testSitutation
        .findFeatureId(tenant = "tenant", project = "project", feature = "f1")
        .get

      val checkResponse =
        testSitutation.checkFeature(featureId, key = "my-key")

      checkResponse.status mustBe OK
    }

    "prevent to call admin endpoints" in {
      var testSitutation = TestSituationBuilder()
        .withTenants(
          TestTenant("tenant")
            .withProjects(
              TestProject("project").withFeatures(TestFeature("f1"))
            )
        )
        .loggedInWithAdminRights()
        .build()

      testSitutation =
        testSitutation.restartServerWithConf(Map("app.mode" -> "worker"))

      val response = testSitutation.fetchTenants()
      response.status mustBe NOT_FOUND
    }

    "not serve frontend" in {
      var testSitutation = TestSituationBuilder()
        .withTenants(
          TestTenant("tenant")
        )
        .loggedInWithAdminRights()
        .build()

      testSitutation =
        testSitutation.restartServerWithConf(Map("app.mode" -> "worker"))

      val response = await(ws.url("http://localhost:9000/login").get())
      response.status mustBe NOT_FOUND
      response.body.toString mustEqual "Page not found"
    }
  }

  "Standalone mode" should {
    "allow to call client endpoints" in {
      var testSitutation = TestSituationBuilder()
        .withTenants(
          TestTenant("tenant")
            .withApiKeys(TestApiKey(name = "my-key", admin = true))
            .withProjects(
              TestProject("project").withFeatures(TestFeature("f1"))
            )
        )
        .withCustomConfiguration(Map("app.mode" -> "standalone"))
        .loggedInWithAdminRights()
        .build()

      val featureId = testSitutation
        .findFeatureId(tenant = "tenant", project = "project", feature = "f1")
        .get

      val checkResponse =
        testSitutation.checkFeature(featureId, key = "my-key")

      checkResponse.status mustBe OK
    }

    "allow to call admin endpoints" in {
      val testSitutation = TestSituationBuilder()
        .withTenants(
          TestTenant("tenant")
            .withProjects(
              TestProject("project").withFeatures(TestFeature("f1"))
            )
        )
        .withCustomConfiguration(Map("app.mode" -> "standalone"))
        .loggedInWithAdminRights()
        .build()

      val response = testSitutation.fetchTenants()
      response.status mustBe OK
    }

    "serve frontend" in {
      var testSitutation = TestSituationBuilder()
        .withTenants(
          TestTenant("tenant")
        )
        .loggedInWithAdminRights()
        .build()
      val response = await(ws.url("http://localhost:9000/login").get());
      (response.json \ "message")
        .as[String] mustEqual "Resource not found by Assets controller"
    }
  }
}
