package fr.maif.izanami.api

import fr.maif.izanami.api.BaseAPISpec._
import play.api.http.Status.{FORBIDDEN, NOT_FOUND, OK, UNAUTHORIZED}
import play.api.libs.json.{JsArray, JsObject, JsValue, Json}

import java.time.{DayOfWeek, LocalDate, LocalDateTime, LocalTime}

class FeatureClientAPISpec extends BaseAPISpec {
  "Feature check GET endpoint" should {
    "return legacy features in modern format" in {
      val situation = TestSituationBuilder()
        .withTenants(TestTenant("tenant").withApiKeys(TestApiKey(name = "my-key", admin = true)))
        .loggedInWithAdminRights()
        .build()

      situation.importAndWaitTermination(
        tenant = "tenant",
        features = Seq(
          """{"id":"project:another:customer-list-feature","enabled":true,"description":"An old style user list feature","parameters":{"customers":["foo","bar","baz"]},"activationStrategy":"CUSTOMERS_LIST"}""".stripMargin,
          """{"id":"project:test:percentage-feature","enabled":true,"description":"An old style percentage feature","parameters":{"percentage":75},"activationStrategy":"PERCENTAGE"}"""
        )
      )

      val projectId = (situation.fetchProject("tenant", "project").json.get \ "id").as[String]

      val response = situation.checkFeatures(key = "my-key", projects = Seq(projectId), conditions = true)
      response.status mustEqual OK
      val json     = response.json.get
      (json \ "project:another:customer-list-feature" \ "conditions" \ "" \ "conditions" \ 0 \ "rule" \ "users")
        .as[Seq[String]] must contain theSameElementsAs Seq("foo", "bar", "baz")
      (json \ "project:test:percentage-feature" \ "conditions" \ "" \ "conditions" \ 0 \ "rule" \ "percentage")
        .as[Int] mustEqual 75
    }

    "return 403 if key does not authorize feature" in {
      val tenantName      = "my-tenant"
      val projectName     = "my-project"
      val testSitutation  = TestSituationBuilder()
        .withTenants(
          TestTenant(tenantName)
            .withProjectNames(projectName)
            .withApiKeys(TestApiKey(name = "my-key"))
        )
        .loggedInWithAdminRights()
        .build()
      val featureResponse = testSitutation.createFeature(
        name = "feature-name",
        project = projectName,
        tenant = tenantName,
        enabled = true
      )
      val checkResponse   = testSitutation.checkFeature(featureResponse.id.get, key = "my-key")

      checkResponse.status mustBe FORBIDDEN
    }

    "return 403 if key is disabled" in {
      val tenantName      = "my-tenant"
      val projectName     = "my-project"
      val testSitutation  = TestSituationBuilder()
        .withTenants(
          TestTenant(tenantName)
            .withProjectNames(projectName)
            .withApiKeys(TestApiKey(name = "my-key", enabled = false, projects = Seq("my-project")))
        )
        .loggedInWithAdminRights()
        .build()
      val featureResponse = testSitutation.createFeature(
        name = "feature-name",
        project = projectName,
        tenant = tenantName,
        enabled = true
      )
      val checkResponse   = testSitutation.checkFeature(featureResponse.id.get, key = "my-key")

      checkResponse.status mustBe FORBIDDEN
    }

    "return 401 if no key is provided" in {
      val tenantName      = "my-tenant"
      val projectName     = "my-project"
      val testSitutation  = TestSituationBuilder()
        .withTenants(
          TestTenant(tenantName)
            .withProjectNames(projectName)
        )
        .loggedInWithAdminRights()
        .build()
      val featureResponse = testSitutation.createFeature(
        name = "feature-name",
        project = projectName,
        tenant = tenantName,
        enabled = true
      )
      val checkResponse   = checkFeature(featureResponse.id.get)

      checkResponse.status mustBe UNAUTHORIZED
    }

    "return feature activation status if key does not allow for feature project but is admin" in {
      val tenantName      = "my-tenant"
      val projectName     = "my-project"
      val testSitutation  = TestSituationBuilder()
        .withTenants(
          TestTenant(tenantName)
            .withProjectNames(projectName)
            .withApiKeys(TestApiKey(name = "my-key", admin = true))
        )
        .loggedInWithAdminRights()
        .build()
      val featureResponse = testSitutation.createFeature(
        name = "feature-name",
        project = projectName,
        tenant = tenantName,
        enabled = true
      )
      val checkResponse   = testSitutation.checkFeature(featureResponse.id.get, key = "my-key")

      checkResponse.status mustBe OK
      (checkResponse.json.get \ "active").get.as[Boolean] mustBe true
    }

    "return true for enabled NO_STRATEGY feature" in {
      val tenantName      = "my-tenant"
      val projectName     = "my-project"
      val testSituation   = TestSituationBuilder()
        .loggedInWithAdminRights()
        .withTenants(TestTenant(tenantName).withProjectNames(projectName).withAllRightsKey("my-key"))
        .build()
      val featureResponse = testSituation.createFeature(
        name = "feature-name",
        project = projectName,
        tenant = tenantName,
        enabled = true
      )
      val checkResponse   = testSituation.checkFeature(featureResponse.id.get, "my-key")

      checkResponse.status mustBe OK
      (checkResponse.json.get \ "active").get.as[Boolean] mustBe true
    }

    "return true for enabled wasm feature that returns true" in {
      val tenantName      = "my-tenant"
      val projectName     = "my-project"
      val testSituation   = TestSituationBuilder()
        .loggedInWithAdminRights()
        .withTenants(TestTenant(tenantName).withProjectNames(projectName).withAllRightsKey("my-key"))
        .build()
      val featureResponse = testSituation.createFeature(
        name = "feature-name",
        project = projectName,
        tenant = tenantName,
        enabled = true,
        wasmConfig = TestWasmConfig(
          name = "wasmScript",
          source = Json.obj(
            "kind" -> "Base64",
            "path" -> enabledFeatureBase64
          )
        )
      )
      val checkResponse   = testSituation.checkFeature(featureResponse.id.get, "my-key")

      checkResponse.status mustBe OK
      (checkResponse.json.get \ "active").get.as[Boolean] mustBe true
    }

    "return false for enabled wasm feature that returns false" in {
      val tenantName      = "my-tenant"
      val projectName     = "my-project"
      val testSituation   = TestSituationBuilder()
        .loggedInWithAdminRights()
        .withTenants(TestTenant(tenantName).withProjectNames(projectName).withAllRightsKey("my-key"))
        .build()
      val featureResponse = testSituation.createFeature(
        name = "feature-name",
        project = projectName,
        tenant = tenantName,
        enabled = true,
        wasmConfig = TestWasmConfig(
          name = "wasmScript",
          source = Json.obj(
            "kind" -> "Base64",
            "path" -> disabledFeatureBase64
          )
        )
      )
      val checkResponse   = testSituation.checkFeature(featureResponse.id.get, "my-key")

      checkResponse.status mustBe OK
      (checkResponse.json.get \ "active").get.as[Boolean] mustBe false
    }

    "return true for enabled RELEASE_DATE feature with past release_date" in {
      val tenantName      = "my-tenant"
      val projectName     = "my-project"
      val situation       = TestSituationBuilder()
        .loggedInWithAdminRights()
        .withTenants(TestTenant(tenantName).withProjectNames(projectName).withAllRightsKey("my-key"))
        .build()
      val featureResponse = situation.createFeature(
        name = "feature-name",
        project = projectName,
        tenant = tenantName,
        enabled = true,
        conditions = Set(TestCondition(period = TestDateTimePeriod(begin = LocalDateTime.now().minusDays(1))))
      )
      val checkResponse   = situation.checkFeature(featureResponse.id.get, "my-key")

      checkResponse.status mustBe OK
      (checkResponse.json.get \ "active").get.as[Boolean] mustBe true
    }

    "return false for disabled RELEASE_DATE feature with past release_date" in {
      val tenantName      = "my-tenant"
      val projectName     = "my-project"
      val situation       = TestSituationBuilder()
        .loggedInWithAdminRights()
        .withTenants(TestTenant(tenantName).withProjectNames(projectName).withAllRightsKey("my-key"))
        .build()
      val featureResponse = situation.createFeature(
        name = "feature-name",
        project = projectName,
        tenant = tenantName,
        enabled = false,
        conditions = Set(TestCondition(period = TestDateTimePeriod(begin = LocalDateTime.now().minusDays(1))))
      )
      val checkResponse   = situation.checkFeature(featureResponse.id.get, "my-key")

      checkResponse.status mustBe OK
      (checkResponse.json.get \ "active").get.as[Boolean] mustBe false
    }

    "return false for enabled RELEASE_DATE feature with future release_date" in {
      val tenantName      = "my-tenant"
      val projectName     = "my-project"
      val situation       = TestSituationBuilder()
        .loggedInWithAdminRights()
        .withTenants(TestTenant(tenantName).withProjectNames(projectName).withAllRightsKey("my-key"))
        .build()
      val featureResponse = situation.createFeature(
        name = "feature-name",
        project = projectName,
        tenant = tenantName,
        enabled = true,
        conditions = Set(TestCondition(period = TestDateTimePeriod(begin = LocalDateTime.now().plusDays(2))))
      )
      val checkResponse   = situation.checkFeature(featureResponse.id.get, "my-key")

      checkResponse.status mustBe OK
      (checkResponse.json.get \ "active").get.as[Boolean] mustBe false
    }

    "return false for disabled NO_STRATEGY feature" in {
      val tenantName     = "my-tenant"
      val projectName    = "my-project"
      val situation      = TestSituationBuilder()
        .loggedInWithAdminRights()
        .withTenants(TestTenant(tenantName).withProjectNames(projectName).withAllRightsKey("my-key"))
        .build()
      val featureRequest = situation.createFeature(
        name = "feature-name",
        project = projectName,
        tenant = tenantName,
        enabled = false
      )
      val checkResponse  = situation.checkFeature(featureRequest.id.get, "my-key")

      checkResponse.status mustBe OK
      (checkResponse.json.get \ "active").get.as[Boolean] mustBe false
    }

    "return true for disabled feature enabled at context level" in {
      val tenantName     = "my-tenant"
      val projectName    = "my-project"
      val contextName    = "my-context"
      val featureName    = "feature-name"
      val situation      = TestSituationBuilder()
        .loggedInWithAdminRights()
        .withTenants(
          TestTenant(tenantName)
            .withProjects(TestProject(projectName).withContextNames(contextName))
            .withAllRightsKey("my-key")
        )
        .build()
      val featureRequest = situation.createFeature(
        name = featureName,
        project = projectName,
        tenant = tenantName,
        enabled = false
      )
      situation.changeFeatureStrategyForContext(tenantName, projectName, contextName, featureName, true)
      val checkResponse  = situation.checkFeature(featureRequest.id.get, key = "my-key", context = contextName)

      checkResponse.status mustBe OK
      (checkResponse.json.get \ "active").get.as[Boolean] mustBe true
    }

    "return true for disabled feature enabled at context level with context starting with /" in {
      val tenantName     = "my-tenant"
      val projectName    = "my-project"
      val contextName    = "my-context"
      val featureName    = "feature-name"
      val situation      = TestSituationBuilder()
        .loggedInWithAdminRights()
        .withTenants(
          TestTenant(tenantName)
            .withProjects(TestProject(projectName).withContextNames(contextName))
            .withAllRightsKey("my-key")
        )
        .build()
      val featureRequest = situation.createFeature(
        name = featureName,
        project = projectName,
        tenant = tenantName,
        enabled = false
      )
      situation.changeFeatureStrategyForContext(tenantName, projectName, contextName, featureName, true)
      val checkResponse  = situation.checkFeature(featureRequest.id.get, key = "my-key", context = s"/$contextName")

      checkResponse.status mustBe OK
      (checkResponse.json.get \ "active").get.as[Boolean] mustBe true
    }

    "return true for disabled feature enabled at context level (subcontext)" in {
      val tenantName     = "my-tenant"
      val projectName    = "my-project"
      val contextName    = "my-context"
      val featureName    = "feature-name"
      val situation      = TestSituationBuilder()
        .loggedInWithAdminRights()
        .withTenants(
          TestTenant(tenantName)
            .withProjects(
              TestProject(projectName).withContexts(TestFeatureContext(contextName).withSubContextNames("subcontext"))
            )
            .withAllRightsKey("my-key")
        )
        .build()
      val featureRequest = situation.createFeature(
        name = featureName,
        project = projectName,
        tenant = tenantName,
        enabled = false
      )
      val contextPath    = s"${contextName}/subcontext"
      situation.changeFeatureStrategyForContext(tenantName, projectName, contextPath, featureName, true)
      val checkResponse  = situation.checkFeature(featureRequest.id.get, key = "my-key", context = contextPath)

      checkResponse.status mustBe OK
      (checkResponse.json.get \ "active").get.as[Boolean] mustBe true
    }

    "return true for disabled feature enabled at an upper context level" in {
      val tenantName     = "my-tenant"
      val projectName    = "my-project"
      val contextName    = "my-context"
      val featureName    = "feature-name"
      val situation      = TestSituationBuilder()
        .loggedInWithAdminRights()
        .withTenants(
          TestTenant(tenantName)
            .withProjects(
              TestProject(projectName).withContexts(TestFeatureContext(contextName).withSubContextNames("subcontext"))
            )
            .withAllRightsKey("my-key")
        )
        .build()
      val featureRequest = situation.createFeature(
        name = featureName,
        project = projectName,
        tenant = tenantName,
        enabled = false
      )
      situation.changeFeatureStrategyForContext(tenantName, projectName, contextName, featureName, true)
      val checkResponse  =
        situation.checkFeature(featureRequest.id.get, key = "my-key", context = s"${contextName}/subcontext")

      checkResponse.status mustBe OK
      (checkResponse.json.get \ "active").get.as[Boolean] mustBe true
    }

    "return false for disabled feature if there is no context override" in {
      val tenantName     = "my-tenant"
      val projectName    = "my-project"
      val contextName    = "my-context"
      val featureName    = "feature-name"
      val situation      = TestSituationBuilder()
        .loggedInWithAdminRights()
        .withTenants(
          TestTenant(tenantName)
            .withProjects(
              TestProject(projectName).withContexts(TestFeatureContext(contextName).withSubContextNames("subcontext"))
            )
            .withAllRightsKey("my-key")
        )
        .build()
      val featureRequest = situation.createFeature(
        name = featureName,
        project = projectName,
        tenant = tenantName,
        enabled = false
      )
      val checkResponse  =
        situation.checkFeature(featureRequest.id.get, key = "my-key", context = s"${contextName}/subcontext")

      checkResponse.status mustBe OK
      (checkResponse.json.get \ "active").get.as[Boolean] mustBe false
    }

    "prioritize 'lower' context strategy when there is several overrides" in {
      val tenantName     = "my-tenant"
      val projectName    = "my-project"
      val contextName    = "my-context"
      val featureName    = "feature-name"
      val situation      = TestSituationBuilder()
        .loggedInWithAdminRights()
        .withTenants(
          TestTenant(tenantName)
            .withProjects(
              TestProject(projectName).withContexts(TestFeatureContext(contextName).withSubContextNames("subcontext"))
            )
            .withAllRightsKey("my-key")
        )
        .build()
      val featureRequest = situation.createFeature(
        name = featureName,
        project = projectName,
        tenant = tenantName,
        enabled = false
      )
      situation.changeFeatureStrategyForContext(tenantName, projectName, contextName, featureName, false)
      situation.changeFeatureStrategyForContext(
        tenantName,
        projectName,
        s"${contextName}/subcontext",
        featureName,
        true
      )
      val checkResponse  =
        situation.checkFeature(featureRequest.id.get, key = "my-key", context = s"${contextName}/subcontext")

      checkResponse.status mustBe OK
      (checkResponse.json.get \ "active").get.as[Boolean] mustBe true
    }

    "return 404 for absent feature" in {
      val situation = TestSituationBuilder()
        .withTenants(TestTenant("my-tenant").withProjectNames("my-project").withAllRightsKey("my-key"))
        .build()

      val checkResponse = situation.checkFeature("d398cb04-1476-4b32-ae9b-8bb4d5f9f3a5", "my-key")

      checkResponse.status mustBe FORBIDDEN
    }

    "return true for enabled DATE_RANGE feature if date is in range" in {
      val tenantName     = "my-tenant"
      val projectName    = "my-project"
      val situation      = TestSituationBuilder()
        .loggedInWithAdminRights()
        .withTenants(TestTenant(tenantName).withProjectNames(projectName).withAllRightsKey("my-key"))
        .build()
      val featureRequest = situation.createFeature(
        name = "feature-name",
        project = projectName,
        tenant = tenantName,
        enabled = true,
        conditions = Set(
          TestCondition(period =
            TestDateTimePeriod(
              begin = LocalDateTime.now().minusDays(2),
              end = LocalDateTime.now().plusDays(2)
            )
          )
        )
      )
      val checkResponse  = situation.checkFeature(featureRequest.id.get, "my-key")

      checkResponse.status mustBe OK
      (checkResponse.json.get \ "active").get.as[Boolean] mustBe true
    }

    "return true for feature with hour range if current hour is in range" in {
      val tenantName     = "my-tenant"
      val projectName    = "my-project"
      val situation      = TestSituationBuilder()
        .loggedInWithAdminRights()
        .withTenants(TestTenant(tenantName).withProjectNames(projectName).withAllRightsKey("my-key"))
        .build()
      val featureRequest = situation.createFeature(
        name = "feature-name",
        project = projectName,
        tenant = tenantName,
        enabled = true,
        conditions = Set(
          TestCondition(period =
            TestDateTimePeriod().atHours(TestHourPeriod(startTime = LocalTime.MIN, endTime = LocalTime.MAX))
          )
        )
      )
      val checkResponse  = situation.checkFeature(featureRequest.id.get, "my-key")

      checkResponse.status mustBe OK
      (checkResponse.json.get \ "active").get.as[Boolean] mustBe true
    }

    "return false for feature with hour range if current hour is not in range" in {
      val tenantName     = "my-tenant"
      val projectName    = "my-project"
      val situation      = TestSituationBuilder()
        .loggedInWithAdminRights()
        .withTenants(TestTenant(tenantName).withProjectNames(projectName).withAllRightsKey("my-key"))
        .build()
      val featureRequest = situation.createFeature(
        name = "feature-name",
        project = projectName,
        tenant = tenantName,
        enabled = true,
        conditions = Set(
          TestCondition(period =
            TestDateTimePeriod().atHours(
              TestHourPeriod(startTime = LocalTime.now.plusMinutes(10), endTime = LocalTime.now.plusMinutes(20))
            )
          )
        )
      )
      val checkResponse  = situation.checkFeature(featureRequest.id.get, "my-key")

      checkResponse.status mustBe OK
      (checkResponse.json.get \ "active").get.as[Boolean] mustBe false
    }

    "return true for feature with activation day if active day is today" in {
      val tenantName     = "my-tenant"
      val projectName    = "my-project"
      val situation      = TestSituationBuilder()
        .loggedInWithAdminRights()
        .withTenants(TestTenant(tenantName).withProjectNames(projectName).withAllRightsKey("my-key"))
        .build()
      val featureRequest = situation.createFeature(
        name = "feature-name",
        project = projectName,
        tenant = tenantName,
        enabled = true,
        conditions = Set(
          TestCondition(period = TestDateTimePeriod().atDays(LocalDate.now().getDayOfWeek))
        )
      )
      val checkResponse  = situation.checkFeature(featureRequest.id.get, "my-key")

      checkResponse.status mustBe OK
      (checkResponse.json.get \ "active").get.as[Boolean] mustBe true
    }

    "return false for feature with activation day if active day is not today" in {
      val tenantName     = "my-tenant"
      val projectName    = "my-project"
      val situation      = TestSituationBuilder()
        .loggedInWithAdminRights()
        .withTenants(TestTenant(tenantName).withProjectNames(projectName).withAllRightsKey("my-key"))
        .build()
      val featureRequest = situation.createFeature(
        name = "feature-name",
        project = projectName,
        tenant = tenantName,
        enabled = true,
        conditions = Set(
          TestCondition(period = TestDateTimePeriod().atDays(LocalDate.now().minusDays(2).getDayOfWeek))
        )
      )
      val checkResponse  = situation.checkFeature(featureRequest.id.get, "my-key")

      checkResponse.status mustBe OK
      (checkResponse.json.get \ "active").get.as[Boolean] mustBe false
    }

    "return true for feature with user list if user is in list" in {
      val tenantName     = "my-tenant"
      val projectName    = "my-project"
      val situation      = TestSituationBuilder()
        .loggedInWithAdminRights()
        .withTenants(TestTenant(tenantName).withProjectNames(projectName).withAllRightsKey("my-key"))
        .build()
      val featureRequest = situation.createFeature(
        name = "feature-name",
        project = projectName,
        tenant = tenantName,
        enabled = true,
        conditions = Set(
          TestCondition(rule = TestUserListRule(users = Set("me")))
        )
      )
      val checkResponse  = situation.checkFeature(featureRequest.id.get, "my-key", "me")

      checkResponse.status mustBe OK
      (checkResponse.json.get \ "active").get.as[Boolean] mustBe true
    }

    "return false for feature with user list if user is not in list" in {
      val tenantName     = "my-tenant"
      val projectName    = "my-project"
      val situation      = TestSituationBuilder()
        .loggedInWithAdminRights()
        .withTenants(TestTenant(tenantName).withProjectNames(projectName).withAllRightsKey("my-key"))
        .build()
      val featureRequest = situation.createFeature(
        name = "feature-name",
        project = projectName,
        tenant = tenantName,
        enabled = true,
        conditions = Set(
          TestCondition(rule = TestUserListRule(users = Set("not-me")))
        )
      )
      val checkResponse  = situation.checkFeature(featureRequest.id.get, "my-key", "me")

      checkResponse.status mustBe OK
      (checkResponse.json.get \ "active").get.as[Boolean] mustBe false
    }

    "always return true for percentage if feature activation was true the first time" in {
      val tenantName     = "my-tenant"
      val projectName    = "my-project"
      val situation      = TestSituationBuilder()
        .loggedInWithAdminRights()
        .withTenants(TestTenant(tenantName).withProjectNames(projectName).withAllRightsKey("my-key"))
        .build()
      val featureRequest = situation.createFeature(
        name = "feature-name",
        project = projectName,
        tenant = tenantName,
        enabled = true,
        conditions = Set(
          TestCondition(rule = TestPercentageRule(10))
        )
      )

      for (i <- 0 until 10) {
        val checkResponse = situation.checkFeature(featureRequest.id.get, "my-key", "d")

        checkResponse.status mustBe OK
        (checkResponse.json.get \ "active").get.as[Boolean] mustBe true
      }
    }

    "always return false for percentage if feature activation was false the first time" in {
      val tenantName     = "my-tenant"
      val projectName    = "my-project"
      val situation      = TestSituationBuilder()
        .loggedInWithAdminRights()
        .withTenants(TestTenant(tenantName).withProjectNames(projectName).withAllRightsKey("my-key"))
        .build()
      val featureRequest = situation.createFeature(
        name = "feature-name",
        project = projectName,
        tenant = tenantName,
        enabled = true,
        conditions = Set(
          TestCondition(rule = TestPercentageRule(10))
        )
      )

      for (i <- 0 until 10) {
        val checkResponse = situation.checkFeature(featureRequest.id.get, "my-key", "a")

        checkResponse.status mustBe OK
        (checkResponse.json.get \ "active").get.as[Boolean] mustBe false
      }
    }

    "Percentage and period feature should be active if both period and percentage match" in {
      val tenantName     = "my-tenant"
      val projectName    = "my-project"
      val situation      = TestSituationBuilder()
        .loggedInWithAdminRights()
        .withTenants(TestTenant(tenantName).withProjectNames(projectName).withAllRightsKey("my-key"))
        .build()
      val featureRequest = situation.createFeature(
        name = "feature-name",
        project = projectName,
        tenant = tenantName,
        enabled = true,
        conditions = Set(
          TestCondition(
            rule = TestPercentageRule(10),
            period =
              TestDateTimePeriod().beginAt(LocalDateTime.now().minusDays(1)).endAt(LocalDateTime.now().plusDays(2))
          )
        )
      )

      val checkResponse = situation.checkFeature(featureRequest.id.get, "my-key", "d")

      checkResponse.status mustBe OK
      (checkResponse.json.get \ "active").get.as[Boolean] mustBe true
    }

    "Period feature should be active when only one of its period is active" in {
      val tenantName     = "my-tenant"
      val projectName    = "my-project"
      val situation      = TestSituationBuilder()
        .loggedInWithAdminRights()
        .withTenants(TestTenant(tenantName).withProjectNames(projectName).withAllRightsKey("my-key"))
        .build()
      val featureRequest = situation.createFeature(
        name = "feature-name",
        project = projectName,
        tenant = tenantName,
        enabled = true,
        conditions = Set(
          TestCondition(period =
            TestDateTimePeriod().beginAt(LocalDateTime.now().minusDays(5)).endAt(LocalDateTime.now().minusDays(3))
          ),
          TestCondition(period =
            TestDateTimePeriod().beginAt(LocalDateTime.now().minusDays(1)).endAt(LocalDateTime.now().plusDays(2))
          )
        )
      )

      val checkResponse = situation.checkFeature(featureRequest.id.get, "my-key", "test-user")

      checkResponse.status mustBe OK
      (checkResponse.json.get \ "active").get.as[Boolean] mustBe true
    }
  }

