import { openActions, screenshotBuilder, setup } from "./utils";
import {
  testBuilder,
  testTenant,
  testProject,
  testGlobalContext,
} from "../testBuilder";
import { base64Mobile } from "./base64-mobile-wasm";

export async function generate() {
  const { page, browser } = await setup(true);

  const screenshot = screenshotBuilder("base64-wasm")(page);

  await testBuilder()
    .withTenant(
      testTenant("bookstore")
        .withProject(testProject("website"))
        .withContext(
          testGlobalContext("prod").withSubContext(testGlobalContext("mobile"))
        )
        .withContext(
          testGlobalContext("dev").withLocalSubContext(
            testGlobalContext("mobile")
          )
        )
        .withContext(testGlobalContext("mobile"))
    )
    .build(page);

  await page.goto("/tenants/bookstore/projects/website");
  await page.getByRole("button", { name: "Create new feature" }).click();
  await page.getByLabel("Name").fill("is-mobile");
  await page.getByLabel("Enabled").check();
  await page.getByRole("combobox", { name: "Feature type" }).click();
  await page.getByText("New WASM script", { exact: true }).click();
  await page.getByLabel("Script name").click();
  await page.getByLabel("Script name").fill("mobile-check");
  await page.getByRole("combobox", { name: "Kind" }).click();
  await page.getByText("Base64", { exact: true }).click();
  await page.getByLabel("Base64 encoded plugin").click();
  await page.getByLabel("Base64 encoded plugin").fill(base64Mobile);
  await page.getByLabel("Open Policy Agent").check();
  await screenshot("filled-feature-form");
  await page.getByRole("button", { name: "Save" }).click();
  await openActions(page, "is-mobile");

  await page.getByRole("link", { name: "Test feature" }).click();
  await page.getByRole("combobox", { name: "Context" }).click();
  await page.getByText("mobile", { exact: true }).click();
  await page.getByRole("button", { name: "Test feature", exact: true }).click();
  await page.getByText('"active": true').click();
  await page.waitForTimeout(200);
  await screenshot("active-mobile");

  await page
    .getByRole("combobox", { name: "Context mobile", exact: true })
    .click();
  await page.getByText("prod/mobile", { exact: true }).click();
  await page.getByRole("button", { name: "Test feature", exact: true }).click();
  await page.getByText('"active": true').click();
  await page.waitForTimeout(200);
  await screenshot("active-prod-mobile");

  await page
    .getByRole("combobox", { name: "Context prod/mobile", exact: true })
    .click();
  await page.getByText("prod", { exact: true }).click();
  await page.getByRole("button", { name: "Test feature", exact: true }).click();
  await page.getByText('"active": false').click();
  await page.waitForTimeout(200);
  await screenshot("inactive-prod");

  await browser.close();
}

//generate();
