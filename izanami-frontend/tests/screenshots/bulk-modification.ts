import { screenshotBuilder, setup } from "./utils";
import {
  testBuilder,
  testFeature,
  testTenant,
  testProject,
} from "../testBuilder";

export async function generate() {
  const { page, browser } = await setup(true);

  const screenshot = screenshotBuilder("bulk-modification")(page);

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

  await page.goto("http://localhost:3000/tenants/bookstore/projects/website");

  await page.getByRole("checkbox", { name: "select comments" }).check();
  await page.getByRole("checkbox", { name: "select summer-sales" }).check();
  await screenshot("selected-rows");
  await page.getByRole("combobox", { name: "Bulk action" }).click();
  await page.getByText("Enable", { exact: true }).hover();
  await screenshot("action-menu");
  await page.getByText("Enable", { exact: true }).click();
  await page.getByRole("button", { name: "Enable 2 features" }).hover();
  await screenshot("button-hover");
  await page.getByRole("button", { name: "Enable 2 features" }).click();

  await screenshot("final-state");
  await browser.close();
}

//generate();
