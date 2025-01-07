package fr.maif.izanami.api

import fr.maif.izanami.api.BaseAPISpec._
import play.api.http.Status._
import play.api.libs.json.JsArray

class ApplicationKeysAPISpec extends BaseAPISpec {
  "API key POST endpoint" should {
    "prevent api key creation if name or description is too long" in {
      val situation   = TestSituationBuilder()
        .loggedInWithAdminRights()
        .withTenantNames("my-tenant")
        .build()

      var response    =
        situation.createAPIKey(tenant = "my-tenant", name = "abcdefghij" * 21, description = "my long description")
      response.status mustBe BAD_REQUEST

      response    =
        situation.createAPIKey(tenant = "my-tenant", name = "abcdefghij", description = "abcdefghij" * 51)
      response.status mustBe BAD_REQUEST
    }

    "allow to create API key" in {
      val situation   = TestSituationBuilder()
        .loggedInWithAdminRights()
        .withTenantNames("my-tenant")
        .build();
      val description = "my long description"
      val response    =
        situation.createAPIKey(tenant = "my-tenant", name = "my-api-key", description = "my long description")

      response.status mustBe CREATED
      (response.json.get \ "name").get.as[String] mustEqual "my-api-key"
      (response.json.get \ "clientId").get.as[String] must startWith("my-tenant")
      (response.json.get \ "description").get.as[String] mustEqual description
    }

    "allow to create admin API key" in {
      val situation = TestSituationBuilder().withTenantNames("tenant").loggedInWithAdminRights().build()

      val response = situation.createAPIKey("tenant", "my-key", admin=true)
      response.status mustBe CREATED
      val keyJson = situation.fetchAPIKeys("tenant").json.get

      (keyJson \\ "admin").head.as[Boolean] mustBe true
    }

    "allow to create API key with authorized projects" in {
      val situation = TestSituationBuilder()
        .loggedInWithAdminRights()
        .withTenants(TestTenant("my-tenant").withProjectNames("my-project", "my-project2"))
        .build();

      val response =
        situation.createAPIKey(tenant = "my-tenant", name = "my-api-key", projects = Seq("my-project", "my-project2"))

      (response.json.get \ "projects").as[JsArray].value.map(v => v.as[String]) must contain theSameElementsAs Seq(
        "my-project",
        "my-project2"
      )
    }

    "prevent admin API key creation if user is not tenant admin" in {
      val situation = TestSituationBuilder().withTenantNames("tenant")
        .withUsers(TestUser("testu").withTenantReadWriteRight("tenant"))
        .loggedAs("testu")
        .build()

      val response = situation.createAPIKey("tenant", name="my-key", admin=true)

      response.status mustBe FORBIDDEN
    }

    "prevent api key creation if one of the specified project does not exist" in {
      val situation = TestSituationBuilder()
        .loggedInWithAdminRights()
        .withTenants(TestTenant("my-tenant").withProjectNames("my-project"))
        .build();

      val response =
        situation.createAPIKey(tenant = "my-tenant", name = "my-api-key", projects = Seq("my-project", "my-project2"))
      response.status mustBe BAD_REQUEST
    }

    "prevent key creation if name is empty" in {
      val situation = TestSituationBuilder()
        .loggedInWithAdminRights()
        .withTenants(TestTenant("my-tenant").withTagNames("my-tag", "my-tag2"))
        .build();

      val response = situation.createAPIKey(tenant = "my-tenant", name = "")
      response.status mustBe BAD_REQUEST
    }

    "prevent key creation if user is not authenticated for project" in {
      val situation = TestSituationBuilder()
        .withUsers(
          TestUser("test-u")
            .withTenantReadWriteRight("my-tenant")
            .withProjectReadWriteRight("project1", "my-tenant")
        )
        .withTenants(
          TestTenant("my-tenant")
            .withProjectNames("project1", "project2")
        )
        .loggedAs("test-u")
        .build();

      val result = situation.createAPIKey("my-tenant", "my-key", projects = Seq("project1", "project2"))
      result.status mustBe FORBIDDEN
    }

    "make creator admin of created key" in {
      val situation = TestSituationBuilder()
        .withTenantNames("my-tenant")
        .withUsers(TestUser("testu").withTenantReadWriteRight("my-tenant"))
        .loggedAs("testu")
        .build();

      val description = "my long description"
      val response =
        situation.createAPIKey(tenant = "my-tenant", name = "my-api-key", description = "my long description")

      response.status mustBe CREATED

      val rightResponse = situation.fetchUserRights()
      (rightResponse.json.get \ "rights" \ "tenants" \ "my-tenant" \ "keys" \ "my-api-key" \ "level").as[String] mustEqual "Admin"
    }
  }

