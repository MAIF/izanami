package fr.maif.izanami.api

import fr.maif.izanami.api.BaseAPISpec._
import play.api.http.Status._
import play.api.libs.json.{JsArray, JsObject, JsUndefined, Json}
import play.api.test.Helpers.{await, defaultAwaitTimeout}

import java.nio.charset.StandardCharsets
import java.util.Base64

class UsersAPISpec extends BaseAPISpec {
  "Users GET endpoint" should {
    "return all users" in {
      val situation = TestSituationBuilder()
        .withUsers(TestUser("Foo"), TestUser("Bar"), TestUser("Baz"))
        .loggedInWithAdminRights()
        .build()

      val response = situation.fetchUsers()

      response.status mustBe OK
      val list = (response.json.get \\ "username").map(v => v.as[String])
      list must contain allElementsOf List("Foo", "Bar", "Baz")
      list must have length 4
    }
  }

  "User DELETE endpoint" should {
    "Delete user if logged in user is admin" in {
      val situation = TestSituationBuilder()
        .withUsers(TestUser("Foo"))
        .loggedInWithAdminRights()
        .build()

      val response = situation.deleteUser("Foo")
      response.status mustBe NO_CONTENT

      val remainingUsers = situation.fetchUsers()
      (remainingUsers.json.get \\ "username").map(v => v.as[String]) must not contain "Foo"
    }

    "Prevent self deletion" in {
      val situation = TestSituationBuilder()
        .withUsers(TestUser("Foo", admin = true))
        .loggedAs("Foo")
        .build()

      val response = situation.deleteUser("Foo")
      response.status mustBe BAD_REQUEST
    }
  }

  "User POST endpoint" should {
    "allow user creation" in {
      val situation = TestSituationBuilder().loggedInWithAdminRights().build()
      val response  = situation.createUser(user = "benjamin", password = "12345678")

      response.status mustBe CREATED
      (response.json.get \ "username").as[String] mustEqual "benjamin"
      (response.json.get \ "password").asOpt[String] mustBe None
    }

    "allow user creation with mail as username" in {
      val situation = TestSituationBuilder().loggedInWithAdminRights().build()
      val response  = situation.createUser(user = "benjamin.bar@foo.com", password = "12345678")

      response.status mustBe CREATED
      (response.json.get \ "username").as[String] mustEqual "benjamin.bar@foo.com"
      (response.json.get \ "password").asOpt[String] mustBe None
    }

    "prevent user creation is username or password is too long" in {
      val situation = TestSituationBuilder().loggedInWithAdminRights().build()

      var response = situation.createUser(user = "abcdefghij" * 33, password = "12345678", email = "benjamin@foo.bar")
      response.status mustBe BAD_REQUEST

      response = situation.createUser(user = "abcdefghij", password = "abcdefghij" * 25, email = "benjamin@foo.bar")
      response.status mustBe BAD_REQUEST
    }

    "prevent user creation if username is empty" in {
      val situation = TestSituationBuilder().loggedInWithAdminRights().build()

      val response = situation.createUser(user = "", password = "12345678")

      response.status mustBe BAD_REQUEST
    }

    "prevent user creation if password is empty" in {
      val situation = TestSituationBuilder().loggedInWithAdminRights().build()

      val response = situation.createUser(user = "benjamin", password = "")

      response.status mustBe BAD_REQUEST
    }

    "prevent user creation if password is too short" in {
      val situation = TestSituationBuilder().loggedInWithAdminRights().build()

      val response = situation.createUser(user = "benjamin", password = "1234567")

      response.status mustBe BAD_REQUEST
    }

    "prevent user creation if username is incorrect" in {
      val situation = TestSituationBuilder().loggedInWithAdminRights().build()

      val response = situation.createUser(user = "benjamin cavy", password = "12345678")

      response.status mustBe BAD_REQUEST
    }
  }

  "User right endpoint" should {
    "return rights for connected user - admin scenario" in {
      val situation = TestSituationBuilder()
        .loggedInWithAdminRights()
        .build()

      val result = situation.fetchUserRights()
      result.status mustBe OK

      (result.json.get \ "admin").as[Boolean] mustBe true
    }

    "return rights for connected user - tenant admin scenario" in {
      val situation = TestSituationBuilder()
        .withTenantNames("my-tenant")
        .withUsers(TestUser("testu").withTenantAdminRight("my-tenant"))
        .loggedAs("testu")
        .build()

      val result = situation.fetchUserRights()
      result.status mustBe OK

      (result.json.get \ "admin").as[Boolean] mustBe false
      (result.json.get \ "rights" \ "tenants" \ "my-tenant" \ "level").as[String] mustBe "Admin"
    }

    "return rights for connected user" in {
      val situation = TestSituationBuilder()
        .withTenants(TestTenant("my-tenant").withProjectNames("my-project").withApiKeyNames("my-key"))
        .withUsers(
          TestUser("testu")
            .withTenantReadRight("my-tenant")
            .withProjectReadWriteRight("my-project", "my-tenant")
            .withApiKeyAdminRight("my-key", "my-tenant")
        )
        .loggedAs("testu")
        .build()

      val result = situation.fetchUserRights()
      result.status mustBe OK

      (result.json.get \ "admin").as[Boolean] mustBe false
      (result.json.get \ "rights" \ "tenants" \ "my-tenant" \ "level").as[String] mustBe "Read"
      (result.json.get \ "rights" \ "tenants" \ "my-tenant" \ "projects" \ "my-project" \ "level")
        .as[String] mustBe "Write"
      (result.json.get \ "rights" \ "tenants" \ "my-tenant" \ "keys" \ "my-key" \ "level").as[String] mustBe "Admin"
    }
  }

