import test, { expect } from "@playwright/test";

test.describe("OIDC should", () => {
  test("allow external login", async ({ browser }) => {
    const page = await (await browser.newContext()).newPage();
    await page.goto("/login");
    await page.getByRole("button", { name: "OpenId connect" }).click();
    await expect(page).toHaveURL(
      new RegExp("^http://localhost:9001/Account/Login")
    );
    await page.getByLabel("Username").fill("User1");
    await page.getByLabel("Password").fill("pwd");
    await page.getByRole("button", { name: "Login" }).click();
    await expect(
      page.getByRole("button", { name: "Sam Tailor" })
    ).toBeVisible();
  });
});
