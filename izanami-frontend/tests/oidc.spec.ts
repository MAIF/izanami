import { Page } from "@playwright/test";
import { test, expect } from "./izanami-test";
import {
  testBuilder,
  testFeature,
  testTenant,
  testProject,
  testKey,
  testWebhook,
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
          .withKey(testKey({ name: "key2" })),
      )
      .withTenant(
        testTenant(secondTenantName)
          .withProject(testProject("secondtenantproject"))
          .withProject(testProject("secondtenantproject2"))
          .withKey(testKey({ name: "secondtenantkey" }))
          .withKey(testKey({ name: "secondtenantkey2" })),
      )
      .build(page);

    await page.goto("/settings");

    await page.getByRole("button", { name: "OIDC Configuration" }).click();
    await page.getByText("Enable OIDC authentication").click();
    await page
      .getByRole("button", { name: "Read OIDC config from URL" })
      .click();
    await page.getByRole("textbox", { name: "URL of the OIDC config" }).click();
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
    await page.getByRole("textbox", { name: "Email field name" }).fill("email");
    await page.getByRole("textbox", { name: "Name field name" }).click();
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

    await page.getByRole("button", { name: "Rights configuration" }).click();
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
      }),
    ).toBeVisible();
    await expect(
      page2.getByRole("link", { name: "project", exact: true }),
    ).toBeVisible();
    await expect(page2.getByRole("link", { name: "project2" })).toBeVisible();
    await page2.getByRole("link", { name: "project", exact: true }).click();
    await expect(
      page2.getByRole("link", { name: "Project settings" }),
    ).toBeVisible();
    await page2
      .getByRole("link", {
        name: "chromium-allow-to-update-oidc-rights-and-roles",
      })
      .click();
    await page2.getByRole("link", { name: "project2" }).click();
    await expect(
      page2.getByRole("link", { name: "Project settings" }),
    ).not.toBeVisible();

    await page.getByRole("button", { name: "Add role" }).click();
    await page
      .getByRole("textbox", { name: "Role name Create role" })
      .fill("admin");
    await page.getByRole("button", { name: "Create role" }).click();
    await page.getByRole("checkbox", { name: "Admin" }).last().check();
    await page2.context().clearCookies();
    await page2.goto("/login");
    await page2.getByRole("button", { name: "OpenId connect" }).click();
    await page2.getByRole("textbox", { name: "Username" }).fill("User1");
    await page2.getByRole("textbox", { name: "Password" }).fill("pwd");
    await page2.getByRole("button", { name: "Login" }).click();
    await expect(
      page.getByRole("heading", { name: "Admin zone" }),
    ).toBeVisible();
    await expect(
      page.getByRole("link", { name: "Global settings" }),
    ).toBeVisible();
  });

  test("allow to update max rights", async ({ page, browser }) => {
    const situation = await testBuilder()
      .withTenant(
        testTenant("test")
          .withProject(testProject("testproject"))
          .withKey(testKey({ name: "testkey" }))
          .withWebhook(testWebhook("testwebhook")),
      )
      .withTenant(
        testTenant("secret")
          .withProject(testProject("secretproject"))
          .withKey(testKey({ name: "secretkey" }))
          .withWebhook(testWebhook("secretwebhook")),
      )
      .build(page);

    await page.goto("http://localhost:3000/tenants/test");
    await page.getByRole("link", { name: "Global settings" }).click();
    await page
      .getByRole("button", { name: "OIDC Configuration DISABLED" })
      .click();
    await page
      .getByRole("checkbox", { name: "Enable OIDC authentication" })
      .check();
    await page
      .getByRole("button", { name: "Read OIDC config from URL" })
      .click();
    await page
      .getByRole("textbox", { name: "URL of the OIDC config" })
      .fill("http://localhost:9001/.well-known/openid-configuration");

    await page.getByRole("button", { name: "Fetch configuration" }).click();
    await expect(
      page.getByRole("heading", { name: "Get from OIDC config" }),
    ).not.toBeVisible();
    await page.getByRole("textbox", { name: "Client ID" }).fill("foo");
    await page.getByRole("textbox", { name: "Client Secret" }).fill("bar");
    await page
      .getByRole("textbox", { name: "Scopes" })
      .fill("openid email profile roles");
    await page.getByRole("checkbox", { name: "Use PKCE flow" }).check();
    await page.getByRole("combobox", { name: "PKCE Algorithm" }).click();
    await page.getByRole("option", { name: "HMAC-SHA256" }).click();
    await page.getByRole("textbox", { name: "Name field name" }).fill("name");
    await page.getByRole("textbox", { name: "Email field name" }).fill("email");
    await page.getByRole("textbox", { name: "Role claim" }).fill("roles");
    await page.getByText("Role right modeSelect...").click();
    await page.getByRole("option", { name: "Initial" }).click();

    /******************** RIGHT CONFIGURATION ************************/
    await page.getByRole("button", { name: "Rights configuration" }).click();
    await page.getByRole("button", { name: "Add default rights" }).click();
    await page.getByRole("button", { name: "Add tenant right" }).click();
    await page.getByRole("combobox", { name: "New Tenant" }).click();
    await page.getByRole("option", { name: "test" }).click();
    await page.getByRole("combobox", { name: "Default project right" }).click();
    await page.getByRole("option", { name: "Read" }).click();
    await page.getByRole("combobox", { name: "Default key right" }).click();
    await page.getByRole("option", { name: "Read" }).click();
    await page.getByRole("combobox", { name: "Default webhook right" }).click();
    await page.getByRole("option", { name: "Read" }).click();
    await page.getByRole("combobox", { name: "Max tenant right" }).click();
    await page.getByRole("option", { name: "Write" }).click();
    await page.getByRole("combobox", { name: "Max project right" }).click();
    await page.getByRole("option", { name: "Write" }).click();
    await page.getByRole("combobox", { name: "Max key right" }).click();
    await page.getByRole("option", { name: "Write" }).click();
    await page.getByRole("combobox", { name: "Max webhook right" }).click();
    await page.getByRole("option", { name: "Write" }).click();

    await page.getByRole("button", { name: "Add tenant right" }).click();
    await page.getByRole("combobox", { name: "New Tenant" }).click();
    await page.getByRole("option", { name: "secret" }).click();
    await page
      .getByRole("combobox", { name: "Tenant secret right level" })
      .click();
    await page.getByRole("option", { name: "None" }).click();

    await page
      .getByRole("combobox", { name: "Max tenant right" })
      .nth(1)
      .click();
    await page.getByRole("option", { name: "Read" }).click();
    await page
      .getByRole("combobox", { name: "Max project right" })
      .nth(1)
      .click();
    await page.getByRole("option", { name: "Read" }).click();
    await page.getByRole("combobox", { name: "Max key right" }).nth(1).click();
    await page.getByRole("option", { name: "Read" }).click();
    await page
      .getByRole("combobox", { name: "Max webhook right" })
      .nth(1)
      .click();
    await page.getByRole("option", { name: "Read" }).click();

    await page.getByRole("button", { name: "Update configuration" }).click();

    /******************** ADMIN ROLE SETUP ************************/
    await page.getByRole("button", { name: "Add role" }).click();
    await page.getByRole("textbox", { name: "Role name" }).fill("admin");
    await page.getByRole("button", { name: "Create role" }).click();

    await page.getByRole("button", { name: "Add tenant right" }).click();
    await page.getByRole("combobox", { name: "New Tenant" }).click();
    await page.getByRole("option", { name: "secret" }).click();

    await page
      .getByRole("combobox", { name: "Max tenant right" })
      .nth(2)
      .click();
    await page.getByRole("option", { name: "Write" }).click();
    await page
      .getByRole("combobox", { name: "Max project right" })
      .nth(2)
      .click();
    await page.getByRole("option", { name: "Write" }).click();
    await page
      .getByRole("combobox", { name: "Max webhook right" })
      .nth(2)
      .click();
    await page.getByRole("option", { name: "Write" }).click();

    await page.getByRole("button", { name: "Update configuration" }).click();

    /******************** OIDC USER CREATION ************************/
    const page2 = await (await browser.newContext()).newPage();
    await page2.context().clearCookies();
    await page2.goto("http://localhost:3000/login?req=/");
    await page2.getByRole("button", { name: "OpenId connect" }).click();
    await page2.getByRole("textbox", { name: "Username" }).fill("User1");
    await page2.getByRole("textbox", { name: "Password" }).fill("pwd");
    await page2.getByRole("button", { name: "Login" }).click();
    await expect(
      page2.getByRole("button", { name: "Sam Tailor" }),
    ).toBeVisible();
    //await page2.close();

    /******************** ACTUAL TEST ************************/

    // TEST RIGHTS FROM GLOBAL SETTINGS PAGE
    await page.getByRole("link", { name: "Settings", exact: true }).click();
    await page.getByRole("button", { name: "actions" }).click();
    await page.getByRole("link", { name: "Edit" }).click();

    await page.getByRole("combobox", { name: "Default project right" }).click();
    await page.getByRole("option", { name: "Admin" }).click();
    await page.getByRole("button", { name: "Update rights" }).click();
    await expect(
      page.getByText(
        "User role doesn't allow him to have following rights on tenant test: default project right Admin (max allowed is Write)",
      ),
    ).toBeVisible();

    await page.getByRole("combobox", { name: "Default project right" }).click();
    await page.getByRole("option", { name: "Read" }).click();
    await page.getByRole("combobox", { name: "Default key right" }).click();
    await page.getByRole("option", { name: "Admin" }).click();
    await page.getByRole("combobox", { name: "Default webhook right" }).click();
    await page.getByRole("option", { name: "Admin" }).click();
    await page.getByRole("button", { name: "Update rights" }).click();

    await expect(
      page.getByText(
        "User role doesn't allow him to have following rights on tenant test: default key right Admin (max allowed is Write) default key right Admin (max allowed is Write)",
      ),
    ).toBeVisible();

    await page.getByRole("combobox", { name: "Default project right" }).click();
    await page.getByRole("option", { name: "Write" }).click();
    await page.getByRole("combobox", { name: "Default key right" }).click();
    await page.getByRole("option", { name: "Write" }).click();
    await page.getByRole("combobox", { name: "Default webhook right" }).click();
    await page.getByRole("option", { name: "Write" }).click();
    await page.getByRole("button", { name: "Update rights" }).click();

    await expect(
      page.getByRole("heading", { name: "User rights" }),
    ).not.toBeVisible();

    // TEST RIGHTS FROM TENANT SETTINGS PAGE
    await page.goto("/tenants/secret/settings");

    await page.getByRole("button", { name: "actions" }).click();
    await page.getByRole("link", { name: "Edit" }).click();
    await page.getByRole("combobox", { name: "Default project right" }).click();
    await page.getByRole("option", { name: "Admin" }).click();
    await page.getByRole("button", { name: "Update rights" }).click();
    await expect(page.getByRole("status")).toContainText(
      "User role doesn't allow him to have following rights on tenant secret: default project right Admin (max allowed is Write)",
    );

    await page.getByRole("combobox", { name: "Default project right" }).click();
    await page.getByRole("option", { name: "Write" }).click();
    await page.getByRole("button", { name: "Update rights" }).click();
    await expect(
      page.getByRole("combobox", { name: "Default project right" }),
    ).not.toBeVisible();

    // TEST RIGHTS FROM PROJECT PAGE
    await page.goto(
      "http://localhost:3000/tenants/secret/projects/secretproject/settings",
    );
    await page.getByRole("button", { name: "actions" }).click();
    await page.getByRole("link", { name: "Edit" }).click();
    await page
      .getByRole("cell", { name: "Right level" })
      .getByRole("combobox")
      .click();
    await page.getByRole("option", { name: "Admin" }).click();
    await page.getByRole("button", { name: "Save" }).click();

    await expect(page.getByRole("status")).toContainText(
      "User role doesn't allow him to have following rights on tenant secret: Admin right for project secretproject (max allowed is Write)",
    );

    await page
      .getByRole("cell", { name: "Right level" })
      .getByRole("combobox")
      .click();
    await page.getByRole("option", { name: "Write" }).click();
    await page.getByRole("button", { name: "Save" }).click();
    await expect(
      page.getByRole("heading", { name: "Right level" }),
    ).not.toBeVisible();
    await expect(
      page
        .getByRole("rowheader", { name: "Sam Tailor" })
        .locator("..")
        .getByRole("cell", { name: "Write" }),
    ).toBeVisible();

    await page.getByRole("link", { name: "Global settings" }).click();
    await page.getByRole("button", { name: "Rights configuration" }).click();
    await page
      .getByRole("combobox", { name: "Max project right" })
      .last()
      .click();
    await page.getByRole("option", { name: "Read" }).click();
    await page.getByRole("button", { name: "Update configuration" }).click();

    await page2.reload();
    await expect(page2).toHaveURL("/login");

    await page.goto(
      "http://localhost:3000/tenants/secret/projects/secretproject/settings",
    );
    await expect(
      page
        .getByRole("rowheader", { name: "Sam Tailor" })
        .locator("..")
        .getByRole("cell", { name: "Read" }),
    ).toBeVisible();

    await page2.getByRole("button", { name: "OpenId connect" }).click();
    await expect(
      page2.getByRole("heading", { name: "Your rights have been updated" }),
    ).toBeVisible();
    await expect(
      page2.getByText(
        "Some of your rights were updated due to the following reason(s):",
      ),
    ).toBeVisible();
    await expect(
      page2.getByText(
        "User role doesn't allow him to have following rights on tenant secret: default project right Write (max allowed is Read) Write right for project secretproject (max allowed is Read)",
      ),
    ).toBeVisible();
    await page2.getByRole("button", { name: "Close" }).click();
    await expect(page2).toHaveURL(
      new RegExp("^http://localhost:3000/tenants/"),
    );

    await page.reload();
    await expect(
      page
        .getByRole("rowheader", { name: "Sam Tailor" })
        .locator("..")
        .getByRole("cell", { name: "Read" }),
    ).toBeVisible();
  });
});