  "Users GET endpoint by name for tenant" should {
    "Retrieve user and tenant right information if requester is tenant admin" in {
      val situation = TestSituationBuilder()
        .withTenants(
          TestTenant("my-tenant").withProjectNames("my-project").withApiKeyNames("my-key"),
          TestTenant("my-tenant2").withProjectNames("my-project2").withApiKeyNames("my-key2")
        )
        .withUsers(
          TestUser("testu")
            .withEmail("testu@foo.baz")
            .withTenantReadRight("my-tenant")
            .withTenantReadWriteRight("my-tenant2")
            .withProjectReadWriteRight("my-project", "my-tenant")
            .withProjectReadWriteRight("my-project2", "my-tenant2")
            .withApiKeyAdminRight("my-key", "my-tenant")
            .withApiKeyAdminRight("my-key2", "my-tenant2"),
          TestUser("loggedin")
            .withTenantAdminRight("my-tenant")
        )
        .loggedAs("loggedin")
        .build()

      val result = situation.fetchUserForTenant("testu", "my-tenant")
      result.status mustBe OK

      (result.json.get \ "username").as[String] mustEqual "testu"
      (result.json.get \ "email").as[String] mustEqual "testu@foo.baz"
      (result.json.get \ "admin").as[Boolean] mustBe false
      (result.json.get \ "rights" \ "tenants" \ "my-tenant2") mustBe a[JsUndefined]
      (result.json.get \ "rights" \ "tenants" \ "my-tenant" \ "level").as[String] mustBe "Read"
      (result.json.get \ "rights" \ "tenants" \ "my-tenant" \ "projects" \ "my-project" \ "level")
        .as[String] mustBe "Write"
      (result.json.get \ "rights" \ "tenants" \ "my-tenant" \ "keys" \ "my-key" \ "level").as[String] mustBe "Admin"
    }
  }

  "Users GET endpoint by name" should {
    "Retrieve user and right information if requester is admin" in {
      val situation = TestSituationBuilder()
        .withTenants(TestTenant("my-tenant").withProjectNames("my-project").withApiKeyNames("my-key"))
        .withUsers(
          TestUser("testu")
            .withEmail("testu@foo.baz")
            .withTenantReadRight("my-tenant")
            .withProjectReadWriteRight("my-project", "my-tenant")
            .withApiKeyAdminRight("my-key", "my-tenant")
        )
        .loggedInWithAdminRights()
        .build()

      val result = situation.fetchUser("testu")
      result.status mustBe OK

      (result.json.get \ "username").as[String] mustEqual "testu"
      (result.json.get \ "email").as[String] mustEqual "testu@foo.baz"
      (result.json.get \ "admin").as[Boolean] mustBe false
      (result.json.get \ "rights" \ "tenants" \ "my-tenant" \ "level").as[String] mustBe "Read"
      (result.json.get \ "rights" \ "tenants" \ "my-tenant" \ "projects" \ "my-project" \ "level")
        .as[String] mustBe "Write"
      (result.json.get \ "rights" \ "tenants" \ "my-tenant" \ "keys" \ "my-key" \ "level").as[String] mustBe "Admin"
    }
  }

  "Invitation POST endpoint" should {
    "prevent user creation if email is too long" in {
      val situation = TestSituationBuilder()
        .loggedInWithAdminRights()
        .build()

      val result = situation.sendInvitation(s"""${"abcdefghij" * 32}@foo.com""", true)
      result.status mustBe BAD_REQUEST
    }

    "prevent admin user invitation if logged in user is not admin" in {
      val situation = TestSituationBuilder()
        .withUsers(TestUser("testu", admin = false))
        .loggedAs("testu")
        .build()

      val result = situation.sendInvitation("foo@imaginaryemail.afezrfr", true)
      result.status mustBe FORBIDDEN
    }

    "prevent invitation is email is already used by another user" in {
      val situation = TestSituationBuilder()
        .withUsers(TestUser("foo", email = "foo@imaginaryemail.afezrfr"))
        .loggedInWithAdminRights()
        .build()

      val result = situation.sendInvitation("foo@imaginaryemail.afezrfr")

      result.status mustBe BAD_REQUEST
    }

    "allow to create invitation with tenant read rights" in {
      val situation = TestSituationBuilder()
        .loggedInWithAdminRights()
        .withTenantNames("foo")
        .build()

      val response = situation.sendInvitation(
        email = "test-user@imaginaryemail.afezrfr",
        admin = false,
        rights = TestRights().addTenantRight("foo", level = "Read")
      )

      response.status mustBe CREATED
    }

    "allow creating invitation with tenant admin right if user is tenant admin" in {
      val situation = TestSituationBuilder()
        .withUsers(TestUser("testu", admin = false).withTenantAdminRight("my-tenant"))
        .withTenantNames("my-tenant")
        .loggedAs("testu")
        .build()

      val result = situation.sendInvitation(
        "foo@imaginaryemail.afezrfr",
        admin = false,
        rights = TestRights().addTenantRight("my-tenant", "Admin")
      )
      result.status mustBe CREATED
    }

    "allow inviting tenant admin if user is admin" in {
      val situation = TestSituationBuilder()
        .withUsers(TestUser("testu", admin = true))
        .withTenantNames("my-tenant")
        .loggedAs("testu")
        .build()

      val result = situation.sendInvitation(
        "foo@imaginaryemail.afezrfr",
        admin = false,
        rights = TestRights().addTenantRight("my-tenant", "Admin")
      )
      result.status mustBe CREATED
    }

    "prevent inviting user if logged in user is not admin nor tenant admin" in {
      val situation = TestSituationBuilder()
        .withUsers(TestUser("testu").withTenantReadWriteRight("my-tenant"))
        .withTenantNames("my-tenant")
        .loggedAs("testu")
        .build()

      val result = situation.sendInvitation(
        "foo@imaginaryemail.afezrfr",
        admin = false,
        rights = TestRights()
      )
      result.status mustBe FORBIDDEN
    }

    "prevent inviting user for a tenant for which logged in user is not admin" in {
      val situation = TestSituationBuilder()
        .withUsers(TestUser("testu").withTenantAdminRight("my-tenant"))
        .withTenantNames("my-tenant", "my-tenant2")
        .loggedAs("testu")
        .build()

      val result = situation.sendInvitation(
        "foo@imaginaryemail.afezrfr",
        admin = false,
        rights = TestRights().addTenantRight("my-tenant2", "Read")
      )
      result.status mustBe FORBIDDEN
    }

    "allow inviting user with project admin right if user is tenant admin" in {
      val situation = TestSituationBuilder()
        .withUsers(TestUser("testu").withTenantAdminRight("my-tenant"))
        .withTenants(TestTenant("my-tenant").withProjectNames("my-project"))
        .loggedAs("testu")
        .build()

      val result = situation.sendInvitation(
        "foo@imaginaryemail.afezrfr",
        admin = false,
        rights = TestRights().addTenantRight("my-tenant", "Read").addProjectRight("my-project", "my-tenant", "Admin")
      )
      result.status mustBe CREATED
    }
  }

