package fr.maif.izanami.api

import fr.maif.izanami.api.BaseAPISpec.{
  TestRights,
  TestSituation,
  TestSituationBuilder,
  TestTenant,
  TestTenantRight
}
import play.api.http.Status.{BAD_REQUEST, NO_CONTENT, OK, UNAUTHORIZED}
import play.api.libs.json.{JsBoolean, JsFalse, __}

class OIDCRightSpec extends BaseAPISpec {
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

  "right by roles" should {
    "prevent oidc user right update if role-right-mode is supervised" in {
      var situation: TestSituation = TestSituationBuilder()
        .withTenantNames("foo")
        .withCustomConfiguration(baseOIDCConfiguration)
        .build()
      val unauthorizedResponse = situation.fetchTenants()
      unauthorizedResponse.status mustBe UNAUTHORIZED
      situation = situation.logAsOIDCUser("User1")
      situation = situation.logout()
      situation = situation.loggedAsAdmin()

      var updateResponse = situation.updateUserRights(
        name = "Sam Tailor",
        admin = true,
        rights = TestRights()
      )
      updateResponse.status mustBe BAD_REQUEST
      updateResponse = situation.updateUserRights(
        name = "Sam Tailor",
        admin = false,
        rights = TestRights(tenants =
          Map("foo" -> TestTenantRight("foo", level = "Write"))
        )
      )
      updateResponse.status mustBe BAD_REQUEST
    }

    "allow oidc user right update if role-right-mode is initial" in {
      var situation: TestSituation = TestSituationBuilder()
        .withTenantNames("foo")
        .withCustomConfiguration(
          baseOIDCConfiguration + ("app.openid.role-right-mode" -> "initial")
        )
        .build()
      val unauthorizedResponse = situation.fetchTenants()
      unauthorizedResponse.status mustBe UNAUTHORIZED
      situation = situation.logAsOIDCUser("User1")
      situation = situation.logout()
      situation = situation.loggedAsAdmin()

      var updateResponse = situation.updateUserRights(
        name = "Sam Tailor",
        admin = true,
        rights = TestRights()
      )
      updateResponse.status mustBe NO_CONTENT
      updateResponse = situation.updateUserRights(
        name = "Sam Tailor",
        admin = false,
        rights = TestRights(tenants =
          Map("foo" -> TestTenantRight("foo", level = "Write"))
        )
      )
      updateResponse.status mustBe NO_CONTENT
    }

    "assign default roles if user don't have any" in {
      var situation: TestSituation = TestSituationBuilder()
        .withTenants(
          TestTenant("test").withProjectNames("proj").withApiKeyNames("apikey")
        )
        .withCustomConfiguration(
          baseOIDCConfiguration ++ Map(
            ("app.openid.right-by-roles.\"\".admin" -> "true"),
            ("app.openid.right-by-roles.\"\".tenants.test.level" -> "read"),
            ("app.openid.right-by-roles.\"\".tenants.test.default-project-right" -> "update"),
            ("app.openid.right-by-roles.\"\".tenants.test.default-key-right" -> "read"),
            ("app.openid.right-by-roles.\"\".tenants.test.default-webhook-right" -> "write"),
            ("app.openid.right-by-roles.\"\".tenants.test.projects.proj" -> "admin"),
            ("app.openid.right-by-roles.\"\".tenants.test.keys.apikey" -> "admin")
          )
        )
        .build()
      val unauthorizedResponse = situation.fetchTenants()
      unauthorizedResponse.status mustBe UNAUTHORIZED
      situation = situation.logAsOIDCUser("User2")
      situation = situation.logout()
      situation = situation.loggedAsAdmin()

      val rightResult = situation.fetchUser("Sam Tailor2")
      rightResult.status mustBe OK
      val rights = rightResult.json.get
      (rights \ "admin").as[Boolean] mustEqual true
      (rights \ "rights" \ "tenants" \ "test" \ "level")
        .as[String] mustEqual "Read"
      (rights \ "rights" \ "tenants" \ "test" \ "defaultProjectRight")
        .as[String] mustEqual "Update"
      (rights \ "rights" \ "tenants" \ "test" \ "defaultKeyRight")
        .as[String] mustEqual "Read"
      (rights \ "rights" \ "tenants" \ "test" \ "defaultWebhookRight")
        .as[String] mustEqual "Write"
      (rights \ "rights" \ "tenants" \ "test" \ "projects" \ "proj" \ "level")
        .as[String] mustEqual "Admin"
      (rights \ "rights" \ "tenants" \ "test" \ "keys" \ "apikey" \ "level")
        .as[String] mustEqual "Admin"
    }

    "merge rights to keep only highers" in {
      var situation: TestSituation = TestSituationBuilder()
        .withTenants(
          TestTenant("test").withProjectNames("proj").withApiKeyNames("apikey")
        )
        .withCustomConfiguration(
          baseOIDCConfiguration ++ Map(
            ("app.openid.right-by-roles.\"\".admin" -> "false"),
            ("app.openid.right-by-roles.\"\".tenants.test.level" -> "read"),
            ("app.openid.right-by-roles.\"\".tenants.test.default-project-right" -> "read"),
            ("app.openid.right-by-roles.\"\".tenants.test.default-key-right" -> "read"),
            ("app.openid.right-by-roles.\"\".tenants.test.default-webhook-right" -> "read"),
            ("app.openid.right-by-roles.\"\".tenants.test.projects.proj" -> "write"),
            ("app.openid.right-by-roles.\"\".tenants.test.keys.apikey" -> "write"),
            ("app.openid.right-by-roles.admin.admin" -> "true"),
            ("app.openid.right-by-roles.admin.tenants.test.level" -> "write"),
            ("app.openid.right-by-roles.admin.tenants.test.default-project-right" -> "update"),
            ("app.openid.right-by-roles.admin.tenants.test.default-key-right" -> "read"),
            ("app.openid.right-by-roles.admin.tenants.test.default-webhook-right" -> "write"),
            ("app.openid.right-by-roles.admin.tenants.test.projects.proj" -> "read"),
            ("app.openid.right-by-roles.admin.tenants.test.keys.apikey" -> "admin")
          )
        )
        .build()
      val unauthorizedResponse = situation.fetchTenants()
      unauthorizedResponse.status mustBe UNAUTHORIZED
      situation = situation.logAsOIDCUser("User1")
      situation = situation.logout()
      situation = situation.loggedAsAdmin()

      val rightResult = situation.fetchUser("Sam Tailor")
      rightResult.status mustBe OK
      val rights = rightResult.json.get
      (rights \ "admin").as[Boolean] mustEqual true
      (rights \ "rights" \ "tenants" \ "test" \ "level")
        .as[String] mustEqual "Write"
      (rights \ "rights" \ "tenants" \ "test" \ "defaultProjectRight")
        .as[String] mustEqual "Update"
      (rights \ "rights" \ "tenants" \ "test" \ "defaultKeyRight")
        .as[String] mustEqual "Read"
      (rights \ "rights" \ "tenants" \ "test" \ "defaultWebhookRight")
        .as[String] mustEqual "Write"
      (rights \ "rights" \ "tenants" \ "test" \ "projects" \ "proj" \ "level")
        .as[String] mustEqual "Write"
      (rights \ "rights" \ "tenants" \ "test" \ "keys" \ "apikey" \ "level")
        .as[String] mustEqual "Admin"
    }

    "Apply right changes if role association change" in {
      var situation: TestSituation = TestSituationBuilder()
        .withTenants(
          TestTenant("test").withProjectNames("proj").withApiKeyNames("apikey")
        )
        .withCustomConfiguration(
          baseOIDCConfiguration ++ Map(
            ("app.openid.right-by-roles.\"\".admin" -> "true"),
            ("app.openid.right-by-roles.\"\".tenants.test.level" -> "read"),
            ("app.openid.right-by-roles.\"\".tenants.test.default-project-right" -> "read"),
            ("app.openid.right-by-roles.\"\".tenants.test.default-key-right" -> "read"),
            ("app.openid.right-by-roles.\"\".tenants.test.default-webhook-right" -> "read"),
            ("app.openid.right-by-roles.\"\".tenants.test.projects.proj" -> "read"),
            ("app.openid.right-by-roles.\"\".tenants.test.keys.apikey" -> "read")
          )
        )
        .build()

      situation = situation.logAsOIDCUser("User1")
      situation = situation.logout()
      situation = situation.restartServerWithConf(
        baseOIDCConfiguration ++ Map(
          ("app.openid.right-by-roles.\"\".admin" -> "false"),
          ("app.openid.right-by-roles.\"\".tenants.test.level" -> "write"),
          ("app.openid.right-by-roles.\"\".tenants.test.default-project-right" -> "write"),
          ("app.openid.right-by-roles.\"\".tenants.test.default-key-right" -> "write"),
          ("app.openid.right-by-roles.\"\".tenants.test.default-webhook-right" -> "write"),
          ("app.openid.right-by-roles.\"\".tenants.test.projects.proj" -> "write"),
          ("app.openid.right-by-roles.\"\".tenants.test.keys.apikey" -> "write")
        )
      )

      situation = situation.logAsOIDCUser("User1")
      situation = situation.logout()
      situation = situation.loggedAsAdmin()
      val rightResult = situation.fetchUser("Sam Tailor")
      rightResult.status mustBe OK
      val rights = rightResult.json.get
      (rights \ "admin").as[Boolean] mustEqual false
      (rights \ "rights" \ "tenants" \ "test" \ "level")
        .as[String] mustEqual "Write"
      (rights \ "rights" \ "tenants" \ "test" \ "defaultProjectRight")
        .as[String] mustEqual "Write"
      (rights \ "rights" \ "tenants" \ "test" \ "defaultKeyRight")
        .as[String] mustEqual "Write"
      (rights \ "rights" \ "tenants" \ "test" \ "defaultWebhookRight")
        .as[String] mustEqual "Write"
      (rights \ "rights" \ "tenants" \ "test" \ "projects" \ "proj" \ "level")
        .as[String] mustEqual "Write"
      (rights \ "rights" \ "tenants" \ "test" \ "keys" \ "apikey" \ "level")
        .as[String] mustEqual "Write"

    }

    "Keep rights in memory if env variable are set then unset" in {
      var situation: TestSituation = TestSituationBuilder()
        .withTenants(
          TestTenant("test").withProjectNames("proj").withApiKeyNames("apikey")
        )
        .withCustomConfiguration(
          baseOIDCConfiguration ++ Map(
            ("app.openid.right-by-roles.\"\".admin" -> "true"),
            ("app.openid.right-by-roles.\"\".tenants.test.level" -> "read"),
            ("app.openid.right-by-roles.\"\".tenants.test.default-project-right" -> "read"),
            ("app.openid.right-by-roles.\"\".tenants.test.default-key-right" -> "read"),
            ("app.openid.right-by-roles.\"\".tenants.test.default-webhook-right" -> "read"),
            ("app.openid.right-by-roles.\"\".tenants.test.projects.proj" -> "read"),
            ("app.openid.right-by-roles.\"\".tenants.test.keys.apikey" -> "read")
          )
        )
        .build()

      situation = situation.restartServerWithConf(Map())

      situation = situation.logAsOIDCUser("User1")
      situation = situation.logout()
      situation = situation.loggedAsAdmin()
      val rightResult = situation.fetchUser("Sam Tailor")
      rightResult.status mustBe OK
      val rights = rightResult.json.get
      (rights \ "admin").as[Boolean] mustEqual true
      (rights \ "rights" \ "tenants" \ "test" \ "level")
        .as[String] mustEqual "Read"
      (rights \ "rights" \ "tenants" \ "test" \ "defaultProjectRight")
        .as[String] mustEqual "Read"
      (rights \ "rights" \ "tenants" \ "test" \ "defaultKeyRight")
        .as[String] mustEqual "Read"
      (rights \ "rights" \ "tenants" \ "test" \ "defaultWebhookRight")
        .as[String] mustEqual "Read"
      (rights \ "rights" \ "tenants" \ "test" \ "projects" \ "proj" \ "level")
        .as[String] mustEqual "Read"
      (rights \ "rights" \ "tenants" \ "test" \ "keys" \ "apikey" \ "level")
        .as[String] mustEqual "Read"
    }

    "Prevent right for role update if they are set from env" in {
      var situation: TestSituation = TestSituationBuilder()
        .withTenants(
          TestTenant("test").withProjectNames("proj").withApiKeyNames("apikey")
        )
        .withCustomConfiguration(
          baseOIDCConfiguration ++ Map(
            ("app.openid.right-by-roles.\"\".admin" -> "true"),
            ("app.openid.right-by-roles.\"\".tenants.test.level" -> "read"),
            ("app.openid.right-by-roles.\"\".tenants.test.default-project-right" -> "read"),
            ("app.openid.right-by-roles.\"\".tenants.test.default-key-right" -> "read"),
            ("app.openid.right-by-roles.\"\".tenants.test.default-webhook-right" -> "read"),
            ("app.openid.right-by-roles.\"\".tenants.test.projects.proj" -> "read"),
            ("app.openid.right-by-roles.\"\".tenants.test.keys.apikey" -> "read")
          )
        )
        .loggedInWithAdminRights()
        .build()

      val result = situation.updateConfigurationWithCallback(conf => {
        val jsonTransformer =
          (__ \ "oidcConfiguration" \ "userRightsByRoles" \ "" \ "admin").json
            .update(__.read[JsBoolean].map { _ =>
              JsFalse
            })
        val newConf = conf.transform(jsonTransformer).get
        newConf
      })

      result.status mustBe BAD_REQUEST

    }

    "Allow role update if configuration is set in database only" in {
      var situation: TestSituation = TestSituationBuilder()
        .withTenants(
          TestTenant("test").withProjectNames("proj").withApiKeyNames("apikey")
        )
        .withCustomConfiguration(
          baseOIDCConfiguration ++ Map(
            ("app.openid.right-by-roles.\"\".admin" -> "true"),
            ("app.openid.right-by-roles.\"\".tenants.test.level" -> "read"),
            ("app.openid.right-by-roles.\"\".tenants.test.default-project-right" -> "read"),
            ("app.openid.right-by-roles.\"\".tenants.test.default-key-right" -> "read"),
            ("app.openid.right-by-roles.\"\".tenants.test.default-webhook-right" -> "read"),
            ("app.openid.right-by-roles.\"\".tenants.test.projects.proj" -> "read"),
            ("app.openid.right-by-roles.\"\".tenants.test.keys.apikey" -> "read")
          )
        )
        .loggedInWithAdminRights()
        .build()

      situation = situation.restartServerWithConf(Map())

      val result = situation.updateConfigurationWithCallback(conf => {
        val jsonTransformer =
          (__ \ "oidcConfiguration" \ "userRightsByRoles" \ "" \ "admin").json
            .update(__.read[JsBoolean].map { _ =>
              JsFalse
            })
        val newConf = conf.transform(jsonTransformer).get
        newConf
      })

      result.status mustBe NO_CONTENT
    }
  }