  "API Key GET endpoint" should {
    "return all API keys for given tenant" in {
      val situation = TestSituationBuilder()
        .loggedInWithAdminRights()
        .withTenants(
          TestTenant("my-tenant")
            .withProjectNames("project-1", "project-2")
            .withApiKeys(
              TestApiKey(name = "my-key", projects = Seq("project-1", "project-2")),
              TestApiKey(name = "my-key2", projects = Seq("project-1", "project-2"))
            )
        )
        .build()

      val result = situation.fetchAPIKeys(tenant = "my-tenant")

      result.status mustBe OK
      (result.json.get \\ "name").map(v => v.as[String]) must contain theSameElementsAs Seq("my-key", "my-key2")
      (result.json.get \\ "projects").flatMap(v => v.as[Seq[String]]) must contain theSameElementsAs Seq(
        "project-1",
        "project-2",
        "project-1",
        "project-2"
      )
    }

    "return only authorized keys" in {
      val situation = TestSituationBuilder()
        .withUsers(TestUser("test-u").withTenantReadRight("my-tenant").withApiKeyReadRight("my-key", "my-tenant"))
        .withTenants(
          TestTenant("my-tenant")
            .withProjectNames("project-1", "project-2")
            .withApiKeys(
              TestApiKey(name = "my-key", projects = Seq("project-1", "project-2")),
              TestApiKey(name = "my-key2", projects = Seq("project-1", "project-2"))
            )
        )
        .loggedAs("test-u")
        .build()

      val result = situation.fetchAPIKeys(tenant = "my-tenant")

      result.status mustBe OK
      (result.json.get \\ "name").map(v => v.as[String]) must contain theSameElementsAs Seq("my-key")
    }
  }