  "Complete user invitation / creation flow" should {
    "allow to create a new user with mail invitation via mailjet" in {
      val situation = TestSituationBuilder()
        .withTenants(TestTenant("my-tenant").withProjectNames("my-project"))
        .withOriginEmail("foo.bar@baz.com")
        .withInvitationMode("Mail")
        .withMailerConfiguration(
          "MailJet",
          Json.obj("apiKey" -> "foooo", "secret" -> "baaaaaar", "url" -> "http://localhost:9998")
        )
        .loggedInWithAdminRights()
        .build()

      val invitationResponse = situation.sendInvitation(
        "foo@imaginaryemail.afezrfr",
        admin = false,
        rights = TestRights().addTenantRight("my-tenant", "Read").addProjectRight("my-project", "my-tenant", "Read")
      )
      invitationResponse.status mustBe NO_CONTENT
      val (request, headers) = mailjetRequests().head
      val mailJetRequestBody = Json.parse(request.getBodyAsString)
      // TODO make this a bit cleaner
      val token              = (mailJetRequestBody \ "Messages" \\ "HTMLPart").head.toString().split("token=")(1).split("\\\\").head
      val response           = situation.createUserWithToken("foo", "barbar123", token)
      val expectedValue      = Base64.getEncoder.encodeToString("foooo:baaaaaar".getBytes(StandardCharsets.UTF_8))
      response.status mustBe CREATED
      headers.getHeader("Authorization").firstValue() mustBe s"""Basic ${expectedValue}"""
    }

    "allow to create a new user with mail invitation via mailgun" in {
      val situation = TestSituationBuilder()
        .withTenants(TestTenant("my-tenant").withProjectNames("my-project"))
        .withOriginEmail("foo.bar@baz.com")
        .withInvitationMode("Mail")
        .withMailerConfiguration(
          "MailGun",
          Json.obj("apiKey" -> "foooo", "region" -> "Europe", "url" -> "http://localhost:9997")
        )
        .loggedInWithAdminRights()
        .build()

      val invitationResponse = situation.sendInvitation(
        "foo@imaginaryemail.afezrfr",
        admin = false,
        rights = TestRights().addTenantRight("my-tenant", "Read").addProjectRight("my-project", "my-tenant", "Read")
      )

      invitationResponse.status mustBe NO_CONTENT
      val (request, headers) = mailgunRequests().head
      val body               = java.net.URLDecoder.decode(request.getBodyAsString, StandardCharsets.UTF_8.name());
      val token              = body.split("token=")(1).split("\"")(0)
      val response           = situation.createUserWithToken("foo", "barbar123", token)
      val expectedValue      = Base64.getEncoder.encodeToString("api:foooo".getBytes(StandardCharsets.UTF_8))
      response.status mustBe CREATED
      headers.getHeader("Authorization").firstValue() mustBe s"""Basic ${expectedValue}"""
    }

    "allow to create a new user with mail invitation via smtp" in {

      val situation = TestSituationBuilder()
        .withTenants(TestTenant("my-tenant").withProjectNames("my-project"))
        .withOriginEmail("foo.bar@baz.com")
        .withInvitationMode("Mail")
        .withMailerConfiguration(
          "SMTP",
          Json.obj("host" -> "localhost", "port" -> 1081, "auth" -> false, "starttlsEnabled" -> false, "smtps" -> false)
        )
        .loggedInWithAdminRights()
        .build()

      val invitationResponse = situation.sendInvitation(
        "foo@imaginaryemail.afezrfr",
        admin = false,
        rights = TestRights().addTenantRight("my-tenant", "Read").addProjectRight("my-project", "my-tenant", "Read")
      )
      invitationResponse.status mustBe NO_CONTENT
      val response           = await(
        ws.url("http://localhost:1080/api/emails")
          .get()
      )
      val html               = (response.json \ 0 \ "html").as[String]

      val token            = html.split("token=")(1).split("\"")(0)
      val creationResponse = situation.createUserWithToken("foo", "barbar123", token)
      creationResponse.status mustBe CREATED
    }

    "allow to create a new user with invitation in request's response" in {
      val situation = TestSituationBuilder()
        .withTenants(TestTenant("my-tenant").withProjectNames("my-project"))
        .loggedInWithAdminRights()
        .build()

      situation.updateConfiguration(invitationMode = "Response")
      val invitationResponse = situation.sendInvitation(
        "foo@imaginaryemail.afezrfr",
        admin = false,
        rights = TestRights().addTenantRight("my-tenant", "Read").addProjectRight("my-project", "my-tenant", "Read")
      )
      invitationResponse.status mustBe CREATED
      val token              = (invitationResponse.json.get \ "invitationUrl").as[String].split("token=")(1)
      val response           = situation.createUserWithToken("foo", "barbar123", token)

      response.status mustBe CREATED
    }

    "invalidate previous invitation if a new one is sent" in {
      val situation = TestSituationBuilder()
        .loggedInWithAdminRights()
        .withInvitationMode("Response")
        .build()

      val result = situation.sendInvitation("foo@imaginaryemail.afezrfr", admin = false)
      val token  = (result.json.get \ "invitationUrl").as[String].split("token=")(1)

      val result2  = situation.sendInvitation("foo@imaginaryemail.afezrfr", admin = true)
      result2.status mustBe CREATED
      val response = situation.createUserWithToken("foo", "barbar123", token)

      response.status mustBe NOT_FOUND
    }

    "invalidate invitation if inviter is deleted" in {
      val situation = TestSituationBuilder()
        .withUsers(TestUser("bar", admin = true), TestUser("baz", admin = true, password = "foobarbar"))
        .loggedAs("bar")
        .withInvitationMode("Response")
        .build()

      val result = situation.sendInvitation("foo@imaginaryemail.afezrfr", admin = false)
      val token  = (result.json.get \ "invitationUrl").as[String].split("token=")(1)

      situation.loggedAs("baz", "foobarbar").deleteUser("bar")

      val response = situation.createUserWithToken("foo", "barbar123", token)

      response.status mustBe NOT_FOUND
    }
  }

