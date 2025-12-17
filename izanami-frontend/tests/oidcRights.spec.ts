import { expect, test } from "./izanami-test";
import { testBuilder, testTenant, testProject, testKey } from "./testBuilder";
import { cleanupConfig } from "./utils";

test.use({
  headless: true,
});

test.beforeEach(async () => {
  await cleanupConfig();
});

function setupOIDCConfiguration(cookie: string, rightByRoles?: object) {
  const payload = {
    invitationMode: "Response",
    originEmail: null,
    anonymousReporting: false,
    anonymousReportingLastAsked: "2026-03-10T13:27:22.402Z",
    oidcConfiguration: {
      enabled: true,
      clientId: "foo",
      clientSecret: "bar",
      tokenUrl: "http://localhost:9001/connect/token",
      authorizeUrl: "http://localhost:9001/connect/authorize",
      scopes: "openid profile email roles",
      nameField: "name",
      emailField: "email",
      callbackUrl: "http://localhost:3000/login",
      pkce: {
        enabled: true,
        algorithm: "S256",
      },
      roleClaim: "roles",
      method: "BASIC",
      roleRightMode: "Initial",
      userRightsByRoles: rightByRoles ?? null,
    },
    mailerConfiguration: {
      mailer: "Console",
    },
  };

  return fetch("http://localhost:3000/api/admin/configuration", {
    headers: {
      cookie: `token=${cookie}`,
      "content-type": "application/json",
    },
    body: JSON.stringify(payload),
    method: "PUT",
  });
}

