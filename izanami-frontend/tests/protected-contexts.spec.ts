import { Page } from "@playwright/test";
import { test, expect } from "./izanami-test";
import {
  testBuilder,
  testFeature,
  testTenant,
  testProject,
  testGlobalContext,
  testOverload,
  testLocalContext,
  testUser,
  testTenantRight,
} from "./testBuilder";
import { clickAction, logAsInNewPage } from "./utils";
import { TestContext } from "node:test";
import { TLevel } from "../src/utils/types";

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

  test("offer to keep old strategy in impacted protected context if user is admin", async ({
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
                .withOverload(testOverload("/unprotected", true))
            )
            .withContext(
              testLocalContext("unprotected").withSubContext(
                testLocalContext("protected").withProtectedStatus(true)
              )
            )
        )
      )
      .build(page);
    await page.goto(`/tenants/${tenantName}/projects/project`);

    await page.getByRole("button", { name: "Overload" }).click();
    await page
      .getByRole("cell", { name: "Feature overloads" })
      .getByLabel("actions")
      .click();

    await page.getByRole("link", { name: "Edit" }).click();
    await page.getByRole("checkbox", { name: "Enabled" }).uncheck();
    await page.getByRole("button", { name: "Save" }).click();

    await expect(page.locator("#root")).toMatchAriaSnapshot(`
      - list:
        - listitem: protected
      - checkbox "Duplicate old strategy for these contexts"
      - text: Duplicate old strategy for these contexts Type feature name below to confirm.
      - textbox "Type feature name below to confirm."
      `);
  });

  test("force to keep old strategy in impacted protected context if user is not admin", async ({
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
                .withOverload(testOverload("/unprotected", true))
            )
            .withContext(
              testLocalContext("unprotected").withSubContext(
                testLocalContext("protected").withProtectedStatus(true)
              )
            )
        )
      )
      .withUser(
        testUser("testu").withTenantRight(
          tenantName,
          testTenantRight(TLevel.Write).withProjectRight(
            "project",
            TLevel.Write
          )
        )
      )
      .build(page);

    const otherPage = await logAsInNewPage(browser, "testu");

    await otherPage.goto(`/tenants/${tenantName}/projects/project`);

    await otherPage.getByRole("button", { name: "Overload" }).click();
    await otherPage
      .getByRole("cell", { name: "Feature overloads" })
      .getByLabel("actions")
      .click();

    await otherPage.getByRole("link", { name: "Edit" }).click();
    await otherPage.getByRole("checkbox", { name: "Enabled" }).uncheck();
    await otherPage.getByRole("button", { name: "Save" }).click();

    await expect(
      otherPage.getByText(
        "You don't have enough rights to update these contexts"
      )
    ).toBeVisible();

    await otherPage.getByRole("button", { name: "Confirm" }).click();
    await expect(
      otherPage.getByRole("rowheader", { name: "unprotected/protected" })
    ).toBeVisible();
  });
});
