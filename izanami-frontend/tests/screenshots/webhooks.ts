import {
  testBuilder,
  testFeature,
  testTenant,
  testProject,
} from "../testBuilder";
import { screenshotBuilder, setup } from "./utils";

export async function generate() {
  const { page, browser } = await setup(true);
  const screenshot = screenshotBuilder("webhooks")(page);

  await testBuilder()
    .withTenant(
      testTenant("bookstore")
        .withProject(
          testProject("website")
            .withFeature(testFeature("comments"))
            .withFeature(testFeature("summer-sales"))
        )
        .withProject(
          testProject("stock").withFeature(testFeature("new-store-ui"))
        )
    )
    .build(page);

  await page.goto("http://localhost:3000/tenants/bookstore/webhooks");
  await screenshot("webhook-page");
  await page
    .getByRole("button", {
      name: "Create new webhook",
    })
    .click();
  await screenshot("webhook-form");
  await page.getByLabel("Name").fill("messaging-hook");
  // TODO once react forms will be fiexed to allow id on these inputs
  await page.keyboard.press("Tab");
  await page.keyboard.press("Space");
  await page.getByLabel("URL").fill("http://my-client-app/hooks");
  await page.getByRole("button", { name: "Add header" }).click();
  await page.getByLabel("header-0-name").fill("Authorization");
  await page.getByLabel("header-0-value").fill("my-secret-authorization-value");
  await page.getByRole("combobox", { name: "Features (project)" }).click();
  await page.getByText("summer-sales (website)", { exact: true }).click();

  await page.getByLabel("User", { exact: true }).focus();
  await page.keyboard.press("Tab");
  await page.keyboard.press("Space");
  await page.getByLabel("Handlebar template").scrollIntoViewIfNeeded();
  await page.getByLabel("Handlebar template").click();
  await screenshot("webhook-handlebar-form");
  await page
    .getByRole("button", { name: "Test the template" })
    .scrollIntoViewIfNeeded();
  await page.getByRole("button", { name: "Test the template" }).click();
  await screenshot("webhook-completed-form");
  await page.getByRole("button", { name: "Save" }).click();

  await browser.close();
}
