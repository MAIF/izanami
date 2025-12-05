package fr.maif.izanami.api

import fr.maif.izanami.api.BaseAPISpec._
import play.api.http.Status._
import play.api.libs.json.JsArray

class ProjectAPISpec extends BaseAPISpec {
  "Project GET endpoint" should {
    "allow to retrieve features for the given project" in {
      val tenantName = "my-tenant"
      val projectName = "my-project"

      val testSituation = TestSituationBuilder()
        .withTenants(
          TestTenant(tenantName)
            .withProjects(
              TestProject(projectName)
                .withFeatures(TestFeature("feature-name1"))
                .withFeatures(TestFeature("feature-name2"))
                .withFeatures(TestFeature("feature-name3"))
            )
        )
        .loggedInWithAdminRights()
        .build()

      val fetchResponse = testSituation.fetchProject(tenantName, projectName)

      fetchResponse.status mustBe OK
      val project = fetchResponse.json.get
      val features = project \ "features"
      (features
        .as[JsArray]
        .value
        .map(v => (v \ "name").as[String])) must contain theSameElementsAs Seq(
        "feature-name1",
        "feature-name2",
        "feature-name3"
      )
    }

    "Prevent feature read if user has not right on the project" in {
      val situation = TestSituationBuilder()
        .withTenants(
          TestTenant("my-tenant").withProjects(
            TestProject("my-project")
              .withFeatures(TestFeature("feature-name1"))
              .withFeatures(TestFeature("feature-name2"))
              .withFeatures(TestFeature("feature-name3"))
          )
        )
        .withUsers(TestUser("foo").withTenantReadWriteRight("my-tenant"))
        .loggedAs("foo")
        .build()

      situation.fetchProject("mytenant", "my-project").status mustBe NOT_FOUND
    }

    "Allow feature read if user has not right on the project but is tenant admin" in {
      val situation = TestSituationBuilder()
        .withTenants(
          TestTenant("my-tenant").withProjects(
            TestProject("my-project")
              .withFeatures(TestFeature("feature-name1"))
              .withFeatures(TestFeature("feature-name2"))
              .withFeatures(TestFeature("feature-name3"))
          )
        )
        .withUsers(TestUser("foo").withTenantAdminRight("my-tenant"))
        .loggedAs("foo")
        .build()

      val fetchResponse = situation.fetchProject("my-tenant", "my-project")

      fetchResponse.status mustBe OK
      val project = fetchResponse.json.get
      val features = project \ "features"
      (features
        .as[JsArray]
        .value
        .map(v => (v \ "name").as[String])) must contain theSameElementsAs Seq(
        "feature-name1",
        "feature-name2",
        "feature-name3"
      )
    }
  }

  "Project DELETE endpoint" should {
    "Delete project with personnal access token with DELETE PROJECT right" in {
      val situation = TestSituationBuilder()
        .withTenants(TestTenant("testtenant").withProjectNames("ppp"))
        .withPersonnalAccessToken(
          TestPersonnalAccessToken(
            "foo",
            allRights = false,
            rights = Map("testtenant" -> Set("DELETE PROJECT"))
          )
        )
        .loggedInWithAdminRights()
        .build()

      val secret = situation.findTokenSecret(situation.user, "foo");

      val res =
        deleteProjectWithToken(tenant = "testtenant", project = "ppp", username=situation.user, token=secret)
      res.status mustBe NO_CONTENT
    }
    
    "Delete project with local overloads" in {
      val situation = TestSituationBuilder()
        .withTenants(
          TestTenant("my-tenant").withProjects(
            TestProject("proj").withContexts(TestFeatureContext("localctx"))
          )
        )
        .loggedInWithAdminRights()
        .build()

      val response = situation.deleteProject("proj", "my-tenant")
      response.status mustBe NO_CONTENT
    }

    "Delete project if user is project admin" in {
      // FIXME
      val situation = TestSituationBuilder()
        .withTenants(
          TestTenant("my-tenant").withProjectNames("my-project")
        )
        .withUsers(
          TestUser("foo")
            .withTenantReadRight("my-tenant")
            .withProjectAdminRight("my-project", "my-tenant")
        )
        .loggedAs("foo")
        .build()

      val response = situation.deleteProject("my-project", "my-tenant")

      situation.fetchProject("my-tenant", "my-project").status mustBe FORBIDDEN

      response.status mustBe NO_CONTENT
    }

    "Prevent project suppression if user is not project admin" in {
      val situation = TestSituationBuilder()
        .withTenants(
          TestTenant("my-tenant").withProjectNames("my-project")
        )
        .withUsers(
          TestUser("foo")
            .withTenantReadRight("my-tenant")
            .withProjectReadWriteRight("my-project", "my-tenant")
        )
        .loggedAs("foo")
        .build()

      val response = situation.deleteProject("my-project", "my-tenant")

      situation.fetchProject("my-tenant", "my-project").status mustBe OK

      response.status mustBe FORBIDDEN
    }

    "Return 403 if project does not exist" in {
      val situation = TestSituationBuilder()
        .withTenants(
          TestTenant("my-tenant")
        )
        .withUsers(TestUser("foo").withTenantAdminRight("my-tenant"))
        .loggedAs("foo")
        .build()

      val response = situation.deleteProject("my-project", "my-tenant")

      response.status mustBe FORBIDDEN
    }
  }