  "Feature check multi-features endpoint" should {
    "return active user feature if user is in list" in {
      val testSituation = TestSituationBuilder()
        .withTenants(
          TestTenant("my-tenant")
            .withProjects(
              TestProject("my-project")
                .withFeatures(
                  TestFeature(
                    name = "test-feature",
                    enabled = true,
                    conditions = Set(TestCondition(rule = TestUserListRule(Set("my-user"))))
                  )
                )
            )
            .withApiKeys(TestApiKey(name = "my-key", projects = Seq("my-project")))
        )
        .build()

      val result = testSituation.checkFeatures(
        projects = Seq(testSituation.findProjectId("my-tenant", "my-project").get),
        key = "my-key",
        user = "my-user"
      )

      (result.json.get \\ "active")
        .map(v => v.as[Boolean])
        .head mustBe true
    }

    "reject request with no key" in {
      val situation = TestSituationBuilder()
        .withTenants(
          TestTenant("my-tenant")
            .withProjects(TestProject("my-project").withFeatures(TestFeature("F1")))
            .withProjects(TestProject("my-project2").withFeatures(TestFeature("F21")))
            .withProjects(TestProject("my-project3").withFeatures(TestFeature("F31")))
        )
        .build()

      val result = checkFeatures(
        projects = Seq(situation.findProjectId("my-tenant", "my-project").get)
      )

      result.status mustBe UNAUTHORIZED
    }

    "reject request with incorrect client secret" in {
      val testData = TestSituationBuilder()
        .withTenants(
          TestTenant("my-tenant")
            .withProjects(TestProject("my-project").withFeatures(TestFeature("F1")))
            .withApiKeys(TestApiKey(name = "my-key", projects = Seq("my-project")))
        )
        .build()

      val result = checkFeatures(
        headers = testData.keyHeaders("my-key") + ("Izanami-Client-Secret" -> "aaaa"),
        projects = Seq(testData.findProjectId("my-tenant", "my-project").get)
      )

      result.status mustBe FORBIDDEN
    }

    "reject request with incorrect clientid" in {
      val testData = TestSituationBuilder()
        .withTenants(
          TestTenant("my-tenant")
            .withProjects(TestProject("my-project").withFeatures(TestFeature("F1")))
            .withApiKeys(TestApiKey(name = "my-key", projects = Seq("my-project")))
        )
        .build()

      val result = checkFeatures(
        projects = Seq(testData.findProjectId("my-tenant", "my-project").get),
        headers = testData.keyHeaders("my-key") + ("Izanami-Client-Id" -> "aaaa")
      )

      result.status mustBe FORBIDDEN
    }

    "reject request with incorrect clientid and clientsecret" in {
      val testData = TestSituationBuilder()
        .withTenants(
          TestTenant("my-tenant")
            .withProjects(TestProject("my-project").withFeatures(TestFeature("F1")))
            .withApiKeys(TestApiKey(name = "my-key", projects = Seq("my-project")))
        )
        .build()

      val result = checkFeatures(
        projects = Seq(testData.findProjectId("my-tenant", "my-project").get),
        headers = testData.keyHeaders("my-key") + ("Izanami-Client-Id" -> "aaaa") + ("Izanami-Client-Secret" -> "aaaa")
      )

      result.status mustBe FORBIDDEN
    }

    "reject request on unauthorized project" in {
      val testSituation = TestSituationBuilder()
        .withTenants(
          TestTenant("my-tenant")
            .withProjects(TestProject("my-project").withFeatures(TestFeature("F1")))
            .withApiKeys(TestApiKey(name = "my-key", projects = Seq()))
        )
        .build()

      val result = testSituation.checkFeatures(
        key = "my-key",
        projects = Seq(testSituation.findProjectId("my-tenant", "my-project").get)
      )

      result.status mustBe FORBIDDEN
    }

    "should allow to search feature request on unauthorized project but with an admin key" in {
      val testSituation = TestSituationBuilder()
        .withTenants(
          TestTenant("my-tenant")
            .withProjects(TestProject("my-project").withFeatures(TestFeature("F1")))
            .withApiKeys(TestApiKey(name = "my-key", projects = Seq(), admin = true))
        )
        .build()

      val result = testSituation.checkFeatures(
        key = "my-key",
        projects = Seq(testSituation.findProjectId("my-tenant", "my-project").get)
      )

      result.status mustBe OK
    }

    "should allow to search features from given project" in {
      val testSituation = TestSituationBuilder()
        .withTenants(
          TestTenant("my-tenant")
            .withProjects(TestProject("my-project").withFeatureNames("F1", "F2"))
            .withProjects(TestProject("my-project2").withFeatures(TestFeature("F21")))
            .withProjects(TestProject("my-project3").withFeatures(TestFeature("F31")))
            .withApiKeys(TestApiKey(name = "my-key", projects = Seq("my-project", "my-project2")))
        )
        .build()

      val result = testSituation.checkFeatures(
        key = "my-key",
        projects = Seq(testSituation.findProjectId("my-tenant", "my-project").get)
      )

      result.status mustBe OK
      result.json.get
        .as[Map[String, JsObject]]
        .values
        .map(obj => (obj \ "name").as[String]) must contain theSameElementsAs Seq(
        "F1",
        "F2"
      )
    }

    "should allow to search features with at least one tag in given list" in {
      val testSituation = TestSituationBuilder()
        .withTenants(
          TestTenant("my-tenant")
            .withTagNames("my-tag", "my-tag2", "my-tag3")
            .withProjects(
              TestProject("my-project")
                .withFeatures(
                  TestFeature("F1", tags = Seq("my-tag")),
                  TestFeature("F2", tags = Seq("my-tag2")),
                  TestFeature("F3", tags = Seq("my-tag3")),
                  TestFeature("F4", tags = Seq("my-tag", "my-tag2", "my-tag3")),
                  TestFeature("F5")
                )
            )
            .withApiKeys(
              TestApiKey(name = "my-key", projects = Seq("my-project"))
            )
        )
        .build();

      val result =
        testSituation.checkFeatures(
          projects = Seq(testSituation.findProjectId("my-tenant", "my-project").get),
          key = "my-key",
          oneTagIn =
            Seq(testSituation.findTagId("my-tenant", "my-tag").get, testSituation.findTagId("my-tenant", "my-tag2").get)
        )

      result.status mustBe OK
      (result.json.get
        .as[Map[String, JsObject]])
        .values
        .map(v => (v \ "name").as[String]) must contain theSameElementsAs Seq(
        "F1",
        "F2",
        "F4"
      )
    }

    "should allow to search features with all tags in given list" in {
      val testSituation = TestSituationBuilder()
        .withTenants(
          TestTenant("my-tenant")
            .withTagNames("my-tag", "my-tag2", "my-tag3")
            .withProjects(
              TestProject("my-project")
                .withFeatures(
                  TestFeature("F1", tags = Seq("my-tag")),
                  TestFeature("F2", tags = Seq("my-tag2")),
                  TestFeature("F3", tags = Seq("my-tag2", "my-tag3")),
                  TestFeature("F4", tags = Seq("my-tag2", "my-tag3", "my-tag")),
                  TestFeature("F5", tags = Seq("my-tag2", "my-tag3", "my-tag")),
                  TestFeature("F6")
                )
            )
            .withApiKeys(TestApiKey("my-key", projects = Seq("my-project")))
        )
        .build();

      val result =
        testSituation.checkFeatures(
          projects = Seq(testSituation.findProjectId("my-tenant", "my-project").get),
          allTagsIn = Seq(
            testSituation.findTagId("my-tenant", "my-tag").get,
            testSituation.findTagId("my-tenant", "my-tag2").get
          ),
          key = "my-key"
        )

      result.status mustBe OK
      (result.json.get
        .as[Map[String, JsObject]])
        .values
        .map(v => (v \ "name").as[String]) must contain theSameElementsAs Seq(
        "F4",
        "F5"
      )
    }

    "should return overridden activation status if context is specified" in {
      val situation = TestSituationBuilder()
        .loggedInWithAdminRights()
        .withTenants(
          TestTenant("tenant")
            .withProjects(
              TestProject("project").withContextNames("context").withFeatures(TestFeature("F1", enabled = false))
            )
            .withAllRightsKey("key")
        )
        .build()
      situation.changeFeatureStrategyForContext("tenant", "project", "context", "F1", true)
      val result    = situation.checkFeatures(
        key = "key",
        projects = Seq(situation.findProjectId("tenant", "project").get),
        contextPath = "context"
      )

      (result.json.get \\ "active")
        .map(v => v.as[Boolean])
        .head mustBe true
    }

    "should return overridden activation status if specified in subcontext" in {
      val situation = TestSituationBuilder()
        .loggedInWithAdminRights()
        .withTenants(
          TestTenant("tenant")
            .withProjects(
              TestProject("project")
                .withContexts(TestFeatureContext("context").withSubContextNames("subcontext"))
                .withFeatures(TestFeature("F1", enabled = false))
            )
            .withAllRightsKey("key")
        )
        .build()
      situation.changeFeatureStrategyForContext("tenant", "project", "context/subcontext", "F1", true)
      val result    = situation.checkFeatures(
        key = "key",
        projects = Seq(situation.findProjectId("tenant", "project").get),
        contextPath = "context/subcontext"
      )

      (result.json.get \\ "active")
        .map(v => v.as[Boolean])
        .head mustBe true
    }

    "should return overridden activation status if specified in parent context" in {
      val situation = TestSituationBuilder()
        .loggedInWithAdminRights()
        .withTenants(
          TestTenant("tenant")
            .withProjects(
              TestProject("project")
                .withContexts(TestFeatureContext("context").withSubContextNames("subcontext"))
                .withFeatures(TestFeature("F1", enabled = false))
            )
            .withAllRightsKey("key")
        )
        .build()
      situation.changeFeatureStrategyForContext("tenant", "project", "context", "F1", true)
      val result    = situation.checkFeatures(
        key = "key",
        projects = Seq(situation.findProjectId("tenant", "project").get),
        contextPath = "context/subcontext"
      )

      (result.json.get \\ "active")
        .map(v => v.as[Boolean])
        .head mustBe true
    }

    "return all features for given project" in {
      val situation = TestSituationBuilder()
        .withTenants(
          TestTenant("tenant")
            .withApiKeys(TestApiKey("key", projects = Seq("project")))
            .withProjects(
              TestProject("project")
                .withFeatureNames(
                  "F1",
                  "F2",
                  "F3"
                )
            )
        )
        .build()

      val response = situation.checkFeatures(
        key = "key",
        projects = Seq(situation.findProjectId("tenant", "project").get)
      )

      response.json.get
        .as[Map[String, JsObject]]
        .values
        .map(obj => (obj \ "name").as[String]) must contain theSameElementsAs Seq("F1", "F2", "F3")

    }
    "return all features for given projects" in {
      val situation = TestSituationBuilder()
        .withTenants(
          TestTenant("tenant")
            .withApiKeys(TestApiKey("key", projects = Seq("project", "project2")))
            .withProjects(
              TestProject("project")
                .withFeatureNames(
                  "F1",
                  "F2",
                  "F3"
                ),
              TestProject("project2")
                .withFeatureNames(
                  "F21",
                  "F22",
                  "F23"
                )
            )
        )
        .build()

      val response = situation.checkFeatures(
        key = "key",
        projects = Seq("project", "project2").map(p => situation.findProjectId("tenant", p).get)
      )
      response.json.get
        .as[Map[String, JsObject]]
        .values
        .map(obj => (obj \ "name").as[String]) must contain theSameElementsAs Seq("F1", "F2", "F3", "F21", "F22", "F23")
    }
    "return all features for given projects filtered by all tags in" in {
      val situation = TestSituationBuilder()
        .withTenants(
          TestTenant("tenant")
            .withApiKeys(TestApiKey("key", projects = Seq("project", "project2")))
            .withTagNames("t1", "t2", "t3")
            .withProjects(
              TestProject("project")
                .withFeatures(
                  TestFeature("F1", tags = Seq("t1")),
                  TestFeature("F2", tags = Seq("t1", "t2", "t3")),
                  TestFeature("F3", tags = Seq("t1", "t2"))
                ),
              TestProject("project2")
                .withFeatures(
                  TestFeature("F21"),
                  TestFeature("F22", tags = Seq("t1", "t3")),
                  TestFeature("F23", tags = Seq("t1", "t2"))
                )
            )
        )
        .build()

      val response = situation.checkFeatures(
        key = "key",
        projects = Seq("project", "project2").map(p => situation.findProjectId("tenant", p).get),
        allTagsIn = Seq("t1", "t2").map(t => situation.findTagId("tenant", t).get)
      )
      response.json.get
        .as[Map[String, JsObject]]
        .values
        .map(obj => (obj \ "name").as[String]) must contain theSameElementsAs Seq("F2", "F3", "F23")
    }
    "return all features for given projects filtered by no tag in" in {
      val situation = TestSituationBuilder()
        .withTenants(
          TestTenant("tenant")
            .withApiKeys(TestApiKey("key", projects = Seq("project", "project2")))
            .withTagNames("t1", "t2", "t3")
            .withProjects(
              TestProject("project")
                .withFeatures(
                  TestFeature("F1", tags = Seq("t1")),
                  TestFeature("F2", tags = Seq("t1", "t2", "t3")),
                  TestFeature("F3", tags = Seq("t3"))
                ),
              TestProject("project2")
                .withFeatures(
                  TestFeature("F21"),
                  TestFeature("F22", tags = Seq("t1", "t3")),
                  TestFeature("F23", tags = Seq("t1", "t2"))
                )
            )
        )
        .build()

      val response = situation.checkFeatures(
        key = "key",
        projects = Seq("project", "project2").map(p => situation.findProjectId("tenant", p).get),
        noTagIn = Seq("t1", "t2").map(t => situation.findTagId("tenant", t).get)
      )
      response.json.get
        .as[Map[String, JsObject]]
        .values
        .map(obj => (obj \ "name").as[String]) must contain theSameElementsAs Seq("F21", "F3")
    }
    "return all features for given projects filtered by one tag in" in {
      val situation = TestSituationBuilder()
        .withTenants(
          TestTenant("tenant")
            .withApiKeys(TestApiKey("key", projects = Seq("project", "project2")))
            .withTagNames("t1", "t2", "t3")
            .withProjects(
              TestProject("project")
                .withFeatures(
                  TestFeature("F1", tags = Seq("t1")),
                  TestFeature("F2", tags = Seq("t1", "t2", "t3")),
                  TestFeature("F3", tags = Seq("t3"))
                ),
              TestProject("project2")
                .withFeatures(
                  TestFeature("F21"),
                  TestFeature("F22", tags = Seq("t2")),
                  TestFeature("F23", tags = Seq("t1", "t2"))
                )
            )
        )
        .build()

      val response = situation.checkFeatures(
        key = "key",
        projects = Seq("project", "project2").map(p => situation.findProjectId("tenant", p).get),
        oneTagIn = Seq("t1", "t3").map(t => situation.findTagId("tenant", t).get)
      )
      response.json.get
        .as[Map[String, JsObject]]
        .values
        .map(obj => (obj \ "name").as[String]) must contain theSameElementsAs Seq("F1", "F2", "F3", "F23")
    }
    "return specified features" in {
      val situation = TestSituationBuilder()
        .withTenants(
          TestTenant("tenant")
            .withApiKeys(TestApiKey("key", projects = Seq("project", "project2")))
            .withProjects(
              TestProject("project")
                .withFeatures(
                  TestFeature("F1"),
                  TestFeature("F2"),
                  TestFeature("F3")
                ),
              TestProject("project2")
                .withFeatures(
                  TestFeature("F21"),
                  TestFeature("F22"),
                  TestFeature("F23")
                )
            )
        )
        .build()

      val response = situation.checkFeatures(
        key = "key",
        features = Seq(
          situation.findFeatureId("tenant", "project", "F1").get,
          situation.findFeatureId("tenant", "project", "F3").get,
          situation.findFeatureId("tenant", "project2", "F21").get
        )
      )
      response.json.get
        .as[Map[String, JsObject]]
        .values
        .map(obj => (obj \ "name").as[String]) must contain theSameElementsAs Seq("F1", "F3", "F21")
    }
    "not filter specified features with given tags" in {
      val situation = TestSituationBuilder()
        .withTenants(
          TestTenant("tenant")
            .withApiKeys(TestApiKey("key", projects = Seq("project", "project2")))
            .withTagNames("t1", "t2")
            .withProjects(
              TestProject("project")
                .withFeatures(
                  TestFeature("F1", tags = Seq("t1")),
                  TestFeature("F2", tags = Seq("t2")),
                  TestFeature("F3", tags = Seq("t1", "t2"))
                ),
              TestProject("project2")
                .withFeatures(
                  TestFeature("F21"),
                  TestFeature("F22", tags = Seq("t2")),
                  TestFeature("F23", tags = Seq("t1", "t2"))
                )
            )
        )
        .build()

      val response = situation.checkFeatures(
        key = "key",
        features = Seq(
          situation.findFeatureId("tenant", "project", "F1").get,
          situation.findFeatureId("tenant", "project", "F3").get,
          situation.findFeatureId("tenant", "project2", "F21").get
        ),
        noTagIn = Seq("t1", "t2").map(t => situation.findTagId("tenant", t).get)
      )
      response.json.get
        .as[Map[String, JsObject]]
        .values
        .map(obj => (obj \ "name").as[String]) must contain theSameElementsAs Seq("F1", "F3", "F21")
    }
    "return correct activation for resulting feature" in {
      val situation = TestSituationBuilder()
        .withTenants(
          TestTenant("tenant")
            .withApiKeys(TestApiKey("key", projects = Seq("project")))
            .withTagNames("t1", "t2")
            .withProjects(
              TestProject("project")
                .withFeatures(
                  TestFeature(
                    "F1",
                    conditions = Set(
                      TestCondition(period =
                        TestDateTimePeriod(
                          begin = LocalDateTime.now().minusDays(1),
                          end = LocalDateTime.now().plusDays(3)
                        )
                      )
                    )
                  ),
                  TestFeature(
                    "F2",
                    conditions = Set(
                      TestCondition(period =
                        TestDateTimePeriod(
                          begin = LocalDateTime.now().plusDays(1),
                          end = LocalDateTime.now().plusDays(3)
                        )
                      )
                    )
                  ),
                  TestFeature(
                    "F3",
                    wasmConfig = TestWasmConfig(
                      name = "wasmScript",
                      source = Json.obj(
                        "kind" -> "Base64",
                        "path" -> enabledFeatureBase64,
                        "opts" -> Json.obj()
                      )
                    )
                  )
                )
            )
        )
        .build()

      val result = situation.checkFeatures("key", projects = Seq(situation.findProjectId("tenant", "project").get))

      result.json.get
        .as[Map[String, JsObject]]
        .values
        .map(obj => ((obj \ "name").as[String], (obj \ "active").as[Boolean])) must contain theSameElementsAs Seq(
        ("F1", true),
        ("F2", false),
        ("F3", true)
      )
    }
    "return correct activation for resulting feature with overload and context" in {
      val situation = TestSituationBuilder()
        .loggedInWithAdminRights()
        .withTenants(
          TestTenant("tenant")
            .withApiKeys(TestApiKey("key", projects = Seq("project")))
            .withTagNames("t1", "t2")
            .withProjects(
              TestProject("project")
                .withFeatures(
                  TestFeature(
                    "F1",
                    conditions = Set(
                      TestCondition(period =
                        TestDateTimePeriod(
                          begin = LocalDateTime.now().minusDays(1),
                          end = LocalDateTime.now().plusDays(3)
                        )
                      )
                    )
                  ),
                  TestFeature(
                    "F2",
                    conditions = Set(
                      TestCondition(period =
                        TestDateTimePeriod(
                          begin = LocalDateTime.now().plusDays(1),
                          end = LocalDateTime.now().plusDays(3)
                        )
                      )
                    )
                  ),
                  TestFeature(
                    "F3"
                  )
                )
                .withContexts(TestFeatureContext("context"))
            )
        )
        .build()

      situation.changeFeatureStrategyForContext(
        "tenant",
        "project",
        "context",
        "F1",
        enabled = true,
        wasmConfig = TestWasmConfig(
          name = "wasmScript",
          source = Json.obj(
            "kind" -> "Base64",
            "path" -> disabledFeatureBase64,
            "opts" -> Json.obj()
          )
        )
      )

      situation.changeFeatureStrategyForContext(
        "tenant",
        "project",
        "context",
        "F2",
        enabled = true,
        conditions = Set(
          TestCondition(period =
            TestDateTimePeriod(
              begin = LocalDateTime.now().minusDays(1),
              end = LocalDateTime.now().plusDays(3)
            )
          )
        )
      )

      situation.changeFeatureStrategyForContext(
        "tenant",
        "project",
        "context",
        "F3",
        enabled = false
      )

      val result = situation.checkFeatures(
        key = "key",
        projects = Seq(situation.findProjectId("tenant", "project").get),
        contextPath = "context"
      )

      result.json.get
        .as[Map[String, JsObject]]
        .values
        .map(obj => ((obj \ "name").as[String], (obj \ "active").as[Boolean])) must contain theSameElementsAs Seq(
        ("F1", false),
        ("F2", true),
        ("F3", false)
      )
    }
    "return correct activation for resulting feature with multiple subcontexts" in {
      val situation = TestSituationBuilder()
        .loggedInWithAdminRights()
        .withTenants(
          TestTenant("tenant")
            .withApiKeys(TestApiKey("key", projects = Seq("project")))
            .withTagNames("t1", "t2")
            .withProjects(
              TestProject("project")
                .withFeatures(
                  TestFeature(
                    "F1",
                    enabled = false
                  )
                )
            )
            .withGlobalContext(TestFeatureContext("global"))
        )
        .build()

      situation.createContext("tenant", "project", "local", "global")

      situation.changeFeatureStrategyForContext(
        "tenant",
        "project",
        "global",
        "F1",
        enabled = true,
        wasmConfig = TestWasmConfig(
          name = "wasmScript",
          source = Json.obj(
            "kind" -> "Base64",
            "path" -> disabledFeatureBase64,
            "opts" -> Json.obj()
          )
        )
      )

      situation.changeFeatureStrategyForContext(
        "tenant",
        "project",
        "global/local",
        "F1",
        enabled = true
      )

      val result = situation.checkFeatures(
        key = "key",
        projects = Seq(situation.findProjectId("tenant", "project").get),
        contextPath = "global/local"
      )

      result.json.get
        .as[Map[String, JsObject]]
        .values
        .map(obj => ((obj \ "name").as[String], (obj \ "active").as[Boolean])) must contain theSameElementsAs Seq(
        ("F1", true)
      )
    }

    "return correct activation for resulting feature width provided user" in {
      val situation = TestSituationBuilder()
        .loggedInWithAdminRights()
        .withTenants(
          TestTenant("tenant")
            .withApiKeys(TestApiKey("key", projects = Seq("project")))
            .withTagNames("t1", "t2")
            .withProjects(
              TestProject("project")
                .withFeatures(
                  TestFeature(
                    "F1",
                    enabled = true,
                    conditions = Set(
                      TestCondition(
                        rule = TestUserListRule(users = Set("foo"))
                      )
                    )
                  ),
                  TestFeature(
                    "F2",
                    enabled = true,
                    conditions = Set(
                      TestCondition(
                        rule = TestUserListRule(users = Set("bar"))
                      )
                    )
                  )
                )
            )
        )
        .build()

      val result = situation.checkFeatures(
        key = "key",
        projects = Seq(situation.findProjectId("tenant", "project").get),
        user = "foo"
      )

      result.json.get
        .as[Map[String, JsObject]]
        .values
        .map(obj => ((obj \ "name").as[String], (obj \ "active").as[Boolean])) must contain theSameElementsAs Seq(
        ("F1", true),
        ("F2", false)
      )
    }

    "return feature conditions if asked" in {
      val situation = TestSituationBuilder()
        .withTenants(
          TestTenant("tenant")
            .withApiKeys(TestApiKey("mykey", admin = true))
            .withProjects(
              TestProject("project")
                .withFeatures(
                  TestFeature(
                    "foo",
                    enabled = true,
                    conditions = Set(TestCondition(rule = TestPercentageRule(percentage = 75)))
                  ),
                  TestFeature(
                    "bar",
                    enabled = false,
                    conditions = Set(
                      TestCondition(
                        rule = TestUserListRule(users = Set("user1", "user2")),
                        period =
                          TestDateTimePeriod(days = TestDayPeriod(days = Set(DayOfWeek.MONDAY, DayOfWeek.TUESDAY)))
                      )
                    )
                  )
                )
                .withContexts(TestFeatureContext(
                  name="dev",
                  overloads = Seq(TestFeature("foo", enabled = true, conditions = Set(TestCondition(rule = TestPercentageRule(percentage = 80))))),
                  subContext = Set(
                    TestFeatureContext(
                      name="bar",
                      overloads = Seq(TestFeature("foo", enabled = true, conditions = Set(TestCondition(rule = TestPercentageRule(percentage = 90)))))
                  )
                )))
            )
        )
        .build()

      val fooId = situation.findFeatureId("tenant", "project", "foo").get
      val barId = situation.findFeatureId("tenant", "project", "bar").get

      val response = situation.checkFeatures(key = "mykey", conditions = true, features = Seq(fooId, barId))
      val json     = response.json.get

      (json \ fooId \ "conditions" \ "" \ "conditions" \ 0 \ "rule" \ "percentage").as[Int] mustEqual 75
      (json \ fooId \ "conditions" \ "dev" \ "conditions" \ 0 \ "rule" \ "percentage").as[Int] mustEqual 80
      (json \ fooId \ "conditions" \ "dev/bar" \ "conditions" \ 0 \ "rule" \ "percentage").as[Int] mustEqual 90
      (json \ barId \ "conditions" \ "" \ "conditions" \ 0 \ "rule" \ "users")
        .as[Seq[String]] must contain theSameElementsAs Seq("user1", "user2")
      (json \ barId \ "conditions" \ "" \ "conditions" \ 0 \ "period" \ "activationDays" \ "days")
        .as[Seq[String]] must contain theSameElementsAs Seq("MONDAY", "TUESDAY")
    }
  }

}
