package fr.maif.izanami.api

import fr.maif.izanami.api.BaseAPISpec._
import play.api.http.Status._
import play.api.libs.json.{
  __,
  JsBoolean,
  JsError,
  JsFalse,
  JsObject,
  JsSuccess,
  Json
}
import play.api.libs.ws.{WSCookie, WSResponse}
import play.api.test.Helpers.await
import play.api.libs.ws.writeableOf_String

import scala.concurrent.Future

class LoginAPISpec extends BaseAPISpec {
  def baseOIDCConfiguration: Map[String, AnyRef] = Map(
    "app.openid.client-id" -> "foo",
    "app.openid.client-secret" -> "bar",
    "app.openid.authorize-url" -> "http://localhost:9001/connect/authorize",
    "app.openid.token-url" -> "http://localhost:9001/connect/token",
    "app.openid.redirect-url" -> "http://localhost:9000/login",
    "app.openid.scopes" -> "openid email profile roles",
    "app.openid.username-field" -> "name",
    "app.openid.email-field" -> "email",
    "app.openid.role-right-mode" -> "supervised",
    "app.openid.role-claim" -> "roles",
    "app.openid.enabled" -> "true",
    "app.openid.email-field" -> "email",
    "app.openid.username-field" -> "name",
    "app.openid.method" -> "BASIC",
    "app.openid.pkce.enabled" -> "true"
  )
  
  "Login endpoint" should {
    "set cookie if login / password is correct" in {
      TestSituationBuilder()
        .withUsers(TestUser("test-user", "password1234"))
        .build()

      val response = login("test-user", "password1234")
      response.status mustBe OK
      response.cookies.find(c => c.name.equals("token")) mustBe defined
    }

    "return rights if asked in login query" in {
      TestSituationBuilder()
        .withUsers(
          TestUser("test-user", "password1234").withTenantAdminRight(
            "my-tenant"
          )
        )
        .withTenantNames("my-tenant")
        .build()

      val response = login("test-user", "password1234", rights = true)
      response.status mustBe OK
      response.cookies.find(c => c.name.equals("token")) mustBe defined
      (response.json.get \ "rights" \ "tenants" \ "my-tenant" \ "level")
        .as[String] mustEqual "Admin"
    }

    "return 401 if authentication header is missing" in {
      TestSituationBuilder()
        .withUsers(TestUser("test-user", "password1234"))
        .build()

      val response = await(
        ws.url(s"""${ADMIN_BASE_URL}/login""")
          .post("")
      )
      response.status mustBe UNAUTHORIZED
    }

    "return 401 if authentication header is incorrect" in {
      TestSituationBuilder()
        .withUsers(TestUser("test-user", "password1234"))
        .build()

      val response = await(
        ws.url(s"""${ADMIN_BASE_URL}/login""")
          .withHttpHeaders(("Authorization", "jkzehfhgezj"))
          .post("")
      )
      response.status mustBe UNAUTHORIZED
    }

    "return 403 if user does not exist" in {
      TestSituationBuilder()
        .withUsers(TestUser("test-user", "password1234"))
        .build()

      val response = login("foo", "password1234")
      response.status mustBe FORBIDDEN
    }

    "return 403 if password does not match" in {
      TestSituationBuilder()
        .withUsers(TestUser("test-user", "password1234"))
        .build()

      val response = login("test-user", "password12345")
      response.status mustBe FORBIDDEN
    }
  }

  "Lougout endpoint" should {
    "prevent user to make new request with an old token" in {
      val situation = TestSituationBuilder()
        .withUsers(TestUser("test-user", "password1234"))
        .loggedAs("test-user")
        .build()

      val response = await(
        ws.url(s"${ADMIN_BASE_URL}/logout")
          .withCookies(situation.cookies: _*)
          .post("")
      )
      response.status mustBe NO_CONTENT
      val tenantResponse = situation.fetchTenants()
      tenantResponse.status mustBe UNAUTHORIZED
    }
  }

  "Openidconnect endpoints" should {
    "allow openid connection" in {
      var situation: TestSituation = TestSituationBuilder()
        .withCustomConfiguration(baseOIDCConfiguration)
        .build()
      val unauthorizedResponse = situation.fetchTenants()
      unauthorizedResponse.status mustBe UNAUTHORIZED
      situation = situation.logAsOIDCUser("User1")
      val response = situation.fetchTenants()
      response.status mustBe OK
    }
    
  }

  

  "CLI OIDC authentication" should {
    "reject invalid state format" in {
      TestSituationBuilder()
        .withCustomConfiguration(baseOIDCConfiguration)
        .build()

      val response = await(
        ws.url(s"${ADMIN_BASE_URL}/cli-login?state=invalid")
          .withFollowRedirects(false)
          .get()
      )
      response.status mustBe BAD_REQUEST
    }

    "redirect to OIDC provider with valid state" in {
      val situation = TestSituationBuilder()
        .withCustomConfiguration(baseOIDCConfiguration)
        .build()

      val state = situation.generateValidState()
      val response = await(
        ws.url(s"${ADMIN_BASE_URL}/cli-login?state=$state")
          .withFollowRedirects(false)
          .get()
      )
      response.status mustBe SEE_OTHER
      response.header("Location").get must include("authorize")
      response.header("Location").get must include(s"state=cli:$state")
    }

    "return 202 when polling before auth completes" in {
      val situation = TestSituationBuilder()
        .withCustomConfiguration(baseOIDCConfiguration)
        .build()

      val state = situation.generateValidState()
      // Initiate CLI login (stores pending auth)
      await(
        ws.url(s"${ADMIN_BASE_URL}/cli-login?state=$state")
          .withFollowRedirects(false)
          .get()
      )

      // Poll before completing auth
      val pollResponse = await(
        ws.url(s"${ADMIN_BASE_URL}/cli-token?state=$state").get()
      )
      pollResponse.status mustBe ACCEPTED
      (pollResponse.json \ "status").as[String] mustBe "pending"
    }

    "return 404 for unknown state" in {
      TestSituationBuilder()
        .withCustomConfiguration(baseOIDCConfiguration)
        .build()

      val state = java.util.Base64.getUrlEncoder.withoutPadding()
        .encodeToString(new Array[Byte](32).map(_ => 'a'.toByte))
      val response = await(
        ws.url(s"${ADMIN_BASE_URL}/cli-token?state=$state").get()
      )
      response.status mustBe NOT_FOUND
    }

    "complete full CLI auth flow and return token" in {
      val situation = TestSituationBuilder()
        .withCustomConfiguration(baseOIDCConfiguration)
        .build()

      val state = situation.generateValidState()
      situation.logAsOIDCUserViaCli("User1", state)

      // Poll for token
      val pollResponse = await(
        ws.url(s"${ADMIN_BASE_URL}/cli-token?state=$state").get()
      )
      pollResponse.status mustBe OK
      (pollResponse.json \ "token").asOpt[String] mustBe defined
    }

    "return token only once (single-use)" in {
      val situation = TestSituationBuilder()
        .withCustomConfiguration(baseOIDCConfiguration)
        .build()

      val state = situation.generateValidState()
      situation.logAsOIDCUserViaCli("User1", state)

      // First poll should succeed
      val firstPoll = await(
        ws.url(s"${ADMIN_BASE_URL}/cli-token?state=$state").get()
      )
      firstPoll.status mustBe OK

      // Second poll should fail (token already claimed)
      val secondPoll = await(
        ws.url(s"${ADMIN_BASE_URL}/cli-token?state=$state").get()
      )
      secondPoll.status mustBe NOT_FOUND
    }
  }

}
