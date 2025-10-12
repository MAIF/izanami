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

test.describe("protected overload", async () => {
  test("1-should ask textual confirmation before deletion", async ({
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

  test("2-should ask textual confirmation before update", async ({
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

  test("3-should prevent feature to be deleted if user is not admin", async ({
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
        testUser("testu113").withTenantRight(
          tenantName,
          testTenantRight(TLevel.Write).withProjectRight(
            "project",
            TLevel.Write
          )
        )
      )
      .build(page);

    await logAs(page, "testu113");
    await page.goto(`/tenants/${tenantName}/projects/project`);

    await clickAction(page, "Delete");
    await expect(
      page.getByText(
        "You don't have admin rights on this project, therefore you can't delete this feature."
      )
    ).toBeVisible();
    await page.getByRole("button", { name: "Close" }).click();
  });
});

test.describe("non protected overload update", async () => {
  test("1-with protected context child should allow to keep old strategy in impacted protected context if user is admin", async ({
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

  test("2-with protected context child should-allow to keep old strategy in impacted protected context if user is admin (toggle button)", async ({
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

  test("3-with protected context child should-allow to not keep old strategy in impacted protected context if user is admin", async ({
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
    await expect(
      row.getByRole("rowheader", { name: "protected" })
    ).toBeVisible();
    await expect(row.getByRole("cell", { name: "Disabled" })).toBeVisible();
  });

  test("4-with protected context child should-allow to not keep old strategy in impacted protected context if user is admin (toggle button)", async ({
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
    await expect(
      row.getByRole("rowheader", { name: "protected" })
    ).toBeVisible();
    await expect(row.getByRole("cell", { name: "Disabled" })).toBeVisible();
  });

  test("5-with protected context child should-force to keep old strategy in impacted protected context if user is not admin", async ({
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
          testTenantRight(TLevel.Write).withProjectRight(
            "project",
            TLevel.Write
          )
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

  test("6-with protected context child should-force to keep old strategy in impacted protected context if user is not admin (toggle button)", async ({
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
          testTenantRight(TLevel.Write).withProjectRight(
            "project",
            TLevel.Write
          )
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
});

test.describe(
  "non protected overload delete with protected context child should",
  async () => {
    test("1-allow to not protect old strategy if user is admin", async ({
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
      await page
        .getByRole("cell", { name: "No items yet", exact: true })
        .click();
    });

    test("2-allow to protect old strategy if user is admin", async ({
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

    test("3-force to keep old strategy in protected context if user is not admin", async ({
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
            testTenantRight(TLevel.Write).withProjectRight(
              "project",
              TLevel.Write
            )
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
  }
);

test.describe(
  "feature with protected context without overload should",
  async () => {
    test("1-be possible to update for non admin user wile forcing startegy preservation", async ({
      tenantName,
      page,
    }) => {
      await testBuilder()
        .withTenant(
          testTenant(tenantName).withProject(
            testProject("project")
              .withFeature(testFeature("f1").withDisableStatus())
              .withContext(
                testLocalContext("protected").withProtectedStatus(true)
              )
          )
        )
        .withUser(
          testUser("testu13").withTenantRight(
            tenantName,
            testTenantRight(TLevel.Write).withProjectRight(
              "project",
              TLevel.Write
            )
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

    test("2-be possible to update for non admin user wile forcing startegy preservation (toggle button)", async ({
      tenantName,
      page,
    }) => {
      await testBuilder()
        .withTenant(
          testTenant(tenantName).withProject(
            testProject("project")
              .withFeature(testFeature("f1").withDisableStatus())
              .withContext(
                testLocalContext("protected").withProtectedStatus(true)
              )
          )
        )
        .withUser(
          testUser("testu14").withTenantRight(
            tenantName,
            testTenantRight(TLevel.Write).withProjectRight(
              "project",
              TLevel.Write
            )
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

    test("3-be possible to update while preserving strategy for admin user", async ({
      tenantName,
      page,
    }) => {
      await testBuilder()
        .withTenant(
          testTenant(tenantName).withProject(
            testProject("project")
              .withFeature(testFeature("f1").withDisableStatus())
              .withContext(
                testLocalContext("protected").withProtectedStatus(true)
              )
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

    test("4-be possible to update while preserving strategy for admin user (toggle button)", async ({
      tenantName,
      page,
    }) => {
      await testBuilder()
        .withTenant(
          testTenant(tenantName).withProject(
            testProject("project")
              .withFeature(testFeature("f1").withDisableStatus())
              .withContext(
                testLocalContext("protected").withProtectedStatus(true)
              )
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

    test("5-be possible to update without preserving strategy for admin user", async ({
      tenantName,
      page,
    }) => {
      await testBuilder()
        .withTenant(
          testTenant(tenantName).withProject(
            testProject("project")
              .withFeature(testFeature("f1").withDisableStatus())
              .withContext(
                testLocalContext("protected").withProtectedStatus(true)
              )
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

    test("6-be possible to update without preserving strategy for admin user (toggle button)", async ({
      tenantName,
      page,
    }) => {
      await testBuilder()
        .withTenant(
          testTenant(tenantName).withProject(
            testProject("project")
              .withFeature(testFeature("f1").withDisableStatus())
              .withContext(
                testLocalContext("protected").withProtectedStatus(true)
              )
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

    test("7-be impossible to delete for non admin user", async ({
      page,
      tenantName,
    }) => {
      await testBuilder()
        .withTenant(
          testTenant(tenantName).withProject(
            testProject("project")
              .withFeature(testFeature("f1").withDisableStatus())
              .withContext(
                testLocalContext("protected").withProtectedStatus(true)
              )
          )
        )
        .withUser(
          testUser("testu19").withTenantRight(
            tenantName,
            testTenantRight(TLevel.Write).withProjectRight(
              "project",
              TLevel.Write
            )
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

    test("8-be possible to update for non admin user if update concerns tags or description", async ({
      page,
      tenantName,
    }) => {
      await testBuilder()
        .withTenant(
          testTenant(tenantName).withProject(
            testProject("project")
              .withFeature(testFeature("f1").withDisableStatus())
              .withContext(
                testLocalContext("protected").withProtectedStatus(true)
              )
          )
        )
        .withUser(
          testUser("testu20").withTenantRight(
            tenantName,
            testTenantRight(TLevel.Write).withProjectRight(
              "project",
              TLevel.Write
            )
          )
        )
        .loggedAs("testu20")
        .build(page);

      await page.goto(`/tenants/${tenantName}/projects/project`);

      await clickAction(page, "Edit");
      await page.locator("label").filter({ hasText: "TagsSelect..." }).click();
      await page.getByRole("combobox", { name: "Tags" }).fill("foo");
      await page.getByRole("option", { name: 'Create "foo"' }).click();
      await page.getByRole("textbox", { name: "Description" }).click();
      await page
        .getByRole("textbox", { name: "Description" })
        .fill("hello world");
      await page.getByRole("button", { name: "Save" }).click();

      const row = page.getByRole("row", { name: "f1" });
      await expect(row.getByText("hello world")).toBeVisible();
      await row.getByRole("link", { name: "foo" }).click();
    });
  }
);

test.describe(
  "Overload creation in non protected context with protected context child",
  async () => {
    test("1-should allow admin to impact protected context without creating old strategy in it", async ({
      page,
      tenantName,
    }) => {
      await testBuilder()
        .withTenant(
          testTenant(tenantName).withProject(
            testProject("project")
              .withFeature(testFeature("f1").withDisableStatus())
              .withContext(
                testLocalContext("unprotected").withSubContext(
                  testLocalContext("protected").withProtectedStatus(true)
                )
              )
          )
        )
        .build(page);
      await page.goto(`/tenants/${tenantName}/projects/project`);

      await clickAction(page, "Overloads");
      await page.getByRole("button", { name: "Create new overload" }).click();
      await page.getByRole("checkbox", { name: "Enabled" }).check();
      await page.getByText("ContextSelect...").click();
      await page.getByText("unprotected", { exact: true }).click();
      await page.getByRole("button", { name: "Save" }).click();

      await expect(page.locator("#root")).toMatchAriaSnapshot(`
      - text: "Creatingf1 overload(s) for context unprotected will impact below protected contexts, since neither them nor their parents define overload for this feature:"
      - list:
        - listitem: unprotected/protected
      - text: These contexts strategies can be left unchanged by duplicating old strategy in them.
      - checkbox "Duplicate old strategy for these contexts"
      - textbox "Type feature name below to confirm."
      `);

      await page
        .getByRole("textbox", { name: "Type feature name below to" })
        .fill("f1");

      await page.getByRole("button", { name: "Confirm" }).click();

      const row = page
        .getByRole("row", { name: "unprotected" })
        .filter({ hasNotText: "Feature overloads" });
      await expect(
        row.getByRole("rowheader", { name: "unprotected" })
      ).toBeVisible();
      await expect(row.getByRole("cell", { name: "Enabled" })).toBeVisible();
    });

    test("2-should allow admin to create old strategy in child protected context", async ({
      page,
      tenantName,
    }) => {
      await testBuilder()
        .withTenant(
          testTenant(tenantName).withProject(
            testProject("project")
              .withFeature(testFeature("f1").withDisableStatus())
              .withContext(
                testLocalContext("unprotected").withSubContext(
                  testLocalContext("protected").withProtectedStatus(true)
                )
              )
          )
        )
        .build(page);
      await page.goto(`/tenants/${tenantName}/projects/project`);

      await clickAction(page, "Overloads");
      await page.getByRole("button", { name: "Create new overload" }).click();
      await page.getByRole("checkbox", { name: "Enabled" }).check();
      await page.getByText("ContextSelect...").click();
      await page.getByText("unprotected", { exact: true }).click();
      await page.getByRole("button", { name: "Save" }).click();

      await expect(page.locator("#root")).toMatchAriaSnapshot(`
      - text: "Creatingf1 overload(s) for context unprotected will impact below protected contexts, since neither them nor their parents define overload for this feature:"
      - list:
        - listitem: unprotected/protected
      - text: These contexts strategies can be left unchanged by duplicating old strategy in them.
      - checkbox "Duplicate old strategy for these contexts"
      - textbox "Type feature name below to confirm."
      `);

      await page
        .getByRole("checkbox", { name: "Duplicate old strategy for" })
        .check();
      await page.getByRole("button", { name: "Confirm" }).click();

      const unprotectedRow = page
        .getByRole("row", { name: "unprotected" })
        .filter({ hasNotText: "Feature overloads" })
        .first();

      await expect(
        unprotectedRow.getByRole("rowheader", { name: "unprotected" })
      ).toBeVisible();
      await expect(
        unprotectedRow.getByRole("cell", { name: "Enabled" })
      ).toBeVisible();

      const protectedRow = page
        .getByRole("row", { name: "unprotected/protected" })
        .filter({ hasNotText: "Feature overloads" });
      await expect(
        protectedRow.getByRole("rowheader", { name: "unprotected/protected" })
      ).toBeVisible();
      await expect(
        protectedRow.getByRole("cell", { name: "Disabled" })
      ).toBeVisible();
    });

    test("3-should force non admin user to create old strategy in child protected context", async ({
      page,
      tenantName,
    }) => {
      await testBuilder()
        .withTenant(
          testTenant(tenantName).withProject(
            testProject("project")
              .withFeature(testFeature("f1").withDisableStatus())
              .withContext(
                testLocalContext("unprotected").withSubContext(
                  testLocalContext("protected").withProtectedStatus(true)
                )
              )
          )
        )
        .withUser(
          testUser("testu133").withTenantRight(
            tenantName,
            testTenantRight(TLevel.Write).withProjectRight(
              "project",
              TLevel.Write
            )
          )
        )
        .loggedAs("testu133")
        .build(page);
      await page.goto(`/tenants/${tenantName}/projects/project`);

      await clickAction(page, "Overloads");
      await page.getByRole("button", { name: "Create new overload" }).click();
      await page.getByRole("checkbox", { name: "Enabled" }).check();
      await page.getByText("ContextSelect...").click();
      await page.getByText("unprotected", { exact: true }).click();
      await page.getByRole("button", { name: "Save" }).click();

      await expect(page.locator("#root")).toMatchAriaSnapshot(`
        - list:
          - listitem: unprotected/protected
        - text: "You don't have enough rights to update these contexts, therefore old strategy will be applied to below contexts:"
        - list:
          - listitem: unprotected/protected
        `);

      await page.getByRole("button", { name: "Confirm" }).click();

      const unprotectedRow = page
        .getByRole("row", { name: "unprotected" })
        .filter({ hasNotText: "Feature overloads" })
        .first();

      await expect(
        unprotectedRow.getByRole("rowheader", { name: "unprotected" })
      ).toBeVisible();
      await expect(
        unprotectedRow.getByRole("cell", { name: "Enabled" })
      ).toBeVisible();

      const protectedRow = page
        .getByRole("row", { name: "unprotected/protected" })
        .filter({ hasNotText: "Feature overloads" });
      await expect(
        protectedRow.getByRole("rowheader", { name: "unprotected/protected" })
      ).toBeVisible();
      await expect(
        protectedRow.getByRole("cell", { name: "Disabled" })
      ).toBeVisible();
    });
  }
);
