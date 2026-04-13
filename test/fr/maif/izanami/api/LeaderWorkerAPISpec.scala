package fr.maif.izanami.api

import com.typesafe.config.ConfigValueFactory
import fr.maif.izanami.api.BaseAPISpec.TestApiKey
import fr.maif.izanami.api.BaseAPISpec.TestFeature
import fr.maif.izanami.api.BaseAPISpec.TestProject
import fr.maif.izanami.api.BaseAPISpec.TestSituationBuilder
import fr.maif.izanami.api.BaseAPISpec.TestTenant
import fr.maif.izanami.api.BaseAPISpec.ws
import play.api.http.Status.BAD_REQUEST
import play.api.http.Status.NOT_FOUND
import play.api.http.Status.OK
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
        .withCustomConfiguration(Map("app.cluster.mode" -> "leader"))
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
        .withCustomConfiguration(Map("app.cluster.mode" -> "leader"))
        .build()

      val featureId = testSitutation
        .findFeatureId(tenant = "tenant", project = "project", feature = "f1")
        .get

      val checkResponse =
        testSitutation.checkFeature(featureId, key = "my-key")

      checkResponse.status mustBe NOT_FOUND
    }

    "serve frontend" in {
      TestSituationBuilder()
        .withTenants(
          TestTenant("tenant")
        )
        .loggedInWithAdminRights()
        .build()
      val response = await(ws.url("http://localhost:9000/login").get());
      response.status mustEqual OK
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

      testSitutation = testSitutation.restartServerWithConf(
        Map("app.cluster.mode" -> "worker")
      )

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

      testSitutation = testSitutation.restartServerWithConf(
        Map("app.cluster.mode" -> "worker")
      )

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

      testSitutation = testSitutation.restartServerWithConf(
        Map("app.cluster.mode" -> "worker")
      )

      val response = await(ws.url("http://localhost:9000/login").get())
      response.status mustBe NOT_FOUND
      response.body.toString mustEqual "Page not found"
    }

    "return 400 when caller request blacklisted context" in {
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

      testSitutation = testSitutation.restartServerWithConf(
        Map(
          "app.cluster.mode" -> "worker",
          "app.cluster.context-blocklist" -> ConfigValueFactory.fromIterable(
            java.util.List.of("prod", "protected")
          )
        )
      )

      val featureId = testSitutation
        .findFeatureId(tenant = "tenant", project = "project", feature = "f1")
        .get

      var checkResponse =
        testSitutation.checkFeature(
          featureId,
          key = "my-key",
          context = "prod"
        );
      checkResponse.status mustBe BAD_REQUEST

      checkResponse = testSitutation.checkFeature(
        featureId,
        key = "my-key",
        context = "protected"
      )
      checkResponse.status mustBe BAD_REQUEST

      checkResponse = testSitutation.checkFeature(
        featureId,
        key = "my-key",
        context = "prod/foo"
      )
      checkResponse.status mustBe BAD_REQUEST

      checkResponse = testSitutation.checkFeature(
        featureId,
        key = "my-key",
        context = "dev"
      )
      checkResponse.status mustBe OK

      checkResponse = testSitutation.checkFeature(
        featureId,
        key = "my-key",
        context = "dev/mobile"
      )
      checkResponse.status mustBe OK
      checkResponse = testSitutation.checkFeature(
        featureId,
        key = "my-key"
      )
      checkResponse.status mustBe OK
    }

    "allow only whitelisted contexts (and context-less calls) when whitelisted contexts are defined" in {
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

      testSitutation = testSitutation.restartServerWithConf(
        Map(
          "app.cluster.mode" -> "worker",
          "app.cluster.context-allowlist" -> ConfigValueFactory.fromIterable(
            java.util.List.of("prod", "protected")
          )
        )
      )

      val featureId = testSitutation
        .findFeatureId(tenant = "tenant", project = "project", feature = "f1")
        .get

      var checkResponse =
        testSitutation.checkFeature(
          featureId,
          key = "my-key",
          context = "prod"
        );
      checkResponse.status mustBe OK

      checkResponse = testSitutation.checkFeature(
        featureId,
        key = "my-key",
        context = "protected"
      )
      checkResponse.status mustBe OK

      checkResponse = testSitutation.checkFeature(
        featureId,
        key = "my-key",
        context = "prod/foo"
      )
      checkResponse.status mustBe OK

      checkResponse = testSitutation.checkFeature(
        featureId,
        key = "my-key",
        context = "dev"
      )
      checkResponse.status mustBe BAD_REQUEST

      checkResponse = testSitutation.checkFeature(
        featureId,
        key = "my-key",
        context = "dev/mobile"
      )
      checkResponse.status mustBe BAD_REQUEST

      checkResponse = testSitutation.checkFeature(
        featureId,
        key = "my-key"
      )
      checkResponse.status mustBe BAD_REQUEST
    }

  }

  "Standalone mode" should {
    "allow to call client endpoints" in {
      val testSitutation = TestSituationBuilder()
        .withTenants(
          TestTenant("tenant")
            .withApiKeys(TestApiKey(name = "my-key", admin = true))
            .withProjects(
              TestProject("project").withFeatures(TestFeature("f1"))
            )
        )
        .withCustomConfiguration(Map("app.cluster.mode" -> "standalone"))
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
        .withCustomConfiguration(Map("app.cluster.mode" -> "standalone"))
        .loggedInWithAdminRights()
        .build()

      val response = testSitutation.fetchTenants()
      response.status mustBe OK
    }

    "serve frontend" in {
      TestSituationBuilder()
        .withTenants(
          TestTenant("tenant")
        )
        .loggedInWithAdminRights()
        .build()
      val response = await(ws.url("http://localhost:9000/login").get());
      response.status mustEqual OK
    }

    "should not take blacklist into account" in {
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

      testSitutation = testSitutation.restartServerWithConf(
        Map(
          "app.cluster.mode" -> "standalone",
          "app.cluster.context-blocklist" -> ConfigValueFactory.fromIterable(
            java.util.List.of("prod", "protected")
          )
        )
      )

      val featureId = testSitutation
        .findFeatureId(tenant = "tenant", project = "project", feature = "f1")
        .get

      val checkResponse =
        testSitutation.checkFeature(
          featureId,
          key = "my-key",
          context = "prod"
        );
      checkResponse.status mustBe OK
    }

    "should not take whitelist into account" in {
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

      testSitutation = testSitutation.restartServerWithConf(
        Map(
          "app.cluster.mode" -> "standalone",
          "app.cluster.context-allowlist" -> ConfigValueFactory.fromIterable(
            java.util.List.of("prod", "protected")
          )
        )
      )

      val featureId = testSitutation
        .findFeatureId(tenant = "tenant", project = "project", feature = "f1")
        .get

      val checkResponse =
        testSitutation.checkFeature(
          featureId,
          key = "my-key",
          context = "dev"
        );
      checkResponse.status mustBe OK
    }
  }

}
