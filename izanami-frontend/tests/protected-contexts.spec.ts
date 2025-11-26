import { Page } from "@playwright/test";
import { test, expect } from "./izanami-test";
import {
  testBuilder,
  testFeature,
  testTenant,
  testProject,
  testGlobalContext,
  testOverload,
} from "./testBuilder";
import { clickAction } from "./utils";
import { TestContext } from "node:test";

test.use({
  headless: true,
});

test.describe("Protected overload should", () => {
  test("ask textual confirmation before deletion", async ({
    page,
    tenantName,
  }) => {
    const situation = await testBuilder()
      .withTenant(
        testTenant(tenantName).withProject(
          testProject("project")
            .withFeature(
              testFeature("f1")
                .withDisableStatus()
                .withOverload(testOverload("/prod", true))
            )
            .withContext(testGlobalContext("prod").withProtectedStatus(true))
        )
      )
      .build(page);
    await page.goto(`/tenants/${tenantName}/projects/project`);

    await page.getByRole("button", { name: "Overload" }).click();
    await page
      .getByRole("cell", { name: "Feature overloads Create new" })
      .getByLabel("actions")
      .click();
    await page.getByRole("link", { name: "Delete" }).click();
    await page.getByRole("textbox", { name: "Confirmation" }).click();
    await page.getByRole("textbox", { name: "Confirmation" }).fill("f1");
    await page.getByRole("button", { name: "Confirm" }).click();
    await page.getByRole("cell", { name: "No items yet", exact: true }).click();
  });

  test("ask textual confirmation before update", async ({
    page,
    tenantName,
  }) => {
    const situation = await testBuilder()
      .withTenant(
        testTenant(tenantName).withProject(
          testProject("project")
            .withFeature(
              testFeature("f1")
                .withDisableStatus()
                .withOverload(testOverload("/prod", true))
            )
            .withContext(testGlobalContext("prod").withProtectedStatus(true))
        )
      )
      .build(page);
    await page.goto(`/tenants/${tenantName}/projects/project`);

    await page.getByRole("button", { name: "Overload" }).click();
    await page
      .getByRole("cell", { name: "Feature overloads Create new" })
      .getByLabel("actions")
      .click();

    await page.getByRole("link", { name: "Edit" }).click();
    await page.getByRole("checkbox", { name: "Enabled" }).uncheck();
    await page.getByRole("button", { name: "Save" }).click();
    await page.getByRole("textbox", { name: "Confirmation" }).click();

    await page.getByRole("button", { name: "Confirm" }).click();

    await page.getByRole("textbox", { name: "Confirmation" }).click();
    await page.getByRole("textbox", { name: "Confirmation" }).fill("f1");
    await page.getByRole("button", { name: "Confirm" }).click();
    await expect(
      page.getByRole("link", { name: "prod protected" })
    ).toBeVisible();
  });
});
