import { screenshotBuilder, setup, openActions, featureAction } from "./utils";
import {
  DEFAULT_TEST_PASSWORD,
  testBuilder,
  testTenant,
  testUser,
} from "../testBuilder";

export async function generate() {
  const { page, browser } = await setup(true);
  const screenshotBase = screenshotBuilder("personnal-access-tokens");
  const screenshot = screenshotBase(page);

  await testBuilder()
    .withTenant(testTenant("bookstore"))
    .withUser(testUser("benjamin", true))
    .build(page);

  await page.goto("/profile");

  await page
    .getByRole("button", { name: "Create new token" })
    .scrollIntoViewIfNeeded();
  await page.getByRole("button", { name: "Create new token" }).focus();
  await screenshot("create-token-button");
  await page.getByRole("button", { name: "Create new token" }).click();

  await page.getByLabel("Name*").fill("test-token");
  await page.getByLabel("All rights").check();

  await page.getByRole("button", { name: "Save" }).scrollIntoViewIfNeeded();
  await screenshot("simple-filled-form");
  await page.getByRole("button", { name: "Save" }).click();

  await screenshot("secret-modal");
  await page.getByLabel("Cancel").click();
  await page.getByLabel("Cancel").click();

  await page.getByRole("rowheader", { name: "test-token" }).click();

  await page.getByRole("button", { name: "Create new token" }).click();
  await page.getByLabel("Name*").fill("another-token");
  await page.getByRole("combobox", { name: "Tenant" }).click();
  await page.getByRole("option", { name: "bookstore" }).click();
  await page.getByRole("combobox", { name: "Rights" }).click();
  await page.getByRole("option", { name: "EXPORT" }).click();

  await page.getByRole("button", { name: "Save" }).scrollIntoViewIfNeeded();
  await screenshot("more-right-form");
  await page.getByRole("button", { name: "Save" }).click();

  await page.getByLabel("Cancel").click();
  await page.getByLabel("Cancel").click();

  await page.getByRole("rowheader", { name: "another-token" }).click();

  await page
    .getByRole("rowheader", { name: "another-token" })
    .scrollIntoViewIfNeeded();

  await screenshot("token-table");

  const page2 = await (await browser.newContext()).newPage();
  const screenshot2 = screenshotBase(page2);
  await page2.goto("/login");
  await page2.getByLabel("Username").fill("benjamin");
  await page2.getByLabel("Password").fill(DEFAULT_TEST_PASSWORD);
  await page2.getByRole("button", { name: "Login" }).click();

  await page2.waitForURL("/tenants/bookstore");
  await page2.goto("/users");

  await featureAction(page2, "Tokens");
  await page2.getByRole("rowheader", { name: "another-token" }).click();
  await page2.getByRole("button", { name: "Close" }).scrollIntoViewIfNeeded();
  await screenshot2("other-user-tokens");
}

generate();
