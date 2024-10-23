import test, { expect } from "@playwright/test";

test.describe("Home screen should", () => {
  test("allow tenant creation", async ({ page }) => {
    await page.goto("/login");
    await page.getByRole("button", { name: "OpenId connect" }).click();
    await expect(page).toHaveURL(
      new RegExp("^http://localhost:9001/Account/Login")
    );
    await page.getByLabel("Username").fill("User1");
    await page.getByLabel("Password").fill("pwd");
    await page.getByRole("button", { name: "Login" }).click();
    await expect(page).toHaveURL("http://localhost:3000/home");
    await expect(
      page.getByRole("button", { name: "Sam Tailor" })
    ).toBeVisible();
  });
});