test.describe("Max rights should", () => {
  test("display details of right loss at login if needed", async ({
    page,
    browser,
  }) => {
    const situation = await testBuilder()
      .withTenant(
        testTenant("foo")
          .withProject(testProject("project"))
          .withProject(testProject("project2"))
          .withKey(testKey({ name: "key" }))
          .withKey(testKey({ name: "key2" })),
      )
      .withTenant(
        testTenant("bar")
          .withProject(testProject("secondtenantproject"))
          .withProject(testProject("secondtenantproject2"))
          .withKey(testKey({ name: "secondtenantkey" }))
          .withKey(testKey({ name: "secondtenantkey2" })),
      )
      .build(page);

    const cookies = await page.context().cookies();
    const token = cookies.find((c) => c.name === "token")!.value;
    await setupOIDCConfiguration(token);

    await page.goto("/settings");

    await page.getByRole("button", { name: "Rights configuration" }).click();
    await page.getByRole("button", { name: "Add role" }).click();
    await page.getByRole("textbox", { name: "Role name" }).fill("admin");
    await page.getByRole("button", { name: "Create role" }).click();
    await page.getByRole("button", { name: "Add tenant right" }).click();
    await page.getByRole("combobox", { name: "Tenant" }).click();
    await page.getByRole("option", { name: "foo" }).click();
    await page
      .getByRole("combobox", { name: "Tenant foo right level" })
      .click();
    await page.getByRole("option", { name: "Write" }).click();

    await page.getByRole("combobox", { name: "Default project right" }).click();
    await page.getByRole("option", { name: "Write" }).click();
    await page.getByRole("button", { name: "Add project rights" }).click();
    await page.getByRole("combobox", { name: "foo new project" }).click();
    await page.getByRole("option", { name: "project", exact: true }).click();
    await page
      .getByRole("combobox", {
        name: "foo project project right level",
        exact: true,
      })
      .click();
    await page.getByRole("option", { name: "Admin" }).click();

    await page.getByRole("combobox", { name: "Default key right" }).click();
    await page.getByRole("option", { name: "Write" }).click();
    await page.getByRole("button", { name: "Add key rights" }).click();
    await page.getByRole("combobox", { name: "foo new key" }).click();
    await page.getByRole("option", { name: "key", exact: true }).click();
    await page
      .getByRole("combobox", {
        name: "foo key key right level",
        exact: true,
      })
      .click();
    await page.getByRole("option", { name: "Admin" }).click();

    await page.getByRole("combobox", { name: "Default webhook right" }).click();
    await page.getByRole("option", { name: "Write" }).click();

    await page.getByRole("button", { name: "Update configuration" }).click();
    await expect(
      page.getByText(
        "Updating max rights will disconnect impacted users, are you sure ?",
      ),
    ).toBeVisible();
    await page.getByRole("button", { name: "Confirm" }).click();

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
    await page2.getByRole("button", { name: "Sam Tailor" }).click();
    await page2.getByRole("link", { name: "Logout" }).click();
    await expect(page2).toHaveURL("http://localhost:3000/login");

    await page.getByRole("combobox", { name: "Max project right" }).click();
    await page.getByRole("option", { name: "Read" }).click();
    await page.getByRole("button", { name: "Update configuration" }).click();
    await expect(
      page.getByText(
        "Updating max rights will disconnect impacted users, are you sure ?",
      ),
    ).toBeVisible();
    await page.getByRole("button", { name: "Confirm" }).click();

    await page2.getByRole("button", { name: "OpenId connect" }).click();
    await expect(
      page2.getByRole("button", { name: "Sam Tailor" }),
    ).toBeVisible();

    await expect(page2.locator("#modal")).toMatchAriaSnapshot(`
      - heading "Your rights have been updated" [level=3]
      - text: Some of your rights were updated.
      - list:
        - listitem: "Tenant foo: your default project right has been updated from Write to Read"
        - listitem: "Tenant foo: your right on project project has been updated from Admin to Read"
      `);

    await page2.getByRole("button", { name: "Close" }).click();
    await expect(
      page2.getByRole("heading", { name: "Projects" }),
    ).toBeVisible();
  });

  test("display badge on user profile where its rights have beend updated by max right update", async ({
    page,
    browser,
  }) => {
    const initialRightByRoles = {
      admin: {
        tenants: {
          foo2: {
            level: "Write",
            projects: {
              project: {
                level: "Admin",
              },
            },
            keys: {
              key: {
                level: "Admin",
              },
            },
            webhooks: {},
            defaultProjectRight: "Read",
            defaultKeyRight: "Read",
            defaultWebhookRight: "Read",
            maxProjectRight: "Admin",
            maxKeyRight: "Admin",
            maxWebhookRight: "Admin",
            maxTenantRight: "Admin",
          },
        },
        admin: false,
        adminAllowed: true,
      },
    };

    const situation = await testBuilder()
      .withTenant(
        testTenant("foo2")
          .withProject(testProject("project"))
          .withProject(testProject("project2"))
          .withKey(testKey({ name: "key" }))
          .withKey(testKey({ name: "key2" })),
      )
      .withTenant(
        testTenant("bar2")
          .withProject(testProject("secondtenantproject"))
          .withProject(testProject("secondtenantproject2"))
          .withKey(testKey({ name: "secondtenantkey" }))
          .withKey(testKey({ name: "secondtenantkey2" })),
      )
      .build(page);

    const cookies = await page.context().cookies();
    const token = cookies.find((c) => c.name === "token")!.value;
    await setupOIDCConfiguration(token, initialRightByRoles);

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

    await page.goto("/settings");

    await page.getByRole("button", { name: "Rights configuration" }).click();
    await page.getByRole("combobox", { name: "Max project right" }).click();
    await page.getByRole("option", { name: "Write" }).click();
    await page.getByRole("button", { name: "Update configuration" }).click();
    const responsePromise = page.waitForResponse("**/api/admin/configuration");
    await page.getByRole("button", { name: "Confirm" }).click();
    await responsePromise;

    await page2.reload();
    await expect(page2).toHaveURL("http://localhost:3000/login");

    await page.goto("http://localhost:3000/home");
    await page.getByRole("link", { name: "foo2" }).click();
    await page.getByRole("link", { name: "project", exact: true }).click();
    await page.getByRole("link", { name: "Project settings" }).click();
    await expect(page.getByLabel("Provisional rights")).toBeVisible();

    await page.goto("/tenants/foo2/settings");
    await page.getByRole("button", { name: "actions" }).click();
    await page.getByRole("link", { name: "Edit" }).click();
    await expect(page.getByLabel("Provisional rights")).toBeVisible();

    await page.goto("/users");
    await page.getByRole("button", { name: "actions" }).click();
    await page.getByRole("link", { name: "Edit" }).click();
    await expect(page.getByLabel("Provisional rights")).toBeVisible();
  });
});
