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

test.describe("Tenant audit screen should", () => {
  test("display log events for tenant", async ({
    page,
    tenantName,
    browser,
    context,
  }) => {
    let tenant = testTenant(tenantName);
    for (let i = 0; i < 10; i++) {
      let project = testProject(`project-${i}`);

      for (let j = 0; j < 10; j++) {
        project = project.withFeature(testFeature(`p${i} feature ${j}`));
      }

      tenant = tenant.withProject(project);
    }

    const situation = await testBuilder().withTenant(tenant).build(page);

    await page.goto(`/tenants/${tenantName}`);

    await page.getByRole("link", { name: "project-1" }).click();
    await page.getByRole("checkbox", { name: "select p1 feature 0" }).check();
    await page.getByRole("checkbox", { name: "select p1 feature 1" }).check();
    await page.getByRole("checkbox", { name: "select p1 feature 2" }).check();
    await page.getByRole("combobox", { name: "Bulk action" }).click();
    await page.getByRole("option", { name: "Enable" }).click();
    await page.getByRole("button", { name: "Enable 3 features" }).click();
    await page
      .getByRole("link", { name: "chromium-display-log-events" })
      .click();
    await page.getByRole("link", { name: "project-9" }).click();
    await page.getByRole("checkbox", { name: "select p9 feature 0" }).check();
    await page.getByRole("checkbox", { name: "select p9 feature 2" }).check();
    await page.getByRole("checkbox", { name: "select p9 feature 5" }).check();
    await page.getByRole("combobox", { name: "Bulk action" }).click();

    await page.getByRole("option", { name: "Delete" }).click();
    await page.getByRole("button", { name: "Delete 3 features" }).click();
    await page.getByLabel("Confirm").click();
    await page
      .getByRole("link", { name: "chromium-display-log-events" })
      .click();
    await page.getByRole("link", { name: "project-7" }).click();
    await page.getByRole("checkbox", { name: "select p7 feature 5" }).check();
    await page.getByRole("checkbox", { name: "select p7 feature 6" }).check();
    await page.getByRole("checkbox", { name: "select p7 feature 7" }).check();
    await page.getByRole("combobox", { name: "Bulk action" }).click();
    await page.getByRole("option", { name: "Transfer" }).click();
    await page.getByRole("combobox", { name: "Target project" }).click();
    await page.getByText("project-0", { exact: true }).click();
    await page.getByRole("button", { name: "Transfer 3 features" }).click();
    await page.getByLabel("Confirm").click();
    await page.getByRole("link", { name: "Logs" }).nth(1).click();

    await expect(page.getByText("119 results")).toBeVisible();

    await page.getByRole("link", { name: "Next" }).click();
    await page.getByRole("link", { name: "Next" }).click();
    await page.getByRole("link", { name: "Next" }).click();
    await page.getByRole("link", { name: "Next" }).click();
    await page.getByRole("link", { name: "Next" }).click();

    await expect(page.getByRole("link", { name: "6" })).toBeVisible();

    await page.getByRole("combobox", { name: "Event type select" }).click();
    await page.getByRole("option", { name: "Feature deleted" }).click();
    await page.getByRole("button", { name: "Search", exact: true }).click();
    await expect(page.getByText("3 results")).toBeVisible();
    await expect(
      page.getByRole("link", { name: "1", exact: true })
    ).toBeVisible();
    await expect(
      page.getByRole("link", { name: "2", exact: true })
    ).toBeHidden();
    await page.getByRole("combobox", { name: "Event type" }).click();
    await page.getByRole("option", { name: "Feature updated" }).click();
    await page.getByRole("button", { name: "Search", exact: true }).click();
    await expect(page.getByText("9 results")).toBeVisible();
    await page
      .getByRole("combobox", { name: "Select entity to search for" })
      .click();
    await page
      .getByRole("combobox", { name: "Select entity to search for" })
      .fill("project-9");
    await page.getByRole("option", { name: "project-9", exact: true }).click();
    await page.getByRole("button", { name: "Search", exact: true }).click();
    await expect(page.getByText("3 results")).toBeVisible();
  });
});
