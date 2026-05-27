package fr.maif.izanami.api

import fr.maif.izanami.api.BaseAPISpec.ADMIN_BASE_URL
import fr.maif.izanami.api.BaseAPISpec.TestSituationBuilder
import fr.maif.izanami.api.BaseAPISpec.TestUser
import fr.maif.izanami.api.BaseAPISpec.ws
import play.api.http.Status.*
import play.api.libs.json.Json
import play.api.test.Helpers.await
import play.api.libs.json.JsObject
import play.api.libs.json.JsBoolean
import play.api.libs.json.JsFalse
import play.api.libs.json.JsTrue
import play.api.libs.json.*

class ConfigurationAPISpec extends BaseAPISpec {

  "configuration GET endpoint" should {

    "return current mailer configuration" in {
      val situation = TestSituationBuilder()
        .withMailerConfiguration(
          "mailjet",
          Json.obj(
            "apiKey" -> "my-key",
            "secret" -> "my-secret"
          )
        )
        .withOriginEmail("foo.bar@baz.com")
        .loggedInWithAdminRights()
        .build()

      val response = situation.fetchConfiguration()
      response.status mustBe OK
      val json = (response.json.get \ "mailerConfiguration")

      (json \ "mailer").as[String] mustEqual "MailJet"
      (json \ "apiKey").as[String] mustEqual "my-key"
      (json \ "secret").asOpt[String] mustBe None
    }

    "return configuration if user is admin" in {
      val situation = TestSituationBuilder().loggedInWithAdminRights().build()

      val response = situation.fetchConfiguration()

      (response.json.get \ "mailerConfiguration" \ "mailer")
        .as[String] mustEqual "Console"
      response.status mustBe OK
    }

    "return 403 if user is not admin" in {
      val situation = TestSituationBuilder()
        .withUsers(TestUser("toto"))
        .loggedAs("toto")
        .build()

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

    "allow to update configuration with big payloads" in {
      val situation = TestSituationBuilder().withTenantNames(
        "foo"
      ).loggedInWithAdminRights().build()
      val json = Json.parse("""
      |{
      |  "invitationMode": "Response",
      |  "originEmail": null,
      |  "anonymousReporting": false,
      |  "anonymousReportingLastAsked": "2026-05-27T18:40:45.147Z",
      |  "oidcConfiguration": {
      |    "enabled": true,
      |    "method": "BASIC",
      |    "clientId": "foo",
      |    "authorizeUrl": "http://localhost:9001/connect/authorize",
      |    "tokenUrl": "http://localhost:9001/connect/token",
      |    "scopes": "openid email profile roles",
      |    "pkce": {
      |      "enabled": true,
      |      "algorithm": "S256"
      |    },
      |    "nameField": "name",
      |    "emailField": "email",
      |    "callbackUrl": "http://localhost:3000/login",
      |    "userRightsByRoles": {
      |      "": {
      |        "admin": false,
      |        "tenants": {
      |          "foo": {
      |            "level": "Read",
      |            "projects": {},
      |            "webhooks": {},
      |            "keys": {},
      |            "defaultProjectRight": "None",
      |            "defaultKeyRight": "None",
      |            "defaultWebhookRight": "None",
      |            "maxProjectRight": "Read",
      |            "maxKeyRight": "Read",
      |            "maxWebhookRight": "Read",
      |            "maxTenantRight": "Read"
      |          }
      |        },
      |        "adminAllowed": true
      |      },
      |      "role1": {
      |        "admin": false,
      |        "tenants": {
      |          "foo": {
      |            "level": "Read",
      |            "projects": {},
      |            "webhooks": {},
      |            "keys": {},
      |            "defaultProjectRight": "None",
      |            "defaultKeyRight": "None",
      |            "defaultWebhookRight": "None",
      |            "maxProjectRight": "Read",
      |            "maxKeyRight": "Read",
      |            "maxWebhookRight": "Read",
      |            "maxTenantRight": "Read"
      |          }
      |        },
      |        "adminAllowed": true
      |      },
      |      "role2": {
      |        "admin": false,
      |        "tenants": {
      |          "foo": {
      |            "level": "Read",
      |            "projects": {},
      |            "webhooks": {},
      |            "keys": {},
      |            "defaultProjectRight": "None",
      |            "defaultKeyRight": "None",
      |            "defaultWebhookRight": "None",
      |            "maxProjectRight": "Read",
      |            "maxKeyRight": "Read",
      |            "maxWebhookRight": "Read",
      |            "maxTenantRight": "Read"
      |          }
      |        },
      |        "adminAllowed": true
      |      },
      |      "role3": {
      |        "admin": false,
      |        "tenants": {
      |          "foo": {
      |            "level": "Read",
      |            "projects": {},
      |            "webhooks": {},
      |            "keys": {},
      |            "defaultProjectRight": "None",
      |            "defaultKeyRight": "None",
      |            "defaultWebhookRight": "None",
      |            "maxProjectRight": "Read",
      |            "maxKeyRight": "Read",
      |            "maxWebhookRight": "Read",
      |            "maxTenantRight": "Read"
      |          }
      |        },
      |        "adminAllowed": true
      |      },
      |      "role4": {
      |        "admin": false,
      |        "tenants": {
      |          "foo": {
      |            "level": "Read",
      |            "projects": {},
      |            "webhooks": {},
      |            "keys": {},
      |            "defaultProjectRight": "None",
      |            "defaultKeyRight": "None",
      |            "defaultWebhookRight": "None",
      |            "maxProjectRight": "Read",
      |            "maxKeyRight": "Read",
      |            "maxWebhookRight": "Read",
      |            "maxTenantRight": "Read"
      |          }
      |        },
      |        "adminAllowed": true
      |      },
      |      "role5": {
      |        "admin": false,
      |        "tenants": {
      |          "foo": {
      |            "level": "Read",
      |            "projects": {},
      |            "webhooks": {},
      |            "keys": {},
      |            "defaultProjectRight": "None",
      |            "defaultKeyRight": "None",
      |            "defaultWebhookRight": "None",
      |            "maxProjectRight": "Read",
      |            "maxKeyRight": "Read",
      |            "maxWebhookRight": "Read",
      |            "maxTenantRight": "Read"
      |          }
      |        },
      |        "adminAllowed": true
      |      },
      |      "role6": {
      |        "admin": false,
      |        "tenants": {
      |          "foo": {
      |            "level": "Read",
      |            "projects": {},
      |            "webhooks": {},
      |            "keys": {},
      |            "defaultProjectRight": "None",
      |            "defaultKeyRight": "None",
      |            "defaultWebhookRight": "None",
      |            "maxProjectRight": "Read",
      |            "maxKeyRight": "Read",
      |            "maxWebhookRight": "Read",
      |            "maxTenantRight": "Read"
      |          }
      |        },
      |        "adminAllowed": true
      |      },
      |      "role7": {
      |        "admin": false,
      |        "tenants": {
      |          "foo": {
      |            "level": "Read",
      |            "projects": {},
      |            "webhooks": {},
      |            "keys": {},
      |            "defaultProjectRight": "None",
      |            "defaultKeyRight": "None",
      |            "defaultWebhookRight": "None",
      |            "maxProjectRight": "Read",
      |            "maxKeyRight": "Read",
      |            "maxWebhookRight": "Read",
      |            "maxTenantRight": "Read"
      |          }
      |        },
      |        "adminAllowed": true
      |      },
      |      "role8": {
      |        "admin": false,
      |        "tenants": {
      |          "foo": {
      |            "level": "Read",
      |            "projects": {},
      |            "webhooks": {},
      |            "keys": {},
      |            "defaultProjectRight": "None",
      |            "defaultKeyRight": "None",
      |            "defaultWebhookRight": "None",
      |            "maxProjectRight": "Read",
      |            "maxKeyRight": "Read",
      |            "maxWebhookRight": "Read",
      |            "maxTenantRight": "Read"
      |          }
      |        },
      |        "adminAllowed": true
      |      },
      |      "role9": {
      |        "admin": false,
      |        "tenants": {
      |          "foo": {
      |            "level": "Read",
      |            "projects": {},
      |            "webhooks": {},
      |            "keys": {},
      |            "defaultProjectRight": "None",
      |            "defaultKeyRight": "None",
      |            "defaultWebhookRight": "None",
      |            "maxProjectRight": "Read",
      |            "maxKeyRight": "Read",
      |            "maxWebhookRight": "Read",
      |            "maxTenantRight": "Read"
      |          }
      |        },
      |        "adminAllowed": true
      |      },
      |      "role10": {
      |        "admin": false,
      |        "tenants": {
      |          "foo": {
      |            "level": "Read",
      |            "projects": {},
      |            "webhooks": {},
      |            "keys": {},
      |            "defaultProjectRight": "None",
      |            "defaultKeyRight": "None",
      |            "defaultWebhookRight": "None",
      |            "maxProjectRight": "Read",
      |            "maxKeyRight": "Read",
      |            "maxWebhookRight": "Read",
      |            "maxTenantRight": "Read"
      |          }
      |        },
      |        "adminAllowed": true
      |      },
      |      "role11": {
      |        "admin": false,
      |        "tenants": {
      |          "foo": {
      |            "level": "Read",
      |            "projects": {},
      |            "webhooks": {},
      |            "keys": {},
      |            "defaultProjectRight": "None",
      |            "defaultKeyRight": "None",
      |            "defaultWebhookRight": "None",
      |            "maxProjectRight": "Read",
      |            "maxKeyRight": "Read",
      |            "maxWebhookRight": "Read",
      |            "maxTenantRight": "Read"
      |          }
      |        },
      |        "adminAllowed": true
      |      },
      |      "role12": {
      |        "admin": false,
      |        "tenants": {
      |          "foo": {
      |            "level": "Read",
      |            "projects": {},
      |            "webhooks": {},
      |            "keys": {},
      |            "defaultProjectRight": "None",
      |            "defaultKeyRight": "None",
      |            "defaultWebhookRight": "None",
      |            "maxProjectRight": "Read",
      |            "maxKeyRight": "Read",
      |            "maxWebhookRight": "Read",
      |            "maxTenantRight": "Read"
      |          }
      |        },
      |        "adminAllowed": true
      |      },
      |      "role13": {
      |        "admin": false,
      |        "tenants": {
      |          "foo": {
      |            "level": "Read",
      |            "projects": {},
      |            "webhooks": {},
      |            "keys": {},
      |            "defaultProjectRight": "None",
      |            "defaultKeyRight": "None",
      |            "defaultWebhookRight": "None",
      |            "maxProjectRight": "Read",
      |            "maxKeyRight": "Read",
      |            "maxWebhookRight": "Read",
      |            "maxTenantRight": "Read"
      |          }
      |        },
      |        "adminAllowed": true
      |      },
      |      "role14": {
      |        "admin": false,
      |        "tenants": {
      |          "foo": {
      |            "level": "Read",
      |            "projects": {},
      |            "webhooks": {},
      |            "keys": {},
      |            "defaultProjectRight": "None",
      |            "defaultKeyRight": "None",
      |            "defaultWebhookRight": "None",
      |            "maxProjectRight": "Read",
      |            "maxKeyRight": "Read",
      |            "maxWebhookRight": "Read",
      |            "maxTenantRight": "Read"
      |          }
      |        },
      |        "adminAllowed": true
      |      },
      |      "role15": {
      |        "admin": false,
      |        "tenants": {
      |          "foo": {
      |            "level": "Read",
      |            "projects": {},
      |            "webhooks": {},
      |            "keys": {},
      |            "defaultProjectRight": "None",
      |            "defaultKeyRight": "None",
      |            "defaultWebhookRight": "None",
      |            "maxProjectRight": "Read",
      |            "maxKeyRight": "Read",
      |            "maxWebhookRight": "Read",
      |            "maxTenantRight": "Read"
      |          }
      |        },
      |        "adminAllowed": true
      |      },
      |      "role16": {
      |        "admin": false,
      |        "tenants": {
      |          "foo": {
      |            "level": "Read",
      |            "projects": {},
      |            "webhooks": {},
      |            "keys": {},
      |            "defaultProjectRight": "None",
      |            "defaultKeyRight": "None",
      |            "defaultWebhookRight": "None",
      |            "maxProjectRight": "Read",
      |            "maxKeyRight": "Read",
      |            "maxWebhookRight": "Read",
      |            "maxTenantRight": "Read"
      |          }
      |        },
      |        "adminAllowed": true
      |      },
      |      "role17": {
      |        "admin": false,
      |        "tenants": {
      |          "foo": {
      |            "level": "Read",
      |            "projects": {},
      |            "webhooks": {},
      |            "keys": {},
      |            "defaultProjectRight": "None",
      |            "defaultKeyRight": "None",
      |            "defaultWebhookRight": "None",
      |            "maxProjectRight": "Read",
      |            "maxKeyRight": "Read",
      |            "maxWebhookRight": "Read",
      |            "maxTenantRight": "Read"
      |          }
      |        },
      |        "adminAllowed": true
      |      },
      |      "role18": {
      |        "admin": false,
      |        "tenants": {
      |          "foo": {
      |            "level": "Read",
      |            "projects": {},
      |            "webhooks": {},
      |            "keys": {},
      |            "defaultProjectRight": "None",
      |            "defaultKeyRight": "None",
      |            "defaultWebhookRight": "None",
      |            "maxProjectRight": "Read",
      |            "maxKeyRight": "Read",
      |            "maxWebhookRight": "Read",
      |            "maxTenantRight": "Read"
      |          }
      |        },
      |        "adminAllowed": true
      |      },
      |      "role19": {
      |        "admin": false,
      |        "tenants": {
      |          "foo": {
      |            "level": "Read",
      |            "projects": {},
      |            "webhooks": {},
      |            "keys": {},
      |            "defaultProjectRight": "None",
      |            "defaultKeyRight": "None",
      |            "defaultWebhookRight": "None",
      |            "maxProjectRight": "Read",
      |            "maxKeyRight": "Read",
      |            "maxWebhookRight": "Read",
      |            "maxTenantRight": "Read"
      |          }
      |        },
      |        "adminAllowed": true
      |      },
      |      "role20": {
      |        "admin": false,
      |        "tenants": {
      |          "foo": {
      |            "level": "Read",
      |            "projects": {},
      |            "webhooks": {},
      |            "keys": {},
      |            "defaultProjectRight": "None",
      |            "defaultKeyRight": "None",
      |            "defaultWebhookRight": "None",
      |            "maxProjectRight": "Read",
      |            "maxKeyRight": "Read",
      |            "maxWebhookRight": "Read",
      |            "maxTenantRight": "Read"
      |          }
      |        },
      |        "adminAllowed": true
      |      }
      |    },
      |    "roleClaim": "roles",
      |    "roleRightMode": "Initial",
      |    "clientSecret": ""
      |  },
      |  "mailerConfiguration": {
      |    "mailer": "Console"
      |  }
      |}""".stripMargin).as[JsObject]

      var res = situation.updateConfigurationWithCallback(_ => { json})
      res.status mustEqual NO_CONTENT
      res = situation.updateConfigurationWithCallback(json => {
        // json.update((__ \ 'key3).json.put(JsString("value3"))
        val transformer =
          (__ \ "oidcConfiguration" \ "userRightsByRoles" \ "role1" \ "admin").json.update(
            play.api.libs.json.Reads.of[JsBoolean].map {
              case _ => JsTrue
            }
          )
        json.transform(transformer).get
      })
      res.status mustEqual NO_CONTENT
    }

    "prevent update for unknown mail provider" in {
      val situation = TestSituationBuilder().loggedInWithAdminRights().build()

      val response = situation.updateConfiguration(mailerConfiguration =
        Json.obj(
          "mailer" -> "foo",
          "apiKey" -> "my-key",
          "secret" -> "my-secret"
        )
      )

      response.status mustBe BAD_REQUEST
    }

    "prevent update of origin email if it's too long" in {
      val situation = TestSituationBuilder().loggedInWithAdminRights().build()

      val response = situation.updateConfiguration(
        mailerConfiguration = Json.obj(
          "mailer" -> "mailjet",
          "apiKey" -> "my-key",
          "secret" -> "my-secret"
        ),
        originEmail = s"""${"abcdefghij" * 32}@foobar.bar"""
      )

      response.status mustBe BAD_REQUEST;
    }

    "allow to update mailer configuration" in {
      val situation = TestSituationBuilder().loggedInWithAdminRights().build()

      val response = situation.updateConfiguration(
        mailerConfiguration = Json.obj(
          "mailer" -> "mailjet",
          "apiKey" -> "my-key",
          "secret" -> "my-secret"
        ),
        originEmail = "foo@baz.bar"
      )

      response.status mustBe NO_CONTENT

      val configuration =
        situation.fetchConfiguration().json.get \ "mailerConfiguration"

      (configuration \ "mailer").as[String] mustEqual "MailJet"
      (configuration \ "secret").asOpt[String] mustBe None
      (configuration \ "apiKey").as[String] mustEqual "my-key"
    }

    "return 403 if user is not admin" in {
      val situation = TestSituationBuilder()
        .withUsers(TestUser("toto"))
        .loggedAs("toto")
        .build()

      val response = situation.updateConfiguration(mailerConfiguration =
        Json.obj("mailer" -> "Console")
      )
      response.status mustBe FORBIDDEN
    }

    "return 401 if user is not authenticated" in {
      val situation = TestSituationBuilder()
        .withUsers(TestUser("toto"))
        .build()

      val response = situation.updateConfiguration(mailerConfiguration =
        Json.obj("mailer" -> "Console")
      )
      response.status mustBe UNAUTHORIZED
    }

    "allow to change mail provider to mailjet" in {
      val situation = TestSituationBuilder().loggedInWithAdminRights().build()

      val response = situation.updateConfiguration(
        mailerConfiguration = Json.obj(
          "mailer" -> "Mailjet",
          "apiKey" -> "foofoo",
          "secret" -> "barbar"
        ),
        originEmail = "foo.bar@gmail.com"
      )
      response.status mustBe NO_CONTENT
    }

    "return 400 if provided mail provider is incorrect" in {
      val situation = TestSituationBuilder().loggedInWithAdminRights().build()

      val response = situation.updateConfiguration(mailerConfiguration =
        Json.obj("mailer" -> "foo")
      )
      response.status mustBe BAD_REQUEST
    }
  }

  "Exposition url get endpoint" should {
    "return exposition url" in {
      val _ = TestSituationBuilder().build()
      val response = await(ws.url(s"${ADMIN_BASE_URL}/exposition").get()).json

      (response \ "url").get.as[String] mustEqual "http://localhost:9000"
    }
  }

}
