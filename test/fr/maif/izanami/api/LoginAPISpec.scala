package fr.maif.izanami.api

import fr.maif.izanami.api.BaseAPISpec._
import play.api.http.Status.{FORBIDDEN, NO_CONTENT, OK, SEE_OTHER, UNAUTHORIZED}
import play.api.libs.json.Json
import play.api.test.Helpers.{await, defaultAwaitTimeout}

import java.net.URI
import java.net.http.HttpResponse.BodyHandlers
import java.net.http.{HttpClient, HttpRequest}

class LoginAPISpec extends BaseAPISpec {
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
        .withUsers(TestUser("test-user", "password1234").withTenantAdminRight("my-tenant"))
        .withTenantNames("my-tenant")
        .build()

      val response = login("test-user", "password1234", rights = true)
      response.status mustBe OK
      response.cookies.find(c => c.name.equals("token")) mustBe defined
      (response.json.get \ "rights" \ "tenants" \ "my-tenant" \ "level").as[String] mustEqual "Admin"
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

      val response       = await(ws.url(s"${ADMIN_BASE_URL}/logout").withCookies(situation.cookies: _*).post(""))
      response.status mustBe NO_CONTENT
      val tenantResponse = situation.fetchTenants()
      tenantResponse.status mustBe UNAUTHORIZED
    }
  }

  "Openidconnect endpoints" should {
    "redirect to authentication provider" in {
      // Using java http client since WSClient timeout in this case
      val request = HttpRequest
        .newBuilder()
        .uri(new URI(s"""${ADMIN_BASE_URL}/openid-connect"""))
        .GET()
        .build();

      val result = HttpClient.newBuilder().build().send(request, BodyHandlers.discarding())
      result.statusCode mustBe SEE_OTHER
      result
        .headers()
        .firstValue("location")
        .get() mustEqual "http://localhost:9001/auth?scope=email%20profile%20openid&client_id=foo&response_type=code&redirect_uri=http://localhost:3000/login"
    }

    "should allow complete flow" in {
      var res     = await(
        ws.url(s"""${ADMIN_BASE_URL}/openid-connect""")
          .withFollowRedirects(false)
          .get()
      )
      res = await(
        ws.url(res.header("location").get)
          .withFollowRedirects(false)
          .get()
      )
      val cookies = res.cookies.toArray.to(scala.collection.immutable.Seq)

      val loginUrl = s"""http://localhost:9001${res.header("location").get}/login"""
      var codeRes  = await(
        ws.url(loginUrl)
          .withFollowRedirects(false)
          .withCookies(cookies: _*)
          .withHttpHeaders(("Content-Type", "application/x-www-form-urlencoded"))
          .post("email=harley%40qlik.example&password=Password1%21&submit=")
      )
      codeRes = await(
        ws.url(codeRes.header("location").get)
          .withCookies(cookies: _*)
          .withFollowRedirects(false)
          .get()
      )

      val myAppRedirectUrl = codeRes.header("location").get
      val uri              = new URI(myAppRedirectUrl)
      val code             = uri.getQuery.split("code=")(1).split("&")(0)

      val finalResponse = await(ws.url(s"""${ADMIN_BASE_URL}/openid-connect-callback""").post(Json.obj("code" -> code)))

      finalResponse.cookie("token") mustBe defined
    }
  }
}
