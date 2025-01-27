import { Page } from "@playwright/test";
import { test, expect } from "./izanami-test";
import {
  testBuilder,
  testFeature,
  testTenant,
  testProject,
} from "./testBuilder";
import { clickAction } from "./utils";

test.use({
  headless: true,
});

async function featureAction(page: Page, name: string) {
  const dropdownLocator = page.getByLabel("actions");
  const locator = page.getByRole("link", { name: name, exact: true });

  await dropdownLocator.click();
  try {
    await locator.waitFor();
  } catch {
    try {
      await dropdownLocator.click();
      await locator.waitFor();
    } catch {
      await dropdownLocator.click();
    }
  }
  await locator.click({ force: true });
}

test.describe("Project screen should", () => {
  test("allow to create features", async ({ page, tenantName }) => {
    const situation = await testBuilder()
      .withTenant(testTenant(tenantName).withProject(testProject("project")))
      .build(page);
    await page.goto(`/tenants/${tenantName}/projects/project`);

    await page.getByRole("button", { name: "Create new feature" }).click();
    await page.getByLabel("Name").fill("feature");
    await page.getByRole("button", { name: "Save" }).click();

    await expect(
      page.getByRole("rowheader", { name: "feature", exact: true })
    ).toBeVisible();
    await expect(page.getByRole("cell", { name: "Disabled" })).toBeVisible();
  });

  test("allow to test created features", async ({ page, tenantName }) => {
    const situation = await testBuilder()
      .withTenant(
        testTenant(tenantName).withProject(
          testProject("project").withFeature(testFeature("feature"))
        )
      )
      .build(page);
    await page.goto(`/tenants/${tenantName}/projects/project`);

    await page.waitForLoadState("domcontentloaded");
    await featureAction(page, "Test feature");
    await page
      .getByRole("button", { name: "Test feature", exact: true })
      .click();

    await expect(page.getByText('"active": false,')).toBeVisible();
  });

  test("allow to test feature while creating it", async ({
    page,
    tenantName,
  }) => {
    const situation = await testBuilder()
      .withTenant(testTenant(tenantName).withProject(testProject("project")))
      .build(page);
    await page.goto(`/tenants/${tenantName}/projects/project`);

    await page.getByRole("button", { name: "Create new feature" }).click();
    await page.getByLabel("Enabled").check();
    await page.getByRole("button", { name: "Test feature" }).click();
    await expect(page.getByText('"active": true,')).toBeVisible();
  });

  test("allow to create dated features", async ({ page, tenantName }) => {
    const situation = await testBuilder()
      .withTenant(testTenant(tenantName).withProject(testProject("project")))
      .build(page);
    await page.goto(`/tenants/${tenantName}/projects/project`);

    await page.getByRole("button", { name: "Create new feature" }).click();
    await page.getByLabel("Name").fill("myname");
    await page.getByRole("button", { name: "Add condition" }).click();
    await page.getByLabel("Active only on specific periods").check();
    await page.getByLabel("date-range-from").fill("2010-01-01T10:15");
    await page.getByLabel("date-range-to").fill("2030-12-25T23:59");
    await page.getByLabel("Remove SATURDAY").click();
    await page.getByLabel("Remove SUNDAY").click();
    await page.getByRole("button", { name: "Add", exact: true }).click();
    await page.getByLabel("From", { exact: true }).fill("10:00");
    await page.getByLabel("to", { exact: true }).fill("15:00");
    await page.getByRole("button", { name: "Save" }).click();

    await expect(
      page.getByRole("cell", {
        name: "Active : from January 1st, 2010 at 10:15 AM to December 25th, 2030 at 11:59 PM on MONDAY, TUESDAY, WEDNESDAY, THURSDAY, FRIDAY from 10:00:00 to 15:00:00 For all users",
      })
    ).toBeVisible();
  });

  test("allow to create user group features", async ({ page, tenantName }) => {
    const situation = await testBuilder()
      .withTenant(testTenant(tenantName).withProject(testProject("project")))
      .build(page);
    await page.goto(`/tenants/${tenantName}/projects/project`);

    await page.getByRole("button", { name: "Create new feature" }).click();
    await page.getByLabel("Name").fill("fifou");
    await page.getByRole("button", { name: "Add condition" }).click();

    /*await page
      .getByRole("combobox", { name: "Strategy to use" })
      .click({ force: true });*/
    //await page.getByLabel("Strategy to use").click({ force: true });
    //await page.getByLabel("Activation strategy").click({ force: true });
    await page.getByRole("combobox", { name: "Strategy to use" }).focus();
    await page.keyboard.press("ArrowDown");
    await page.keyboard.press("ArrowDown");
    await page.keyboard.press("Enter");
    await page
      .getByLabel("Percentage of users that should activate feature")
      .fill("75");
    await page.getByRole("button", { name: "Add condition (OR)" }).click();
    await page;
    await page
      .getByRole("combobox", { name: "Strategy to use" })
      .nth(1)
      .focus();
    await page.keyboard.press("ArrowDown");
    await page.keyboard.press("ArrowDown");
    await page.keyboard.press("ArrowDown");
    await page.keyboard.press("Enter");
    await page
      .getByLabel("Users that should activate feature")
      .nth(1)
      .fill("user1");
    await page.getByText('Create "user1"', { exact: true }).click();
    await page
      .getByRole("combobox", { name: "Users that should activate feature" })
      .click();
    await page
      .getByLabel("Users that should activate feature")
      .nth(1)
      .fill("user2");
    await page.getByText('Create "user2"', { exact: true }).click();
    await page.getByRole("button", { name: "Save" }).click();
    await page
      .getByRole("cell", {
        name: "Active : For 75% of users -OR- Active : Only for : user1, user2",
      })
      .click();
  });

  test("allow to update activation status", async ({ page, tenantName }) => {
    const situation = await testBuilder()
      .withTenant(
        testTenant(tenantName).withProject(
          testProject("project").withFeature(
            testFeature("myname").withDisableStatus()
          )
        )
      )
      .build(page);
    await page.goto(`/tenants/${tenantName}/projects/project`);

    await expect(page.getByRole("row", { name: "myname" })).toBeVisible();
    await expect(page.getByRole("cell", { name: "Enabled" })).toHaveCount(0);

    await featureAction(page, "Edit");
    await page.getByLabel("Enabled").check();
    await page.getByRole("button", { name: "Save" }).click();
    await expect(page.getByRole("cell", { name: "Enabled" })).toBeVisible();
  });

  test("allow to update time conditions", async ({ page, tenantName }) => {
    const situation = await testBuilder()
      .withTenant(testTenant(tenantName).withProject(testProject("project")))
      .build(page);
    await page.goto(`/tenants/${tenantName}/projects/project`);

    await page.getByRole("button", { name: "Create new feature" }).click();
    await page.getByLabel("Name").fill("test");
    await page.getByRole("button", { name: "Add condition" }).click();
    await page.getByLabel("Active only on specific periods").check();
    await page.getByRole("button", { name: "Add", exact: true }).click();
    await page.getByLabel("From", { exact: true }).fill("10:00");
    await page.getByLabel("to", { exact: true }).fill("14:00");
    await page.getByLabel("date-range-to").fill("2030-01-01T10:10");
    await page.getByLabel("Remove SUNDAY").click();
    await page.getByLabel("Remove SATURDAY").click();
    await page.getByRole("button", { name: "Save" }).click();
    await expect(page.getByRole("row", { name: "test" })).toBeVisible();

    await featureAction(page, "Edit");
    await page.getByRole("button", { name: "Activation condition #0" }).click();
    await page.getByLabel("date-range-from").fill("2023-10-25T00:00");
    await page.getByLabel("date-range-to").fill("2030-01-02T10:10");
    await page.getByLabel("Remove TUESDAY").click();
    await page.getByRole("combobox", { name: "Activation days" }).focus();
    await page.keyboard.type("SUN");
    await page.keyboard.press("ArrowDown");
    await page.keyboard.press("Enter");
    await page.getByLabel("From", { exact: true }).fill("11:01");
    await page.getByLabel("to", { exact: true }).fill("15:15");
    await page.getByRole("button", { name: "Save" }).click();

    await page
      .getByRole("cell", {
        name: "Active : from October 25th, 2023 at 12:00 AM to January 2nd, 2030 at 10:10 AM on MONDAY, WEDNESDAY, THURSDAY, FRIDAY, SUNDAY from 11:01:00 to 15:15:00 for all users",
      })
      .click();
  });

  test("allow bulk activate desactivate", async ({ page, tenantName }) => {
    const situation = await testBuilder()
      .withTenant(
        testTenant(tenantName).withProject(
          testProject("project")
            .withFeature(testFeature("test"))
            .withFeature(testFeature("test2").withEnableStatus())
            .withFeature(testFeature("test3"))
        )
      )
      .build(page);
    await page.goto(`/tenants/${tenantName}/projects/project`);

    await page.getByRole("checkbox", { name: "select all rows" }).check();
    await page.getByLabel("Bulk action").focus();
    await page.keyboard.press("ArrowDown");
    await page.keyboard.type("Enabl");
    await page.keyboard.press("ArrowDown");
    await page.keyboard.press("Enter");
    await page.getByRole("button", { name: "Enable 3 features" }).click();
    await expect(page.getByRole("cell", { name: "Enabled" })).toHaveCount(3);

    await page.getByRole("checkbox", { name: "select all rows" }).check();
    await page.getByLabel("Bulk action").focus();
    await page.keyboard.press("ArrowDown");
    await page.keyboard.type("Disabl");
    await page.keyboard.press("ArrowDown");
    await page.keyboard.press("Enter");
    await page.getByRole("button", { name: "Disable 3 features" }).click();
    await expect(page.getByRole("cell", { name: "Disabled" })).toHaveCount(3);
  });

  test("allow bulk delete", async ({ page, tenantName }) => {
    const situation = await testBuilder()
      .withTenant(
        testTenant(tenantName).withProject(
          testProject("project")
            .withFeature(testFeature("test"))
            .withFeature(testFeature("test2").withEnableStatus())
            .withFeature(testFeature("test3"))
        )
      )
      .build(page);
    await page.goto(`/tenants/${tenantName}/projects/project`);

    await page.getByRole("checkbox", { name: "select all rows" }).check();
    await page.getByLabel("Bulk action").focus();
    await page.keyboard.press("ArrowDown");
    await page.keyboard.type("Dele");
    await page.keyboard.press("ArrowDown");
    await page.keyboard.press("Enter");
    await page.getByRole("button", { name: "Delete 3 features" }).click();
    await page.getByRole("button", { name: "Confirm" }).click();
    await expect(page.getByRole("row", { name: "test" })).toHaveCount(0);
  });

  test("allow feature duplication", async ({ page, tenantName }) => {
    const situation = await testBuilder()
      .withTenant(
        testTenant(tenantName).withProject(
          testProject("project").withFeature(testFeature("test"))
        )
      )
      .build(page);
    await page.goto(`/tenants/${tenantName}/projects/project`);

    await featureAction(page, "Duplicate");
    await page.getByLabel("Name").fill("test-bis");
    await page.getByRole("button", { name: "Save" }).click();
    await expect(page.getByRole("row", { name: "test-bis" })).toHaveCount(1);
  });

  test("allow feature deletion", async ({ page, tenantName }) => {
    const situation = await testBuilder()
      .withTenant(
        testTenant(tenantName).withProject(
          testProject("project").withFeature(testFeature("test"))
        )
      )
      .build(page);
    await page.goto(`/tenants/${tenantName}/projects/project`);

    await featureAction(page, "Delete");
    await page.getByRole("button", { name: "Confirm" }).click();
    await expect(page.getByRole("row", { name: "test" })).toHaveCount(0);
  });
});