  "API key DELETE endpoint" should {
    "delete given API key" in {
      val situation = TestSituationBuilder()
        .withUsers(TestUser(username = "admin", admin = true, password = "barfoofoo"))
        .withTenants(
          TestTenant("my-tenant")
            .withProjectNames("project1")
            .withApiKeys(
              TestApiKey(name = "key1", projects = Seq("project1"), description = "foo"),
              TestApiKey(name = "key2", projects = Seq("project1"), description = "foo")
            )
        )
        .loggedAs("admin")
        .build()

      val response    = situation.deleteAPIKey("my-tenant", "key1", "barfoofoo")
      val keyResponse = situation.fetchAPIKeys("my-tenant")

      response.status mustBe NO_CONTENT
      keyResponse.json.get.as[JsArray].value must have size 1
      (keyResponse.json.get \\ "name").map(v => v.as[String]) must contain theSameElementsAs Seq("key2")

    }

    "return 404 on non existing key" in {
      val situation = TestSituationBuilder()
        .withUsers(TestUser(username = "admin", admin = true, password = "barfoofoo"))
        .withTenants(
          TestTenant("my-tenant")
            .withProjectNames("project1")
            .withApiKeys(
              TestApiKey(name = "key1", projects = Seq("project1"), description = "foo")
            )
        )
        .loggedAs("admin")
        .build()

      val response = situation.deleteAPIKey("my-tenant", "key2", "barfoofoo" )

      response.status mustBe NOT_FOUND
    }

    "prevent key suppression if user is not admin on the key" in {
      val situation = TestSituationBuilder()
        .withUsers(TestUser("test-u").withTenantReadRight("my-tenant").withApiKeyReadWriteRight("key1", "my-tenant"))
        .withTenants(
          TestTenant("my-tenant")
            .withProjectNames("project1")
            .withApiKeys(
              TestApiKey(name = "key1", projects = Seq("project1"))
            )
        )
        .loggedAs("test-u")
        .build()

      val response = situation.deleteAPIKey("my-tenant", "key1", "barbar123")

      response.status mustBe FORBIDDEN
    }

    "prevent key suppression if user password is not valid" in {

      val situation = TestSituationBuilder()
        .withUsers(TestUser(username = "admin", admin = true, password = "barfoofoo"))
        .withTenants(
          TestTenant("my-tenant")
            .withProjectNames("project1")
            .withApiKeys(
              TestApiKey(name = "key1", projects = Seq("project1"))
            )
        )
        .loggedAs("admin")
        .build()

      val response = situation.deleteAPIKey("my-tenant", "key1", "barbar123")

      response.status mustBe  UNAUTHORIZED
    }

    "allow key suppression from key creator (since it should be made admin by creation)" in {
      val situation = TestSituationBuilder()
        .withUsers(TestUser("test-u").withTenantReadWriteRight("my-tenant").withProjectReadWriteRight("project1", "my-tenant"))
        .withTenants(
          TestTenant("my-tenant")
            .withProjectNames("project1")
        )
        .loggedAs("test-u")
        .build()

      situation.createAPIKey("my-tenant", "key1", projects = Seq("project1"))
      val response = situation.deleteAPIKey("my-tenant", "key1", "barbar123")

      response.status mustBe NO_CONTENT
    }
  }

