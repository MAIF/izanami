import { Page, chromium, Locator } from "playwright";
import { cleanup } from "../utils.js";

export function screenshotBuilder(folder) {
  return function (page) {
    return async function (name, locator?: Locator) {
      await page.waitForTimeout(500);
      if (locator) {
        await locator.screenshot({
          path: `../manual/static/img/screenshots/${folder}/${name}.png`,
        });
      } else {
        await page.screenshot({
          path: `../manual/static/img/screenshots/${folder}/${name}.png`,
        });
      }
    };
  };
}

export async function openActions(page: Page, rowHeader?: string) {
  await page.waitForTimeout(500);
  if (rowHeader) {
    await page
      .getByRole("row", { name: rowHeader })
      .getByRole("button", { name: "actions" })
      .click();
  } else {
    await page.getByRole("button", { name: "actions" }).click();
  }
}

export async function setup(shouldLogin) {
  await cleanup();
  const browser = await chromium.launch({ headless: false });
  const context = await browser.newContext();
  await context.grantPermissions(["clipboard-read", "clipboard-write"]);
  const page = await context.newPage();

  if (shouldLogin) {
    await login(page);
  }

  return { browser, context, page };
}

export async function login(page: Page) {
  await page.goto("http://localhost:3000");
  await page.getByLabel("Username").fill("RESERVED_ADMIN_USER");
  await page.getByLabel("Password").fill("ADMIN_DEFAULT_PASSWORD");
  await page.getByRole("button", { name: "Login" }).click();
  await page.waitForURL((url) => {
    return !url.pathname.includes("login");
  });
}

export async function createTenant(name, page) {
  await page.getByRole("button", { name: "Create new tenant" }).click();
  await page.getByLabel("Tenant name").fill(name);
  await page.getByRole("button", { name: "Save" }).click();
  await page.waitForURL(`/tenants/${name}`);
}

export async function createProject(name, page) {
  await page
    .getByRole("button", {
      name: "Create new project",
    })
    .click();
  await page.getByLabel("Project name").fill(name);
  await page.getByRole("button", { name: "Save" }).click();
}