  "project GET endpoint without name" should {
    "return all projects for tenant" in {
      val situation = TestSituationBuilder()
        .loggedInWithAdminRights()
        .withTenants(
          TestTenant("my-tenant")
            .withProjectNames("project-1", "project-2", "project-3")
        )
        .build()

      val result = situation.fetchProjects("my-tenant")

      result.status mustBe OK
      (result.json.get
        .as[JsArray])
        .value
        .map(v => (v \ "name").as[String]) must contain theSameElementsAs Seq(
        "project-1",
        "project-2",
        "project-3"
      )
    }

    "return all projects for tenant for which user is accredited" in {
      val situation = TestSituationBuilder()
        .withTenants(
          TestTenant("my-tenant")
            .withProjectNames("project-1", "project-2", "project-3")
        )
        .withUsers(
          TestUser("tuser")
            .withTenantReadRight("my-tenant")
            .withProjectReadRight("project-1", "my-tenant")
            .withProjectReadRight("project-2", "my-tenant")
        )
        .loggedAs("tuser")
        .build()

      val result = situation.fetchProjects("my-tenant")

      result.status mustBe OK
      (result.json.get
        .as[JsArray])
        .value
        .map(v => (v \ "name").as[String]) must contain theSameElementsAs Seq(
        "project-1",
        "project-2"
      )
    }

    "return all tenants projects if user is admin" in {
      val situation = TestSituationBuilder()
        .withTenants(
          TestTenant("my-tenant")
            .withProjectNames("project-1", "project-2", "project-3")
        )
        .withUsers(TestUser("tuser").withTenantAdminRight("my-tenant"))
        .loggedAs("tuser")
        .build()

      val result = situation.fetchProjects("my-tenant")

      result.status mustBe OK
      (result.json.get
        .as[JsArray])
        .value
        .map(v => (v \ "name").as[String]) must contain theSameElementsAs Seq(
        "project-1",
        "project-2",
        "project-3"
      )
    }
  }

