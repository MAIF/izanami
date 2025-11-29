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
import { clickAction, logAs } from "./utils";
import { TLevel } from "../src/utils/types";

test.use({
  headless: true,
});

test("1-protected overload should ask textual confirmation before deletion", async ({
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

test("2-protected overload should ask textual confirmation before update", async ({
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

test("3-protected overload should prevent feature to be deleted if user is not admin", async ({
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
        testTenantRight(TLevel.Write).withProjectRight("project", TLevel.Write)
      )
    )
    .build(page);

  await logAs(page, "testu");
  await page.goto(`/tenants/${tenantName}/projects/project`);

  await clickAction(page, "Delete");
  await expect(
    page.getByText(
      "You don't have admin rights on this project, therefore you can't delete this feature."
    )
  ).toBeVisible();
  await page.getByRole("button", { name: "Close" }).click();
});

test("4-non protected overload update with protected context child should-allow to keep old strategy in impacted protected context if user is admin", async ({
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

test("5-non protected overload update with protected context child should-allow to keep old strategy in impacted protected context if user is admin (toggle button)", async ({
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
  await page.getByRole("checkbox", { name: "Disable f1" }).click();

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

test("6-non protected overload update with protected context child should-allow to not keep old strategy in impacted protected context if user is admin", async ({
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

  const row = page
    .getByRole("row", { name: "unprotected" })
    .filter({ hasNotText: "Feature overloads" });
  await expect(row.getByRole("rowheader", { name: "protected" })).toBeVisible();
  await expect(row.getByRole("cell", { name: "Disabled" })).toBeVisible();
});

test("7-non protected overload update with protected context child should-allow to not keep old strategy in impacted protected context if user is admin (toggle button)", async ({
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

  await page.getByRole("checkbox", { name: "Disable f1" }).click();

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

  const row = page
    .getByRole("row", { name: "unprotected" })
    .filter({ hasNotText: "Feature overloads" });
  await expect(row.getByRole("rowheader", { name: "protected" })).toBeVisible();
  await expect(row.getByRole("cell", { name: "Disabled" })).toBeVisible();
});

test("8-non protected overload update with protected context child should-force to keep old strategy in impacted protected context if user is not admin", async ({
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
      testUser("testu8").withTenantRight(
        tenantName,
        testTenantRight(TLevel.Write).withProjectRight("project", TLevel.Write)
      )
    )
    .build(page);

  await logAs(page, "testu8");

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

test("9-non protected overload update with protected context child should-force to keep old strategy in impacted protected context if user is not admin (toggle button)", async ({
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
      testUser("testu9").withTenantRight(
        tenantName,
        testTenantRight(TLevel.Write).withProjectRight("project", TLevel.Write)
      )
    )
    .build(page);

  await logAs(page, "testu9");

  await page.goto(`/tenants/${tenantName}/projects/project`);

  await page.getByRole("button", { name: "Overload" }).click();

  await page.getByRole("checkbox", { name: "Disable f1" }).click();

  await expect(page.locator("form")).toMatchAriaSnapshot(`
          - text: "You don't have enough rights to update these contexts, therefore old strategy will be applied to below contexts:"
          - list:
            - listitem: protected
          `);
  await page.getByRole("button", { name: "Confirm" }).click();

  const row = page
    .getByRole("row", { name: "unprotected/protected" })
    .filter({ hasNotText: "Feature overloads" });
  await expect(
    row.getByRole("rowheader", { name: "unprotected/protected" })
  ).toBeVisible();
  await expect(row.getByRole("cell", { name: "Enabled" })).toBeVisible();
});

test("10-non protected overload delete with protected context child should allow to not protect old strategy if user is admin", async ({
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

  await page.getByRole("link", { name: "Delete", exact: true }).click();

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
  await page.getByRole("cell", { name: "No items yet", exact: true }).click();
});

test("11-non protected overload delete with protected context child should allow to protect old strategy if user is admin", async ({
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

  await page.getByRole("link", { name: "Delete", exact: true }).click();

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

test("12-non protected overload delete with protected context child should force to keep old strategy in protected context if user is not admin", async ({
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
      testUser("testu12").withTenantRight(
        tenantName,
        testTenantRight(TLevel.Write).withProjectRight("project", TLevel.Write)
      )
    )
    .build(page);
  await logAs(page, "testu12");

  await page.goto(`/tenants/${tenantName}/projects/project`);

  await page.getByRole("button", { name: "Overload" }).click();
  await page
    .getByRole("cell", { name: "Feature overloads" })
    .getByLabel("actions")
    .click();

  await page.getByRole("link", { name: "Delete", exact: true }).click();

  await expect(
    page.getByText("You don't have enough rights to update these contexts")
  ).toBeVisible();

  await page.getByRole("button", { name: "Confirm" }).click();
  await expect(
    page.getByRole("rowheader", { name: "unprotected/protected" })
  ).toBeVisible();
});

test("13-feature with protected context without overload should be possible to update for non admin user wile forcing startegy preservation", async ({
  tenantName,
  page,
}) => {
  await testBuilder()
    .withTenant(
      testTenant(tenantName).withProject(
        testProject("project")
          .withFeature(testFeature("f1").withDisableStatus())
          .withContext(testLocalContext("protected").withProtectedStatus(true))
      )
    )
    .withUser(
      testUser("testu13").withTenantRight(
        tenantName,
        testTenantRight(TLevel.Write).withProjectRight("project", TLevel.Write)
      )
    )
    .loggedAs("testu13")
    .build(page);

  await page.goto(`/tenants/${tenantName}/projects/project`);

  await clickAction(page, "Edit");
  await page.getByRole("checkbox", { name: "Enabled" }).check();
  await page.getByRole("button", { name: "Save" }).click();
  await expect(page.locator("#root")).toMatchAriaSnapshot(`
        - text: "You don't have enough rights to update these contexts, therefore old strategy will be applied to below contexts:"
        - list:
          - listitem: protected
        `);
  await page.getByRole("button", { name: "Confirm" }).click();
  await page.getByRole("button", { name: "Overload" }).click();
  await expect(
    page.getByRole("rowheader", { name: "protected" })
  ).toBeVisible();
});

test("14-feature with protected context without overload should be possible to update for non admin user wile forcing startegy preservation (toggle button)", async ({
  tenantName,
  page,
}) => {
  await testBuilder()
    .withTenant(
      testTenant(tenantName).withProject(
        testProject("project")
          .withFeature(testFeature("f1").withDisableStatus())
          .withContext(testLocalContext("protected").withProtectedStatus(true))
      )
    )
    .withUser(
      testUser("testu14").withTenantRight(
        tenantName,
        testTenantRight(TLevel.Write).withProjectRight("project", TLevel.Write)
      )
    )
    .loggedAs("testu14")
    .build(page);

  await page.goto(`/tenants/${tenantName}/projects/project`);

  await page.getByRole("checkbox", { name: "Enable f1" }).click();

  await expect(page.locator("#root")).toMatchAriaSnapshot(`
        - text: "You don't have enough rights to update these contexts, therefore old strategy will be applied to below contexts:"
        - list:
          - listitem: protected
        `);
  await page.getByRole("button", { name: "Confirm" }).click();
  await page.getByRole("button", { name: "Overload" }).click();
  await expect(
    page.getByRole("rowheader", { name: "protected" })
  ).toBeVisible();
});

test("15-feature with protected context without overload should be possible to update while preserving strategy for admin user", async ({
  tenantName,
  page,
}) => {
  await testBuilder()
    .withTenant(
      testTenant(tenantName).withProject(
        testProject("project")
          .withFeature(testFeature("f1").withDisableStatus())
          .withContext(testLocalContext("protected").withProtectedStatus(true))
      )
    )
    .build(page);

  await page.goto(`/tenants/${tenantName}/projects/project`);

  await clickAction(page, "Edit");
  await page.getByRole("checkbox", { name: "Enabled" }).check();
  await page.getByRole("button", { name: "Save" }).click();

  await expect(page.locator("#root")).toMatchAriaSnapshot(`
        - list:
          - listitem: protected
        - text: These contexts strategies can be left unchanged by duplicating old strategy in them.
        - checkbox "Duplicate old strategy for these contexts"
        - text: Duplicate old strategy for these contexts Type feature name below to confirm.
        - textbox "Type feature name below to confirm."
        `);

  await page
    .getByRole("checkbox", { name: "Duplicate old strategy for" })
    .check();
  await page.getByRole("button", { name: "Confirm" }).click();
  await page.getByRole("button", { name: "Overload" }).click();
  await expect(
    page.getByRole("rowheader", { name: "protected" })
  ).toBeVisible();
});

test("16-feature with protected context without overload should be possible to update while preserving strategy for admin user (toggle button)", async ({
  tenantName,
  page,
}) => {
  await testBuilder()
    .withTenant(
      testTenant(tenantName).withProject(
        testProject("project")
          .withFeature(testFeature("f1").withDisableStatus())
          .withContext(testLocalContext("protected").withProtectedStatus(true))
      )
    )
    .build(page);

  await page.goto(`/tenants/${tenantName}/projects/project`);

  await page.getByRole("checkbox", { name: "Enable f1" }).click();

  await expect(page.locator("#root")).toMatchAriaSnapshot(`
        - list:
          - listitem: protected
        - text: These contexts strategies can be left unchanged by duplicating old strategy in them.
        - checkbox "Duplicate old strategy for these contexts"
        - text: Duplicate old strategy for these contexts Type feature name below to confirm.
        - textbox "Type feature name below to confirm."
        `);

  await page
    .getByRole("checkbox", { name: "Duplicate old strategy for" })
    .check();
  await page.getByRole("button", { name: "Confirm" }).click();
  await page.getByRole("button", { name: "Overload" }).click();
  await expect(
    page.getByRole("rowheader", { name: "protected" })
  ).toBeVisible();
});

test("17-feature with protected context without overload should be possible to update without preserving strategy for admin user", async ({
  tenantName,
  page,
}) => {
  await testBuilder()
    .withTenant(
      testTenant(tenantName).withProject(
        testProject("project")
          .withFeature(testFeature("f1").withDisableStatus())
          .withContext(testLocalContext("protected").withProtectedStatus(true))
      )
    )
    .build(page);

  await page.goto(`/tenants/${tenantName}/projects/project`);

  await clickAction(page, "Edit");
  await page.getByRole("checkbox", { name: "Enabled" }).check();
  await page.getByRole("button", { name: "Save" }).click();

  await expect(page.locator("#root")).toMatchAriaSnapshot(`
        - list:
          - listitem: protected
        - text: These contexts strategies can be left unchanged by duplicating old strategy in them.
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

  await expect(page.locator("tbody")).toMatchAriaSnapshot(`
          - cell "Disable f1 Enabled":
            - checkbox "Disable f1" [checked]
          `);

  await expect(
    page.getByRole("button", { name: "Overload" })
  ).not.toBeVisible();
});

test("18-feature with protected context without overload should be possible to update without preserving strategy for admin user (toggle button)", async ({
  tenantName,
  page,
}) => {
  await testBuilder()
    .withTenant(
      testTenant(tenantName).withProject(
        testProject("project")
          .withFeature(testFeature("f1").withDisableStatus())
          .withContext(testLocalContext("protected").withProtectedStatus(true))
      )
    )
    .build(page);

  await page.goto(`/tenants/${tenantName}/projects/project`);

  await page.getByRole("checkbox", { name: "Enable f1" }).click();

  await expect(page.locator("#root")).toMatchAriaSnapshot(`
        - list:
          - listitem: protected
        - text: These contexts strategies can be left unchanged by duplicating old strategy in them.
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

  await expect(page.locator("tbody")).toMatchAriaSnapshot(`
          - cell "Disable f1 Enabled":
            - checkbox "Disable f1" [checked]
          `);

  await expect(
    page.getByRole("button", { name: "Overload" })
  ).not.toBeVisible();
});

test("19-feature with protected context without overload should be impossible to delete for non admin user", async ({
  page,
  tenantName,
}) => {
  await testBuilder()
    .withTenant(
      testTenant(tenantName).withProject(
        testProject("project")
          .withFeature(testFeature("f1").withDisableStatus())
          .withContext(testLocalContext("protected").withProtectedStatus(true))
      )
    )
    .withUser(
      testUser("testu19").withTenantRight(
        tenantName,
        testTenantRight(TLevel.Write).withProjectRight("project", TLevel.Write)
      )
    )
    .loggedAs("testu19")
    .build(page);

  await page.goto(`/tenants/${tenantName}/projects/project`);

  await clickAction(page, "Delete");

  //await page.getByRole("button", { name: "actions" }).click();
  //await page.getByRole("link", { name: "Delete", exact: true }).click();
  await expect(page.locator("#root")).toMatchAriaSnapshot(`
        - text: "Deleting feature f1 would impact below protected contexts:"
        - list:
          - listitem: protected
        - paragraph: You don't have admin rights on this project, therefore you can't delete this feature.
        `);
  await page.getByRole("button", { name: "Close" }).click();
});
