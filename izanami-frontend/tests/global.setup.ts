import { test as setup, expect } from "@playwright/test";
import { STORAGE_STATE } from "../playwright.config";
import { cleanup } from "./utils";

setup("do login", async ({ page }) => {
  await cleanup();
  await page.goto("/");
  await page.getByLabel("Username").fill("RESERVED_ADMIN_USER");
  await page.getByLabel("Password").fill("ADMIN_DEFAULT_PASSWORD");
  await page.getByRole("button", { name: "Login" }).click();

  // Wait until the page actually signs in.
  await expect(page).toHaveURL("/home");

  await page.context().storageState({ path: STORAGE_STATE });
});