  "User rights update endpoint for tenant" should {
    "Prevent tenant right modification if logged in user is not admin nor tenant admin" in {
      val user      = TestUser(
        username = "foo",
        admin = false,
        rights = TestRights()
          .addTenantRight("tenant1", level = "Read")
      )
      val situation = TestSituationBuilder()
        .withUsers(user, TestUser("notAnAdmin").withTenantReadWriteRight("tenant1"))
        .withTenantNames(
          "tenant1"
        )
        .loggedAs("notAnAdmin")
        .build()

      val response = situation.updateUserRightsForTenant(
        name = user.username,
        rights = TestTenantRight(name = "tenant1", level = "Write")
      )

      response.status mustBe FORBIDDEN
    }

    "Prevent tenant right addition if logged in user is not admin nor tenant admin" in {
      val user      = TestUser(
        username = "foo",
        admin = false,
        rights = TestRights()
          .addTenantRight("tenant1", level = "Read")
      )
      val situation = TestSituationBuilder()
        .withUsers(user, TestUser("notAnAdmin").withTenantReadWriteRight("tenant1").withTenantReadWriteRight("tenant2"))
        .withTenantNames(
          "tenant1",
          "tenant2"
        )
        .loggedAs("notAnAdmin")
        .build()

      val response = situation.updateUserRightsForTenant(
        name = user.username,
        rights = TestTenantRight("tenant2", level = "Read")
      )

      response.status mustBe FORBIDDEN
    }

    "Allow tenant right modification if logged in user is tenant admin" in {
      val user      = TestUser(
        username = "foo",
        admin = false,
        rights = TestRights()
          .addTenantRight("tenant1", level = "Read")
      )
      val situation = TestSituationBuilder()
        .withUsers(user, TestUser("tenantAdmin").withTenantAdminRight("tenant1"))
        .withTenantNames(
          "tenant1"
        )
        .loggedAs("tenantAdmin")
        .build()

      val response = situation.updateUserRightsForTenant(
        name = user.username,
        rights = TestTenantRight("tenant1", level = "Write")
      )

      response.status mustBe NO_CONTENT
    }

    "Allow project right modification if logged in user is project admin" in {
      val user      = TestUser(
        username = "foo",
        admin = false,
        rights = TestRights()
          .addTenantRight("tenant1", level = "Read")
          .addProjectRight("project1", tenant = "tenant1", level = "Read")
      )
      val situation = TestSituationBuilder()
        .withUsers(
          user,
          TestUser("projectAdmin").withTenantReadRight("tenant1").withProjectAdminRight("project1", tenant = "tenant1")
        )
        .withTenants(
          TestTenant("tenant1").withProjectNames("project1")
        )
        .loggedAs("projectAdmin")
        .build()

      val response = situation.updateUserRightsForTenant(
        name = user.username,
        rights = user.rights.tenants("tenant1").addProjectRight("project1", level = "Admin")
      )

      response.status mustBe NO_CONTENT
    }

    "Prevent project right modification if logged in user is not project admin nor tenant admin nor admin" in {
      val user      = TestUser(
        username = "foo",
        admin = false,
        rights = TestRights()
          .addTenantRight("tenant1", level = "Read")
          .addProjectRight("project1", tenant = "tenant1", level = "Read")
      )
      val situation = TestSituationBuilder()
        .withUsers(
          user,
          TestUser("notProjectAdmin")
            .withTenantReadRight("tenant1")
            .withProjectReadWriteRight("project1", tenant = "tenant1")
        )
        .withTenants(
          TestTenant("tenant1").withProjectNames("project1")
        )
        .loggedAs("notProjectAdmin")
        .build()

      val response = situation.updateUserRightsForTenant(
        name = user.username,
        rights = user.rights.tenants("tenant1").addProjectRight("project1", level = "Write")
      )

      response.status mustBe FORBIDDEN
    }

    "Allow key right modification if logged in user is key admin" in {
      val user      = TestUser(
        username = "foo",
        admin = false,
        rights = TestRights()
          .addTenantRight("tenant1", level = "Read")
          .addKeyRight("key1", tenant = "tenant1", level = "Read")
      )
      val situation = TestSituationBuilder()
        .withUsers(
          user,
          TestUser("keyAdmin").withTenantReadRight("tenant1").withApiKeyAdminRight("key1", tenant = "tenant1")
        )
        .withTenants(
          TestTenant("tenant1").withApiKeyNames("key1")
        )
        .loggedAs("keyAdmin")
        .build()

      val response = situation.updateUserRightsForTenant(
        name = user.username,
        rights = user.rights.tenants("tenant1").addKeyRight("key1", level = "Write")
      )

      response.status mustBe NO_CONTENT
    }

    "Prevent key right modification if logged in user is not key admin nor tenant admin or admin" in {
      val user      = TestUser(
        username = "foo",
        admin = false,
        rights = TestRights()
          .addTenantRight("tenant1", level = "Read")
          .addKeyRight("key1", tenant = "tenant1", level = "Read")
      )
      val situation = TestSituationBuilder()
        .withUsers(
          user,
          TestUser("keyAdmin").withTenantReadRight("tenant1").withApiKeyReadWriteRight("key1", tenant = "tenant1")
        )
        .withTenants(
          TestTenant("tenant1").withApiKeyNames("key1")
        )
        .loggedAs("keyAdmin")
        .build()

      val response = situation.updateUserRightsForTenant(
        name = user.username,
        rights = user.rights.tenants("tenant1").addKeyRight("key1", level = "Write")
      )

      response.status mustBe FORBIDDEN
    }
  }

