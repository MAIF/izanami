package fr.maif.izanami.helpers

import fr.maif.izanami.api.BaseAPISpec.{TestUser, _}

import java.time.{DayOfWeek, LocalDateTime, LocalTime}

object DemoDataLoader {
  def main(args: Array[String]): Unit = {
    cleanUpDB(hard = true)
    TestSituationBuilder()
      .withTenants(
        TestTenant("demo")
          .withProjects(
            TestProject("stock"),
            TestProject("shop")
              .withContexts(
                TestFeatureContext(
                  "prod",
                  isProtected = true,
                  subContext =
                    Set(TestFeatureContext("mobile", isProtected = true)),
                  overloads = Seq(TestFeature("new ui", enabled = false))
                ),
                TestFeatureContext(
                  "dev",
                  subContext = Set(TestFeatureContext("mobile"))
                )
              )
              .withFeatures(TestFeature("new ui"), TestFeature("summer sales"))
          )
          .withGlobalContext(
            TestFeatureContext(
              "globalprod",
              isProtected = true,
              subContext =
                Set(TestFeatureContext("subglobal", isProtected = true))
            ),
            TestFeatureContext(
              "publicglobal",
              subContext = Set(
                TestFeatureContext("subpublicprotected", isProtected = true)
              )
            )
          )
      )
      .withUsers(
        TestUser("benjamin")
          .withTenantReadRight("demo")
          .withProjectReadWriteRight(project = "stock", tenant = "demo")
          .withProjectReadWriteRight(project = "shop", tenant = "demo")
      )
      .build()
    System.exit(0)
  }
}
