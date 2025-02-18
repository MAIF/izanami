package fr.maif.izanami.helpers

import fr.maif.izanami.api.BaseAPISpec.{TestUser, _}

import java.time.{DayOfWeek, LocalDateTime, LocalTime}

object DemoDataLoader {
  def main(args: Array[String]): Unit = {
    cleanUpDB(hard = true)
    TestSituationBuilder()
      .withTenants(
        TestTenant("demo")
          .withTagNames("tag1", "tag2", "tag3")
          .withProjects(
            TestProject("project-1", "The first project")
              .withFeatures(
                TestFeature("feature1", tags = Seq("tag1")),
                TestFeature("feature2", tags = Seq("tag2", "tag1"))
              )
              .withFeatures(
                TestFeature(
                  "weekly",
                  conditions = Set(
                    TestCondition(period =
                      TestDateTimePeriod()
                        .beginAt(LocalDateTime.now())
                        .endAt(LocalDateTime.now().plusWeeks(1))
                        .atHours(
                          TestHourPeriod(LocalTime.of(8, 0), LocalTime.of(12, 0)),
                          TestHourPeriod(LocalTime.of(14, 0), LocalTime.of(18, 0))
                        )
                        .atDays(
                          DayOfWeek.MONDAY,
                          DayOfWeek.TUESDAY,
                          DayOfWeek.WEDNESDAY,
                          DayOfWeek.THURSDAY,
                          DayOfWeek.FRIDAY
                        )
                    )
                  ),
                  tags = Seq("tag1", "tag2")
                )
              )
              .withContexts(
                TestFeatureContext(
                  "my-context",
                  Set(
                    TestFeatureContext("my-subcontext"),
                    TestFeatureContext(
                      "other-subcontext",
                      Set(
                        TestFeatureContext("subsubcontext").withFeatureOverload(
                          TestFeature(
                            "weekly",
                            enabled = false,
                            conditions = Set(
                              TestCondition(period =
                                TestDateTimePeriod(days = TestDayPeriod(days = Set(DayOfWeek.SUNDAY)))
                              )
                            )
                          )
                        )
                      )
                    )
                  )
                ),
                TestFeatureContext("another-context-without-children")
              ),
            TestProject("project-2", "The second project")
          )
          .withApiKeys(
            TestApiKey(
              name = "test-key",
              projects = Seq("project-1", "project-2"),
              description = "Key with all access"
            ),
            TestApiKey(
              name = "another-test-key",
              projects = Seq("project-1"),
              description = "Key tag1 / project-1 access"
            )
          )
      )
      .withTenants(
        TestTenant("demo2")
          .withProjects(TestProject("project21").withFeatureNames("feature211", "feature212"), TestProject("project22"))
          .withApiKeyNames("key21", "key22")
      )
      .withUsers(
        TestUser("myself")
          .withTenantAdminRight("demo")
          .withTenantReadWriteRight("demo2")
          .withProjectReadRight("project21", "demo2")
          .withProjectAdminRight("project22", "demo2")
          .withApiKeyAdminRight("key21", "demo2"),
        TestUser("lowrights")
          .withTenantReadRight("demo")
          .withProjectReadRight("project-1", "demo"),
        TestUser("tenantAdmin")
          .withTenantAdminRight("demo2"),
        TestUser("allTenantAdmin")
          .withTenantAdminRight("demo2")
          .withTenantAdminRight("demo")
      )
      .build()
    System.exit(0)
  }
}
