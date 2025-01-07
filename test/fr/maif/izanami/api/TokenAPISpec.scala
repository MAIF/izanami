package fr.maif.izanami.api

import fr.maif.izanami.api.BaseAPISpec.{TestPersonnalAccessToken, TestSituationBuilder, TestTenant, TestUser}
import play.api.http.Status.{BAD_REQUEST, CREATED, FORBIDDEN, NOT_FOUND, NO_CONTENT, OK, UNAUTHORIZED}
import play.api.libs.json.{JsArray, JsObject}

import java.time.{LocalDateTime, ZoneId}
import java.util.UUID

class TokenAPISpec extends BaseAPISpec {
  "Token POST endpoint" should {
    "prevent token creation if name is too long" in {
      val situation = TestSituationBuilder()
        .loggedInWithAdminRights()
        .withTenants(
          TestTenant("tenant")
        )
        .withUsers(TestUser("testu").withTenantAdminRight("tenant"))
        .loggedAs("testu")
        .build()

      val response = situation.createPersonnalAccessToken(TestPersonnalAccessToken("abcdefghij" * 21, allRights = true))
      response.status mustBe BAD_REQUEST
    }

    "allow to create all rights token" in {
      val situation = TestSituationBuilder()
        .loggedInWithAdminRights()
        .withTenants(
          TestTenant("tenant")
        )
        .withUsers(TestUser("testu").withTenantAdminRight("tenant"))
        .loggedAs("testu")
        .build()

      val response = situation.createPersonnalAccessToken(TestPersonnalAccessToken("test-token", allRights = true))
      response.status mustBe CREATED
    }

    "allow creating token with specific rights" in {
      val situation = TestSituationBuilder()
        .loggedInWithAdminRights()
        .withTenants(
          TestTenant("tenant")
        )
        .withUsers(TestUser("testu").withTenantAdminRight("tenant"))
        .loggedAs("testu")
        .build()

      val response = situation.createPersonnalAccessToken(
        TestPersonnalAccessToken("test-token", allRights = false, rights = Map("tenant" -> Set("EXPORT")))
      )
      response.status mustBe CREATED
    }

    "reject token without name" in {
      val situation = TestSituationBuilder()
        .loggedInWithAdminRights()
        .withTenants(
          TestTenant("tenant")
        )
        .withUsers(TestUser("testu").withTenantAdminRight("tenant"))
        .loggedAs("testu")
        .build()

      val response = situation.createPersonnalAccessToken(TestPersonnalAccessToken(null, allRights = true))
      response.status mustBe BAD_REQUEST
    }

    "prevent creating token with right on tenant user is not admin on tenant" in {
      val situation = TestSituationBuilder()
        .loggedInWithAdminRights()
        .withTenants(
          TestTenant("tenant")
        )
        .withUsers(TestUser("testu").withTenantReadWriteRight("tenant"))
        .loggedAs("testu")
        .build()

      val response = situation.createPersonnalAccessToken(
        TestPersonnalAccessToken("foo", allRights = false, rights = Map("tenant" -> Set("EXPORT")))
      )
      response.status mustBe FORBIDDEN
    }

    "prevent creating token on with rights on non existing tenant" in {
      val situation = TestSituationBuilder()
        .loggedInWithAdminRights()
        .build()

      val response = situation.createPersonnalAccessToken(
        TestPersonnalAccessToken("test-token", allRights = false, rights = Map("tenant" -> Set("EXPORT")))
      )
      response.status mustBe NOT_FOUND
    }

    "allow creating token with expiration date" in {
      val situation = TestSituationBuilder()
        .loggedInWithAdminRights()
        .withTenants(
          TestTenant("tenant")
        )
        .withUsers(TestUser("testu").withTenantAdminRight("tenant"))
        .loggedAs("testu")
        .build()

      val response = situation.createPersonnalAccessToken(
        TestPersonnalAccessToken(
          "test-token",
          allRights = true,
          expiresAt = Some(LocalDateTime.of(2024, 1, 1, 0, 0)),
          expirationTimezone = Some(ZoneId.of("Europe/Paris"))
        )
      )
      response.status mustBe CREATED
      val json     = response.json.get
      (json \ "expiresAt").as[String] mustEqual "2024-01-01T00:00:00"
      (json \ "expirationTimezone").as[String] mustEqual "Europe/Paris"
    }

    "Prevent token creation for another user" in {
      val situation = TestSituationBuilder()
        .loggedInWithAdminRights()
        .withTenants(
          TestTenant("tenant")
        )
        .withUsers(TestUser("testu").withTenantAdminRight("tenant"))
        .withUsers(TestUser("anotheru").withTenantAdminRight("tenant"))
        .loggedAs("testu")
        .build()

      val response = situation.createPersonnalAccessToken(
        TestPersonnalAccessToken("test-token", allRights = true),
        user = Some("anotheru")
      )
      response.status mustBe BAD_REQUEST
    }
  }