  "User right update endpoint for project" should {
    "allow to use special 'Update' right for project" in {
      val situation = TestSituationBuilder()
        .withTenants(TestTenant("tenant").withProjectNames("project"))
        .withUsers(
          TestUser("user")
            .withTenantReadRight("tenant")
            .withProjectReadRight("project", tenant = "tenant")
        )
        .loggedInWithAdminRights()
        .build()

      val response =
        situation.updateUserRightsForProject("user", tenant = "tenant", project = "project", level = "Update")
      response.status mustBe NO_CONTENT

      val checkResponse = situation.fetchUsersForProject("tenant", "project")
      (checkResponse.json.get
        .as[JsArray]
        .value
        .find(jsValue => (jsValue \ "username").as[String] == "user")
        .get \ "right").as[String] mustEqual "Update"
    }

    "delete user right for project if payload is empty" in {
      val situation = TestSituationBuilder()
        .withTenants(TestTenant("tenant").withProjectNames("project"))
        .withUsers(
          TestUser("user")
            .withTenantReadRight("tenant")
            .withProjectAdminRight("project", tenant = "tenant")
        )
        .loggedInWithAdminRights()
        .build()

      val response = situation.updateUserRightsForProject("user", tenant = "tenant", project = "project", level = null)
      response.status mustBe NO_CONTENT

      val checkResponse = situation.fetchUsersForProject("tenant", "project")

      (checkResponse.json.get \\ "username").map(jsValue => jsValue.as[String]) must not contain "user"

    }

    "update right for given user" in {
      val situation = TestSituationBuilder()
        .withTenants(TestTenant("tenant").withProjectNames("project"))
        .withUsers(
          TestUser("user")
            .withTenantReadRight("tenant")
            .withProjectReadRight("project", tenant = "tenant")
        )
        .loggedInWithAdminRights()
        .build()

      val response =
        situation.updateUserRightsForProject("user", tenant = "tenant", project = "project", level = "Admin")
      response.status mustBe NO_CONTENT

      val checkResponse = situation.fetchUsersForProject("tenant", "project")
      (checkResponse.json.get
        .as[JsArray]
        .value
        .find(jsValue => (jsValue \ "username").as[String] == "user")
        .get \ "right").as[String] mustEqual "Admin"
    }

    "give right to current user if it previously has no right on project" in {
      val situation = TestSituationBuilder()
        .withTenants(TestTenant("tenant").withProjectNames("project"))
        .withUsers(TestUser("user").withTenantReadRight("tenant"))
        .loggedInWithAdminRights()
        .build()

      val response =
        situation.updateUserRightsForProject("user", tenant = "tenant", project = "project", level = "Admin")
      response.status mustBe NO_CONTENT

      val checkResponse = situation.fetchUsersForProject("tenant", "project")
      (checkResponse.json.get
        .as[JsArray]
        .value
        .find(jsValue => (jsValue \ "username").as[String] == "user")
        .get \ "right").as[String] mustEqual "Admin"
    }

    "give right to current user if it previously has no right on project AND tenant" in {
      val situation = TestSituationBuilder()
        .withTenants(TestTenant("tenant").withProjectNames("project"))
        .withUsers(TestUser("user"))
        .loggedInWithAdminRights()
        .build()

      val response =
        situation.updateUserRightsForProject("user", tenant = "tenant", project = "project", level = "Admin")
      response.status mustBe NO_CONTENT

      val checkResponse = situation.fetchUsersForProject("tenant", "project")
      (checkResponse.json.get
        .as[JsArray]
        .value
        .find(jsValue => (jsValue \ "username").as[String] == "user")
        .get \ "right").as[String] mustEqual "Admin"
    }
  }

  "User POST endpoint for tenant" should {
    "allow to add new users to tenant" in {
      val situation = TestSituationBuilder()
        .withTenants(TestTenant("tenant"))
        .withUsers(TestUser("user1"), TestUser("user2"), TestUser("user3"), TestUser("user4"))
        .loggedInWithAdminRights()
        .build()

      val response =
        situation.inviteUsersToTenants("tenant", Seq(("user1", "Read"), ("user2", "Write"), ("user3", "Admin")))

      response.status mustEqual NO_CONTENT

      val projectResponse = situation.fetchUsersForTenant("tenant")
      (projectResponse.json.get \\ "username").map(v => v.as[String]) must contain allOf ("user1", "user2", "user3")
    }
  }

  "User POST endpoint for project" should {
    "allow to add new users to project" in {
      val situation = TestSituationBuilder()
        .withTenants(TestTenant("tenant").withProjectNames("project"))
        .withUsers(TestUser("user1"), TestUser("user2"), TestUser("user3"), TestUser("user4"))
        .loggedInWithAdminRights()
        .build()

      val response = situation.inviteUsersToProject(
        "tenant",
        "project",
        Seq(("user1", "Read"), ("user2", "Write"), ("user3", "Admin"))
      )

      response.status mustEqual NO_CONTENT

      val projectResponse = situation.fetchUsersForProject("tenant", "project")
      (projectResponse.json.get \\ "username").map(v => v.as[String]) must contain allOf ("user1", "user2", "user3")
    }

    "ignore users that already have rights for this project" in {
      val situation = TestSituationBuilder()
        .withTenants(TestTenant("tenant").withProjectNames("project"))
        .withUsers(
          TestUser("user1"),
          TestUser("user2"),
          TestUser("user3").withTenantReadRight("tenant").withProjectReadWriteRight("project", tenant = "tenant"),
          TestUser("user4")
        )
        .loggedInWithAdminRights()
        .build()

      val response = situation.inviteUsersToProject(
        "tenant",
        "project",
        Seq(("user1", "Read"), ("user2", "Write"), ("user3", "Read"))
      )

      response.status mustEqual NO_CONTENT

      val projectResponse = situation.fetchUsersForProject("tenant", "project")
      (projectResponse.json.get \\ "username").map(v => v.as[String]) must contain allOf ("user1", "user2", "user3")
      val user3Right      = projectResponse.json.get
        .as[JsArray]
        .value
        .filter(v => (v \ "username").as[String] == "user3")
        .map(v => (v \ "right").as[String])
        .head

      user3Right mustBe "Write"
    }

    "reject request if logged in user is not project admin" in {
      val situation = TestSituationBuilder()
        .withTenants(TestTenant("tenant").withProjectNames("project"))
        .withUsers(TestUser("user1").withTenantReadWriteRight("tenant"), TestUser("user2"))
        .loggedAs("user1")
        .build()

      val response = situation.inviteUsersToProject("tenant", "project", Seq(("user2", "Read")))

      response.status mustEqual FORBIDDEN
    }
  }

