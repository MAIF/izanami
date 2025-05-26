import { test, expect } from "./izanami-test";
import {
  testBuilder,
  testFeature,
  testTenant,
  testProject,
  testLocalContext,
  testUser,
  testTenantRight,
} from "./testBuilder";
import { logAsInNewPage } from "./utils";

test.use({
  headless: true,
});

test.describe("Project context screen should", () => {
  test("hide protected contexts delete and unprotect button if user doesn't have right to see them", async ({
    page,
    tenantName,
    browser,
  }) => {
    const situation = await testBuilder()
      .withTenant(
        testTenant(tenantName).withProject(
          testProject("project").withContext(
            testLocalContext("ctx").withProtectedStatus(true)
          )
        )
      )
      .withUser(
        testUser(tenantName, false).withTenantRight(
          tenantName,
          testTenantRight("Write").withProjectRight("project", "Write")
        )
      )
      .build(page);

    await page.goto(`/tenants/${tenantName}/projects/project/contexts`);

    const otherPage = await logAsInNewPage(browser, tenantName);
    await otherPage.goto(`/tenants/${tenantName}/projects/project/contexts`);

    await expect(
      otherPage.getByRole("button", { name: "actions" })
    ).not.toBeAttached();
  });

  test("allow toggling context protection if user is admin", async ({
    page,
    tenantName,
  }) => {
    const situation = await testBuilder()
      .withTenant(testTenant(tenantName).withProject(testProject("project")))
      .build(page);
    await page.goto(`/tenants/${tenantName}/projects/project/contexts`);

    await page.getByRole("button", { name: "Create new context" }).click();
    await page.getByPlaceholder("Context name").fill("prod");
    await page.getByPlaceholder("Context name").press("Enter");
    await page.getByRole("button", { name: "actions" }).click();
    await page.getByRole("link", { name: "Protect context" }).click();
    await page.getByLabel("Confirm").click();

    await expect(
      page.getByRole("link", { name: "prod protected" })
    ).toBeVisible();

    await page.getByRole("button", { name: "actions" }).click();
    await page.getByRole("link", { name: "Unprotect context" }).click();
    await page.getByRole("textbox", { name: "Confirmation" }).fill("prod");
    await page.getByRole("button", { name: "Confirm" }).click();
    await expect(
      page.getByRole("link", { name: "prod", exact: true })
    ).toBeVisible();
  });

  test("allow to create root context", async ({ page, tenantName }) => {
    const situation = await testBuilder()
      .withTenant(testTenant(tenantName).withProject(testProject("project")))
      .build(page);
    await page.goto(`/tenants/${tenantName}/projects/project`);
    await page.getByRole("link", { name: "Contexts", exact: true }).click();
    await page.getByRole("button", { name: "Create new context" }).click();
    await page.getByLabel("Name").fill("root-ctx");
    await page.getByRole("button", { name: "Save" }).click();
    await expect(page.getByRole("link", { name: "root-ctx" })).toBeVisible();
  });

  test("allow to create subcontext", async ({ page, tenantName }) => {
    const situation = await testBuilder()
      .withTenant(testTenant(tenantName).withProject(testProject("project")))
      .build(page);
    await page.goto(`/tenants/${tenantName}/projects/project`);
    await page.getByRole("link", { name: "Contexts", exact: true }).click();
    await page.getByRole("button", { name: "Create new context" }).click();
    await page.getByLabel("Name").fill("root-ctx");
    await page.getByRole("button", { name: "Save" }).click();
    await page.getByRole("button", { name: "actions" }).click();
    await page.getByRole("link", { name: "Add subcontext" }).click();
    await page.getByLabel("Name").fill("subtcx");
    await page.getByRole("button", { name: "Save" }).click();
    await expect(page.getByRole("link", { name: "subtcx" })).toBeVisible();
  });

  test("allow to create feature overload", async ({ page, tenantName }) => {
    const situation = await testBuilder()
      .withTenant(
        testTenant(tenantName).withProject(
          testProject("project").withFeature(testFeature("testfeature"))
        )
      )
      .build(page);
    await page.goto(`/tenants/${tenantName}/projects/project`);

    await page.getByRole("link", { name: "Contexts", exact: true }).click();
    // TODO factorize
    await page.getByRole("button", { name: "Create new context" }).click();
    await page.getByLabel("Name").fill("root-ctx");
    await page.getByRole("button", { name: "Save" }).click();
    await page.getByRole("link", { name: "root-ctx" }).click();
    await page.getByRole("link", { name: "no overloads" }).click();
    await page.getByRole("button", { name: "Create new overload" }).click();
    await page.getByRole("combobox", { name: "name" }).focus();
    await page.keyboard.press("ArrowDown");
    await page.keyboard.press("ArrowDown");
    await page.keyboard.press("Enter");
    await page.getByLabel("Enabled").check();
    await page.getByRole("button", { name: "Save" }).click();
    await expect(page.getByRole("cell", { name: "Enabled" })).toBeVisible();
    await expect(page.getByRole("cell", { name: "testfeature" })).toBeVisible();
  });

  test("allow to display strategy for all features", async ({
    page,
    tenantName,
  }) => {
    const situation = await testBuilder()
      .withTenant(
        testTenant(tenantName).withProject(
          testProject("project")
            .withFeature(testFeature("testfeature"))
            .withFeature(testFeature("anotherfeature"))
        )
      )
      .build(page);
    await page.goto(`/tenants/${tenantName}/projects/project`);
    await page.getByRole("link", { name: "Contexts", exact: true }).click();
    // TODO factorize
    await page.getByRole("button", { name: "Create new context" }).click();
    await page.getByLabel("Name").fill("root-ctx");
    await page.getByRole("button", { name: "Save" }).click();
    await page.getByRole("link", { name: "root-ctx" }).click();
    await page.getByRole("link", { name: "no overloads" }).click();
    await page.getByRole("button", { name: "Create new overload" }).click();
    await page.getByRole("combobox", { name: "name" }).focus();
    await page.keyboard.type("another");
    await page.keyboard.press("ArrowDown");
    await page.keyboard.press("Enter");
    await page.getByLabel("Enabled").check({ force: true });
    await page.getByRole("button", { name: "Save" }).click();
    await expect(page.getByRole("cell", { name: "another" })).toHaveCount(1);
    await page
      .getByLabel("Display all features strategy for this context")
      .check();

    await expect(page.getByRole("cell", { name: "Enabled" })).toHaveCount(1);
    await expect(page.getByRole("cell", { name: "Disabled" })).toHaveCount(1);
    await expect(
      page.getByRole("cell", { name: "actions" }).getByLabel("actions")
    ).toHaveCount(1);
  });
});
