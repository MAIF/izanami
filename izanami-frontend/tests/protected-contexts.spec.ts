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
import { clickAction, logAs, logAsInNewPage } from "./utils";
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
    await testBuilder()
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
    await testBuilder()
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

  test("prevent feature to be deleted if user is not admin", async ({
    page,
    tenantName,
  }) => {
    await testBuilder()
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

    await logAs(page, "testu");
    await page.goto(`/tenants/${tenantName}/projects/project`);

    await clickAction(page, "Delete");
    await expect(
      page.getByText(
        "You don't have admin right on this project, therefore you can't delete this feature."
      )
    ).toBeVisible();
    await page.getByRole("button", { name: "Close" }).click();
  });
});

test.describe(
  "non protected overload update with protected context child should",
  async () => {
    test("allow to keep old strategy in impacted protected context if user is admin", async ({
      page,
      tenantName,
    }) => {
      await testBuilder()
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

      await page
        .getByRole("checkbox", { name: "Duplicate old strategy for" })
        .check();
      await page.getByRole("button", { name: "Confirm" }).click();
      const row = page
        .getByRole("row", { name: "unprotected/protected" })
        .filter({ hasNotText: "Feature overloads" });
      await expect(
        row.getByRole("rowheader", { name: "unprotected/protected" })
      ).toBeVisible();
      await expect(row.getByRole("cell", { name: "Enabled" })).toBeVisible();
    });

    test("allow to not keep old strategy in impacted protected context if user is admin", async ({
      page,
      tenantName,
    }) => {
      await testBuilder()
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

      await page
        .getByRole("textbox", { name: "Type feature name below to" })
        .click();
      await page
        .getByRole("textbox", { name: "Type feature name below to" })
        .fill("f1");
      await page.getByRole("button", { name: "Confirm" }).click();

      await page.getByRole("button", { name: "Confirm" }).click();
      const row = page
        .getByRole("row", { name: "unprotected" })
        .filter({ hasNotText: "Feature overloads" });
      await expect(
        row.getByRole("rowheader", { name: "protected" })
      ).toBeVisible();
      await expect(row.getByRole("cell", { name: "Disabled" })).toBeVisible();
    });

    test("force to keep old strategy in impacted protected context if user is not admin", async ({
      page,
      tenantName,
    }) => {
      await testBuilder()
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

      await logAs(page, "testu");

      await page.goto(`/tenants/${tenantName}/projects/project`);

      await page.getByRole("button", { name: "Overload" }).click();
      await page
        .getByRole("cell", { name: "Feature overloads" })
        .getByLabel("actions")
        .click();

      await page.getByRole("link", { name: "Edit" }).click();
      await page.getByRole("checkbox", { name: "Enabled" }).uncheck();
      await page.getByRole("button", { name: "Save" }).click();

      await expect(
        page.getByText("You don't have enough rights to update these contexts")
      ).toBeVisible();

      await page.getByRole("button", { name: "Confirm" }).click();
      const row = page
        .getByRole("row", { name: "unprotected/protected" })
        .filter({ hasNotText: "Feature overloads" });
      await expect(
        row.getByRole("rowheader", { name: "unprotected/protected" })
      ).toBeVisible();
      await expect(row.getByRole("cell", { name: "Enabled" })).toBeVisible();
    });
  }
);

test.describe(
  "non protected overload delete with protected context child should",
  async () => {
    test("allow to not protect old strategy if user is admin", async ({
      page,
      tenantName,
    }) => {
      await testBuilder()
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

      await page.getByRole("link", { name: "Delete" }).click();

      await expect(page.locator("#root")).toMatchAriaSnapshot(`
      - list:
        - listitem: protected
      - checkbox "Duplicate old strategy for these contexts"
      - text: Duplicate old strategy for these contexts Type feature name below to confirm.
      - textbox "Type feature name below to confirm."
      `);

      await page
        .getByRole("textbox", { name: "Type feature name below to" })
        .fill("f1");
      await page.getByRole("button", { name: "Confirm" }).click();
      await page
        .getByRole("cell", { name: "No items yet", exact: true })
        .click();
    });

    test("allow to protect old strategy if user is admin", async ({
      page,
      tenantName,
    }) => {
      await testBuilder()
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

      await page.getByRole("link", { name: "Delete" }).click();

      await expect(page.locator("#root")).toMatchAriaSnapshot(`
      - list:
        - listitem: protected
      - checkbox "Duplicate old strategy for these contexts"
      - text: Duplicate old strategy for these contexts Type feature name below to confirm.
      - textbox "Type feature name below to confirm."
      `);

      await page
        .getByRole("checkbox", { name: "Duplicate old strategy for" })
        .check();
      await page.getByRole("button", { name: "Confirm" }).click();
      await expect(
        page.getByRole("rowheader", { name: "unprotected/protected" })
      ).toBeVisible();
    });

    test("force to keep old strategy in protected context if user is not admin", async ({
      page,
      tenantName,
    }) => {
      await testBuilder()
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
      await logAs(page, "testu");

      await page.goto(`/tenants/${tenantName}/projects/project`);

      await page.getByRole("button", { name: "Overload" }).click();
      await page
        .getByRole("cell", { name: "Feature overloads" })
        .getByLabel("actions")
        .click();

      await page.getByRole("link", { name: "Delete" }).click();

      await expect(
        page.getByText("You don't have enough rights to update these contexts")
      ).toBeVisible();

      await page.getByRole("button", { name: "Confirm" }).click();
      await expect(
        page.getByRole("rowheader", { name: "unprotected/protected" })
      ).toBeVisible();
    });
  }
);
