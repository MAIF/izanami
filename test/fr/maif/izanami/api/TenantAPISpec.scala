package fr.maif.izanami.api

import fr.maif.izanami.api.BaseAPISpec._
import play.api.http.Status._

import scala.util.Success

class TenantAPISpec extends BaseAPISpec {
  "Tenant DELETE endpoint" should {
    "delete tenant if user is tenant admin" in {
      val testSituation = TestSituationBuilder()
        .withTenantNames("foo")
        .withUsers(
          TestUser(username = "test-user", password = "barbar123")
            .withTenantAdminRight("foo")
        )
        .loggedAs("test-user")
        .build()

      val response = testSituation.deleteTenant("foo")
      response.status mustBe NO_CONTENT

      testSituation.fetchTenant("foo").status mustBe FORBIDDEN

    }

    "prevent tenant suppression if user is not tenant admin" in {
      val testSituation = TestSituationBuilder()
        .withTenantNames("foo")
        .withUsers(
          TestUser(username = "test-user", password = "barbar123")
            .withTenantReadWriteRight("foo")
        )
        .loggedAs("test-user")
        .build()

      val response = testSituation.deleteTenant("foo")
      response.status mustBe FORBIDDEN

      testSituation.fetchTenant("foo").status mustBe OK
    }

    "return 404 if tenant does not exist" in {
      val testSituation = TestSituationBuilder()
        .withUsers(TestUser(username = "test-user", password = "barbar123"))
        .loggedAs("test-user")
        .build()

      val response = testSituation.deleteTenant("foo")
      response.status mustBe FORBIDDEN
    }
  }

  "Tenant POST endpoint" should {
    "allow to create tenant using Personnal Access Token" in {
      val situation = TestSituationBuilder()
        .withPersonnalAccessToken(
          TestPersonnalAccessToken(
            "foo",
            allRights = false,
            globalRights = Set("CREATE TENANT")
          )
        )
        .loggedInWithAdminRights()
        .build()

      val secret = situation.findTokenSecret(situation.user, "foo")
      var response = createTenantWithToken(
        name = "foo",
        username = situation.user,
        token = secret
      )
      response.status mustBe CREATED
    }

    "prevent tenant creation if name or description is too long" in {
      val testSituation = TestSituationBuilder()
        .loggedInWithAdminRights()
        .build()

      val name = "abcdefghij" * 7
      var response = testSituation.createTenant(name)
      response.status mustBe BAD_REQUEST

      response = testSituation.createTenant(
        "abcdefghij",
        description = "abcdefghij" * 51
      )
      response.status mustBe BAD_REQUEST
    }

    "prevent tenant creation if name contains uppercase" in {
      val testSituation = TestSituationBuilder()
        .loggedInWithAdminRights()
        .build()

      val response = testSituation.createTenant("Foo")
      response.status mustBe BAD_REQUEST

    }
    "prevent tenant creation if user is not admin" in {
      val testSituation = TestSituationBuilder()
        .withUsers(TestUser(username = "test-user", password = "barbar123"))
        .loggedAs("test-user")
        .build()

      val response = testSituation.createTenant("foo")
      response.status mustBe FORBIDDEN

    }

    "require authentication" in {
      val testSituation = TestSituationBuilder()
        .build()

      val response = testSituation.createTenant("foo")

      response.status mustBe UNAUTHORIZED
    }

    "allow tenant creation" in {
      val testSituation = TestSituationBuilder()
        .loggedInWithAdminRights()
        .build()

      val name = "my-tenant"
      val response = testSituation.createTenant(name)

      response.status mustBe CREATED
      val json = response.json
      (json.get \ "name").as[String] mustEqual name
    }

    "make creator admin of created tenant" in {
      val testSituation = TestSituationBuilder()
        .loggedInWithAdminRights()
        .build()

      val name = "my-tenant"
      val response = testSituation.createTenant(name)
      response.status mustBe CREATED

      val rights = testSituation.fetchUserRights()
      (rights.json.get \ "rights" \ "tenants" \ "my-tenant" \ "level")
        .as[String] mustEqual "Admin"
    }

    "allow tenant creation with description" in {
      val testSituation = TestSituationBuilder()
        .loggedInWithAdminRights()
        .build()

      val name = "my-tenant"
      val description = "my super description"
      val response = testSituation.createTenant(name, description)

      response.status mustBe CREATED
      val json = response.json
      (json.get \ "name").as[String] mustEqual name
      (json.get \ "description").as[String] mustEqual description
    }

    "prevent tenant if name is invalid" in {
      val testSituation = TestSituationBuilder()
        .loggedInWithAdminRights()
        .build()

      val name = "my incorrect &&&& @@@ tenant"
      val response = testSituation.createTenant(name)

      response.status mustBe BAD_REQUEST
    }

    "prevent tenant creation if name already exists" in {
      val testSituation = TestSituationBuilder()
        .loggedInWithAdminRights()
        .build()

      testSituation.createTenant("foo")
      val response = testSituation.createTenant("foo")

      response.status mustBe BAD_REQUEST
    }
  }