  "Token GET endpoints" should {
    "List existing tokens" in {
      val situation = TestSituationBuilder()
        .loggedInWithAdminRights()
        .withTenants(
          TestTenant("tenant")
        )
        .withPersonnalAccessToken(
          TestPersonnalAccessToken(name = "foo", allRights = true),
          TestPersonnalAccessToken(name = "bar", allRights = true),
          TestPersonnalAccessToken(name = "baz", allRights = false),
          TestPersonnalAccessToken(name = "lol", allRights = false, rights = Map("tenant" -> Set("EXPORT", "IMPORT")))
        )
        .build()

      val response = situation.fetchPersonnalAccessTokens()
      response.status mustBe OK
      val arr      = response.json.get.as[JsArray].value
      val foo      = arr.find(j => (j \ "name").as[String] == "foo").get.as[JsObject]
      val bar      = arr.find(j => (j \ "name").as[String] == "bar").get.as[JsObject]
      val baz      = arr.find(j => (j \ "name").as[String] == "baz").get.as[JsObject]
      val lol      = arr.find(j => (j \ "name").as[String] == "lol").get.as[JsObject]

      arr.length mustEqual 4

      (foo \ "allRights").as[Boolean] mustBe true
      (bar \ "allRights").as[Boolean] mustBe true

      (baz \ "allRights").as[Boolean] mustBe false
      (baz \ "rights").as[Map[String, Set[String]]] mustEqual Map.empty

      (lol \ "allRights").as[Boolean] mustBe false
      (lol \ "rights" \ "tenant").as[Set[String]] mustEqual Set("EXPORT", "IMPORT")
    }

    "Prevent reading another user token for non admin user" in {
      val situation = TestSituationBuilder()
        .loggedInWithAdminRights()
        .withTenants(
          TestTenant("tenant")
        )
        .withPersonnalAccessToken(
          TestPersonnalAccessToken(name = "foo", allRights = true),
          TestPersonnalAccessToken(name = "bar", allRights = true)
        )
        .withUsers(TestUser("testu", password = "testutestu").withTenantAdminRight("tenant"))
        .build()
      situation
        .loggedAs("testu", "testutestu")
        .fetchPersonnalAccessTokens("RESERVED_ADMIN_USER")
        .status mustBe FORBIDDEN
    }

    "Allow reading another user token for admin user" in {
      val situation = TestSituationBuilder()
        .loggedInWithAdminRights()
        .withTenants(
          TestTenant("tenant")
        )
        .withPersonnalAccessToken(
          TestPersonnalAccessToken(name = "foo", allRights = true),
          TestPersonnalAccessToken(name = "bar", allRights = true)
        )
        .withUsers(TestUser("testu", password = "testutestu").withAdminRights)
        .build()
      val response  = situation.loggedAs("testu", "testutestu").fetchPersonnalAccessTokens("RESERVED_ADMIN_USER")
      response.status mustBe OK
      response.json.get.as[JsArray].value.length mustEqual 2
    }
  }

