import { openActions, screenshotBuilder, setup } from "./utils";
import {
  testBuilder,
  testTenant,
  testProject,
  testGlobalContext,
} from "../testBuilder";

export async function generate() {
  const { page, browser } = await setup(true);

  const screenshot = screenshotBuilder("remote-wasmo")(page);

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
  await page.goto("http://localhost:5001/");
  await page.getByText("Plugins", { exact: true }).click();
  await page.getByRole("button", { name: "Javascript" }).click();
  await page.getByRole("button", { name: "Izanami" }).hover();
  await screenshot("plugin-template-selection");
  await page.getByRole("button", { name: "Izanami" }).click();
  await page.getByRole("textbox").fill("mobile");
  await screenshot("plugin-name-selection");
  await page.getByRole("textbox").press("Enter");
  await page.getByRole("button", { name: "index.js" }).click();
  await page
    .locator(".d-flex > div:nth-child(2) > div > div:nth-child(2)")
    .first()
    .click();
  await page
    .getByText(
      "export function execute() { Host.outputString(JSON.stringify({ active: true"
    )
    .press("Meta+a");

  await page.keyboard.press("Backspace");
  await page.keyboard.type(`export function execute() {
        let input = JSON.parse(Host.inputString());
        Host.outputString(JSON.stringify({
            active: input.executionContext.includes("mobile")    
    `);
  await page.keyboard.press("ArrowDown");
  await page.keyboard.press("Enter");
  await page.keyboard.type(`return 0;`);
  await screenshot("plugin-filled");
  await page.locator('button[tooltip="Save plugin"]').click();
  await page.locator('button[tooltip="Build"]').click();
  await page.waitForTimeout(2000);
  await page
    .getByText("[RELEASE] You can now use the")
    .click({ timeout: 120000 });
  await screenshot("plugin-builded");
  await page.goto("/tenants/bookstore/projects/website/");

  await page.getByRole("button", { name: "Create new feature" }).click();
  await page.getByLabel("Name").fill("wasmobile");
  await page.getByLabel("Enabled").check();
  await page.getByRole("combobox", { name: "Feature type" }).click();
  await page.getByText("New WASM script", { exact: true }).click();
  await page.getByText("Script name").click();
  await page.getByLabel("Script name").fill("wamobile-script");
  await page.getByRole("combobox", { name: "Kind" }).click();
  await page.getByText("Wasmo", { exact: true }).click();
  await page.getByRole("combobox", { name: "Script path on" }).click();
  await page.getByText("mobile-1.0.0-dev - [DEV]", { exact: true }).click();
  await screenshot("plugin-form-filled");
  await page.getByRole("button", { name: "Save" }).click();
  await openActions(page, "wasmobile");

  await page.getByRole("link", { name: "Test feature" }).click();
  await page.getByRole("combobox", { name: "Context" }).click();
  await page.getByText("mobile", { exact: true }).click();
  await page.getByRole("button", { name: "Test feature", exact: true }).click();
  await page.getByText('"active": true').click();
  await page.waitForTimeout(200);
  await screenshot("mobile-active");

  await page
    .getByRole("combobox", { name: "Context mobile", exact: true })
    .click();
  await page.getByText("prod/mobile", { exact: true }).click();
  await page.getByRole("button", { name: "Test feature", exact: true }).click();
  await page.getByText('"active": true').click();
  await page.waitForTimeout(200);
  await screenshot("prod-mobile-active");

  await page
    .getByRole("combobox", { name: "Context prod/mobile", exact: true })
    .click();
  await page.getByText("prod", { exact: true }).click();
  await page.getByRole("button", { name: "Test feature", exact: true }).click();
  await page.getByText('"active": false').click();
  await page.waitForTimeout(200);
  await screenshot("prod-inactive");

  await browser.close();
}

//generate();
