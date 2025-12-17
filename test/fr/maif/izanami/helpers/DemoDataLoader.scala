package fr.maif.izanami.helpers

import fr.maif.izanami.api.BaseAPISpec.{TestUser, _}

import java.time.{DayOfWeek, LocalDateTime, LocalTime}

object DemoDataLoader {
  def main(args: Array[String]): Unit = {
    cleanUpDB(hard = true)
    val situation = TestSituationBuilder()
      .withTenants(
        TestTenant("demo")
          .withProjects(
            TestProject("shop")
              .withFeatures(TestFeature("new ui"), TestFeature("summer sales"))
          )
          .withGlobalContext(
            TestFeatureContext(
              "prod",
              isProtected = true
            ),
            TestFeatureContext(
              "formation",
              isProtected = true
            ),
            TestFeatureContext(
              "dev",
              isProtected = false
            ),
            TestFeatureContext(
              "sandbox",
              isProtected = false
            )
          )
      )
      .withUsers(
        TestUser("benjamin")
          .withTenantReadRight("demo")
          .withProjectReadWriteRight(project = "shop", tenant = "demo")
      )
      .loggedInWithAdminRights()
      .build()

    val res = situation.changeFeatureStrategyForContext(
      tenant = "demo",
      project = "shop",
      contextPath = "formation",
      enabled = false,
      feature = "new ui"
    )
    System.exit(0)
  }
}
