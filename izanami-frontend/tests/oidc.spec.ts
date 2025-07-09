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
  test("allow to update oidc rights and roles", async ({
    page,
    tenantName,
    browser,
  }) => {
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

    await page.getByRole("button", { name: "Configuration DISABLED" }).click();
    await page.getByText("Enable OIDC authentication").click();
    await page
      .getByRole("button", { name: "Read OIDC config from URL" })
      .click();
    await page.getByRole("textbox", { name: "URL of the OIDC config" }).click();
    await page
      .getByRole("textbox", { name: "URL of the OIDC config" })
      .press("ControlOrMeta+a");
    await page
      .getByRole("textbox", { name: "URL of the OIDC config" })
      .fill("http://localhost:9001/.well-known/openid-configuration");
    await page.getByRole("button", { name: "Fetch configuration" }).click();
    await page.getByRole("textbox", { name: "Client ID" }).fill("foo");
    await page.getByRole("textbox", { name: "Client Secret" }).fill("bar");
    await page.getByRole("textbox", { name: "Name field name" }).click();
    await page
      .getByRole("textbox", { name: "Name field name" })
      .fill("username");
    await page.getByRole("textbox", { name: "Name field name" }).press("Tab");
    await page.getByRole("textbox", { name: "Email field name" }).fill("email");
    await page.getByRole("textbox", { name: "Name field name" }).click();
    await page
      .getByRole("textbox", { name: "Name field name" })
      .fill("usernamen");
    await page
      .getByRole("textbox", { name: "Name field name" })
      .press("ControlOrMeta+a");
    await page.getByRole("textbox", { name: "Name field name" }).fill("name");
    await page
      .getByRole("textbox", { name: "Scopes" })
      .fill("openid profile email roles");
    await page.getByRole("checkbox", { name: "Use PKCE flow" }).check();
    await page.getByRole("combobox", { name: "PKCE Algorithm" }).click();
    await page.getByRole("option", { name: "HMAC-SHA256" }).click();
    await page.getByRole("button", { name: "Update configuration" }).click();

    await page.getByRole("textbox", { name: "Role claim" }).fill("roles");
    await page.getByRole("combobox", { name: "Role right mode" }).click();
    await page.getByRole("option", { name: "Supervised" }).click();
    await page.getByRole("button", { name: "Update configuration" }).click();

    await page.getByRole("button", { name: "Default rights" }).click();
    await page.getByRole("button", { name: "Add default rights" }).click();
    await page.getByRole("button", { name: "Add tenant right" }).click();
    await page.getByRole("combobox", { name: "Tenant" }).click();
    await page
      .getByRole("option", {
        name: "chromium-allow-to-update-oidc-rights-and-roles",
        exact: true,
      })
      .click();
    await page.getByRole("button", { name: "Add project rights" }).click();
    await page
      .getByRole("combobox", {
        name: "new project",
      })
      .click();
    await page.getByRole("option", { name: "project", exact: true }).click();
    await page
      .getByRole("combobox", {
        name: "project right level",
      })
      .click();
    await page.getByRole("option", { name: "Admin" }).click();
    await page
      .getByRole("combobox", {
        name: "Default project right",
      })
      .click();
    await page.getByRole("option", { name: "Update" }).click();

    await page.getByRole("button", { name: "Update configuration" }).click();

    const page2 = await (await browser.newContext()).newPage();
    await page2.context().clearCookies();
    await page2.goto("/");
    await page2.getByRole("button", { name: "OpenId connect" }).click();
    await page2.getByRole("textbox", { name: "Username" }).fill("User1");
    await page2.getByRole("textbox", { name: "Password" }).fill("pwd");
    await page2.getByRole("button", { name: "Login" }).click();

    await expect(
      page2.getByRole("link", {
        name: "chromium-allow-to-update-oidc-rights-and-roles",
      })
    ).toBeVisible();
    await expect(
      page2.getByRole("link", { name: "project", exact: true })
    ).toBeVisible();
    await expect(page2.getByRole("link", { name: "project2" })).toBeVisible();
    await page2.getByRole("link", { name: "project", exact: true }).click();
    await expect(
      page2.getByRole("link", { name: "Project settings" })
    ).toBeVisible();
    await page2
      .getByRole("link", {
        name: "chromium-allow-to-update-oidc-rights-and-roles",
      })
      .click();
    await page2.getByRole("link", { name: "project2" }).click();
    await expect(
      page2.getByRole("link", { name: "Project settings" })
    ).not.toBeVisible();

    await page.getByRole("button", { name: "Add role" }).click();
    await page
      .getByRole("textbox", { name: "Role name Create role" })
      .fill("admin");
    await page.getByRole("button", { name: "Create role" }).click();
    await page.getByRole("checkbox", { name: "Admin" }).last().check();
    await page2.context().clearCookies();
    await page2.goto("/");
    await page2.getByRole("button", { name: "OpenId connect" }).click();
    await page2.getByRole("textbox", { name: "Username" }).fill("User1");
    await page2.getByRole("textbox", { name: "Password" }).fill("pwd");
    await page2.getByRole("button", { name: "Login" }).click();
    await expect(
      page.getByRole("heading", { name: "Admin zone" })
    ).toBeVisible();
    await expect(
      page.getByRole("link", { name: "Global settings" })
    ).toBeVisible();
  });
});
