package fr.maif.izanami.helpers

import fr.maif.izanami.api.BaseAPISpec.*

object OIDCSecurityDemoLoader {
  def main(args: Array[String]): Unit = {
    cleanUpDB(hard = true)

    val situation = TestSituationBuilder()
      .loggedInWithAdminRights()
      .withTenants(
        TestTenant("openbar")
          .withGlobalContext(
            TestFeatureContext(name = "prod", isProtected = true),
            TestFeatureContext(name = "dev", isProtected = false)
          )
          .withProjects(
            TestProject("shop").withFeatureNames("new-ui", "sales"),
            TestProject("store").withFeatureNames(
              "rating",
              "supplier-availability"
            )
          ),
        TestTenant("secret")
          .withGlobalContext(
            TestFeatureContext(name = "prod", isProtected = true),
            TestFeatureContext(name = "dev", isProtected = false)
          )
          .withProjects(
            TestProject("secret-car").withFeatureNames(
              "fifth-wheel",
              "swimmin-pool"
            ),
            TestProject("secret-boat").withFeatureNames("submarine", "fly")
          )
      )
      .withCustomConfiguration(Map(
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
        "app.openid.pkce.enabled" -> "true",
        // Openbar default rights
        "app.openid.right-by-roles.\"\".admin" -> "false",
        "app.openid.right-by-roles.\"\".tenants.openbar.level" -> "write",
        "app.openid.right-by-roles.\"\".tenants.openbar.default-project-right" -> "write",
        "app.openid.right-by-roles.\"\".tenants.openbar.default-key-right" -> "write",
        "app.openid.right-by-roles.\"\".tenants.openbar.default-webhook-right" -> "write",
        // Openbar admin rights
        "app.openid.right-by-roles.admin.admin" -> "false",
        "app.openid.right-by-roles.admin.tenants.openbar.level" -> "admin",
        "app.openid.right-by-roles.admin.tenants.openbar.default-project-right" -> "admin",
        "app.openid.right-by-roles.admin.tenants.openbar.default-key-right" -> "admin",
        "app.openid.right-by-roles.admin.tenants.openbar.default-webhook-right" -> "admin",
        // Secret admin rights
        "app.openid.right-by-roles.admin.tenants.secret.level" -> "read",
        "app.openid.right-by-roles.admin.tenants.secret.default-project-right" -> "write",
        "app.openid.right-by-roles.admin.tenants.secret.default-key-right" -> "read",
        "app.openid.right-by-roles.admin.tenants.secret.default-webhook-right" -> "read",
        // Secret max rights
        "app.openid.right-by-roles.\"\".admin-allowed" -> "false",
        "app.openid.right-by-roles.\"\".tenants.secret.max-tenant-right" -> "read",
        "app.openid.right-by-roles.\"\".tenants.secret.max-project-right" -> "read",
        "app.openid.right-by-roles.\"\".tenants.secret.max-key-right" -> "read",
        "app.openid.right-by-roles.\"\".tenants.secret.max-webhook-right" -> "read"
      ))
      .build()

    situation.changeFeatureStrategyForContext(
      "openbar",
      "shop",
      "prod",
      "new-ui",
      enabled = false
    )

    situation.changeFeatureStrategyForContext(
      "openbar",
      "store",
      "prod",
      "rating",
      enabled = false
    )

    situation.changeFeatureStrategyForContext(
      "secret",
      "secret-car",
      "prod",
      "fifth-wheel",
      enabled = false
    )

    situation.changeFeatureStrategyForContext(
      "secret",
      "secret-boat",
      "prod",
      "fly",
      enabled = false
    )

    System.exit(0)
  }
}
