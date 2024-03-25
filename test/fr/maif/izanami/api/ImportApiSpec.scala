package fr.maif.izanami.api

import fr.maif.izanami.api.BaseAPISpec.{TestFeature, TestProject, TestSituationBuilder, TestTenant, TestUser, getProjectsForTenant, shouldCleanUpWasmServer}
import play.api.http.Status.{ACCEPTED, FORBIDDEN, NOT_FOUND, NO_CONTENT, OK}
import play.api.libs.json.{JsNull, JsObject}

class ImportApiSpec extends BaseAPISpec {
  "Feature import" should {
    "import 'basic' feature" in {
      val situation = TestSituationBuilder()
        .withTenantNames("testtenant")
        .loggedInWithAdminRights()
        .build()

      val response = situation.importAndWaitTermination(tenant = "testtenant",
        features=Seq(
        """{"id":"project:foo:default-feature","enabled":false,"description":"An old default feature","parameters":{},"activationStrategy":"NO_STRATEGY"}""".stripMargin
        )
      )

      val project = situation.fetchProject("testtenant", "project")

      project.status mustBe OK
      val jsonFeatures = (project.json.get \ "features").as[Seq[JsObject]]
      jsonFeatures must have size 1
      val feature = jsonFeatures.head
      (feature \ "name").as[String] mustEqual "foo:default-feature"
      (feature \ "id").as[String] mustEqual "project:foo:default-feature"
      (feature \ "enabled").as[Boolean] mustBe false
      (feature \ "description").as[String] mustEqual "An old default feature"
    }

    "give admin right on imported project to current user" in {
      val situation = TestSituationBuilder()
        .withTenantNames("testtenant")
        .loggedInWithAdminRights()
        .build()

      situation.importAndWaitTermination(tenant = "testtenant",
        features = Seq(
          """{"id":"project:foo:default-feature","enabled":false,"description":"An old default feature","parameters":{},"activationStrategy":"NO_STRATEGY"}""".stripMargin
        )
      )

      (situation.fetchUserRights().json.get \ "rights" \ "tenants" \ "testtenant" \ "projects" \ "project" \ "level").as[String] mustEqual "Admin"
    }

    "import all types of non script features" in {
      val situation = TestSituationBuilder()
        .withTenantNames("testtenant")
        .loggedInWithAdminRights()
        .build()

      val response = situation.importAndWaitTermination(tenant = "testtenant",
        features = Seq(
          """{"id":"project:test:percentage-feature","enabled":true,"description":"An old style percentage feature","parameters":{"percentage":75},"activationStrategy":"PERCENTAGE"}""",
          """{"id":"project:test:date-range","enabled":true,"description":"An old style date range feature","parameters":{"from":"2023-01-01 00:00:00","to":"2023-12-31 23:59:59"},"activationStrategy":"DATE_RANGE"}""",
          """{"id":"project:another:customer-list-feature","enabled":true,"description":"An old style user list feature","parameters":{"customers":["foo","bar","baz"]},"activationStrategy":"CUSTOMERS_LIST"}""",
          """{"id":"project:baz:release-date","enabled":true,"description":"An old release date feature","parameters":{"releaseDate":"22/07/2023 14:18:11"},"activationStrategy":"RELEASE_DATE"}""",
          """{"id":"project:baz:hour-range-feature","enabled":true,"description":"An old style hour range feature","parameters":{"endAt":"18:00","startAt":"08:00"},"activationStrategy":"HOUR_RANGE"}"""
        )
      )

      val project = situation.fetchProject("testtenant", "project")

      project.status mustBe OK
      val jsonFeatures = (project.json.get \ "features").as[Seq[JsObject]]
      jsonFeatures must have size 5

      val byId = jsonFeatures.map(obj => ((obj \ "id").as[String], obj)).toMap
      (byId("project:test:percentage-feature") \ "conditions" \ "percentage").as[Int] mustEqual 75
      (byId("project:test:percentage-feature") \ "enabled").as[Boolean] mustBe true

      (byId("project:test:date-range") \ "conditions" \ "begin").as[String] mustEqual "2022-12-31T23:00:00Z"
      (byId("project:test:date-range") \ "conditions" \ "end").as[String] mustEqual "2023-12-31T22:59:59Z"
      (byId("project:test:date-range") \ "conditions" \ "timezone").as[String] mustEqual "Europe/Paris"
      (byId("project:test:date-range") \ "enabled").as[Boolean] mustBe true

      (byId("project:another:customer-list-feature") \ "conditions" \ "users").as[Seq[String]] must contain theSameElementsAs Seq("foo","bar","baz")
      (byId("project:another:customer-list-feature") \ "enabled").as[Boolean] mustBe true

      (byId("project:baz:release-date") \ "conditions" \ "begin").as[String] mustEqual "2023-07-22T12:18:11Z"
      (byId("project:baz:release-date") \ "conditions" \ "end").asOpt[String] mustBe None
      (byId("project:test:date-range") \ "conditions" \ "timezone").as[String] mustEqual "Europe/Paris"
      (byId("project:test:date-range") \ "enabled").as[Boolean] mustBe true

      (byId("project:baz:hour-range-feature") \ "conditions" \ "startTime").as[String] mustEqual "08:00:00"
      (byId("project:baz:hour-range-feature") \ "conditions" \ "endTime").as[String] mustEqual "18:00:00"
      (byId("project:baz:hour-range-feature") \ "conditions" \ "timezone").as[String] mustEqual "Europe/Paris"
      (byId("project:baz:hour-range-feature") \ "enabled").as[Boolean] mustBe true
    }


    "import all features in a single project if asked" in {
      val situation = TestSituationBuilder()
        .withTenantNames("testtenant")
        .loggedInWithAdminRights()
        .build()

      val response = situation.importAndWaitTermination(tenant = "testtenant",
        deduceProject=false,
        project="fifou",
        features = Seq(
          """{"id":"project:foo:default-feature","enabled":false,"description":"An old default feature","parameters":{},"activationStrategy":"NO_STRATEGY"}""".stripMargin,
          """{"id":"baz:foo","enabled":false,"description":"An old default feature","parameters":{},"activationStrategy":"NO_STRATEGY"}""".stripMargin,
          """{"id":"bar:another","enabled":false,"description":"An old default feature","parameters":{},"activationStrategy":"NO_STRATEGY"}""".stripMargin
        )
      )

      val project = situation.fetchProject("testtenant", "fifou")

      project.status mustBe OK
      val jsonFeatures = (project.json.get \ "features").as[Seq[JsObject]]
      jsonFeatures must have size 3
      jsonFeatures.map(js => (js \ "name").as[String]) must contain theSameElementsAs Seq("project:foo:default-feature", "baz:foo", "bar:another")
      jsonFeatures.map(js => (js \ "id").as[String]) must contain theSameElementsAs Seq("project:foo:default-feature", "baz:foo", "bar:another")
    }


    "build project name with given number of elements" in {
      val situation = TestSituationBuilder()
        .withTenantNames("testtenant")
        .loggedInWithAdminRights()
        .build()

      val response = situation.importAndWaitTermination(tenant = "testtenant",
        deduceProject = true,
        projectPartSize = Some(2),
        features = Seq(
          """{"id":"project:foo:default-feature","enabled":false,"description":"An old default feature","parameters":{},"activationStrategy":"NO_STRATEGY"}""".stripMargin,
          """{"id":"project:foo:default-feature2","enabled":false,"description":"An old default feature","parameters":{},"activationStrategy":"NO_STRATEGY"}""".stripMargin,
          """{"id":"baz:foo:lol","enabled":false,"description":"An old default feature","parameters":{},"activationStrategy":"NO_STRATEGY"}""".stripMargin,
          """{"id":"baz:another:bi","enabled":false,"description":"An old default feature","parameters":{},"activationStrategy":"NO_STRATEGY"}""".stripMargin
        )
      )

      val project = situation.fetchProject("testtenant", "project:foo")
      val jsonFeatures = (project.json.get \ "features").as[Seq[JsObject]]
      jsonFeatures must have size 2
      jsonFeatures.map(js => (js \ "name").as[String]) must contain theSameElementsAs Seq("default-feature", "default-feature2")
      jsonFeatures.map(js => (js \ "id").as[String]) must contain theSameElementsAs Seq("project:foo:default-feature", "project:foo:default-feature2")

      val project2 = situation.fetchProject("testtenant", "baz:foo")
      val jsonFeatures2 = (project2.json.get \ "features").as[Seq[JsObject]]
      jsonFeatures2 must have size 1
      jsonFeatures2.map(js => (js \ "name").as[String]) must contain theSameElementsAs Seq("lol")
      jsonFeatures2.map(js => (js \ "id").as[String]) must contain theSameElementsAs Seq("baz:foo:lol")

      val project3 = situation.fetchProject("testtenant", "baz:another")
      val jsonFeatures3 = (project3.json.get \ "features").as[Seq[JsObject]]
      jsonFeatures3 must have size 1
      jsonFeatures3.map(js => (js \ "name").as[String]) must contain theSameElementsAs Seq("bi")
      jsonFeatures3.map(js => (js \ "id").as[String]) must contain theSameElementsAs Seq("baz:another:bi")
    }

    "reject import query is project part is too long" in {
      val situation = TestSituationBuilder()
        .withTenantNames("testtenant")
        .loggedInWithAdminRights()
        .build()

      val response = situation.importAndWaitTermination(tenant = "testtenant",
        deduceProject = true,
        projectPartSize = Some(2),
        features = Seq(
          """{"id":"project:foo:default-feature","enabled":false,"description":"An old default feature","parameters":{},"activationStrategy":"NO_STRATEGY"}""".stripMargin,
          """{"id":"project:bar","enabled":false,"description":"An old default feature","parameters":{},"activationStrategy":"NO_STRATEGY"}""".stripMargin
        )
      )
      (response.json.get \ "status").as[String] mustEqual "Failed"
    }

    "allow to import feature with built-in script" in {
      val situation = TestSituationBuilder()
        .withTenantNames("testtenant")
        .loggedInWithAdminRights()
        .build()

      val response = situation.importAndWaitTermination(tenant = "testtenant",
        features = Seq(
          """{"id":"project:foo:script-feature","enabled":true,"description":"An old style inline script feature","parameters":{"type":"javascript","script":"function enabled(context, enabled, disabled, http) {\n  if (context.id === 'benjamin') {\n    return enabled();\n  }\n  return disabled();\n}"},"activationStrategy":"SCRIPT"}""".stripMargin
        ),
        inlineScript = false
      )

      val project = situation.fetchProject(tenant = "testtenant", projectId = "project")
      val jsonFeatures = (project.json.get \ "features").as[Seq[JsObject]]
      jsonFeatures must have size 1
      val first = jsonFeatures.head
      (first \ "wasmConfig" \ "name").as[String] mustEqual "project:foo:script-feature_script"
      (first \ "wasmConfig" \ "source" \ "kind").as[String] mustEqual "Wasmo"
      (first \ "wasmConfig" \ "source" \ "path").as[String] must not be null

      var testResponse = situation.testExistingFeature(tenant = "testtenant", featureId = "project:foo:script-feature", user = "foo")
      testResponse.status mustBe OK
      (testResponse.json.get \ "active").as[Boolean] mustBe false

      testResponse = situation.testExistingFeature(tenant = "testtenant", featureId = "project:foo:script-feature", user = "benjamin")
      testResponse.status mustBe OK
      (testResponse.json.get \ "active").as[Boolean] mustBe true
    }

    "fail import if conflict strategy is fail and given project already exists" in {
      val situation = TestSituationBuilder()
        .withTenants(TestTenant("testtenant").withProjectNames("fifou"))
        .loggedInWithAdminRights()
        .build()

      val response = situation.importAndWaitTermination(tenant = "testtenant",
        deduceProject = false,
        project = "fifou",
        features = Seq(
          """{"id":"project:foo:default-feature","enabled":false,"description":"An old default feature","parameters":{},"activationStrategy":"NO_STRATEGY"}""".stripMargin,
          """{"id":"baz:lalala","enabled":false,"description":"An old default feature","parameters":{},"activationStrategy":"NO_STRATEGY"}""".stripMargin
        )
      )
      (response.json.get \ "status").as[String] mustEqual "Failed"
    }

    "skip feature that already exists when skip strategy is chosen" in {
      val situation = TestSituationBuilder()
        .withTenants(TestTenant("testtenant").withProjects(TestProject("fifou").withFeatures(TestFeature("baz:lalala", enabled = true))))
        .loggedInWithAdminRights()
        .build()

      val response = situation.importAndWaitTermination(tenant = "testtenant",
        deduceProject = false,
        project = "fifou",
        conflictStrategy = "SKIP",
        features = Seq(
          """{"id":"baz:lalala","enabled":false,"description":"An old default feature","parameters":{},"activationStrategy":"NO_STRATEGY"}""".stripMargin
        )
      )

      val project = situation.fetchProject("testtenant", "fifou")

      project.status mustBe OK
      val jsonFeatures = (project.json.get \ "features").as[Seq[JsObject]]
      jsonFeatures must have size 1
      (jsonFeatures.head \ "enabled").as[Boolean] mustBe true
    }


    "fail import if fail strategy is chosen and importing a feature that already exists" in {
      val situation = TestSituationBuilder()
        .withTenants(TestTenant("testtenant").withProjects(TestProject("fifou").withFeatures(TestFeature("baz:lalala", enabled = true))))
        .loggedInWithAdminRights()
        .build()

      val response = situation.importAndWaitTermination(tenant = "testtenant",
        deduceProject = false,
        project = "fifou",
        conflictStrategy = "FAIL",
        features = Seq(
          """{"id":"baz:lalala","enabled":false,"description":"An old default feature","parameters":{},"activationStrategy":"NO_STRATEGY"}""".stripMargin
        )
      )
      (response.json.get \ "status").as[String] mustEqual "Failed"
    }

    "overwrite feature if overwrite strategy is chosen and feature already exists with different id" in {
      val situation = TestSituationBuilder()
        .withTenants(TestTenant("testtenant").withProjects(TestProject("fifou").withFeatures(TestFeature("baz:lalala", enabled = true))))
        .loggedInWithAdminRights()
        .build()

      val response = situation.importAndWaitTermination(tenant = "testtenant",
        deduceProject = false,
        project = "fifou",
        conflictStrategy = "OVERWRITE",
        features = Seq(
          """{"id":"baz:lalala","enabled":false,"description":"An old default feature","parameters":{},"activationStrategy":"NO_STRATEGY"}""".stripMargin
        )
      )

      (response.json.get \ "status").as[String] mustEqual "Success"

      val project = situation.fetchProject("testtenant", "fifou")
      project.status mustBe OK
      val jsonFeatures = (project.json.get \ "features").as[Seq[JsObject]]
      jsonFeatures must have size 1
      (jsonFeatures.head \ "enabled").as[Boolean] mustBe false
    }

    "overwrite feature if overwrite strategy is chosen and feature already exists with the same id" in {
      val situation = TestSituationBuilder()
        .withTenants(TestTenant("testtenant"))
        .loggedInWithAdminRights()
        .build()

      var response = situation.importAndWaitTermination(tenant = "testtenant",
        deduceProject = false,
        project = "fifou",
        conflictStrategy = "OVERWRITE",
        features = Seq(
          """{"id":"baz:lalala","enabled":false,"description":"An old default feature","parameters":{},"activationStrategy":"NO_STRATEGY"}""".stripMargin
        )
      )

      response = situation.importAndWaitTermination(tenant = "testtenant",
        deduceProject = false,
        project = "fifou",
        conflictStrategy = "OVERWRITE",
        features = Seq(
          """{"id":"baz:lalala","enabled":true,"description":"An old default feature","parameters":{},"activationStrategy":"NO_STRATEGY"}""".stripMargin
        )
      )

      val project = situation.fetchProject("testtenant", "fifou")
      project.status mustBe OK
      val jsonFeatures = (project.json.get \ "features").as[Seq[JsObject]]
      jsonFeatures must have size 1
      (jsonFeatures.head \ "enabled").as[Boolean] mustBe true
    }

    "prevent feature import for non admin user" in {
      val situation = TestSituationBuilder()
        .withTenants(TestTenant("testtenant"))
        .withUsers(TestUser("testu").withTenantReadWriteRight("testtenant"))
        .loggedAs("testu")
        .build()

      var response = situation.importV1Data(tenant = "testtenant",
        deduceProject = false,
        project = "fifou",
        conflictStrategy = "OVERWRITE",
        features = Seq(
          """{"id":"baz:lalala","enabled":false,"description":"An old default feature","parameters":{},"activationStrategy":"NO_STRATEGY"}""".stripMargin
        )
      )
      response.status mustBe FORBIDDEN
    }

    "allow feature import tenant admin user" in {
      val situation = TestSituationBuilder()
        .withTenants(TestTenant("testtenant"))
        .withUsers(TestUser("testu").withTenantAdminRight("testtenant"))
        .loggedAs("testu")
        .build()

      var response = situation.importV1Data(tenant = "testtenant",
        deduceProject = false,
        project = "fifou",
        conflictStrategy = "OVERWRITE",
        features = Seq(
          """{"id":"baz:lalala","enabled":false,"description":"An old default feature","parameters":{},"activationStrategy":"NO_STRATEGY"}""".stripMargin
        )
      )
      response.status mustBe ACCEPTED
    }
  }