  "User rights update endpoint" should {
    "Allow to update admin status" in {
      val user      = TestUser(username = "foo", admin = false)
      val situation = TestSituationBuilder()
        .withUsers(user)
        .loggedInWithAdminRights()
        .build()

      val response = situation.updateUserRights(
        name = user.username,
        admin = true,
        rights = user.rights
      )

      (situation.fetchUser(user.username).json.get \ "admin").as[Boolean] mustBe true
      response.status mustBe NO_CONTENT
    }
    "Allow to add project right" in {
      val user      = TestUser(
        username = "foo",
        admin = false,
        rights = TestRights().addTenantRight("my-tenant", level = "Read")
      )
      val situation = TestSituationBuilder()
        .withUsers(user)
        .withTenants(
          TestTenant(name = "my-tenant")
            .withProjectNames("project1", "project2")
            .withApiKeyNames("key1", "key2")
        )
        .loggedInWithAdminRights()
        .build()

      val response = situation.updateUserRights(
        name = user.username,
        admin = user.admin,
        rights = user.rights.addProjectRight("project1", tenant = "my-tenant", level = "Admin")
      )

      response.status mustBe NO_CONTENT
      (situation
        .fetchUser(user.username)
        .json
        .get \ "rights" \ "tenants" \ "my-tenant" \ "projects" \ "project1" \ "level").as[String] mustBe "Admin"
    }

    "Allow to add key right" in {
      val user      = TestUser(
        username = "foo",
        admin = false,
        rights = TestRights().addTenantRight("my-tenant", level = "Read")
      )
      val situation = TestSituationBuilder()
        .withUsers(user)
        .withTenants(
          TestTenant(name = "my-tenant")
            .withProjectNames("project1", "project2")
            .withApiKeyNames("key1", "key2")
        )
        .loggedInWithAdminRights()
        .build()

      val response = situation.updateUserRights(
        name = user.username,
        admin = user.admin,
        rights = user.rights.addKeyRight("key1", tenant = "my-tenant", level = "Read")
      )

      response.status mustBe NO_CONTENT
      (situation.fetchUser(user.username).json.get \ "rights" \ "tenants" \ "my-tenant" \ "keys" \ "key1" \ "level")
        .as[String] mustBe "Read"
    }

    "Allow to add tenant right" in {
      val user      = TestUser(
        username = "foo",
        admin = false,
        rights = TestRights().addTenantRight("my-tenant", level = "Read")
      )
      val situation = TestSituationBuilder()
        .withUsers(user)
        .withTenants(
          TestTenant(name = "my-tenant"),
          TestTenant(name = "my-tenant2")
        )
        .loggedInWithAdminRights()
        .build()

      val response = situation.updateUserRights(
        name = user.username,
        admin = user.admin,
        rights = user.rights.addTenantRight("my-tenant2", level = "Write")
      )

      response.status mustBe NO_CONTENT
      (situation.fetchUser(user.username).json.get \ "rights" \ "tenants" \ "my-tenant2" \ "level")
        .as[String] mustBe "Write"
    }

    "Allow to modify tenant right" in {
      val user      = TestUser(
        username = "foo",
        admin = false,
        rights = TestRights().addTenantRight("my-tenant", level = "Read")
      )
      val situation = TestSituationBuilder()
        .withUsers(user)
        .withTenants(
          TestTenant(name = "my-tenant")
        )
        .loggedInWithAdminRights()
        .build()

      val response = situation.updateUserRights(
        name = user.username,
        admin = user.admin,
        rights = user.rights.addTenantRight("my-tenant", level = "Write")
      )

      response.status mustBe NO_CONTENT
      (situation.fetchUser(user.username).json.get \ "rights" \ "tenants" \ "my-tenant" \ "level")
        .as[String] mustBe "Write"
    }

    "Allow to modify project right" in {
      val user      = TestUser(
        username = "foo",
        admin = false,
        rights = TestRights()
          .addTenantRight("my-tenant", level = "Read")
          .addProjectRight("project1", tenant = "my-tenant", level = "Write")
      )
      val situation = TestSituationBuilder()
        .withUsers(user)
        .withTenants(
          TestTenant(name = "my-tenant")
            .withProjectNames("project1", "project2")
            .withApiKeyNames("key1", "key2")
        )
        .loggedInWithAdminRights()
        .build()

      val response = situation.updateUserRights(
        name = user.username,
        admin = user.admin,
        rights = user.rights.addProjectRight("project1", tenant = "my-tenant", level = "Admin")
      )

      response.status mustBe NO_CONTENT
      (situation
        .fetchUser(user.username)
        .json
        .get \ "rights" \ "tenants" \ "my-tenant" \ "projects" \ "project1" \ "level").as[String] mustBe "Admin"
    }

    "Allow to modify key right" in {
      val user      = TestUser(
        username = "foo",
        admin = false,
        rights = TestRights()
          .addTenantRight("my-tenant", level = "Read")
          .addKeyRight("key1", tenant = "my-tenant", level = "Read")
      )
      val situation = TestSituationBuilder()
        .withUsers(user)
        .withTenants(
          TestTenant(name = "my-tenant")
            .withProjectNames("project1", "project2")
            .withApiKeyNames("key1", "key2")
        )
        .loggedInWithAdminRights()
        .build()

      val response = situation.updateUserRights(
        name = user.username,
        admin = user.admin,
        rights = user.rights.addKeyRight("key1", tenant = "my-tenant", level = "Write")
      )

      response.status mustBe NO_CONTENT
      (situation
        .fetchUser(user.username)
        .json
        .get \ "rights" \ "tenants" \ "my-tenant" \ "keys" \ "key1" \ "level").as[String] mustBe "Write"
    }

    "Prevent admin promotion if logged in user is not admin" in {
      val user      = TestUser(
        username = "foo",
        admin = false,
        rights = TestRights()
      )
      val situation = TestSituationBuilder()
        .withUsers(user, TestUser("nonAdmin", admin = false))
        .loggedAs("nonAdmin")
        .build()

      val response = situation.updateUserRights(
        name = user.username,
        admin = true,
        rights = user.rights
      )

      response.status mustBe FORBIDDEN
    }
  }