test.describe("Multi type features", () => {
  test("should be creatable", async ({ page, tenantName }) => {
    const situation = await testBuilder()
      .withTenant(testTenant(tenantName).withProject(testProject("project")))
      .build(page);

    await page.goto(`/tenants/${tenantName}/projects/project`);
    await page.getByRole("button", { name: "Create new feature" }).click();
    await page.getByLabel("Name*").fill("str feature");
    await page.getByLabel("Enabled").check();
    await page.getByRole("combobox", { name: "Feature result type" }).click();
    await page.getByText("string").click();
    await page.getByLabel("Base value*").fill("foo");
    await page
      .getByRole("button", { name: "Test feature", exact: true })
      .click();

    await expect(page.getByText('"active": "foo"')).toBeVisible();
    await page.getByRole("button", { name: "Save" }).click();

    await clickAction(page, "Test feature", "str feature");
    await page
      .getByRole("button", { name: "Test feature", exact: true })
      .click();
    await expect(page.getByText('"active": "foo"')).toBeVisible();

    await page.getByRole("link", { name: "Global contexts" }).click();
    await page.getByRole("button", { name: "Create new context" }).click();
    await page.getByPlaceholder("Context name").fill("globalctx");
    await page.getByPlaceholder("Context name").press("Enter");

    await page.goto(`/tenants/${tenantName}/projects/project`);
    await featureAction(page, "Overloads");
    await page.getByRole("button", { name: "Create new overload" }).click();
    await page.getByRole("combobox", { name: "Context" }).click();
    await page.getByText("globalctx").click();
    await page.getByLabel("Base value*").fill("bar");
    await page.getByRole("button", { name: "Save" }).click();
    await expect(page.getByRole("button", { name: "Save" })).toBeHidden();
    await clickAction(page, "Test feature", "str feature");
    await page
      .getByRole("button", { name: "Test feature", exact: true })
      .click();
    await expect(page.getByText('"active": "foo"')).toBeVisible();

    await page.getByRole("combobox", { name: "Context" }).click();
    await page.getByText("globalctx").click();
    await page
      .getByRole("button", { name: "Test feature", exact: true })
      .click();

    await expect(page.getByText('"active": "bar"')).toBeVisible();

    await clickAction(page, "Edit", "str feature");
    await page.getByRole("button", { name: "Add alternative value" }).click();
    await page.getByLabel("Alternative value").fill("bobv");
    await page.getByRole("combobox", { name: "Strategy to use" }).click();
    await page.getByText("User list").click();
    await page
      .getByRole("combobox", { name: "Users that should activate feature" })
      .fill("bob");
    await page.getByText(`Create "bob"`).click();
    await page.getByRole("button", { name: "Save" }).click();

    await page.getByRole("link", { name: "Query builder" }).click();
    await page.getByRole("combobox", { name: "Features" }).click();
    await page.getByText("str feature").click();
    await page.getByRole("button", { name: "Test it!" }).click();
    await expect(page.getByRole("cell", { name: "foo" })).toBeVisible();
    await page.getByText("Context Select...").click();
    await page.getByRole("option", { name: "/globalctx" }).click();

    await page.getByRole("button", { name: "Test it!" }).click();
    await expect(page.getByRole("cell", { name: "bar" })).toBeVisible();
    await page.getByLabel("User", { exact: true }).fill("bob");
    await page.getByRole("button", { name: "Test it!" }).click();
    await expect(page.getByRole("cell", { name: "bar" })).toBeVisible();
    await page.getByRole("combobox", { name: "Context" }).click();
    await page.keyboard.press("Backspace");
    await page.getByRole("button", { name: "Test it!" }).click();
    await page.getByRole("cell", { name: "bobv" }).click();
  });
});