  "User import" should {
    "Allow to import users" in {
      val situation = TestSituationBuilder()
        .withTenants(TestTenant("testtenant"))
        .loggedInWithAdminRights()
        .build()

      val response = situation.importAndWaitTermination(tenant = "testtenant",
        users = Seq(
          """{"id":"project-create-accredited-user","name":"pcau","email":"pcau@maif.fr","password":"199e0ada63510b01d4b752b872a56a7a57f70cd6ace36fb38683207308c88a3f0a417bffbb39c59098b56d541e84053e072174389ce27438f6902c6217384ba3","admin":false,"temporary":false,"authorizedPatterns":[{"pattern":"project:*","rights":["C","R"]}],"type":"Izanami"}""".stripMargin
        )
      )

      val userResponse = situation.fetchUser("project-create-accredited-user")

      userResponse.status mustBe OK
      val userJson = userResponse.json.get

      (userJson \ "username").as[String] mustEqual "project-create-accredited-user"
      (userJson \ "admin").as[Boolean] mustBe false
      (userJson \ "rights" \ "tenants" \ "testtenant"\ "level" ).as[String] mustEqual "Read"
      (userJson \ "email" ).as[String] mustEqual "pcau@maif.fr"
      (userJson \ "userType" ).as[String] mustEqual "INTERNAL"
    }

    "Give correct project right to imported users (C,R => Write)" in {
      val situation = TestSituationBuilder()
        .withTenants(TestTenant("testtenant"))
        .loggedInWithAdminRights()
        .build()


      val response = situation.importAndWaitTermination(tenant = "testtenant",
        deduceProject = false,
        project = "project",
        features = Seq(
          """{"id":"baz:lalala","enabled":false,"description":"An old default feature","parameters":{},"activationStrategy":"NO_STRATEGY"}""".stripMargin
        ),
        users = Seq(
          """{"id":"project-create-accredited-user","name":"pcau","email":"pcau@maif.fr","password":"199e0ada63510b01d4b752b872a56a7a57f70cd6ace36fb38683207308c88a3f0a417bffbb39c59098b56d541e84053e072174389ce27438f6902c6217384ba3","admin":false,"temporary":false,"authorizedPatterns":[{"pattern":"project:*","rights":["C","R"]}],"type":"Izanami"}""".stripMargin
        )
      )

      val userResponse = situation.fetchUser("project-create-accredited-user")

      userResponse.status mustBe OK
      val userJson = userResponse.json.get

      (userJson \ "rights" \ "tenants" \ "testtenant" \ "projects" \ "project" \ "level" ).as[String] mustEqual "Write"
    }

    "Give correct project right to imported users (R => Read)" in {
      val situation = TestSituationBuilder()
        .withTenants(TestTenant("testtenant"))
        .loggedInWithAdminRights()
        .build()


      val response = situation.importAndWaitTermination(tenant = "testtenant",
        deduceProject = false,
        project = "project",
        features = Seq(
          """{"id":"baz:lalala","enabled":false,"description":"An old default feature","parameters":{},"activationStrategy":"NO_STRATEGY"}""".stripMargin
        ),
        users = Seq(
          """{"id":"project-create-accredited-user","name":"pcau","email":"pcau@maif.fr","password":"199e0ada63510b01d4b752b872a56a7a57f70cd6ace36fb38683207308c88a3f0a417bffbb39c59098b56d541e84053e072174389ce27438f6902c6217384ba3","admin":false,"temporary":false,"authorizedPatterns":[{"pattern":"project:*","rights":["R"]}],"type":"Izanami"}""".stripMargin
        )
      )

      val userResponse = situation.fetchUser("project-create-accredited-user")

      userResponse.status mustBe OK
      val userJson = userResponse.json.get

      (userJson \ "rights" \ "tenants" \ "testtenant" \ "projects" \ "project" \ "level").as[String] mustEqual "Read"
    }

    "Give correct project right to imported users (C,R,U => Write)" in {
      val situation = TestSituationBuilder()
        .withTenants(TestTenant("testtenant"))
        .loggedInWithAdminRights()
        .build()


      val response = situation.importAndWaitTermination(tenant = "testtenant",
        deduceProject = false,
        project = "project",
        features = Seq(
          """{"id":"baz:lalala","enabled":false,"description":"An old default feature","parameters":{},"activationStrategy":"NO_STRATEGY"}""".stripMargin
        ),
        users = Seq(
          """{"id":"project-create-accredited-user","name":"pcau","email":"pcau@maif.fr","password":"199e0ada63510b01d4b752b872a56a7a57f70cd6ace36fb38683207308c88a3f0a417bffbb39c59098b56d541e84053e072174389ce27438f6902c6217384ba3","admin":false,"temporary":false,"authorizedPatterns":[{"pattern":"project:*","rights":["C","R","U"]}],"type":"Izanami"}""".stripMargin
        )
      )

      val userResponse = situation.fetchUser("project-create-accredited-user")

      userResponse.status mustBe OK
      val userJson = userResponse.json.get

      (userJson \ "rights" \ "tenants" \ "testtenant" \ "projects" \ "project" \ "level").as[String] mustEqual "Write"
    }

    "Give correct project right to imported users (C,R,U,D => Admin)" in {
      val situation = TestSituationBuilder()
        .withTenants(TestTenant("testtenant"))
        .loggedInWithAdminRights()
        .build()


      val response = situation.importAndWaitTermination(tenant = "testtenant",
        deduceProject = false,
        project = "project",
        features = Seq(
          """{"id":"baz:lalala","enabled":false,"description":"An old default feature","parameters":{},"activationStrategy":"NO_STRATEGY"}""".stripMargin
        ),
        users = Seq(
          """{"id":"project-create-accredited-user","name":"pcau","email":"pcau@maif.fr","password":"199e0ada63510b01d4b752b872a56a7a57f70cd6ace36fb38683207308c88a3f0a417bffbb39c59098b56d541e84053e072174389ce27438f6902c6217384ba3","admin":false,"temporary":false,"authorizedPatterns":[{"pattern":"project:*","rights":["C","R","U","D"]}],"type":"Izanami"}""".stripMargin
        )
      )

      val userResponse = situation.fetchUser("project-create-accredited-user")

      userResponse.status mustBe OK
      val userJson = userResponse.json.get

      (userJson \ "rights" \ "tenants" \ "testtenant" \ "projects" \ "project" \ "level").as[String] mustEqual "Admin"
    }

    "Allow to login as imported user" in {
      var situation = TestSituationBuilder()
        .withTenants(TestTenant("testtenant"))
        .loggedInWithAdminRights()
        .build()

      situation.importAndWaitTermination(tenant = "testtenant",
        users = Seq(
          """{"id":"pcau","name":"pcau","email":"pcau@maif.fr","password":"81da262dd4b4d531576925ab45dcdaad7f8d46c668b2bbbde414939402f01fe16223fc872a179a21bcfbc526ca43a7a972cb89befec69287bd5323a88c650957","admin":false,"temporary":false,"authorizedPatterns":[{"pattern":"project:*","rights":["C","R","U","D"]}],"type":"Izanami"}""".stripMargin
        )
      )

      situation = situation.logout().loggedAs("pcau", "benjamin")

      val rightResponse = situation.fetchUserRights()
      rightResponse.status mustBe OK
    }

    "Allow to import admin user as admin user" in {
      var situation = TestSituationBuilder()
        .withTenants(TestTenant("testtenant"))
        .loggedInWithAdminRights()
        .build()


      val response = situation.importAndWaitTermination(tenant = "testtenant",
        users = Seq(
          """{"id":"pcau","name":"pcau","email":"pcau@maif.fr","password":"81da262dd4b4d531576925ab45dcdaad7f8d46c668b2bbbde414939402f01fe16223fc872a179a21bcfbc526ca43a7a972cb89befec69287bd5323a88c650957","admin":true,"temporary":false,"authorizedPatterns":[{"pattern":"project:*","rights":["R"]}],"type":"Izanami"}""".stripMargin
        )
      )

      val userResponse = situation.fetchUser("pcau")

      userResponse.status mustBe OK
      val userJson = userResponse.json.get

      (userJson \ "admin").as[Boolean] mustEqual true
    }
  }

