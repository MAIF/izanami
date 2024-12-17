import { Locator, Page } from "@playwright/test";
import { Client } from "pg";
import { expect } from "./izanami-test";

export const PASSWORD = "ADMIN_DEFAULT_PASSWORD";

export async function clickAction(
  page: Page,
  name: string,
  rowHeader?: string
) {
  let dropdownLocator: Locator | null = null;
  if (rowHeader) {
    dropdownLocator = page
      .getByRole("rowheader", { name: rowHeader })
      .locator("..")
      .getByRole("button", { name: "actions" });
  } else {
    dropdownLocator = page.getByLabel("actions");
  }
  const locator = page.getByRole("link", { name: name, exact: true });

  await dropdownLocator.click();
  try {
    await locator.waitFor();
  } catch {
    try {
      await dropdownLocator.click();
      await locator.waitFor();
    } catch {
      await dropdownLocator.click();
    }
  }
  await locator.click({ force: true });
}

const SCHEMAS_TO_KEEP = [
  "public",
  "izanami",
  "information_schema",
  "pg_catalog",
  "pg_toast",
];

const clearWasmo = async () => {
  return fetch("http://localhost:5001/api/plugins")
    .then((response) => response.json())
    .then((plugins) => {
      return plugins.map((p) => p.pluginId);
    })
    .then((ids) => {
      return Promise.all(
        ids.map((id) => {
          fetch(`http://localhost:5001/api/plugins/${id}`, {
            method: "DELETE",
          });
        })
      );
    });
};

export const cleanup = async () => {
  await clearWasmo();
  const client = new Client({
    host: "localhost",
    port: 5432,
    database: "postgres",
    user: "postgres",
    password: "postgres",
  });
  await client.connect();

  const schemaData = await client.query(
    "SELECT schema_name AS sch FROM information_schema.schemata"
  );

  const schemas = schemaData.rows
    .map((r) => r.sch)
    .filter((s) => !SCHEMAS_TO_KEEP.includes(s)); // Hello world!

  const promises: Promise<any>[] = schemas.map((s) =>
    client.query(`DROP SCHEMA "${s}" CASCADE`).catch((err) => {
      console.error(err);
      return 1;
    })
  );
  promises.push(client.query("DELETE FROM izanami.tenants CASCADE"));
  promises.push(
    client.query("TRUNCATE TABLE izanami.users_tenants_rights CASCADE")
  );
  promises.push(
    client.query("TRUNCATE TABLE izanami.personnal_access_tokens CASCADE")
  );
  promises.push(
    client.query(
      "DELETE FROM izanami.users WHERE username <> 'RESERVED_ADMIN_USER'"
    )
  );
  promises.push(client.query("TRUNCATE TABLE izanami.invitations CASCADE"));
  promises.push(client.query("TRUNCATE TABLE izanami.sessions CASCADE"));
  promises.push(client.query("TRUNCATE TABLE izanami.pending_imports CASCADE"));
  promises.push(
    client.query("UPDATE izanami.mailers SET configuration='{}'::JSONB")
  );
  promises.push(client.query("TRUNCATE TABLE izanami.key_tenant CASCADE"));
  promises.push(
    client.query("UPDATE izanami.mailers SET configuration='{}'::JSONB")
  );
  promises.push(
    client.query(
      "UPDATE izanami.configuration SET mailer='CONSOLE', invitation_mode='RESPONSE', anonymous_reporting_date=NOW(), oidc_configuration=jsonb_set(oidc_configuration, '{defaultOIDCUserRights}', 'null')"
    )
  );

  await Promise.all(promises);

  await client.end();
};

export async function createTenant(page: Page, tenant: string) {
  await page.goto("/home");

  Promise.race([
    page.getByRole("button", { name: "create a new tenant" }).click(),
    page.getByRole("button", { name: "Create new tenant" }).click(),
  ]);

  await page.getByLabel("Tenant name").fill(tenant);
  await page.getByRole("button", { name: "Save" }).click();
  await page.waitForURL(`/tenants/${tenant}`);
}

export async function createProject(
  page: Page,
  tenant: string,
  project: string
) {
  await page.goto(`/tenants/${tenant}`);
  await page.getByRole("button", { name: "Create new project" }).click();
  await page.getByLabel("Project name").fill(project);
  await page.getByRole("button", { name: "Save" }).click();
  await page.waitForURL(/http:\/\/localhost:3000\/tenants\/.*\/projects\/.*/);
}

export async function createTenantThenProject(
  page: Page,
  tenant: string,
  project: string
) {
  createTenant(page, tenant);
  await page.getByRole("button", { name: "Create new project" }).click();
  await page.getByLabel("Project name").fill(project);
  await page.getByRole("button", { name: "Save" }).click();
  await page.waitForURL(/http:\/\/localhost:3000\/tenants\/.*\/projects\/.*/);
}

// TODO replace with test builder pattern
export async function createTenantThenProjectThenFeatures(
  page: Page,
  tenant: string,
  project: string,
  features: string[]
) {
  createTenant(page, tenant);
  await page.getByRole("button", { name: "Create new project" }).click();
  await page.getByLabel("Project name").fill(project);
  await page.getByRole("button", { name: "Save" }).click();
  await page.waitForURL(/http:\/\/localhost:3000\/tenants\/.*\/projects\/.*/);
  for (const feature of features) {
    await page.getByRole("button", { name: "Create new feature" }).click();
    await page.getByLabel("Name").fill(feature);
    await page.getByRole("button", { name: "Save" }).click();
    await expect(page.getByRole("row", { name: feature })).toBeVisible();
  }
}
