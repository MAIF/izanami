import { openActions, screenshotBuilder, setup } from "./utils.js";

export async function generate() {
  const { page, browser } = await setup(true);
  const screenshot = screenshotBuilder("gettingstarted")(page);

  await page.getByText("Welcome to Izanami").waitFor();
  await screenshot("empty-landing-page");
  await page.getByRole("button", { name: "Create new tenant" }).click();
  const saveButton = page.getByRole("button", { name: "Save" });
  await page.getByLabel("Tenant name").fill("demo");
  await page.waitForTimeout(500);
  await screenshot("tenant-form");
  await saveButton.click();
  await page.getByRole("link", { name: "demo" }).waitFor();
  await screenshot("first-tenant");
  await page
    .getByRole("button", {
      name: "Create new project",
    })
    .click();
  await page.getByLabel("Project name").fill("demo-project");
  await page.waitForTimeout(500);
  await screenshot("project-form");
  await page.getByRole("button", { name: "Save" }).click();
  const createFeatureButton = page.getByRole("button", {
    name: "Create new feature",
  });
  await createFeatureButton.waitFor();
  await screenshot("first-project");
  await createFeatureButton.waitFor();
  await createFeatureButton.click();
  await page.getByLabel("Name").fill("demo-feature");
  await page.getByLabel("Enabled").check();
  await page.waitForTimeout(500);
  await screenshot("feature-form");
  await page.getByRole("button", { name: "Save" }).click();
  await page.getByRole("rowheader", { name: "demo-feature" }).waitFor();
  await screenshot("first-feature");
  await page.getByRole("button", { name: "actions" }).click();
  const testLink = page.getByRole("link", { name: "Test feature" });
  await testLink.waitFor();
  await screenshot("test-menu");
  await page.getByRole("link", { name: "Test feature" }).click();
  await page.waitForTimeout(500);
  await screenshot("test-form");
  await page.getByRole("button", { name: "Test feature", exact: true }).click();
  await page.getByText('"active": true').waitFor();
  await page.waitForTimeout(500);
  await screenshot("feature-result");
  await page.getByRole("button", { name: "Close test feature form" }).click();
  await openActions(page, "demo-feature");
  await page.getByRole("link", { name: "Url" }).click();
  await page.waitForTimeout(200);
  await screenshot("url-screen");
  await page.getByRole("button", { name: "Close" }).click();
  await page.getByRole("link", { name: "Keys" }).click();
  await page.getByRole("heading", { name: "Client keys" }).waitFor();
  await screenshot("key-screen");
  await page.getByRole("button", { name: "Create new key" }).click();

  await page.getByLabel("Name*", { exact: true }).fill("test-key");

  await page.locator(".react-form-select__input-container").click();
  await page.getByText("demo-project", { exact: true }).click();

  await page
    .locator("div")
    .filter({ hasText: /^Enabled$/ })
    .getByRole("checkbox")
    .check();
  await screenshot("key-form");
  await page.getByRole("button", { name: "Save" }).click();
  const copyKeyLocator = page.getByRole("button", { name: "Copy client id" });
  await copyKeyLocator.waitFor();
  await page.waitForTimeout(500);
  await screenshot("key-secret");

  await page.getByRole("button", { name: "Close" }).click();
  await screenshot("first-key");

  const curl = `\`\`\`bash
  curl -H "izanami-client-id: <YOUR CLIENT ID>" \\
  -H "izanami-client-secret: <YOUR CLIENT SECRET>" \\
  <FEATURE URL>
  \`\`\``;

  //fs.writeFileSync("../../../manual/static/code/curl.md", curl);
  await browser.close();
}

//generate();