  "Key import" should {
    "allow to import a key" in {
      val situation = TestSituationBuilder()
        .withTenants(TestTenant("testtenant"))
        .loggedInWithAdminRights()
        .build()

      val response = situation.importAndWaitTermination(tenant = "testtenant",
        keys = Seq(
          """{"clientId":"yfsc5ooy3v3hu5z2","name":"local create read key","clientSecret":"sygl4ls9sjr93v1p9ufc7y8p83117w1f3t2p6nh8w15b7njfoz9er4sgjgabkxmw","authorizedPatterns":[{"pattern":"project:*","rights":["C","R"]}],"admin":false}""".stripMargin
        )
      )

      val keyResponse = situation.fetchAPIKeys("testtenant")
      val keysJson = keyResponse.json.get.as[Seq[JsObject]]

      keysJson must have size 1

      val keyJson = keysJson.head

      (keyJson \ "name").as[String] mustEqual "local create read key"
      (keyJson \ "clientId").as[String] mustEqual "yfsc5ooy3v3hu5z2"
      (keyJson \ "enabled").as[Boolean] mustBe true
    }

    "make current user admin of imported key" in {
      val situation = TestSituationBuilder()
        .withTenants(TestTenant("testtenant"))
        .loggedInWithAdminRights()
        .build()


      situation.importAndWaitTermination(tenant = "testtenant",
        keys = Seq(
          """{"clientId":"yfsc5ooy3v3hu5z2","name":"local create read key","clientSecret":"sygl4ls9sjr93v1p9ufc7y8p83117w1f3t2p6nh8w15b7njfoz9er4sgjgabkxmw","authorizedPatterns":[{"pattern":"project:*","rights":["C","R"]}],"admin":false}""".stripMargin
        )
      )

      (situation.fetchUserRights().json.get  \ "rights" \ "tenants" \ "testtenant" \ "keys" \ "local create read key" \ "level").as[String] mustEqual "Admin"
    }


    "import usable keys" in {
      val situation = TestSituationBuilder()
        .withTenantNames("testtenant")
        .loggedInWithAdminRights()
        .build()


      val response = situation.importAndWaitTermination(tenant = "testtenant",
        features = Seq(
          """{"id":"project:foo","enabled":true,"description":"An old default feature","parameters":{},"activationStrategy":"NO_STRATEGY"}""".stripMargin
        ),
        keys = Seq(
          """{"clientId":"yfsc5ooy3v3hu5z2","name":"local create read key","clientSecret":"sygl4ls9sjr93v1p9ufc7y8p83117w1f3t2p6nh8w15b7njfoz9er4sgjgabkxmw","authorizedPatterns":[{"pattern":"project:*","rights":["C","R"]}],"admin":false}""".stripMargin
        )
      )

      val pid = (situation.fetchProject(tenant="testtenant", projectId = "project").json.get \ "id").as[String]

      val result = situation.checkFeaturesWithRawKey(clientId="yfsc5ooy3v3hu5z2", clientSecret="sygl4ls9sjr93v1p9ufc7y8p83117w1f3t2p6nh8w15b7njfoz9er4sgjgabkxmw", projects = Seq(pid))
      result.status mustBe OK

      val activationResult = result.json.get

      (activationResult \ "project:foo" \ "active").as[Boolean] mustBe true
    }
  }