  "User information update endpoint" should {
    "Prevent update username if username or email is too long" in {
      val user      = TestUser(username = "foo", email = "foo.bar@baz.com", password = "barbarfoo")
      val situation = TestSituationBuilder().withUsers(user).loggedAs("foo").build()

      var result = situation.updateUserInformation(
        oldName = user.username,
        email = user.email,
        newName = "abcdefghij" * 33,
        password = user.password
      )
      result.status mustBe BAD_REQUEST

      result = situation.updateUserInformation(
        oldName = user.username,
        email = s""" ${"abcdefghij" * 310}@foobar.com """,
        newName = "abcdefghij",
        password = user.password
      )

      result.status mustBe BAD_REQUEST
    }

    "Allow a user to modify its own username" in {
      val user      = TestUser(username = "foo", email = "foo.bar@baz.com", password = "barbarfoo")
      val situation = TestSituationBuilder().withUsers(user).loggedAs("foo").build()

      val result = situation.updateUserInformation(
        oldName = user.username,
        email = user.email,
        newName = "bar",
        password = user.password
      )

      result.status mustBe NO_CONTENT

      val fetchResult = situation.fetchUserRights()

      (fetchResult.json.get \ "username").as[String] mustEqual "bar"
    }

    "Allow a user to modify its own email" in {
      val user      = TestUser(username = "foo", email = "foo.bar@baz.com", password = "barbarfoo")
      val situation = TestSituationBuilder().withUsers(user).loggedAs("foo").build()

      val result =
        situation.updateUserInformation(oldName = user.username, email = "foo.bar@foo.com", password = user.password)

      result.status mustBe NO_CONTENT

      val fetchResult = situation.fetchUserRights()

      (fetchResult.json.get \ "email").as[String] mustEqual "foo.bar@foo.com"
    }

    "Prevent modification from another account" in {
      val user      = TestUser(username = "foo", email = "foo.bar@baz.com", password = "barbarfoo")
      val admin     = TestUser(username = "admin", admin = true, password = "barfoofoo")
      val situation = TestSituationBuilder().withUsers(user, admin).loggedAs("admin").build()

      var result =
        situation.updateUserInformation(oldName = user.username, email = "foo.bar@foo.com", password = user.password)
      result.status mustBe FORBIDDEN

      result =
        situation.updateUserInformation(oldName = user.username, email = "foo.bar@foo.com", password = admin.password)
      result.status mustBe FORBIDDEN
    }

    "Reject update if new username is taken" in {
      val user      = TestUser(username = "foo", email = "foo.bar@baz.com", password = "barbarfoo")
      val situation = TestSituationBuilder().withUsers(user, TestUser("bar")).loggedAs("foo").build()

      val result = situation.updateUserInformation(
        oldName = user.username,
        email = user.email,
        password = user.password,
        newName = "bar"
      )
      result.status mustBe BAD_REQUEST
    }

    "Reject update if new email is taken" in {
      val user      = TestUser(username = "foo", email = "foo.bar@baz.com", password = "barbarfoo")
      val situation =
        TestSituationBuilder().withUsers(user, TestUser("bar", email = "bar.bar@baz.com")).loggedAs("foo").build()

      val result =
        situation.updateUserInformation(oldName = user.username, email = "bar.bar@baz.com", password = user.password)
      result.status mustBe BAD_REQUEST
    }

    "Reject update if password is wrong" in {
      val user      = TestUser(username = "foo", email = "foo.bar@baz.com", password = "barbarfoo")
      val situation = TestSituationBuilder().withUsers(user).loggedAs("foo").build()

      val result =
        situation.updateUserInformation(oldName = user.username, email = "bar.bar@baz.com", password = "barbarfo")
      result.status mustBe UNAUTHORIZED
    }
  }

  "User password update endpoint" should {
    "prevent password update if it's too long" in {
      val user      = TestUser(username = "foo", password = "barbarfoo")
      val situation = TestSituationBuilder().withUsers(user).loggedAs("foo").build()

      val response =
        situation.updateUserPassword(user.username, oldPassword = user.password, newPassword = "abcdefghij" * 60)

      response.status mustBe BAD_REQUEST
    }

    "Allow user own password modification" in {
      val user      = TestUser(username = "foo", password = "barbarfoo")
      val situation = TestSituationBuilder().withUsers(user).loggedAs("foo").build()

      val response = situation.updateUserPassword(user.username, oldPassword = user.password, newPassword = "barfoofoo")

      response.status mustBe NO_CONTENT
      val loginResponse = login(user.username, "barfoofoo")
      loginResponse.status mustBe OK
    }

    "Reject another user password modification" in {
      val user      = TestUser(username = "foo", password = "barfoofoo", admin = true)
      val situation =
        TestSituationBuilder().withUsers(user, TestUser("bar", password = "barbarfoo")).loggedAs("foo").build()

      var response = situation.updateUserPassword("bar", oldPassword = "barbarfoo", newPassword = "barbarfoo2")
      response.status mustBe FORBIDDEN

      response = situation.updateUserPassword("bar", oldPassword = "barfoofoo", newPassword = "barbarfoo2")
      response.status mustBe FORBIDDEN
    }

    "Reject password modification with incorrect old password" in {
      val user      = TestUser(username = "foo", password = "barbarfoo")
      val situation = TestSituationBuilder().withUsers(user).loggedAs("foo").build()

      val response = situation.updateUserPassword(user.username, oldPassword = "barbarbar", newPassword = "barfoofoo")

      response.status mustBe UNAUTHORIZED
    }

    "Reject password modification if new password is incorrect" in {
      val user      = TestUser(username = "foo", password = "barbarfoo")
      val situation = TestSituationBuilder().withUsers(user).loggedAs("foo").build()

      val response = situation.updateUserPassword(user.username, oldPassword = "barbarfoo", newPassword = "aaa")

      response.status mustBe BAD_REQUEST
    }
  }

  "Password reset endpoint" should {
    "Send email to specified adress" in {
      val situation = TestSituationBuilder()
        .withUsers(TestUser(username = "foo", email = "foo.bar@baz.com"))
        .withOriginEmail("izanami@baz.com")
        .withMailerConfiguration(
          "MailJet",
          Json.obj(
            "apiKey" -> "my-key",
            "secret" -> "my-secret",
            "url"    -> "http://localhost:9998"
          )
        )
        .build()

      val response = situation.resetPassword("foo.bar@baz.com")
      response.status mustBe NO_CONTENT

      val requests = mailjetRequests()
      requests.size mustBe 1
      val body     = requests.head._1.getBodyAsString
      (Json.parse(body) \\ "To")
        .flatMap(jsValue => (jsValue \\ "Email"))
        .map(v => v.as[String])
        .head mustBe "foo.bar@baz.com"
    }

    "Return normally even if email does not exist in DB but don't send mail" in {
      val situation = TestSituationBuilder()
        .withOriginEmail("izanami@baz.com")
        .withMailerConfiguration(
          "MailJet",
          Json.obj(
            "apiKey" -> "my-key",
            "secret" -> "my-secret",
            "url"    -> "http://localhost:9998"
          )
        )
        .build()

      val response = situation.resetPassword("foo.bar.baz@foo.com")
      response.status mustBe NO_CONTENT

      mailjetRequests().size mustBe 0
    }
  }