  "projects POST endpoint" should {
    "prevent project creation with too long fields" in {
      val tenantName = "my-tenant"
      val testSituation = TestSituationBuilder()
        .withTenantNames(tenantName)
        .loggedInWithAdminRights()
        .build()

      var projectResponse = testSituation.createProject(
        name = "abcdefghij" * 21,
        tenant = tenantName
      )
      projectResponse.status mustBe BAD_REQUEST

      projectResponse = testSituation.createProject(
        name = "abcdefghij",
        description = "abcdefghij" * 51,
        tenant = tenantName
      )
      projectResponse.status mustBe BAD_REQUEST
    }

    "allow to create new project" in {
      val tenantName = "my-tenant"
      val testSituation = TestSituationBuilder()
        .withTenantNames(tenantName)
        .loggedInWithAdminRights()
        .build()

      val projectResponse =
        testSituation.createProject(name = "my-project", tenant = tenantName)

      projectResponse.status mustBe CREATED
      val response = projectResponse.json.get
      (response \ "name").as[String] mustEqual "my-project"
    }

    "make creator admin of created project" in {
      val tenantName = "my-tenant"
      val testSituation = TestSituationBuilder()
        .withTenantNames(tenantName)
        .withUsers(TestUser("testu").withTenantReadWriteRight("my-tenant"))
        .loggedAs("testu")
        .build()

      val projectResponse =
        testSituation.createProject(name = "my-project", tenant = tenantName)

      projectResponse.status mustBe CREATED
      val rightResponse = testSituation.fetchUserRights()
      (rightResponse.json.get \ "rights" \ "tenants" \ "my-tenant" \ "projects" \ "my-project" \ "level")
        .as[String] mustEqual "Admin"
    }

    "allow to create new project with description" in {
      val tenantName = "my-tenant"
      val testSituation = TestSituationBuilder()
        .loggedInWithAdminRights()
        .withTenantNames(tenantName)
        .build()
      val description = "my-description"
      val projectResponse =
        testSituation.createProject(
          name = "my-project",
          tenant = tenantName,
          description = description
        )

      projectResponse.status mustBe CREATED
      val response = projectResponse.json.get
      (response \ "name").as[String] mustEqual "my-project"
      (response \ "description").as[String] mustEqual description
    }

    "prevent project creation if tenant not exists" in {
      val testSituation = TestSituationBuilder()
        .loggedInWithAdminRights()
        .build()
      val projectResponse =
        testSituation.createProject(name = "my-project", tenant = "foo")

      projectResponse.status mustBe NOT_FOUND
    }

    "prevent project creation if it already exists" in {
      val tenantName = "my-tenant"
      val testSituation = TestSituationBuilder()
        .loggedInWithAdminRights()
        .withTenantNames(tenantName)
        .build()
      testSituation.createProject(name = "my-project", tenant = tenantName)
      val projectResponse =
        testSituation.createProject(name = "my-project", tenant = tenantName)

      projectResponse.status mustBe BAD_REQUEST
    }

    "prevent project creation if user does not have write right on tenant" in {
      val tenantName = "my-tenant"
      val testSituation = TestSituationBuilder()
        .withUsers(
          TestUser("test-user", "barbar123").withTenantReadRight(tenantName)
        )
        .loggedAs("test-user")
        .withTenantNames(tenantName)
        .build()
      val projectResponse =
        testSituation.createProject(name = "my-project", tenant = tenantName)

      projectResponse.status mustBe FORBIDDEN
    }

    "allow project creation if user has write right on tenant" in {
      val tenantName = "my-tenant"
      val testSituation = TestSituationBuilder()
        .withUsers(
          TestUser("test-user", "barbar123").withTenantReadWriteRight(
            tenantName
          )
        )
        .loggedAs("test-user")
        .withTenantNames(tenantName)
        .build()
      val projectResponse =
        testSituation.createProject(name = "my-project", tenant = tenantName)

      projectResponse.status mustBe CREATED
    }
  }

  "Project PUT endpoint" should {
    "prevent project update with too long fields" in {
      val situation = TestSituationBuilder()
        .withTenants(
          TestTenant("tenant").withProjectNames("project")
        )
        .loggedInWithAdminRights()
        .build()

      var response =
        situation.updateProject("tenant", "project", "abcdefghij" * 21)
      response.status mustBe BAD_REQUEST

      response = situation.updateProject(
        "tenant",
        "project",
        "project",
        "abcdefghij" * 51
      )
      response.status mustBe BAD_REQUEST
    }

    "allow to update project name" in {
      val situation = TestSituationBuilder()
        .withTenants(
          TestTenant("tenant").withProjectNames("project")
        )
        .loggedInWithAdminRights()
        .build()

      val response = situation.updateProject("tenant", "project", "newProject")
      response.status mustBe NO_CONTENT

      val newResponse = situation.fetchProject("tenant", "newProject")
      newResponse.status mustBe OK
      (newResponse.json.get \ "name").as[String] mustEqual "newProject"
    }

    "allow to update project description" in {
      val situation = TestSituationBuilder()
        .withTenants(
          TestTenant("tenant").withProjectNames("project")
        )
        .loggedInWithAdminRights()
        .build()

      val response = situation.updateProject(
        "tenant",
        "project",
        description = "new description"
      )
      response.status mustBe NO_CONTENT

      val newResponse = situation.fetchProject("tenant", "project")
      newResponse.status mustBe OK
      (newResponse.json.get \ "description")
        .as[String] mustEqual "new description"
    }

    "reject modification request if user is not project admin" in {
      val situation = TestSituationBuilder()
        .withTenants(
          TestTenant("tenant").withProjectNames("project")
        )
        .withUsers(
          TestUser(username = "foo")
            .withTenantReadRight("tenant")
            .withProjectReadWriteRight("project", tenant = "tenant")
        )
        .loggedAs("foo")
        .build()

      val response = situation.updateProject(
        "tenant",
        "project",
        description = "new description"
      )
      response.status mustBe FORBIDDEN
    }
  }
}
