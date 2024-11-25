import test, { expect } from "@playwright/test";
import {
  TestSituationBuilder,
  testFeature,
  testGlobalContext,
  testKey,
  testLocalContext,
  testOverload,
  testTag,
  testTenant,
  testProject,
} from "./testBuilder";
import { tenantFromTitleAndBrowser } from "./izanami-test";

test.describe("Export / import should", () => {
  test("allow to export then import data", async ({ page, browser }, {
    title,
  }) => {
    const tenantName = await tenantFromTitleAndBrowser(title, browser);
    const emptyTenantName = `${tenantName}-empty-tenant`;
    await TestSituationBuilder.newBuilder()
      .withTenant(
        testTenant(tenantName)
          .withProject(
            testProject("testProject")
              .withFeature(
                testFeature("f1").withOverload(
                  testOverload("testcontext", true)
                )
              )
              .withContext(
                testLocalContext("testcontext").withSubContext(
                  testLocalContext("localsubcontext")
                )
              )
          )
          .withTag(testTag("my-tag"))
          .withKey(testKey({ name: "my-key" }))
          .withContext(
            testGlobalContext("global-context").withSubContext(
              testGlobalContext("subcontext")
            )
          )
      )
      .withTenant(testTenant(emptyTenantName))
      .build(page);

    await page.goto(`/tenants/${tenantName}/settings`);
    await page.getByRole("button", { name: "Export data" }).click();
    const downloadPromise = page.waitForEvent("download");

    await page.getByRole("button", { name: "Export" }).click();
    const download = await downloadPromise;
    // Wait for the download process to complete and save the downloaded file somewhere.
    const filepath = "/tmp/" + download.suggestedFilename();
    await download.saveAs(filepath);
    await page.goto(`/tenants/${emptyTenantName}/settings`);
    await page.getByRole("button", { name: "Import data" }).click();
    await page.getByLabel("Exported file (ndjson)").setInputFiles(filepath);
    await page.getByRole("button", { name: "Import" }).click();
    await page.getByRole("button", { name: "Close" }).click();
    await expect(page.getByLabel("Exported file (ndjson)")).not.toBeVisible();

    await page.getByRole("link", { name: "Projects" }).click();
    await expect(page.getByRole("link", { name: "testProject" })).toBeVisible();
    await page.getByRole("link", { name: "testProject" }).click();

    await expect(page.getByRole("rowheader", { name: "f1" })).toBeVisible();

    await expect(page.getByLabel("1 Overload")).toHaveText("1");
    await page.getByLabel("1 Overload").click();

    await page.getByRole("rowheader", { name: "testcontext" }).click();
    await page.getByRole("link", { name: "Contexts", exact: true }).click();
    await page.getByRole("link", { name: "testcontext" }).click();
    await expect(
      page.getByRole("link", { name: "localsubcontext" })
    ).toBeVisible();

    await page.getByRole("link", { name: "Tags" }).click();
    await expect(page.getByRole("rowheader", { name: "my-tag" })).toBeVisible();

    await page.getByRole("link", { name: "Keys" }).click();
    await expect(page.getByRole("rowheader", { name: "my-key" })).toBeVisible();

    await page.goto(`/tenants/${emptyTenantName}/settings`);
    await page.getByRole("button", { name: "Import data" }).click();
    await page.getByLabel("Exported file (ndjson)").setInputFiles(filepath);
    await page.getByRole("combobox", { name: "On conflict" }).click();
    await page.getByText("Fail").click();
    await page.getByRole("button", { name: "Import" }).click();
    await expect(
      page.getByRole("heading", { name: "Import errors !" })
    ).toBeVisible();
  });
});