  "Password reinitialisation process" should {
    "Update user password" in {
      var situation = TestSituationBuilder()
        .withUsers(TestUser("foo", email = "foo.bar@baz.bar"))
        .withOriginEmail("izanami@baz.com")
        .withMailerConfiguration(
          "MailJet",
          Json.obj(
            "apiKey" -> "my-key",
            "secret" -> "my-secret",
            "url"    -> "http://localhost:9998"
          )
        )
        .build()

      val response = situation.resetPassword("foo.bar@baz.bar")
      response.status mustBe NO_CONTENT

      val mailBody = mailjetRequests().head._1.getBodyAsString
      val htmlMail = (Json.parse(mailBody) \ "Messages" \\ "HTMLPart").head.toString()
      val token    = htmlMail.split("token=")(1).split("\\\\")(0)
      token must not be empty

      val reinitResponse = situation.reinitializePassword(password = "foofoofoo", token = token)
      reinitResponse.status mustBe NO_CONTENT

      situation = situation.logout().loggedAs("foo", "foofoofoo")

      situation.fetchUserRights().status mustBe OK
    }

    "Invalidate reset request if a new one is sent" in {
      var situation = TestSituationBuilder()
        .withUsers(TestUser("foo", email = "foo.bar@baz.bar"))
        .withOriginEmail("izanami@baz.com")
        .withMailerConfiguration(
          "MailJet",
          Json.obj(
            "apiKey" -> "my-key",
            "secret" -> "my-secret",
            "url"    -> "http://localhost:9998"
          )
        )
        .build()

      val response      = situation.resetPassword("foo.bar@baz.bar")
      val secondRequest = situation.resetPassword("foo.bar@baz.bar")
      secondRequest.status mustBe NO_CONTENT

      val mailBody = mailjetRequests().head._1.getBodyAsString
      val htmlMail = (Json.parse(mailBody) \ "Messages" \\ "HTMLPart").head.toString()
      val token    = htmlMail.split("token=")(1).split("\\\\")(0)

      val reinitResponse = situation.reinitializePassword(password = "foofoofoo", token = token)

      reinitResponse.status mustBe NOT_FOUND
    }

  }

  "User get by tenant endpoint" should {
    "return users that have rights for this tenant" in {
      val situation = TestSituationBuilder()
        .withTenantNames("foo", "bar")
        .withUsers(
          TestUser("foouser").withTenantReadRight("foo"),
          TestUser("baruser").withTenantReadRight("bar"),
          TestUser("tenantAdmin").withTenantAdminRight("foo")
        )
        .loggedAs("tenantAdmin")
        .build()

      val response = situation.fetchUsersForTenant("foo")

      response.status mustBe OK
      val body = response.json.get

      (body \\ "username").map(_.as[String]) must contain allOf ("foouser", "tenantAdmin")
      (body \\ "email").map(
        _.as[String]
      ) must contain allOf ("foouser@imaginarymail.frfrfezfezrf", "tenantAdmin@imaginarymail.frfrfezfezrf")
      (body \\ "right").map(_.as[String]) must contain allOf ("Read", "Admin")
    }

    "return admin users as well" in {
      val situation = TestSituationBuilder()
        .withTenantNames("foo", "bar")
        .withUsers(
          TestUser("foouser").withTenantReadRight("foo"),
          TestUser("baruser").withTenantReadRight("bar"),
          TestUser("my-admin").withAdminRights
        )
        .loggedInWithAdminRights()
        .build()

      val response = situation.fetchUsersForTenant("foo")

      response.status mustBe OK
      val body = response.json.get

      (body \\ "username").map(_.as[String]) must contain allOf ("foouser", "my-admin")
    }

    "prevent request if user is not tenant admin" in {
      val situation = TestSituationBuilder()
        .withTenantNames("foo", "bar")
        .withUsers(
          TestUser("foouser").withTenantReadRight("foo"),
          TestUser("baruser").withTenantReadRight("bar"),
          TestUser("tenantAdmin").withTenantReadWriteRight("foo")
        )
        .loggedAs("tenantAdmin")
        .build()

      val response = situation.fetchUsersForTenant("foo")

      response.status mustEqual FORBIDDEN
    }
  }

  "User get by project endpoint" should {
    "return users that have rights for the project" in {
      val situation = TestSituationBuilder()
        .withTenants(
          TestTenant("tenant")
            .withProjectNames("project")
        )
        .withUsers(
          TestUser("shouldHaveRight").withTenantAdminRight("tenant"),
          TestUser("shouldHaveRightTwo")
            .withTenantReadRight("tenant")
            .withProjectReadRight("project", tenant = "tenant"),
          TestUser("shouldAlsoHaveRight").withAdminRights,
          TestUser("shouldNotHaveRight").withTenantReadWriteRight("tenant")
        )
        .loggedInWithAdminRights()
        .build()

      val response = situation.fetchUsersForProject("tenant", "project")

      (response.json.get \\ "username")
        .map(jsValue => jsValue.as[String])
        .toSeq must contain allOf ("shouldHaveRight", "shouldHaveRightTwo", "shouldAlsoHaveRight")
    }

    "reject request if logged in user is not project admin" in {
      val situation = TestSituationBuilder()
        .withTenants(
          TestTenant("tenant")
            .withProjectNames("project")
        )
        .withUsers(
          TestUser("shouldHaveRight").withTenantAdminRight("tenant"),
          TestUser("shouldHaveRightTwo")
            .withTenantReadRight("tenant")
            .withProjectReadRight("project", tenant = "tenant"),
          TestUser("shouldAlsoHaveRight").withAdminRights,
          TestUser("shouldNotHaveRight").withTenantReadWriteRight("tenant")
        )
        .loggedAs("shouldNotHaveRight")
        .build()

      val response = situation.fetchUsersForProject("tenant", "project")
      response.status mustEqual FORBIDDEN
    }
  }

  "User search endpoint" should {
    "should work" in {
      val situation = TestSituationBuilder()
        .withUsers(
          TestUser("myse"),
          TestUser("MYSELF"),
          TestUser("myseff"),
          TestUser("myseeeef")
        )
        .loggedInWithAdminRights()
        .build()

      val response = situation.searchUsers("mys", count = 3)
      response.status mustBe OK

      response.json.get.as[JsArray].value.map(v => v.as[String]) must contain allElementsOf Seq(
        "myse",
        "MYSELF",
        "myseff"
      )
    }
  }
}
