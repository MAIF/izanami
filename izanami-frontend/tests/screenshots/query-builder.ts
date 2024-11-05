import { screenshotBuilder, setup } from "./utils";
import {
  testBuilder,
  testFeature,
  testGlobalContext,
  testTenant,
  testProject,
} from "../testBuilder";

export async function generate() {
  const { page, browser } = await setup(true);

  const screenshot = screenshotBuilder("query-builder")(page);

  await testBuilder()
    .withTenant(
      testTenant("bookstore")
        .withProject(
          testProject("website")
            .withFeature(testFeature("comments").withEnableStatus())
            .withFeature(testFeature("summer-sales"))
        )
        .withProject(
          testProject("suppliers").withFeature(testFeature("advises"))
        )
        .withContext(testGlobalContext("QA"))
    )
    .build(page);

  await page.goto("http://localhost:3000/tenants/bookstore/query-builder");
  await page.getByRole("link", { name: "Query builder" }).hover();
  await screenshot("menu-item-hover");

  await page
    .locator("div")
    .filter({ hasText: /^Test it!$/ })
    .click();
  await page.getByRole("combobox", { name: "Projects" }).click();
  await page.getByText("suppliers", { exact: true }).click();
  await page.getByLabel("User", { exact: true }).dblclick();
  await page.getByRole("combobox", { name: "Features" }).click();
  await page.getByText("comments (website)", { exact: true }).click();
  await page.getByRole("combobox", { name: "Features" }).click();
  await page.getByText("summer-sales (website)", { exact: true }).click();
  await page.getByRole("combobox", { name: "Context" }).click();
  await page.getByText("QA").click();
  await page.getByLabel("User", { exact: true }).fill("beta-user");
  await page.getByRole("button", { name: "Test it!" }).click();
  await screenshot("test-result");
  await page.getByLabel("Display json").check();
  await page.evaluate(() =>
    window.scrollTo(0, document.documentElement.scrollHeight)
  );
  await screenshot("test-result-json");

  await browser.close();
}

//generate();
