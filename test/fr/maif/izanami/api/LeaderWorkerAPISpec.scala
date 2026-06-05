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
import play.api.libs.json.Json
import scala.jdk.CollectionConverters.*


class LeaderWorkerAPISpec extends BaseAPISpec {
  val workerUrlByContexts = Map(
    "app.cluster.worker-url-by-contexts.foo.prod" -> "http://prod.com"
  )
  "Leader mode" should {
    "serve worker by context urls" in {

      val testSitutation = TestSituationBuilder()
        .withTenantNames("foo")
        .withCustomConfiguration(Map("app.cluster.mode" -> "leader") ++ workerUrlByContexts)
        .loggedInWithAdminRights()
        .build()

        val res = (testSitutation.fetchExposition().json.get \ "clientUrlByContexts").as[Map[String, Map[String, String]]]

        res mustEqual Map("foo" -> Map("prod" -> "http://prod.com"))
    }

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
      // In some cases the frontend is not build (for instance in CI, and we must rely on play response)
      response.status must (equal (OK) or equal (NOT_FOUND))
      if(response.status == NOT_FOUND) {
        ((Json.parse(response.body)) \ "message").as[String] mustEqual "Resource not found by Assets controller"
      }
    }
  }

  "Worker mode" should {
    "not serve worker by context urls" in {
      var testSitutation = TestSituationBuilder()
        .withTenantNames("foo")
        .loggedInWithAdminRights()
        .build()

        val res = (testSitutation.fetchExposition().json.get \ "clientUrlByContexts").as[Map[String, Map[String, String]]]
        testSitutation = testSitutation.restartServerWithConf(Map("app.cluster.mode" -> "worker") ++ workerUrlByContexts)

        res mustBe empty
    }
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

    "return 400 when caller request blacklisted context using by tenant blocklist" in {
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
          "app.cluster.context-blocklist-by-tenant" -> ConfigValueFactory.fromMap(java.util.Map.of("tenant",
            java.util.List.of("prod", "protected")
          )
        )
      ))

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

    "allow only whitelisted contexts (and context-less calls) when whitelisted contexts are defined using by tenant whitelist" in {
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
          "app.cluster.context-allowlist-by-tenant" -> ConfigValueFactory.fromMap(java.util.Map.of("tenant",
            java.util.List.of("prod", "protected")
          ))
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
    "not serve worker by context urls" in {

      val testSitutation = TestSituationBuilder()
        .withTenantNames("foo")
        .withCustomConfiguration(Map("app.cluster.mode" -> "standalone") ++ workerUrlByContexts)
        .loggedInWithAdminRights()
        .build()

        val res = (testSitutation.fetchExposition().json.get \ "clientUrlByContexts").as[Map[String, Map[String, String]]]

        res mustBe empty
    }
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
      val response = await(ws.url("http://localhost:9000/").get());
      
      // In some cases the frontend is not build (for instance in CI, and we must rely on play response)
      response.status must (equal (OK) or equal (NOT_FOUND))
      if(response.status == NOT_FOUND) {
        ((Json.parse(response.body)) \ "message").as[String] mustEqual "Resource not found by Assets controller"
      }
    
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