  "Tenants GET endpoint" should {
    "return authorized tenants only with pac" in {
      val testSituation = TestSituationBuilder()
        .withTenantNames("foo", "bar", "baz")
        .withUsers(
          TestUser("tuser")
            .withTenantReadRight("foo")
            .withTenantReadRight("baz")
        )
        .loggedAs("tuser")
        .build()

      val tokenResponse = testSituation.createPersonnalAccessToken(
        TestPersonnalAccessToken(
          "foo",
          allRights = false,
          globalRights = Set("READ TENANTS")
        )
      )

      val token = (tokenResponse.json.get \ "token").as[String]
      val fetchResponse = fetchTenantsWithToken(username = "tuser", token = token)

      fetchResponse.status mustBe OK
      val json = fetchResponse.json
      json mustBe a[Success[_]]
      (json.get \\ "name").map(v =>
        v.as[String]
      ) must contain theSameElementsAs Seq("foo", "baz")
    }

    "return all tenants if admin" in {
      val testSituation = TestSituationBuilder()
        .loggedInWithAdminRights()
        .withTenantNames("foo", "bar")
        .build()
      val fetchResponse = testSituation.fetchTenants()

      fetchResponse.status mustBe OK
      val json = fetchResponse.json
      json mustBe a[Success[_]]
      (json.get \\ "name").map(v =>
        v.as[String]
      ) must contain theSameElementsAs Seq("foo", "bar")
    }

    "return authorized tenants only" in {
      val testSituation = TestSituationBuilder()
        .withTenantNames("foo", "bar", "baz")
        .withUsers(
          TestUser("tuser")
            .withTenantReadRight("foo")
            .withTenantReadRight("baz")
        )
        .loggedAs("tuser")
        .build()
      val fetchResponse = testSituation.fetchTenants()

      fetchResponse.status mustBe OK
      val json = fetchResponse.json
      json mustBe a[Success[_]]
      (json.get \\ "name").map(v =>
        v.as[String]
      ) must contain theSameElementsAs Seq("foo", "baz")
    }

    "filter tenant with provided right" in {
      val testSituation = TestSituationBuilder()
        .withTenantNames("foo", "bar", "baz")
        .withUsers(
          TestUser("tuser")
            .withTenantReadWriteRight("foo")
            .withTenantAdminRight("baz")
            .withTenantReadRight("bar")
        )
        .loggedAs("tuser")
        .build()
      val fetchResponse = testSituation.fetchTenants(right = "Write")

      fetchResponse.status mustBe OK
      val json = fetchResponse.json
      json mustBe a[Success[_]]
      (json.get \\ "name").map(v =>
        v.as[String]
      ) must contain theSameElementsAs Seq("foo", "baz")
    }
  }

