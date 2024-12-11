import { screenshotBuilder, setup, openActions } from "./utils";
import { testBuilder, testTenant, testProject } from "../testBuilder";

export async function generate() {
  const { page, browser } = await setup(true);

  const screenshot = screenshotBuilder("multi-condition-feature")(page);

  await testBuilder()
    .withTenant(testTenant("bookstore").withProject(testProject("website")))
    .build(page);

  await page.goto("http://localhost:3000/tenants/bookstore/projects/website");

  await page.getByRole("button", { name: "Create new feature" }).click();
  await page.getByLabel("Name").fill("summer-sales");
  await page.getByRole("button", { name: "Add condition" }).focus();
  await page.getByRole("button", { name: "Add condition" }).hover();
  await screenshot("add-condition-hover");
  await page.getByRole("button", { name: "Add condition" }).click();
  await page.getByLabel("Active only on specific").focus();
  await screenshot("specific-period-focus");
  await page.getByLabel("Active only on specific").check();
  await page.getByLabel("date-range-from").fill("2024-06-01T08:00");
  await page.getByLabel("date-range-to").fill("2024-06-24T23:59");

  await screenshot("feature-form");

  await page.getByRole("button", { name: "Save" }).click();
  await screenshot("summer-sale-summary");

  await openActions(page);
  await page.getByRole("link", { name: "Edit" }).hover();
  await screenshot("edit-hover");

  await page.getByRole("link", { name: "Edit" }).click();
  await page.getByRole("button", { name: "Add condition (OR)" }).click();
  const secondConditionLocator = page
    .getByRole("heading", {
      name: "Activation condition #1",
    })
    .locator("..");
  await secondConditionLocator.click();
  await secondConditionLocator.getByLabel("Active only on specific").check();
  await secondConditionLocator
    .getByRole("combobox", { name: "Strategy to use" })
    .click();
  await secondConditionLocator.getByText("User list").click();
  await secondConditionLocator
    .getByRole("combobox", { name: "Users that should activate feature" })
    .click();
  await secondConditionLocator
    .getByRole("combobox", { name: "Users that should activate feature" })
    .fill("beta-tester1");
  await secondConditionLocator
    .getByText('Create "beta-tester1"', { exact: true })
    .click();
  await secondConditionLocator
    .getByRole("combobox", { name: "Users that should activate feature" })
    .fill("beta-tester2");
  await secondConditionLocator
    .getByText('Create "beta-tester2"', { exact: true })
    .click();
  await secondConditionLocator
    .getByLabel("date-range-from")
    .fill("2024-05-15T08:00");
  await secondConditionLocator
    .getByLabel("date-range-to")
    .fill("2024-06-24T23:59");
  await screenshot("second-condition-form");
  await page.getByRole("button", { name: "Save" }).click();
  await screenshot("summer-sale-summary2");

  await browser.close();
}

//generate();
