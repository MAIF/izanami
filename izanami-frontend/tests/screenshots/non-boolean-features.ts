import { screenshotBuilder, setup, openActions, featureAction } from "./utils";
import {
  testBuilder,
  testGlobalContext,
  testTenant,
  testProject,
} from "../testBuilder";

export async function generate() {
  const { page, browser } = await setup(true);

  const screenshot = screenshotBuilder("non-boolean-feature")(page);

  await testBuilder()
    .withTenant(
      testTenant("bookstore")
        .withProject(testProject("website"))
        .withContext(testGlobalContext("dev"))
    )
    .build(page);

  await page.goto("/tenants/bookstore/projects/website");

  await page.getByRole("button", { name: "Create new feature" }).click();
  await page.getByLabel("Name").fill("comments");
  await page.getByLabel("Enabled").check();
  await page.getByRole("combobox", { name: "Result type" }).click();
  await screenshot("result-type-menu");
  await page.getByText("string").click();
  await page.getByLabel("Base value").fill("disabled");

  await page.getByRole("button", { name: "Add alternative value" }).click();
  await page.getByLabel("Alternative value").fill("comments");
  await page.getByRole("combobox", { name: "Strategy to use" }).click();
  await page.getByText("Percentage").click();
  await page
    .getByLabel("Percentage of users that should activate feature")
    .fill("25");
  await screenshot("alternative-value");
  await page.getByRole("button", { name: "Save" }).click();

  await page.getByRole("rowheader", { name: "comments", exact: true }).click();
  await screenshot("string-feature-in-project");

  await featureAction(page, "Overloads");
  await page.getByRole("button", { name: "Create new overload" }).click();
  await page.getByRole("button", { name: "Delete", exact: true }).click();
  await page.getByRole("combobox", { name: "Context" }).click();
  await page.getByText("dev", { exact: true }).click();
  await page.getByLabel("Base value").fill("comments");
  await screenshot("overload-form");
  await page.getByRole("button", { name: "Save" }).click();
  await screenshot("overload-in-project");
}
