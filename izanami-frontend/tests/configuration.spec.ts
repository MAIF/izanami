import { Page } from "@playwright/test";
import { test, expect } from "./izanami-test";
import {
  testBuilder,
  testFeature,
  testTenant,
  testProject,
  testKey,
} from "./testBuilder";
import { clickAction } from "./utils";

test.use({
  headless: true,
});

test.describe("Global settings page should", () => {
  test("allow to update oidc rights", async ({ page, tenantName, browser }) => {
    const secondTenantName = `${tenantName}2`;
    const situation = await testBuilder()
      .withTenant(
        testTenant(tenantName)
          .withProject(testProject("project"))
          .withProject(testProject("project2"))
          .withKey(testKey({ name: "key" }))
          .withKey(testKey({ name: "key2" }))
      )
      .withTenant(
        testTenant(secondTenantName)
          .withProject(testProject("secondtenantproject"))
          .withProject(testProject("secondtenantproject2"))
          .withKey(testKey({ name: "secondtenantkey" }))
          .withKey(testKey({ name: "secondtenantkey2" }))
      )
      .build(page);

    await page.goto("/settings");

    await page.getByRole("button", { name: "Default rights" }).click();
    await page.getByRole("button", { name: "Add tenant right" }).click();
    await page.getByLabel("New tenant", { exact: true }).click();
    await page
      .getByRole("option", {
        name: "chromium-allow-to-update-oidc-rights",
        exact: true,
      })
      .click();
    await page.getByRole("button", { name: "Add project rights" }).click();
    await page
      .getByLabel("chromium-allow-to-update-oidc-rights new project", {
        exact: true,
      })
      .click();
    await page.getByRole("option", { name: "project", exact: true }).click();

    await page
      .getByLabel(
        "chromium-allow-to-update-oidc-rights project project right level"
      )
      .click();
    await page.getByRole("option", { name: "Write" }).click();

    await page.getByRole("button", { name: "Add project rights" }).click();
    await page
      .getByLabel("chromium-allow-to-update-oidc-rights new project", {
        exact: true,
      })
      .click();
    await page.getByRole("option", { name: "project2" }).click();
    await page
      .getByLabel(
        "chromium-allow-to-update-oidc-rights project project right level"
      )
      .click();
    await page.getByRole("option", { name: "Admin" }).click();

    await page.getByRole("button", { name: "Add key rights" }).click();
    await page
      .getByLabel("chromium-allow-to-update-oidc-rights new key", {
        exact: true,
      })
      .click();
    await page.getByRole("option", { name: "key", exact: true }).click();
    await page.getByRole("button", { name: "Add key rights" }).click();
    await page
      .getByLabel("chromium-allow-to-update-oidc-rights new key", {
        exact: true,
      })
      .click();
    await page.getByRole("option", { name: "key2" }).click();
    await page
      .getByLabel("chromium-allow-to-update-oidc-rights key key2 right level")
      .click();
    await page.getByRole("option", { name: "Admin" }).click();
    await page.getByRole("button", { name: "Add tenant right" }).click();
    await page.getByLabel("New tenant", { exact: true }).click();
    await page
      .getByRole("option", { name: "chromium-allow-to-update-oidc" })
      .click();
    await page.getByRole("button", { name: "Add project rights" }).click();
    await page
      .getByLabel("chromium-allow-to-update-oidc-rights2 new project", {
        exact: true,
      })
      .click();
    await page
      .getByRole("option", { name: "secondtenantproject", exact: true })
      .click();
    await page
      .getByLabel(
        "chromium-allow-to-update-oidc-rights2 project secondtenantproject right level"
      )
      .click();
    await page.getByRole("option", { name: "Write" }).click();
    await page.getByRole("button", { name: "Add project rights" }).click();
    await page
      .getByLabel("chromium-allow-to-update-oidc-rights2 new project", {
        exact: true,
      })
      .click();
    await page.getByRole("option", { name: "secondtenantproject2" }).click();
    await page.getByRole("button", { name: "Add key rights" }).click();
    await page
      .getByLabel("chromium-allow-to-update-oidc-rights2 new key", {
        exact: true,
      })
      .click();
    await page
      .getByRole("option", { name: "secondtenantkey", exact: true })
      .click();
    await page
      .getByLabel(
        "chromium-allow-to-update-oidc-rights2 key secondtenantkey right level"
      )
      .click();
    await page.getByRole("option", { name: "Admin" }).click();
    await page.getByRole("button", { name: "Add key rights" }).click();
    await page
      .getByLabel("chromium-allow-to-update-oidc-rights2 new key", {
        exact: true,
      })
      .click();
    await page.getByRole("option", { name: "secondtenantkey2" }).click();
    await page.getByRole("button", { name: "Update configuration" }).click();

    await page.goto("/");
    await page.getByRole("link", { name: "Global settings" }).click();

    await page.getByRole("button", { name: "Default rights" }).click();
    await expect(
      page.getByText(
        "Project rights for chromium-allow-to-update-oidc-rights",
        { exact: true }
      )
    ).toBeVisible();

    await expect(
      page.getByText(
        "Project rights for chromium-allow-to-update-oidc-rights",
        { exact: true }
      )
    ).toBeVisible();

    await expect(
      page.getByText(
        "Project rights for chromium-allow-to-update-oidc-rights2",
        {
          exact: true,
        }
      )
    ).toBeVisible();

    await expect(
      page.getByText("Keys rights for chromium-allow-to-update-oidc-rights2", {
        exact: true,
      })
    ).toBeVisible();

    await page.goto("/home");
    await page
      .getByRole("link", { name: "chromium-allow-to-update-oidc-rights2" })
      .click();
    await page.getByRole("link", { name: "Settings", exact: true }).click();
    await page.getByRole("button", { name: "Delete Tenant" }).click();
    await page
      .getByRole("textbox", { name: "Confirmation" })
      .fill("chromium-allow-to-update-oidc-rights2");
    await page.getByRole("button", { name: "Confirm" }).click();

    await page
      .getByRole("link", {
        name: "chromium-allow-to-update-oidc-rights",
        exact: true,
      })
      .click();
    await page.getByRole("link", { name: "project2" }).click();
    await page.getByLabel("Project settings").click();
    await page.getByRole("button", { name: "Delete project" }).click();
    await page.getByRole("textbox", { name: "Confirmation" }).fill("project2");
    await page.getByRole("button", { name: "Confirm" }).click();
    await page.getByRole("link", { name: "Keys" }).click();
    await clickAction(page, "Delete", "key2");
    await page.getByRole("textbox", { name: "Confirmation" }).fill("key2");
    await page.getByRole("button", { name: "Confirm" }).click();

    await page.getByRole("link", { name: "Global settings" }).click();
    await page.getByRole("button", { name: "Default rights" }).click();
    await page.getByRole("button", { name: "Update configuration" }).click();

    await expect(
      page.getByLabel("Tenant chromium-allow-to-update-oidc-rights", {
        exact: true,
      })
    ).toBeVisible();
    await expect(
      page.getByLabel("chromium-allow-to-update-oidc-rights project project", {
        exact: true,
      })
    ).toBeVisible();
    await expect(
      page.getByLabel(
        "chromium-allow-to-update-oidc-rights project2 project2",
        {
          exact: true,
        }
      )
    ).toBeHidden();
    await expect(
      page.getByText(`Project rights for ${tenantName}2`)
    ).toBeHidden();

    const page1 = await (await browser.newContext()).newPage();
    await page1.goto("http://localhost:3000/login");
    await page1.getByRole("button", { name: "OpenId connect" }).click();
    await expect(page1).toHaveURL(/http:\/\/localhost:9001\/Account\/Login.*/);
    await page1.getByLabel("Username").fill("User1");
    await page1.getByLabel("Password").fill("pwd");
    await page1.getByRole("button", { name: "Login" }).click();

    await expect(
      page1.getByRole("button", { name: "Sam Tailor" })
    ).toBeVisible();

    await expect(
      page1.getByRole("link", { name: "project", exact: true })
    ).toBeVisible();
  });
});
