import { openActions, screenshotBuilder, setup } from "./utils";
import { testBuilder, testTenant } from "../testBuilder";

export async function generate() {
  const { page, browser } = await setup(true);
  const screenshot = screenshotBuilder("import-v1")(page);

  await testBuilder().withTenant(testTenant("bookstore")).build(page);
  await page.goto("/tenants/bookstore/settings");
  const importFormButtonLocator = page.getByRole("button", {
    name: "Import V1 data",
  });
  await page.getByRole("button", { name: "Import V1 data" }).hover();
  await screenshot("form-button-hover");
  await importFormButtonLocator.click();
  const featureLocator = page.getByLabel("Exported feature files");
  await featureLocator.setInputFiles(
    "./tests/screenshots/import/features.ndjson"
  );
  await featureLocator.scrollIntoViewIfNeeded();
  await page.getByLabel("New project name").fill("website");
  await screenshot("filled-form");

  await page.getByRole("button", { name: "Import", exact: true }).click();
  await page.getByText("Import succeeded !").waitFor();
  await screenshot("import-confirmed");
  await page.goto("/tenants/bookstore/projects/website");
  await page.getByRole("rowheader", { name: "summer-sales" }).waitFor();
  await screenshot("created-feature");

  await openActions(page, "comments");
  await page.getByRole("link", { name: "Edit" }).click();
  await page.getByText("Strategy").scrollIntoViewIfNeeded();
  await screenshot("legacy-update-form");

  await browser.close();
}

//generate();
