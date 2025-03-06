import { Page } from "@playwright/test";
import { test, expect } from "./izanami-test";
import {
  testBuilder,
  testFeature,
  testTenant,
  testProject,
} from "./testBuilder";

test.use({
  headless: true,
});

test.describe("Project audit screen should", () => {
  test("display log events", async ({ page, tenantName, browser, context }) => {
    const situation = await testBuilder()
      .withTenant(
        testTenant(tenantName).withProject(
          testProject("project")
            .withFeature(testFeature("f1"))
            .withFeature(testFeature("f2"))
            .withFeature(testFeature("f3"))
            .withFeature(testFeature("f4"))
            .withFeature(testFeature("f5"))
            .withFeature(testFeature("f6"))
            .withFeature(testFeature("f7"))
            .withFeature(testFeature("f8"))
            .withFeature(testFeature("f9"))
        )
      )
      .build(page);
    await page.goto(`/tenants/${tenantName}/projects/project/logs`);

    await expect(page.getByText("9 results")).toBeVisible();
    await expect(
      page.getByRole("row", { name: "RESERVED_ADMIN_USER" })
    ).toHaveCount(9);
    await expect(page.getByRole("row", { name: "Created" })).toHaveCount(9);
    await expect(
      page.getByRole("row", { name: "Triggered from backoffice" })
    ).toHaveCount(9);

    await page.goto(`/tenants/${tenantName}/projects/project`);
    await page
      .getByRole("row", { name: "f1" })
      .getByRole("button", { name: "copy feature id" })
      .click();
    await page.getByRole("checkbox", { name: "select f1" }).check();
    await page.getByRole("checkbox", { name: "select f2" }).check();
    await page.getByRole("checkbox", { name: "select f4" }).check();
    await page.getByLabel("Bulk action").click();
    await page.getByRole("option", { name: "Delete" }).click();
    await page.getByRole("button", { name: "Delete 3 features" }).click();
    await page.getByLabel("Confirm").click();
    await page.getByRole("link", { name: "project logs" }).click();
    await expect(page.getByText("12 results")).toBeVisible();

    await expect(page.getByRole("cell", { name: "Deleted" })).toHaveCount(3);

    await page.getByText("Event type").click();
    await page.getByRole("option", { name: "Deleted" }).click();
    await page.getByRole("button", { name: "Search", exact: true }).click();
    await expect(page.getByText("3 results")).toBeVisible();

    await page.getByLabel("Feature").click();
    const id = await (
      await page.evaluateHandle(() => navigator.clipboard.readText())
    ).jsonValue();

    await page.keyboard.type(id);
    await page.getByRole("option", { name: "Create" }).click();
    await page.getByRole("button", { name: "Search", exact: true }).click();
    await expect(page.getByText("1 results")).toBeVisible();

    await page.goto(`/tenants/${tenantName}/projects/project`);

    await page.getByRole("checkbox", { name: "select all rows" }).check();
    await page.getByLabel("Bulk action").click();
    await page.getByRole("option", { name: "Enable" }).click();
    await page.getByRole("button", { name: "Enable 6 features" }).click();
    await expect(page.getByRole("cell", { name: "Enabled" })).toHaveCount(6);
    await page.getByRole("combobox", { name: "Bulk action" }).click();
    await page.getByRole("option", { name: "Disable" }).click();
    await page.getByRole("button", { name: "Disable 6 features" }).click();
    await expect(page.getByRole("cell", { name: "Disabled" })).toHaveCount(6);
    await page.getByLabel("Bulk action").click();
    await page.getByRole("option", { name: "Enable" }).click();
    await page.getByRole("button", { name: "Enable 6 features" }).click();
    await expect(page.getByRole("cell", { name: "Enabled" })).toHaveCount(6);
    await page.getByRole("link", { name: "project logs" }).click();
    await expect(page.getByText("30 results")).toBeVisible();
    await expect(page.locator("li").filter({ hasText: "..." })).toBeVisible();
    await expect(page.getByRole("cell", { name: "Updated" })).toHaveCount(18);
    await expect(page.getByRole("cell", { name: "Deleted" })).toHaveCount(2);

    await page.locator("a").filter({ hasText: "»" }).click();
    await expect(page.getByRole("cell", { name: "Deleted" })).toHaveCount(1);
    await expect(page.getByRole("cell", { name: "Created" })).toHaveCount(9);
    await expect(page.locator("li").filter({ hasText: "..." })).toBeHidden();

    await page.getByRole("button", { name: "sort in ascending order" }).click();
    await expect(page.locator("li").filter({ hasText: "..." })).toBeVisible();
    await expect(
      page.getByRole("link", { name: "2", exact: true })
    ).toBeHidden();
    await expect(page.getByRole("cell", { name: "Created" })).toHaveCount(9);
    await expect(page.getByRole("cell", { name: "Deleted" })).toHaveCount(3);
    await expect(page.getByRole("cell", { name: "Updated" })).toHaveCount(8);

    await page.getByLabel("Feature").click();
    await page.getByRole("option", { name: "f9" }).click();
    await page.getByRole("button", { name: "Search", exact: true }).click();

    await expect(page.getByRole("cell", { name: "Updated" })).toHaveCount(3);
    await expect(page.getByRole("cell", { name: "Created" })).toHaveCount(1);

    const newPage = await context.newPage();
    await newPage.goto(page.url());

    await expect(newPage.getByRole("cell", { name: "Updated" })).toHaveCount(3);
    await expect(newPage.getByRole("cell", { name: "Created" })).toHaveCount(1);
  });
});
