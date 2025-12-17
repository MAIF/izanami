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
  "right by roles" should {
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
    def baseOIDCConfiguration: Map[String, AnyRef] = Map(
      "app.openid.client-id" -> "foo",
      "app.openid.client-secret" -> "bar",
      "app.openid.authorize-url" -> "http://localhost:9001/connect/authorize",
      "app.openid.token-url" -> "http://localhost:9001/connect/token",
      "app.openid.redirect-url" -> "http://localhost:9000/login",
      "app.openid.scopes" -> "openid email profile roles",
      "app.openid.username-field" -> "name",
      "app.openid.email-field" -> "email",
      "app.openid.role-right-mode" -> "initial",
      "app.openid.role-claim" -> "roles",
      "app.openid.enabled" -> "true",
      "app.openid.email-field" -> "email",
      "app.openid.username-field" -> "name",
      "app.openid.method" -> "BASIC",
      "app.openid.pkce.enabled" -> "true"
    )


    "prevent adding tenant / project rights if max-rights is set to none" in {
      val initialConfig = baseOIDCConfiguration ++ Map(
        // MAX RIGHTS
        ("app.openid.right-by-roles.\"\".admin-allowed" -> "false"),
        ("app.openid.right-by-roles.\"\".tenants.test.max-tenant-right" -> "read"),
        ("app.openid.right-by-roles.\"\".tenants.test.max-project-right" -> "none"),
        ("app.openid.right-by-roles.\"\".tenants.another.max-tenant-right" -> "none"),
      )
      var situation: TestSituation = TestSituationBuilder()
        .withTenants(
          TestTenant("test").withProjectNames("proj"),
          TestTenant("another").withProjectNames("anotherproj")
        )
        .withCustomConfiguration(initialConfig)
        .build()

      situation = situation.logout().logAsOIDCUser("User2")
      situation = situation.logout().loggedAsAdmin()

      val projectResponse =
        situation.updateUserRightsForProject(
          "Sam Tailor2",
          tenant = "test",
          project = "proj",
          level = "Read"
        )

      projectResponse.status mustBe BAD_REQUEST


      val tenantResponse =
        situation.updateUserRightsForTenant(
          name = "Sam Tailor2",
          rights = TestTenantRight(
            name = "another",
            level = "Read"
          )
        )

      tenantResponse.status mustBe BAD_REQUEST

      val forbiddenTenantProjectResponse =
        situation.updateUserRightsForProject(
          "Sam Tailor2",
          tenant = "another",
          project = "anotherproj",
          level = "Read"
        )

      forbiddenTenantProjectResponse.status mustBe BAD_REQUEST
    }


    "apply even when adding a user with no rights on a given project" in {
      val initialConfig = baseOIDCConfiguration ++ Map(
        // DEFAULT INITIAL RIGHTS
        ("app.openid.right-by-roles.\"\".admin" -> "false"),
        ("app.openid.right-by-roles.\"\".tenants.test.level" -> "read"),
        ("app.openid.right-by-roles.\"\".tenants.test.default-project-right" -> "write"),
        ("app.openid.right-by-roles.\"\".tenants.test.default-key-right" -> "write"),
        ("app.openid.right-by-roles.\"\".tenants.test.default-webhook-right" -> "write"),
        // MAX INITIAL RIGHTS
        ("app.openid.right-by-roles.\"\".admin-allowed" -> "false"),
        ("app.openid.right-by-roles.\"\".tenants.test.max-tenant-right" -> "write"),
        ("app.openid.right-by-roles.\"\".tenants.test.max-project-right" -> "write"),
        ("app.openid.right-by-roles.\"\".tenants.test.max-key-right" -> "write"),
        ("app.openid.right-by-roles.\"\".tenants.test.max-webhook-right" -> "write")
      )
      var situation: TestSituation = TestSituationBuilder()
        .withTenants(
          TestTenant("test").withProjectNames("proj")
        )
        .withCustomConfiguration(initialConfig)
        .build()

      situation = situation.logout().logAsOIDCUser("User1")
      situation = situation.logout().loggedAsAdmin()

      val response =
        situation.updateUserRightsForProject(
          "Sam Tailor",
          tenant = "test",
          project = "proj",
          level = "Admin"
        )
      response.status mustBe BAD_REQUEST
    }

    "reduce rights at login if current rights are superior to max rights - max rights set up at restart" in {
      val initialConfig = baseOIDCConfiguration ++ Map(
        // DEFAULT INITIAL RIGHTS
        ("app.openid.right-by-roles.\"\".admin" -> "true"),
        ("app.openid.right-by-roles.\"\".tenants.test.level" -> "admin"),
        ("app.openid.right-by-roles.\"\".tenants.test.default-project-right" -> "write"),
        ("app.openid.right-by-roles.\"\".tenants.test.default-key-right" -> "write"),
        ("app.openid.right-by-roles.\"\".tenants.test.default-webhook-right" -> "write"),
        ("app.openid.right-by-roles.\"\".tenants.test.projects.proj" -> "write"),
        ("app.openid.right-by-roles.\"\".tenants.test.keys.apikey" -> "write")
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
          ("app.openid.right-by-roles.\"\".admin-allowed" -> "false"),
          ("app.openid.right-by-roles.\"\".tenants.test.max-tenant-right" -> "read"),
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

    "reduce rights at login if current rights are superior to max rights - max rights updated at restart" in {
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
        ("app.openid.right-by-roles.\"\".admin-allowed" -> "false"),
        ("app.openid.right-by-roles.\"\".tenants.test.max-tenant-right" -> "write"),
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
          ("app.openid.right-by-roles.\"\".admin-allowed" -> "false"),
          ("app.openid.right-by-roles.\"\".tenants.test.max-tenant-right" -> "read"),
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
        ("app.openid.right-by-roles.\"\".admin-allowed" -> "false"),
        ("app.openid.right-by-roles.\"\".tenants.test.max-tenant-right" -> "write"),
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

    "prevent updating user tenant rights above max rights" in {
      val initialConfig = baseOIDCConfiguration ++ Map(
        // DEFAULT INITIAL RIGHTS
        ("app.openid.right-by-roles.\"\".admin" -> "false"),
        ("app.openid.right-by-roles.\"\".tenants.test.level" -> "read"),
        ("app.openid.right-by-roles.\"\".tenants.test.default-project-right" -> "read"),
        ("app.openid.right-by-roles.\"\".tenants.test.default-key-right" -> "read"),
        ("app.openid.right-by-roles.\"\".tenants.test.default-webhook-right" -> "read"),
        ("app.openid.right-by-roles.\"\".tenants.test.projects.proj" -> "read"),
        ("app.openid.right-by-roles.\"\".tenants.test.keys.apikey" -> "read"),
        // MAX INITIAL RIGHTS
        ("app.openid.right-by-roles.\"\".admin-allowed" -> "false"),
        ("app.openid.right-by-roles.\"\".tenants.test.max-tenant-right" -> "write"),
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
      val adminUpdateResponse = situation.updateUserRightsForTenant(
        "Sam Tailor2",
        TestTenantRight(name = "test").addProjectRight("proj", "admin")
      )

      adminUpdateResponse.status mustBe BAD_REQUEST
    }

    "prevent updating user rights above max rights" in {
      val initialConfig = baseOIDCConfiguration ++ Map(
        // DEFAULT INITIAL RIGHTS
        ("app.openid.right-by-roles.\"\".admin" -> "false"),
        ("app.openid.right-by-roles.\"\".tenants.test.level" -> "read"),
        ("app.openid.right-by-roles.\"\".tenants.test.default-project-right" -> "read"),
        ("app.openid.right-by-roles.\"\".tenants.test.default-key-right" -> "read"),
        ("app.openid.right-by-roles.\"\".tenants.test.default-webhook-right" -> "read"),
        ("app.openid.right-by-roles.\"\".tenants.test.projects.proj" -> "read"),
        ("app.openid.right-by-roles.\"\".tenants.test.keys.apikey" -> "read"),
        // MAX INITIAL RIGHTS
        ("app.openid.right-by-roles.\"\".admin-allowed" -> "false"),
        ("app.openid.right-by-roles.\"\".tenants.test.max-tenant-right" -> "write"),
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
      val adminUpdateResponse = situation.updateUserRights(
        "Sam Tailor2",
        admin = true,
        rights = TestRights()
      )

      adminUpdateResponse.status mustBe BAD_REQUEST
    }

    "merge max rights correctly" in {
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
        ("app.openid.right-by-roles.admin.admin-allowed" -> "true"),
        ("app.openid.right-by-roles.admin.tenants.test.max-tenant-right" -> "admin"),
        ("app.openid.right-by-roles.admin.tenants.test.max-project-right" -> "admin"),
        ("app.openid.right-by-roles.admin.tenants.test.max-key-right" -> "admin"),
        ("app.openid.right-by-roles.admin.tenants.test.max-webhook-right" -> "admin"),
        ("app.openid.right-by-roles.\"\".admin-allowed" -> "true"),
        ("app.openid.right-by-roles.\"\".tenants.test.max-tenant-right" -> "admin"),
        ("app.openid.right-by-roles.\"\".tenants.test.max-project-right" -> "admin"),
        ("app.openid.right-by-roles.\"\".tenants.test.max-key-right" -> "admin"),
        ("app.openid.right-by-roles.\"\".tenants.test.max-webhook-right" -> "admin")
      )
      var situation: TestSituation = TestSituationBuilder()
        .withTenants(
          TestTenant("test").withProjectNames("proj").withApiKeyNames("apikey")
        )
        .withCustomConfiguration(initialConfig)
        .build()

      situation = situation.logAsOIDCUser("User1")
      situation = situation.logout()

      situation = situation.restartServerWithConf(
        initialConfig ++ Map(
          // MAX INITIAL RIGHTS
          ("app.openid.right-by-roles.admin.admin-allowed" -> "false"),
          ("app.openid.right-by-roles.admin.tenants.test.max-tenant-right" -> "write"),
          ("app.openid.right-by-roles.admin.tenants.test.max-project-right" -> "admin"),
          ("app.openid.right-by-roles.admin.tenants.test.max-key-right" -> "write"),
          ("app.openid.right-by-roles.admin.tenants.test.max-webhook-right" -> "read"),
          ("app.openid.right-by-roles.\"\".admin-allowed" -> "false"),
          ("app.openid.right-by-roles.\"\".tenants.test.max-tenant-right" -> "read"),
          ("app.openid.right-by-roles.\"\".tenants.test.max-project-right" -> "write"),
          ("app.openid.right-by-roles.\"\".tenants.test.max-key-right" -> "read"),
          ("app.openid.right-by-roles.\"\".tenants.test.max-webhook-right" -> "write")
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
        .as[String] mustEqual "Admin"
      (rights \ "rights" \ "tenants" \ "test" \ "defaultKeyRight")
        .as[String] mustEqual "Write"
      (rights \ "rights" \ "tenants" \ "test" \ "defaultWebhookRight")
        .as[String] mustEqual "Write"
      (rights \ "rights" \ "tenants" \ "test" \ "projects" \ "proj" \ "level")
        .as[String] mustEqual "Admin"
      (rights \ "rights" \ "tenants" \ "test" \ "keys" \ "apikey" \ "level")
        .as[String] mustEqual "Write"
    }

    "allow to have max rights without default rights for a tenant" in {
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
        ("app.openid.right-by-roles.\"\".admin-allowed" -> "true"),
        ("app.openid.right-by-roles.\"\".tenants.test.max-tenant-right" -> "admin"),
        ("app.openid.right-by-roles.\"\".tenants.test.max-project-right" -> "admin"),
        ("app.openid.right-by-roles.\"\".tenants.test.max-key-right" -> "admin"),
        ("app.openid.right-by-roles.\"\".tenants.test.max-webhook-right" -> "admin"),
        ("app.openid.right-by-roles.\"\".tenants.bar.max-tenant-right" -> "read"),
        ("app.openid.right-by-roles.\"\".tenants.bar.max-project-right" -> "write"),
        ("app.openid.right-by-roles.\"\".tenants.bar.max-key-right" -> "read"),
        ("app.openid.right-by-roles.\"\".tenants.bar.max-webhook-right" -> "read")
      )
      var situation: TestSituation = TestSituationBuilder()
        .withTenants(
          TestTenant("test").withProjectNames("proj").withApiKeyNames("apikey"),
          TestTenant("bar").withProjectNames("proj2")
        )
        .withCustomConfiguration(initialConfig)
        .build()

      situation = situation.logAsOIDCUser("User1")
      situation = situation.logout()
      situation = situation.loggedAsAdmin()
      var response = situation.updateUserRightsForTenant("Sam Tailor", rights = TestTenantRight(name = "bar", level = "Write"))
      response.status mustBe BAD_REQUEST
      response = situation.updateUserRightsForTenant("Sam Tailor", rights = TestTenantRight(name = "bar", level = "Read"))
      response.status mustBe NO_CONTENT
      response = situation.updateUserRightsForTenant("Sam Tailor", rights = TestTenantRight(name = "bar", level = "Read", defaultProjectRight = Some("Admin")))
      response.status mustBe BAD_REQUEST
      response = situation.updateUserRightsForTenant("Sam Tailor", rights = TestTenantRight(name = "bar", level = "Read", defaultProjectRight = Some("Write")))
      response.status mustBe NO_CONTENT
    }

    // prevent rights from tenant without default rights

  }
}
