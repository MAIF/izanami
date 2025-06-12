import { Page } from "@playwright/test";
import { test, expect } from "./izanami-test";
import {
  testBuilder,
  testTenant,
  testProject,
  testFeature,
} from "./testBuilder";
import { backendUrl, clickAction } from "./utils";

test.use({
  headless: true,
});

test.describe("Personnal access token", () => {
  test("should work", async ({ page, tenantName }) => {
    function exportRequest() {
      const callUrl = `${backendUrl()}/api/admin/tenants/${tenantName}/_export`;
      return fetch(callUrl, {
        method: "POST",
        body: JSON.stringify({
          allProjects: true,
          allKeys: true,
          allWebhooks: true,
          userRights: false,
          webhooks: [],
          projects: [],
          keys: [],
        }),
        headers: {
          "Content-Type": "application/json",
          Authorization: `Basic ${btoa(`RESERVED_ADMIN_USER:${token}`)}`,
        },
      });
    }

    const situation = await testBuilder()
      .withTenant(
        testTenant(tenantName).withProject(
          testProject("tokenProject")
            .withFeature(testFeature("f1"))
            .withFeature(testFeature("f2"))
        )
      )
      .build(page);

    await page.goto("/home");

    await page.getByRole("button", { name: "RESERVED_ADMIN_USER" }).click();
    await page.getByRole("link", { name: "Profile" }).click();
    await page.getByRole("button", { name: "Create new token" }).click();
    await page.getByLabel("Name*").fill("test-token");
    await page.getByLabel("All rights").check();
    await page.getByRole("button", { name: "Save" }).click();

    const token = await page
      .getByRole("textbox", { name: "secret" })
      .inputValue();

    await page.getByLabel("Cancel").click();
    await page.getByLabel("Cancel").click();

    let fetchRes = await exportRequest();
    expect(fetchRes.status).toEqual(200);
    let body = await fetchRes.text();

    expect(body).toContain(`"name":"f1"`);
    expect(body).toContain(`"name":"f2"`);
    expect(body).toContain(`"name":"tokenProject"`);

    await expect(
      page.getByRole("rowheader", { name: "test-token" })
    ).toBeVisible();

    await clickAction(page, "Edit");
    await page.getByLabel("Name*").fill("test-token-no-rights");
    await page.getByLabel("All rights").uncheck();
    await page.getByRole("combobox", { name: "Tenant" }).click();
    await page.getByRole("option", { name: tenantName }).click();
    await page.getByRole("combobox", { name: "Rights" }).click();
    await page.getByRole("option", { name: "IMPORT" }).click();
    await page.getByRole("button", { name: "Save" }).click();

    await expect(
      page.getByRole("rowheader", { name: "test-token-no-rights" })
    ).toBeVisible();

    fetchRes = await exportRequest();
    expect(fetchRes.status).toEqual(401);

    await clickAction(page, "Edit");
    await page.getByLabel("Name*").fill("test-token-with-rights");
    await page.getByRole("button", { name: "Delete", exact: true }).click();
    await page.getByRole("button", { name: "Add" }).click();
    await page.getByRole("combobox", { name: "Tenant" }).click();
    await page.getByRole("option", { name: tenantName }).click();
    await page.getByRole("combobox", { name: "Rights" }).click();
    await page.getByRole("option", { name: "EXPORT" }).click();
    await page.getByRole("button", { name: "Save" }).click();

    await expect(
      page.getByRole("rowheader", { name: "test-token-with-rights" })
    ).toBeVisible();

    fetchRes = await exportRequest();
    expect(fetchRes.status).toEqual(200);

    await clickAction(page, "Edit");
    await page.getByLabel("Name*").fill("test-token2");
    await page.getByLabel("All rights").check();
    await page.getByLabel("date-range-to").click();
    await page.getByLabel("date-range-to").fill("1999-01-01T08:00");
    await page.getByRole("button", { name: "Save" }).click();

    await expect(
      page.getByRole("rowheader", { name: "test-token2" })
    ).toBeVisible();
    await expect(page.getByRole("cell", { name: "01/01/1999" })).toBeVisible();

    fetchRes = await exportRequest();
    expect(fetchRes.status).toEqual(401);

    await clickAction(page, "Edit");
    await page.getByLabel("date-range-to").fill("2099-01-01T08:00");
    await page.getByRole("button", { name: "Save" }).click();

    await expect(page.getByRole("cell", { name: "01/01/2099" })).toBeVisible();

    fetchRes = await exportRequest();
    expect(fetchRes.status).toEqual(200);
    body = await fetchRes.text();
    expect(body).toContain(`"name":"f1"`);
    expect(body).toContain(`"name":"f2"`);
    expect(body).toContain(`"name":"tokenProject"`);

    await clickAction(page, "Delete");
    await page.getByLabel("Confirm").click();
    await expect(
      page.getByRole("rowheader", { name: "test-token2" })
    ).toBeHidden();

    fetchRes = await exportRequest();
    expect(fetchRes.status).toEqual(401);
  });
});
