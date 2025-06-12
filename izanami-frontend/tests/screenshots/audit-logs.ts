import { screenshotBuilder, setup } from "./utils";
import {
  testBuilder,
  testFeature,
  testTenant,
  testProject,
} from "../testBuilder";
import { expect } from "@playwright/test";

export async function generate() {
  const { page, browser } = await setup(true);

  const screenshot = screenshotBuilder("audit-logs")(page);

  await testBuilder()
    .withTenant(
      testTenant("bookstore").withProject(
        testProject("website")
          .withFeature(testFeature("comments"))
          .withFeature(testFeature("summer-sales"))
          .withFeature(testFeature("phone-support"))
          .withFeature(testFeature("new-layout"))
      )
    )
    .build(page);

  await page.goto("/tenants/bookstore/projects/website");

  await page.getByRole("checkbox", { name: "select all rows" }).check();
  await page.getByLabel("Bulk action").click();
  await page.getByRole("option", { name: "Enable" }).click();
  await page.getByRole("button", { name: "Enable 4 features" }).click();

  await expect(page.getByRole("cell", { name: "Enabled" })).toHaveCount(4);

  await page.getByLabel("Bulk action").click();
  await page.getByRole("option", { name: "Disable" }).click();
  await page.getByRole("button", { name: "Disable 4 features" }).click();
  await expect(page.getByRole("cell", { name: "Disabled" })).toHaveCount(4);

  await page.getByLabel("Bulk action").click();
  await page.getByRole("option", { name: "Delete" }).click();
  await page.getByRole("button", { name: "Delete 4 features" }).click();
  await page.getByLabel("Confirm").click();
  await expect(page.getByRole("cell", { name: "Disabled" })).toHaveCount(0);

  await page.goto("/tenants/bookstore/projects/website/logs");

  await page
    .getByRole("columnheader", { name: "User" })
    .scrollIntoViewIfNeeded();

  await screenshot("log-table");

  await page.getByRole("button", { name: "actions" }).first().click();
  await page.getByRole("link", { name: "Details" }).click();
  await page.getByLabel("Display JSON diff").scrollIntoViewIfNeeded();

  await screenshot("natural-language-diff");
  await page.getByLabel("Display JSON diff").click();
  await page.getByRole("button", { name: "Close" }).scrollIntoViewIfNeeded();
  await page.evaluate(() => window.scrollBy(0, -230));
  await screenshot("json-diff");

  await page.getByRole("button", { name: "Close" }).click();
  await page.getByLabel("Event type").click();
  await page.getByRole("option", { name: "Updated" }).click();
  await page.getByRole("button", { name: "Search", exact: true }).click();
  await screenshot("search-form");
}

//generate();