  "Script import" should {
    "Convert basic global js script to wasm script" in {
      val situation = TestSituationBuilder()
        .withTenantNames("testtenant")
        .loggedInWithAdminRights()
        .build()

      val response = situation.importAndWaitTermination(tenant = "testtenant",
        features = Seq(
          """{"id":"project:another:global-script-feature","enabled":true,"description":"A test global script feature","parameters":{"ref":"project:global-script"},"activationStrategy":"GLOBAL_SCRIPT"}""".stripMargin
        ),
        scripts = Seq(
          """{"id":"project:global-script","name":"A global script","description":"A test global script","source":{"type":"javascript","script":"/**\n * context:  a JSON object containing app specific value \n *           to evaluate the state of the feature\n * enabled:  a callback to mark the feature as active \n *           for this request\n * disabled: a callback to mark the feature as inactive \n *           for this request \n * http:     a http client\n */ \nfunction enabled(context, enabled, disabled, http) {\n  if (context.id === 'benjamin') {\n    return disabled();\n  }\n  return enabled();\n}"}}""".stripMargin
        ),
        inlineScript = false
      )

      val project = situation.fetchProject(tenant="testtenant", projectId="project")
      val jsonFeatures = (project.json.get \ "features").as[Seq[JsObject]]
      jsonFeatures must have size 1
      val first = jsonFeatures.head
      (first \ "wasmConfig" \ "name").as[String] mustEqual "project-global-script"
      (first \ "wasmConfig" \ "source" \ "kind").as[String] mustEqual "Wasmo"
      (first \ "wasmConfig" \ "source" \ "path").as[String] must not be null


      var testResponse = situation.testExistingFeature(tenant="testtenant", featureId="project:another:global-script-feature", user = "foo")
      testResponse.status mustBe OK
      (testResponse.json.get \ "active").as[Boolean] mustBe true

      testResponse = situation.testExistingFeature(tenant="testtenant", featureId="project:another:global-script-feature", user = "benjamin")
      testResponse.status mustBe OK
      (testResponse.json.get \ "active").as[Boolean] mustBe false
    }

    "Convert embeded js script to wasm script" in {
      val situation = TestSituationBuilder()
        .withTenantNames("testtenant")
        .loggedInWithAdminRights()
        .build()

      val response = situation.importAndWaitTermination(tenant = "testtenant",
        features = Seq(
          """{"id":"project:foo:script-feature","enabled":true,"description":"An old style inline script feature","parameters":{"type":"javascript","script":"/**\n * context:  a JSON object containing app specific value \n *           to evaluate the state of the feature\n * enabled:  a callback to mark the feature as active \n *           for this request\n * disabled: a callback to mark the feature as inactive \n *           for this request \n * http:     a http client\n */ \nfunction enabled(context, enabled, disabled, http) {\n  if (context.id === 'benjamin') {\n    return enabled();\n  }\n  return disabled();\n}"},"activationStrategy":"SCRIPT"}""".stripMargin
        ),
        inlineScript = false
      )

      val project = situation.fetchProject(tenant = "testtenant", projectId = "project")
      val jsonFeatures = (project.json.get \ "features").as[Seq[JsObject]]
      jsonFeatures must have size 1
      val first = jsonFeatures.head
      (first \ "wasmConfig" \ "name").as[String] mustEqual "project:foo:script-feature_script"
      (first \ "wasmConfig" \ "source" \ "kind").as[String] mustEqual "Wasmo"
      (first \ "wasmConfig" \ "source" \ "path").as[String] must not be null


      var testResponse = situation.testExistingFeature(tenant = "testtenant", featureId = "project:foo:script-feature", user = "foo")
      testResponse.status mustBe OK
      (testResponse.json.get \ "active").as[Boolean] mustBe false

      testResponse = situation.testExistingFeature(tenant = "testtenant", featureId = "project:foo:script-feature", user = "benjamin")
      testResponse.status mustBe OK
      (testResponse.json.get \ "active").as[Boolean] mustBe true
    }

    "Import script as embeded Base64 if asked" in {
      val situation = TestSituationBuilder()
        .withTenantNames("testtenant")
        .loggedInWithAdminRights()
        .build()

      val response = situation.importAndWaitTermination(tenant = "testtenant",
        features = Seq(
          """{"id":"project:foo:script-feature","enabled":true,"description":"An old style inline script feature","parameters":{"type":"javascript","script":"/**\n * context:  a JSON object containing app specific value \n *           to evaluate the state of the feature\n * enabled:  a callback to mark the feature as active \n *           for this request\n * disabled: a callback to mark the feature as inactive \n *           for this request \n * http:     a http client\n */ \nfunction enabled(context, enabled, disabled, http) {\n  if (context.id === 'benjamin') {\n    return enabled();\n  }\n  return disabled();\n}"},"activationStrategy":"SCRIPT"}""".stripMargin
        ),
        inlineScript=false
      )

      val project = situation.fetchProject(tenant = "testtenant", projectId = "project")
      val jsonFeatures = (project.json.get \ "features").as[Seq[JsObject]]
      jsonFeatures must have size 1
      val first = jsonFeatures.head
      (first \ "wasmConfig" \ "name").as[String] mustEqual "project:foo:script-feature_script"
      (first \ "wasmConfig" \ "source" \ "kind").as[String] mustEqual "Wasmo"
      (first \ "wasmConfig" \ "source" \ "path").as[String] must not be null


      var testResponse = situation.testExistingFeature(tenant = "testtenant", featureId = "project:foo:script-feature", user = "foo")
      testResponse.status mustBe OK
      (testResponse.json.get \ "active").as[Boolean] mustBe false

      testResponse = situation.testExistingFeature(tenant = "testtenant", featureId = "project:foo:script-feature", user = "benjamin")
      testResponse.status mustBe OK
      (testResponse.json.get \ "active").as[Boolean] mustBe true
    }

    "indicate incompatible scripts" in {
      val situation = TestSituationBuilder()
        .withTenantNames("testtenant")
        .loggedInWithAdminRights()
        .build()

      val response = situation.importAndWaitTermination(tenant = "testtenant",
        features = Seq(
          """{"id":"project:simple:feature","enabled":true,"parameters":{},"activationStrategy":"NO_STRATEGY"}""".stripMargin,
          """{"id":"project:js:feature:","enabled":true,"description":"feature with incompatible js script","parameters":{"type":"javascript","script":"/**\n * context:  a JSON object containing app specific value \n *           to evaluate the state of the feature\n * enabled:  a callback to mark the feature as active \n *           for this request\n * disabled: a callback to mark the feature as inactive \n *           for this request \n * http:     a http client\n */ \nfunction enabled(context, enabled, disabled, http) {\n  http(\"www.google.com\");\n  if (context.id === 'john.doe@gmail.com') {\n    return enabled();\n  }\n  return disabled();\n}"},"activationStrategy":"SCRIPT"}""".stripMargin,
          """{"id":"project:scala:feature","enabled":true,"parameters":{"type":"scala","script":"/**\n * context:  a play JSON object containing app specific value\n *           to evaluate the state of the feature\n * enabled:  a callback to mark the feature as active\n *           for this request\n * disabled: a callback to mark the feature as inactive\n *           for this request\n * http:     the play http client\n */\n def enabled(context: play.api.libs.json.JsObject,\n             enabled: () => Unit,\n             disabled: () => Unit,\n             http: play.api.libs.ws.WSClient)(implicit ec: ExecutionContext): Unit = {\n             \n    if ( (context \\ \"user\").asOpt[String].contains(\"benjamin\")) {\n      enabled()\n    } else {\n      disabled()\n    }\n    \n}"},"activationStrategy":"SCRIPT"}""".stripMargin
        ),
        inlineScript = true
      )

      val resp = response.json.get

      (resp \ "incompatibleScripts").as[Seq[String]] must not be empty
    }

    "transform feature associated to incompatible script to normal disabled features" in {
      val situation = TestSituationBuilder()
        .withTenantNames("testtenant")
        .loggedInWithAdminRights()
        .build()

      val response = situation.importAndWaitTermination(tenant = "testtenant",
        features = Seq(
          """{"id":"project:simple:feature","enabled":true,"parameters":{},"activationStrategy":"NO_STRATEGY"}""".stripMargin,
          """{"id":"project:js:feature:","enabled":true,"description":"feature with incompatible js script","parameters":{"type":"javascript","script":"/**\n * context:  a JSON object containing app specific value \n *           to evaluate the state of the feature\n * enabled:  a callback to mark the feature as active \n *           for this request\n * disabled: a callback to mark the feature as inactive \n *           for this request \n * http:     a http client\n */ \nfunction enabled(context, enabled, disabled, http) {\n  http(\"www.google.com\");\n  if (context.id === 'john.doe@gmail.com') {\n    return enabled();\n  }\n  return disabled();\n}"},"activationStrategy":"SCRIPT"}""".stripMargin,
          """{"id":"project:scala:feature","enabled":true,"parameters":{"type":"scala","script":"/**\n * context:  a play JSON object containing app specific value\n *           to evaluate the state of the feature\n * enabled:  a callback to mark the feature as active\n *           for this request\n * disabled: a callback to mark the feature as inactive\n *           for this request\n * http:     the play http client\n */\n def enabled(context: play.api.libs.json.JsObject,\n             enabled: () => Unit,\n             disabled: () => Unit,\n             http: play.api.libs.ws.WSClient)(implicit ec: ExecutionContext): Unit = {\n             \n    if ( (context \\ \"user\").asOpt[String].contains(\"benjamin\")) {\n      enabled()\n    } else {\n      disabled()\n    }\n    \n}"},"activationStrategy":"SCRIPT"}""".stripMargin
        ),
        inlineScript = true
      )

      val project = situation.fetchProject(tenant = "testtenant", projectId = "project")
      val jsonFeatures = (project.json.get \ "features").as[Seq[JsObject]]
      jsonFeatures must have size 3

      val wasmConfig = project.json.get \\ "wasmConfig"
      wasmConfig mustBe empty
    }
  }