  "max rights by roles" should {
    "reduce rights at login if current rights are superior to max rights - max rights set up after restart" in {
      val initialConfig = baseOIDCConfiguration ++ Map(
        // DEFAULT INITIAL RIGHTS
        ("app.openid.right-by-roles.\"\".admin" -> "true"),
        ("app.openid.right-by-roles.\"\".tenants.test.level" -> "admin"),
        ("app.openid.right-by-roles.\"\".tenants.test.default-project-right" -> "write"),
        ("app.openid.right-by-roles.\"\".tenants.test.default-key-right" -> "write"),
        ("app.openid.right-by-roles.\"\".tenants.test.default-webhook-right" -> "write"),
        ("app.openid.right-by-roles.\"\".tenants.test.projects.proj" -> "write"),
        ("app.openid.right-by-roles.\"\".tenants.test.keys.apikey" -> "write")
        // DEFAULT ADMIN RIGHTS
        // ("app.openid.right-by-roles.admin.admin" -> "true"),
        // ("app.openid.right-by-roles.admin.tenants.test.level" -> "write"),
        // ("app.openid.right-by-roles.admin.tenants.test.default-project-right" -> "update"),
        // ("app.openid.right-by-roles.admin.tenants.test.default-key-right" -> "read"),
        // ("app.openid.right-by-roles.admin.tenants.test.default-webhook-right" -> "write"),
        // ("app.openid.right-by-roles.admin.tenants.test.projects.proj" -> "read"),
        // ("app.openid.right-by-roles.admin.tenants.test.keys.apikey" -> "admin"),
        // MAX INITIAL RIGHTS
        // ("app.openid.max-right-by-roles.\"\".admin" -> "false"),
        // ("app.openid.right-by-roles.\"\".tenants.test.level" -> "write"),
        // ("app.openid.right-by-roles.\"\".tenants.test.max-project-right" -> "write"),
        // ("app.openid.right-by-roles.\"\".tenants.test.max-key-right" -> "write"),
        // ("app.openid.right-by-roles.\"\".tenants.test.max-webhook-right" -> "write"),
        // MAX ADMIN RIGHTS
        // ("app.openid.max-right-by-roles.admin.admin" -> "true"),
        // ("app.openid.max-right-by-roles.admin.tenants.test.level" -> "admin"),
        // ("app.openid.max-right-by-roles.admin.tenants.test.max-project-right" -> "admin"),
        // ("app.openid.max-right-by-roles.admin.tenants.test.max-key-right" -> "admin"),
        // ("app.openid.max-right-by-roles.admin.tenants.test.max-webhook-right" -> "admin")
      )
      var situation: TestSituation = TestSituationBuilder()
        .withTenants(
          TestTenant("test").withProjectNames("proj").withApiKeyNames("apikey")
        )
        .withCustomConfiguration(initialConfig)
        .build()

      situation = situation.logAsOIDCUser("User2")
      situation = situation.logout()

      situation = situation.restartServerWithConf(
        initialConfig ++ Map(
          // MAX INITIAL RIGHTS
          ("app.openid.max-right-by-roles.\"\".admin" -> "false"),
          ("app.openid.right-by-roles.\"\".tenants.test.level" -> "read"),
          ("app.openid.right-by-roles.\"\".tenants.test.max-project-right" -> "read"),
          ("app.openid.right-by-roles.\"\".tenants.test.max-key-right" -> "read"),
          ("app.openid.right-by-roles.\"\".tenants.test.max-webhook-right" -> "read")
        )
      )

      situation = situation.logAsOIDCUser("User2")
      situation = situation.logout()
      situation = situation.loggedAsAdmin()

      val rightResult = situation.fetchUser("Sam Tailor2")
      rightResult.status mustBe OK

      val rights = rightResult.json.get

      (rights \ "admin").as[Boolean] mustEqual false
      (rights \ "rights" \ "tenants" \ "test" \ "level")
        .as[String] mustEqual "Read"
      (rights \ "rights" \ "tenants" \ "test" \ "defaultProjectRight")
        .as[String] mustEqual "Read"
      (rights \ "rights" \ "tenants" \ "test" \ "defaultKeyRight")
        .as[String] mustEqual "Read"
      (rights \ "rights" \ "tenants" \ "test" \ "defaultWebhookRight")
        .as[String] mustEqual "Read"
      (rights \ "rights" \ "tenants" \ "test" \ "projects" \ "proj" \ "level")
        .as[String] mustEqual "Read"
      (rights \ "rights" \ "tenants" \ "test" \ "keys" \ "apikey" \ "level")
        .as[String] mustEqual "Read"

    }

    "reduce rights at login if current rights are superior to max rights - max rights updated after restart" in {
      val initialConfig = baseOIDCConfiguration ++ Map(
        // DEFAULT INITIAL RIGHTS
        ("app.openid.right-by-roles.\"\".admin" -> "false"),
        ("app.openid.right-by-roles.\"\".tenants.test.level" -> "write"),
        ("app.openid.right-by-roles.\"\".tenants.test.default-project-right" -> "write"),
        ("app.openid.right-by-roles.\"\".tenants.test.default-key-right" -> "write"),
        ("app.openid.right-by-roles.\"\".tenants.test.default-webhook-right" -> "write"),
        ("app.openid.right-by-roles.\"\".tenants.test.projects.proj" -> "write"),
        ("app.openid.right-by-roles.\"\".tenants.test.keys.apikey" -> "write"),
        // MAX INITIAL RIGHTS
        ("app.openid.max-right-by-roles.\"\".admin" -> "false"),
        ("app.openid.right-by-roles.\"\".tenants.test.level" -> "write"),
        ("app.openid.right-by-roles.\"\".tenants.test.max-project-right" -> "write"),
        ("app.openid.right-by-roles.\"\".tenants.test.max-key-right" -> "write"),
        ("app.openid.right-by-roles.\"\".tenants.test.max-webhook-right" -> "write")
      )
      var situation: TestSituation = TestSituationBuilder()
        .withTenants(
          TestTenant("test").withProjectNames("proj").withApiKeyNames("apikey")
        )
        .withCustomConfiguration(initialConfig)
        .build()

      situation = situation.logAsOIDCUser("User2")
      situation = situation.logout()

      situation = situation.restartServerWithConf(
        initialConfig ++ Map(
          // MAX INITIAL RIGHTS
          ("app.openid.max-right-by-roles.\"\".admin" -> "false"),
          ("app.openid.right-by-roles.\"\".tenants.test.level" -> "read"),
          ("app.openid.right-by-roles.\"\".tenants.test.max-project-right" -> "read"),
          ("app.openid.right-by-roles.\"\".tenants.test.max-key-right" -> "read"),
          ("app.openid.right-by-roles.\"\".tenants.test.max-webhook-right" -> "read")
        )
      )

      situation = situation.logAsOIDCUser("User2")
      situation = situation.logout()
      situation = situation.loggedAsAdmin()

      val rightResult = situation.fetchUser("Sam Tailor2")
      rightResult.status mustBe OK

      val rights = rightResult.json.get

      (rights \ "admin").as[Boolean] mustEqual false
      (rights \ "rights" \ "tenants" \ "test" \ "level")
        .as[String] mustEqual "Read"
      (rights \ "rights" \ "tenants" \ "test" \ "defaultProjectRight")
        .as[String] mustEqual "Read"
      (rights \ "rights" \ "tenants" \ "test" \ "defaultKeyRight")
        .as[String] mustEqual "Read"
      (rights \ "rights" \ "tenants" \ "test" \ "defaultWebhookRight")
        .as[String] mustEqual "Read"
      (rights \ "rights" \ "tenants" \ "test" \ "projects" \ "proj" \ "level")
        .as[String] mustEqual "Read"
      (rights \ "rights" \ "tenants" \ "test" \ "keys" \ "apikey" \ "level")
        .as[String] mustEqual "Read"

    }

    "reduce rights at login if current rights are superior to max rights - max rights less than initial right" in {
      val initialConfig = baseOIDCConfiguration ++ Map(
        // DEFAULT INITIAL RIGHTS
        ("app.openid.right-by-roles.\"\".admin" -> "true"),
        ("app.openid.right-by-roles.\"\".tenants.test.level" -> "admin"),
        ("app.openid.right-by-roles.\"\".tenants.test.default-project-right" -> "admin"),
        ("app.openid.right-by-roles.\"\".tenants.test.default-key-right" -> "admin"),
        ("app.openid.right-by-roles.\"\".tenants.test.default-webhook-right" -> "admin"),
        ("app.openid.right-by-roles.\"\".tenants.test.projects.proj" -> "admin"),
        ("app.openid.right-by-roles.\"\".tenants.test.keys.apikey" -> "admin"),
        // MAX INITIAL RIGHTS
        ("app.openid.max-right-by-roles.\"\".admin" -> "false"),
        ("app.openid.right-by-roles.\"\".tenants.test.level" -> "write"),
        ("app.openid.right-by-roles.\"\".tenants.test.max-project-right" -> "write"),
        ("app.openid.right-by-roles.\"\".tenants.test.max-key-right" -> "write"),
        ("app.openid.right-by-roles.\"\".tenants.test.max-webhook-right" -> "write")
      )
      var situation: TestSituation = TestSituationBuilder()
        .withTenants(
          TestTenant("test").withProjectNames("proj").withApiKeyNames("apikey")
        )
        .withCustomConfiguration(initialConfig)
        .build()

      situation = situation.logAsOIDCUser("User2")
      situation = situation.logout()
      situation = situation.loggedAsAdmin()

      val rightResult = situation.fetchUser("Sam Tailor2")
      rightResult.status mustBe OK

      val rights = rightResult.json.get
      (rights \ "admin").as[Boolean] mustEqual false
      (rights \ "rights" \ "tenants" \ "test" \ "level")
        .as[String] mustEqual "Write"
      (rights \ "rights" \ "tenants" \ "test" \ "defaultProjectRight")
        .as[String] mustEqual "Write"
      (rights \ "rights" \ "tenants" \ "test" \ "defaultKeyRight")
        .as[String] mustEqual "Write"
      (rights \ "rights" \ "tenants" \ "test" \ "defaultWebhookRight")
        .as[String] mustEqual "Write"
      (rights \ "rights" \ "tenants" \ "test" \ "projects" \ "proj" \ "level")
        .as[String] mustEqual "Write"
      (rights \ "rights" \ "tenants" \ "test" \ "keys" \ "apikey" \ "level")
        .as[String] mustEqual "Write"

    }

    "prevent updating user right above max rights" in {
      // TODO
    }
  }
}