  "Tenants get with name endpoint" should {
    "allow to read tenant using Personnal Access Token" in {
      val situation = TestSituationBuilder()
        .withTenants(TestTenant("foo"))
        .withPersonnalAccessToken(
          TestPersonnalAccessToken(
            "foo",
            allRights = false,
            rights = Map("foo" -> Set("READ TENANT"))
          )
        )
        .loggedInWithAdminRights()
        .build()

      val secret = situation.findTokenSecret(situation.user, "foo")
      var response = readTenantWithToken(
        name = "foo",
        username = situation.user,
        token = secret
      )
      response.status mustBe OK
    }

    "return forbidden if user is not authorized to read tenant" in {
      val tenantName = "foo"
      val testSituation = TestSituationBuilder()
        .withTenants(
          TestTenant(tenantName).withProjectNames("foo-1", "foo-2", "foo-3")
        )
        .withUsers(TestUser("tuser"))
        .loggedAs("tuser")
        .build()

      val fetchResponse = testSituation.fetchTenant(tenantName)
      fetchResponse.status mustBe FORBIDDEN
    }

    "return tenant and its projects if it exists" in {
      val tenantName = "foo"
      val testSituation = TestSituationBuilder()
        .withTenants(
          TestTenant(tenantName).withProjectNames("foo-1", "foo-2", "foo-3")
        )
        .loggedInWithAdminRights()
        .build()

      val fetchResponse = testSituation.fetchTenant(tenantName)

      val tenant = fetchResponse.json.get
      (tenant \ "name").as[String] mustEqual tenantName
      (tenant \ "projects" \\ "name").map(v =>
        v.as[String]
      ) must contain theSameElementsAs Seq("foo-1", "foo-2", "foo-3")
    }

    "return only projects of the given tenant" in {
      val tenantName = "foo"
      val testSituation = TestSituationBuilder()
        .withTenants(
          TestTenant(tenantName).withProjectNames("foo-1"),
          TestTenant("bar").withProjectNames("bar-1")
        )
        .loggedInWithAdminRights()
        .build()

      val fetchResponse = testSituation.fetchTenant(tenantName)

      val json = fetchResponse.json.get
      (json \ "name").as[String] mustEqual "foo"
      (json \ "projects" \\ "name").map(v =>
        v.as[String]
      ) must contain theSameElementsAs Seq("foo-1")
    }

    "return tenant's tags" in {
      val tenantName = "foo"
      val otherTenantName = "another"
      val testSituation = TestSituationBuilder()
        .withTenants(
          TestTenant(name = tenantName).withTagNames("tag-1", "tag-2"),
          TestTenant(name = otherTenantName).withTagNames("tag-3")
        )
        .loggedInWithAdminRights()
        .build()

      val fetchResponse = testSituation.fetchTenant(tenantName)

      val json = fetchResponse.json.get
      (json \ "name").as[String] mustEqual tenantName
      (json \ "tags" \\ "name").map(v =>
        v.as[String]
      ) must contain theSameElementsAs Seq("tag-1", "tag-2")
    }
  }

  "Tenant update endpoint" should {
    "prevent updating tenant description" in {
      val situation = TestSituationBuilder()
        .withTenants(TestTenant("foo", "my description"))
        .loggedInWithAdminRights()
        .build()

      val response = situation.updateTenant(
        "foo",
        newName = "foo",
        description = "abcdefghij" * 51
      )
      response.status mustEqual BAD_REQUEST
    }

    "allow to update tenant description" in {
      val situation = TestSituationBuilder()
        .withTenants(TestTenant("foo", "my description"))
        .loggedInWithAdminRights()
        .build()

      val response = situation.updateTenant(
        "foo",
        newName = "foo",
        description = "new description"
      )
      response.status mustEqual NO_CONTENT

      val tenantResponse = situation.fetchTenant("foo")
      (tenantResponse.json.get \ "description")
        .as[String] mustEqual "new description"
    }

    "not allow to update tenant name" in {
      val situation = TestSituationBuilder()
        .withTenants(TestTenant("foo", "my description"))
        .loggedInWithAdminRights()
        .build()

      val response = situation.updateTenant(
        "foo",
        newName = "bar",
        description = "my description"
      )
      response.status mustEqual BAD_REQUEST
    }

    "return 404 if tenant does not exist" in {
      val situation = TestSituationBuilder()
        .withTenants(TestTenant("foo", "my description"))
        .loggedInWithAdminRights()
        .build()

      val response = situation.updateTenant(
        "bar",
        newName = "bar",
        description = "my description"
      )
      response.status mustEqual NOT_FOUND
    }

    "prevent tenant update if user is not tenant admin" in {
      val situation = TestSituationBuilder()
        .withTenants(TestTenant("foo", "my description"))
        .withUsers(TestUser("testu").withTenantReadRight("foo"))
        .loggedAs("testu")
        .build()

      val response = situation.updateTenant(
        "foo",
        newName = "foo",
        description = "new description"
      )
      response.status mustEqual FORBIDDEN
    }
  }
}
