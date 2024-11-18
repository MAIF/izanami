import {
  testBuilder,
  testFeature,
  testGlobalContext,
  testKey,
  testOverload,
  testProject,
  testTenant,
} from "../testBuilder";
import { screenshotBuilder, setup } from "./utils";

export async function generate() {
  const { page, browser } = await setup(true);
  const screenshot = screenshotBuilder("export-import-v2")(page);

  await testBuilder()
    .withTenant(
      testTenant("bookstore")
        .withContext(testGlobalContext("prod"))
        .withProject(
          testProject("webstore")
            .withFeature(
              testFeature("comments")
                .withEnableStatus()
                .withOverload(testOverload("prod", false))
            )
            .withFeature(testFeature("phone-support").withDisableStatus())
        )
        .withKey(testKey({ name: "adminkey", admin: true }))
    )
    .withTenant(testTenant("target-bookstore"))
    .build(page);
  await page.goto("http://localhost:3000/tenants/bookstore/settings");
  const exportFormButtonLocator = page.getByRole("button", {
    name: "Export data",
  });
  await exportFormButtonLocator.focus();
  await screenshot("export-button-hover");
  await exportFormButtonLocator.click();

  await screenshot("export-form");
  await page.getByRole("button", { name: "Export" }).click();

  const downloadPromise = page.waitForEvent("download");
  const download = await downloadPromise;
  // Wait for the download process to complete and save the downloaded file somewhere.
  const filepath = "/tmp/" + download.suggestedFilename();
  await download.saveAs(filepath);

  await page.goto(`http://localhost:3000/tenants/target-bookstore/settings`);

  const importLocator = page.getByRole("button", { name: "Import data" });
  await importLocator.focus();
  await screenshot("import-button-hover");
  await importLocator.click();
  await screenshot("import-form");
  await page.getByLabel("Exported file (ndjson)").setInputFiles(filepath);
  await page.getByRole("button", { name: "Import" }).click();
  await page.getByRole("button", { name: "Close", exact: true }).click();
  await page.getByLabel("Exported file (ndjson)").waitFor({
    state: "hidden",
  });

  await page.goto(`http://localhost:3000/tenants/target-bookstore`);
  await screenshot("imported-project");

  await browser.close();
}