  "import process" should {
    "return Accepted and an id as import query response" in {
      val situation = TestSituationBuilder()
        .withTenantNames("testtenant")
        .loggedInWithAdminRights()
        .build()

      val response = situation.importV1Data(tenant = "testtenant",
        features = Seq(
          """{"id":"project:foo:default-feature","enabled":false,"description":"An old default feature","parameters":{},"activationStrategy":"NO_STRATEGY"}""".stripMargin
        )
      )

      response.status mustBe ACCEPTED
      (response.json.get \ "id").as[String] must not be null
    }

    "return an object with pending status while import is pending and then return an object with success status" in {
      val situation = TestSituationBuilder()
        .withTenantNames("testtenant")
        .loggedInWithAdminRights()
        .build()

      val response = situation.importV1Data(tenant = "testtenant",
        features = Seq(
          """{"id":"project:another:global-script-feature","enabled":true,"description":"A test global script feature","parameters":{"ref":"project:global-script"},"activationStrategy":"GLOBAL_SCRIPT"}""".stripMargin
        ),
        scripts = Seq(
          """{"id":"project:global-script","name":"A global script","description":"A test global script","source":{"type":"javascript","script":"/**\n * context:  a JSON object containing app specific value \n *           to evaluate the state of the feature\n * enabled:  a callback to mark the feature as active \n *           for this request\n * disabled: a callback to mark the feature as inactive \n *           for this request \n * http:     a http client\n */ \nfunction enabled(context, enabled, disabled, http) {\n  if (context.id === 'benjamin') {\n    return disabled();\n  }\n  return enabled();\n}"}}""".stripMargin
        ),
        inlineScript = true
      )

      var checkResponse = situation.checkImportStatus("testtenant", response.id.get)
      (checkResponse.json.get \ "status").as[String] mustEqual "Pending"

      while((situation.checkImportStatus("testtenant", response.id.get).json.get \ "status").as[String] == "Pending") {
        Thread.sleep(200)
      }

      checkResponse = situation.checkImportStatus("testtenant", response.id.get)
      checkResponse.status mustBe OK
      (checkResponse.json.get \ "status").as[String] mustEqual "Success"
    }

    "return an object with failed status if import failed" in {
      val situation = TestSituationBuilder()
        .withTenantNames("testtenant")
        .loggedInWithAdminRights()
        .build()

      val response = situation.importV1Data(tenant = "testtenant",
        features = Seq(
          """{"foo": "bar"}""".stripMargin
        )
      )

      while ((situation.checkImportStatus("testtenant", response.id.get).json.get \ "status").as[String] == "Pending") {
        Thread.sleep(200)
      }

      val checkResponse = situation.checkImportStatus("testtenant", response.id.get)
      checkResponse.status mustBe OK
      (checkResponse.json.get \ "status").as[String] mustEqual "Failed"
    }

    "allow to delete import status" in {
      val situation = TestSituationBuilder()
        .withTenantNames("testtenant")
        .loggedInWithAdminRights()
        .build()

      val response = situation.importAndWaitTermination(tenant = "testtenant",
        features = Seq(
          """{"id":"project:foo:default-feature","enabled":false,"description":"An old default feature","parameters":{},"activationStrategy":"NO_STRATEGY"}""".stripMargin
        )
      )

      val deleteResponse = situation.deleteImportResult("testtenant", response.id.get)
      deleteResponse.status mustBe NO_CONTENT

      val check = situation.checkImportStatus("testtenant", response.id.get)
      check.status mustBe NOT_FOUND
    }
  }

}