  "Token PUT endpoint" should {
    "Allow to update token name, rights and expiration" in {
      val situation = TestSituationBuilder()
        .loggedInWithAdminRights()
        .withTenants(
          TestTenant("tenant")
        )
        .withPersonnalAccessToken(
          TestPersonnalAccessToken(name = "foo", allRights = true)
        )
        .build()

      val id = situation.findTokenId(situation.user, "foo")

      val response = situation.updatePersonnalAccessToken(
        id,
        TestPersonnalAccessToken(
          "bar",
          allRights = false,
          id = id,
          rights = Map("tenant" -> Set("EXPORT")),
          expiresAt = Some(LocalDateTime.of(2019, 1, 1, 0, 0)),
          expirationTimezone = Some(ZoneId.of("Europe/Paris"))
        )
      )
      response.status mustBe OK

      val readResponse = situation.fetchPersonnalAccessTokens()
      readResponse.status mustBe OK
      val jsonToken    = readResponse.json.get.as[JsArray].value.head
      (jsonToken \ "name").as[String] mustEqual "bar"
      (jsonToken \ "allRights").as[Boolean] mustEqual false
      (jsonToken \ "rights" \ "tenant").as[Set[String]] mustEqual Set("EXPORT")
      (jsonToken \ "expiresAt").as[String] mustEqual "2019-01-01T00:00:00"
      (jsonToken \ "expirationTimezone").as[String] mustEqual "Europe/Paris"
    }

    "Prevent name update if new name is too long" in {
      val situation = TestSituationBuilder()
        .loggedInWithAdminRights()
        .withTenants(
          TestTenant("tenant")
        )
        .withPersonnalAccessToken(
          TestPersonnalAccessToken(name = "foo", allRights = true)
        )
        .build()

      val id = situation.findTokenId(situation.user, "foo")

      val response = situation.updatePersonnalAccessToken(
        id,
        TestPersonnalAccessToken(
          "abcdefghij" * 21,
          allRights = false,
          id = id,
          rights = Map("tenant" -> Set("EXPORT")),
          expiresAt = Some(LocalDateTime.of(2019, 1, 1, 0, 0)),
          expirationTimezone = Some(ZoneId.of("Europe/Paris"))
        )
      )
      response.status mustBe BAD_REQUEST
    }

    "Prevent token update for another user if not admin" in {
      val situation = TestSituationBuilder()
        .loggedInWithAdminRights()
        .withTenants(
          TestTenant("tenant")
        )
        .withPersonnalAccessToken(
          TestPersonnalAccessToken(name = "foo", allRights = true)
        )
        .withUsers(TestUser("testu", password = "testutestu").withTenantReadWriteRight("tenant"))
        .build()

      val id             = situation.findTokenId(situation.user, "foo")
      val updateResponse = situation
        .loggedAs("testu", "testutestu")
        .updatePersonnalAccessToken(
          id,
          TestPersonnalAccessToken("bar", allRights = false, id = id),
          user = "RESERVED_ADMIN_USER"
        )
      updateResponse.status mustBe FORBIDDEN
    }

    "Allow another user token update if admin" in {
      val situation = TestSituationBuilder()
        .loggedInWithAdminRights()
        .withTenants(
          TestTenant("tenant")
        )
        .withPersonnalAccessToken(
          TestPersonnalAccessToken(name = "foo", allRights = true)
        )
        .withUsers(TestUser("testu", password = "testutestu").withAdminRights)
        .build()

      val id             = situation.findTokenId(situation.user, "foo")
      val updateResponse = situation
        .loggedAs("testu", "testutestu")
        .updatePersonnalAccessToken(
          id,
          TestPersonnalAccessToken("bar", allRights = false, id = id),
          user = "RESERVED_ADMIN_USER"
        )
      updateResponse.status mustBe OK

      val readResponse = situation.fetchPersonnalAccessTokens()
      readResponse.status mustBe OK
      val jsonToken    = readResponse.json.get.as[JsArray].value.head
      (jsonToken \ "name").as[String] mustEqual "bar"
      (jsonToken \ "allRights").as[Boolean] mustEqual false
    }

    "Reject update if url and token ids don't match" in {
      val situation = TestSituationBuilder()
        .loggedInWithAdminRights()
        .withTenants(
          TestTenant("tenant")
        )
        .withPersonnalAccessToken(
          TestPersonnalAccessToken(name = "foo", allRights = true)
        )
        .build()

      val id = situation.findTokenId(situation.user, "foo")

      val response = situation.updatePersonnalAccessToken(
        UUID.randomUUID().toString,
        TestPersonnalAccessToken(
          "bar",
          allRights = false,
          id = id
        )
      )
      response.status mustBe BAD_REQUEST
    }
  }

  "Token DELETE endpoint" should {
    "Allow to delete token" in {
      val situation = TestSituationBuilder()
        .loggedInWithAdminRights()
        .withTenants(
          TestTenant("tenant")
        )
        .withPersonnalAccessToken(
          TestPersonnalAccessToken(name = "foo", allRights = true)
        )
        .build()

      val id = situation.findTokenId(situation.user, "foo")

      val response = situation.deletePersonnalAccessToken(id)
      response.status mustBe NO_CONTENT

      val readResponse = situation.fetchPersonnalAccessTokens()
      readResponse.status mustBe OK
      readResponse.json.get.as[JsArray].value.length mustEqual 0
    }

    "Prevent deleting another user token if not admin" in {
      val situation = TestSituationBuilder()
        .loggedInWithAdminRights()
        .withTenants(
          TestTenant("tenant")
        )
        .withPersonnalAccessToken(
          TestPersonnalAccessToken(name = "foo", allRights = true)
        )
        .withUsers(TestUser("testu", "testutestu").withTenantReadWriteRight("tenant"))
        .build()

      val id = situation.findTokenId(situation.user, "foo")

      val response = situation.loggedAs("testu", "testutestu").deletePersonnalAccessToken(id, "RESERVED_ADMIN_USER")
      response.status mustBe FORBIDDEN
    }

    "Allow deleting another user token if admin" in {
      val situation = TestSituationBuilder()
        .loggedInWithAdminRights()
        .withTenants(
          TestTenant("tenant")
        )
        .withPersonnalAccessToken(
          TestPersonnalAccessToken(name = "foo", allRights = true)
        )
        .withUsers(TestUser("testu", "testutestu").withAdminRights)
        .build()

      val id = situation.findTokenId(situation.user, "foo")

      val response = situation.loggedAs("testu", "testutestu").deletePersonnalAccessToken(id, "RESERVED_ADMIN_USER")
      response.status mustBe NO_CONTENT

      val readResponse = situation.fetchPersonnalAccessTokens()
      readResponse.status mustBe OK
      readResponse.json.get.as[JsArray].value.length mustEqual 0
    }

    "Prevent deleting non existent token" in {
      val situation = TestSituationBuilder()
        .loggedInWithAdminRights()
        .withTenants(
          TestTenant("tenant")
        )
        .withPersonnalAccessToken(
          TestPersonnalAccessToken(name = "foo", allRights = true)
        )
        .build()

      val response = situation.deletePersonnalAccessToken(UUID.randomUUID().toString)
      response.status mustBe NOT_FOUND
    }
  }
}
