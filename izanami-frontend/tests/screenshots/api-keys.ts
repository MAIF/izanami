import { screenshotBuilder, setup } from "./utils";
import {
  testBuilder,
  testFeature,
  testTenant,
  testProject,
} from "../testBuilder";

export async function generate() {
  const { page, browser } = await setup(true);

  const screenshot = screenshotBuilder("api-keys")(page);

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

  await page.goto("/tenants/bookstore/keys");

  await page.getByRole("button", { name: "Create new key" }).hover();
  await screenshot("key-empty-form");
  await page.getByRole("button", { name: "Create new key" }).click();

  await page.getByLabel("Name*", { exact: true }).fill("website-key");
  await page
    .locator("div")
    .filter({ hasText: /^Enabled$/ })
    .getByRole("checkbox")
    .check();

  await page.getByRole("combobox", { name: "projects" }).click();
  await page.getByText("website", { exact: true }).click();
  await page.getByRole("button", { name: "Save" }).hover();
  await screenshot("key-filled-form");

  await page.getByRole("button", { name: "Save" }).click();
  await screenshot("key-modal");

  await browser.close();
}

//generate();
