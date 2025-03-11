import { test, expect } from "./izanami-test";
import {
  testBuilder,
  testFeature,
  testTenant,
  testProject,
  testLocalContext,
  testUser,
  testTenantRight,
  testOverload,
} from "./testBuilder";
import { logAsInNewPage } from "./utils";

test.use({
  headless: true,
});

test.describe("Project screen overload table should", () => {
  test("hide delete and edit button if overload is on a protected context and user is not admin", async ({
    page,
    tenantName,
    browser,
  }) => {
    const situation = await testBuilder()
      .withTenant(
        testTenant(tenantName).withProject(
          testProject("project")
            .withFeature(
              testFeature("f1")
                .withDisableStatus()
                .withOverload(testOverload("ctx", true))
            )
            .withContext(testLocalContext("ctx").withProtectedStatus(true))
        )
      )
      .withUser(
        testUser("testu", false).withTenantRight(
          tenantName,
          testTenantRight("Write").withProjectRight("project", "Write")
        )
      )
      .build(page);
    const otherPage = await logAsInNewPage(browser, "testu");

    await otherPage.goto(`/tenants/${tenantName}/projects/project`);
    await otherPage.getByLabel("Overload").click();

    await expect(
      otherPage
        .getByRole("cell", { name: "Feature overloads" })
        .getByLabel("actions")
    ).not.toBeAttached();

    await page.goto(`/tenants/${tenantName}/projects/project`);
    await page.getByLabel("Overload").click();

    await expect(
      page
        .getByRole("cell", { name: "Feature overloads" })
        .getByLabel("actions")
    ).toBeAttached();
  });
});
