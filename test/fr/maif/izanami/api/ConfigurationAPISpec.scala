package fr.maif.izanami.api

import fr.maif.izanami.api.BaseAPISpec.{ADMIN_BASE_URL, TestSituationBuilder, TestUser, ws}
import play.api.http.Status._
import play.api.libs.json.Json
import play.api.test.Helpers.{await, defaultAwaitTimeout}

class ConfigurationAPISpec extends BaseAPISpec {

  "configuration GET endpoint" should {
    "return configuration if user is admin" in {
      val situation = TestSituationBuilder().loggedInWithAdminRights().build()

      val response = situation.fetchConfiguration()

      (response.json.get \ "mailer").as[String] mustEqual "Console"
      response.status mustBe OK
    }

    "return 403 if user is not admin" in {
      val situation = TestSituationBuilder().withUsers(TestUser("toto")).loggedAs("toto").build()

      val response = situation.fetchConfiguration()
      response.status mustBe FORBIDDEN
    }

    "return 401 if user is not authenticated" in {
      val situation = TestSituationBuilder().build()

      val response = situation.fetchConfiguration()
      response.status mustBe UNAUTHORIZED
    }
  }

  "configuration PUT endpoint" should {
    "return 403 if user is not admin" in {
      val situation = TestSituationBuilder()
        .withUsers(TestUser("toto"))
        .loggedAs("toto")
        .build()

      val response = situation.updateConfiguration(mailProvider = "Mailjet")
      response.status mustBe FORBIDDEN
    }

    "return 401 if user is not authenticated" in {
      val situation = TestSituationBuilder()
        .withUsers(TestUser("toto"))
        .build()

      val response = situation.updateConfiguration(mailProvider = "Mailjet")
      response.status mustBe UNAUTHORIZED
    }

    "allow to change mail provider to mailjet" in {
      val situation = TestSituationBuilder().loggedInWithAdminRights().build()

      val response = situation.updateConfiguration(mailProvider = "Mailjet", originEmail = "foo.bar@gmail.com")
      response.status mustBe NO_CONTENT
    }

    "return 400 if provided mail provider is incorrect" in {
      val situation = TestSituationBuilder().loggedInWithAdminRights().build()

      val response = situation.updateConfiguration(mailProvider = "foo")
      response.status mustBe BAD_REQUEST
    }
  }

  "Mailer configuration PUT endpoint" should {
    "allow to update mailer configuration" in {
      val situation = TestSituationBuilder().loggedInWithAdminRights().build()

      val response = situation.updateMailerConfiguration("mailjet", Json.obj("apiKey" -> "my-key", "secret" -> "my-secret"))

      response.status mustBe NO_CONTENT

      val configuration = situation.readMailerConfiguration("mailjet").json.get

      (configuration \ "secret").as[String] mustEqual  "my-secret"
      (configuration \ "apiKey").as[String] mustEqual  "my-key"
    }

    "prevent update for console" in {
      val situation = TestSituationBuilder().loggedInWithAdminRights().build()

      val response = situation.updateMailerConfiguration("console", Json.obj("apiKey" -> "my-key", "secret" -> "my-secret"))

      response.status mustBe BAD_REQUEST
    }

    "prevent update for unknown mail provider" in {
      val situation = TestSituationBuilder().loggedInWithAdminRights().build()

      val response = situation.updateMailerConfiguration("foo", Json.obj("apiKey" -> "my-key", "secret" -> "my-secret"))

      response.status mustBe BAD_REQUEST
    }
  }

  "Mailer configuration GET endpoint" should {
    "return current mailer configuration" in {
      val situation = TestSituationBuilder()
        .withMailerConfiguration(
          "mailjet",
          Json.obj(
            "apiKey" -> "my-key",
            "secret" -> "my-secret"
          )
        )
        .loggedInWithAdminRights()
        .build()

      val response = situation.readMailerConfiguration("mailjet")
      response.status mustBe OK
      val json     = response.json.get

      (json \ "apiKey").as[String] mustEqual "my-key"
      (json \ "secret").as[String] mustEqual "my-secret"
    }

    "fail if no associated configuration is found" in {
      val situation = TestSituationBuilder()
        .loggedInWithAdminRights()
        .build()

      val response = situation.readMailerConfiguration("mailfoo")
      response.status mustBe BAD_REQUEST
    }
  }

  "Exposition url get endpoint" should {
    "return exposition url" in {
      val response = await(ws.url(s"${ADMIN_BASE_URL}/exposition").get()).json

      (response \ "url").get.as[String] mustEqual "http://localhost:9000"
    }
  }

}