  "API key PUT endpoint" should {
    "prevent key update if name or description is too long" in {
      val situation = TestSituationBuilder()
        .loggedInWithAdminRights()
        .withTenants(
          TestTenant(name = "my-tenant")
            .withProjectNames("project1")
            .withApiKeys(TestApiKey("my-key", projects = Seq("project1")))
        )
        .build()

      var result = situation.updateAPIKey(
        tenant = "my-tenant",
        currentName = "my-key",
        projects = Seq("project1"),
        description = "",
        enabled = false,
        admin=false,
        newName = "abcdefghij" * 21
      )
      result.status mustBe BAD_REQUEST

      result = situation.updateAPIKey(
        tenant = "my-tenant",
        currentName = "my-key",
        projects = Seq("project1"),
        description = "abcdefghij" * 51,
        enabled = false,
        admin=false,
        newName = "abcdefghij"
      )

      result.status mustBe BAD_REQUEST
    }

    "allow to disable a key" in {
      val situation = TestSituationBuilder()
        .loggedInWithAdminRights()
        .withTenants(
          TestTenant(name = "my-tenant")
            .withProjectNames("project1")
            .withApiKeys(TestApiKey("my-key", projects = Seq("project1")))
        )
        .build()

      val result = situation.updateAPIKey(
        tenant = "my-tenant",
        currentName = "my-key",
        projects = Seq("project1"),
        description = "",
        enabled = false,
        admin=false
      )

      result.status mustBe NO_CONTENT

      val fetch = situation.fetchAPIKeys("my-tenant")
      ((fetch.json.get.as[JsArray]).value.head \ "enabled").as[Boolean] mustEqual false
    }

    "allow to update key description" in {
      val situation = TestSituationBuilder()
        .loggedInWithAdminRights()
        .withTenants(
          TestTenant(name = "my-tenant")
            .withProjectNames("project1", "project2")
            .withApiKeys(TestApiKey("my-key", projects = Seq("project1", "project2"), description = "Foo"))
        )
        .build()

      val result = situation.updateAPIKey(
        "my-tenant",
        currentName = "my-key",
        projects = Seq("project1", "project2"),
        description = "Bar",
        enabled=true,
        admin=false
      )

      result.status mustBe NO_CONTENT

      val fetch = situation.fetchAPIKeys("my-tenant")
      ((fetch.json.get.as[JsArray]).value.head \ "description").as[String] mustEqual "Bar"
    }

    "allow to update key name" in {
      val situation = TestSituationBuilder()
        .loggedInWithAdminRights()
        .withTenants(
          TestTenant(name = "my-tenant")
            .withProjectNames("project1", "project2")
            .withApiKeys(TestApiKey("my-key", projects = Seq("project1", "project2"), description = "Foo"))
        )
        .build()

      val result = situation.updateAPIKey(
        "my-tenant",
        currentName = "my-key",
        newName = "my-key2",
        projects = Seq("project1", "project2"),
        description = "Foo",
        enabled=true,
        admin=false
      )

      result.status mustBe NO_CONTENT

      val fetch = situation.fetchAPIKeys("my-tenant")
      (fetch.json.get.as[JsArray].value.head \ "name").as[String] mustEqual "my-key2"
    }

    "prevent updating a key by adding an unauthorized project" in {
      val situation = TestSituationBuilder()
        .loggedInWithAdminRights()
        .withUsers(
          TestUser("foo")
            .withTenantReadRight("my-tenant")
            .withProjectReadRight("project1", "my-tenant")
            .withProjectReadRight("project2", "my-tenant")
            .withApiKeyReadWriteRight("my-key", "my-tenant")
        )
        .withTenants(
          TestTenant(name = "my-tenant")
            .withProjectNames("project1", "project2", "project3")
            .withApiKeys(TestApiKey("my-key", projects = Seq("project1", "project2"), description = "Foo"))
        )
        .loggedAs("foo")
        .build()

      val result = situation.updateAPIKey(
        "my-tenant",
        currentName = "my-key",
        projects = Seq("project1", "project2", "project3"),
        description = "Foo",
        enabled=true,
        admin=false
      )

      result.status mustBe FORBIDDEN
    }

    "allow adding authorized projects to a key that contains unauthorized ones" in {
      val situation = TestSituationBuilder()
        .loggedInWithAdminRights()
        .withUsers(
          TestUser("foo")
            .withTenantReadRight("my-tenant")
            .withProjectReadWriteRight("project3", "my-tenant")
            .withApiKeyReadWriteRight("my-key", "my-tenant")
        )
        .withTenants(
          TestTenant(name = "my-tenant")
            .withProjectNames("project1", "project2", "project3")
            .withApiKeys(TestApiKey("my-key", projects = Seq("project1", "project2"), description = "Foo"))
        )
        .loggedAs("foo")
        .build()

      val result = situation.updateAPIKey(
        "my-tenant",
        currentName = "my-key",
        projects = Seq("project1", "project2", "project3"),
        description = "Foo",
        enabled=true,
        admin=false
      )

      result.status mustBe NO_CONTENT
    }

    "allow to modify admin status of a key" in {
      val situation = TestSituationBuilder()
        .loggedInWithAdminRights()
        .withTenants(
          TestTenant(name = "my-tenant")
            .withApiKeys(TestApiKey("my-key", admin=false, enabled=true))
        )
        .loggedInWithAdminRights()
        .build()

      val result = situation.updateAPIKey(
        "my-tenant",
        currentName = "my-key",
        description="",
        projects=Seq(),
        enabled=true,
        admin=true
      )

      result.status mustBe NO_CONTENT
    }

    "prevent admin status update if user is not tenant admin" in {
      val situation = TestSituationBuilder()
        .withTenants(
          TestTenant(name = "tenant")
            .withApiKeys(TestApiKey("my-key", admin = false, enabled = true))
        )
        .withUsers(TestUser("testu")
          .withTenantReadWriteRight("tenant")
          .withApiKeyAdminRight(tenant="tenant", key="my-key")
        )
        .loggedAs("testu")
        .build()

      val result = situation.updateAPIKey(
        "tenant",
        currentName = "my-key",
        description = "",
        projects = Seq(),
        enabled = true,
        admin = true
      )

      result.status mustBe FORBIDDEN
    }
  }
}
