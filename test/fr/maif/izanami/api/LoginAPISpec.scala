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

  

}
