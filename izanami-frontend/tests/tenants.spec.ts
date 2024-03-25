import { Page } from "@playwright/test";
import { test, expect } from "./izanami-test";

test.use({
  headless: true,
});

async function createTenant(page: Page, tenant: string) {
  Promise.race([
    page.getByRole("button", { name: "create a new tenant" }).click(),
    page.getByRole("button", { name: "Create new tenant" }).click(),
  ]);

  await page.getByLabel("Tenant name").fill(tenant);
  await page.getByRole("button", { name: "Save" }).click();
  await page.waitForURL(`/tenants/${tenant}`);
}

test.describe("Home screen should", () => {
  test("allow tenant creation", async ({ page, tenantName }) => {
    await page.goto("/home");
    await createTenant(page, tenantName);
    await expect(page).toHaveURL(`/tenants/${tenantName}`);
  });

  test("list existing tenants", async ({ page, tenantName }) => {
    const tenants = [`${tenantName}-1`, `${tenantName}-2`, `${tenantName}-3`];

    for (const tenant of tenants) {
      await page.goto("/home");
      await createTenant(page, tenant);
    }
    await page.goto("/home");

    for (const tenant of tenants) {
      await expect(
        page.getByRole("link", {
          name: tenant,
        })
      ).toBeVisible();
    }
  });

  test("allow to access tenant", async ({ page, tenantName }) => {
    await page.goto("/home");
    await createTenant(page, tenantName);
    await page.goto("/home");
    await page
      .getByRole("link", {
        name: tenantName,
      })
      .click();
    await expect(page).toHaveURL(`/tenants/${tenantName}`);
  });
});

test("Tenant load error should be handled gracefully", async ({ page }) => {
  await page.route("*/**/api/admin/tenants", async (route) => {
    const json = [{ message: "Failed to fetch tenants" }];
    await route.fulfill({ json, status: 500 });
  });

  await page.goto("/home");

  await page.getByText(`Failed to fetch tenants`).click();
});
