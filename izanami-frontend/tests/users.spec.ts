import { test, expect } from "./izanami-test";

test.use({
  headless: true,
});

test("User invitation flow should work", async ({
  page,
  browser,
  context,
  browserName,
}) => {
  await page.goto("/home");

  await page.getByRole("link", { name: "Users" }).click();
  await page.getByRole("button", { name: "Invite new user" }).click();
  await page
    .getByLabel("Email to invite")
    .fill(`benjamin.cavy.${browserName}@gmail.com`);
  await page.getByRole("checkbox").check();
  await page.getByRole("button", { name: "Send invitation" }).click();

  let url = "";
  if (browserName === "chromium") {
    await context.grantPermissions(["clipboard-read", "clipboard-write"]);
    await page.getByRole("button", { name: "Copy" }).click();
    url = await page.evaluate("navigator.clipboard.readText()");
  } else {
    const locator = page.locator("input#secret");
    await expect(locator).toHaveValue(/.+/);
    url = await page.inputValue("input#secret");
  }

  const page1 = await (await browser.newContext()).newPage();

  await page1.goto(url);
  await page1.getByLabel("Username").fill(`foobar${browserName}`);
  await page1
    .getByLabel("Password*", { exact: true })
    .fill(`foobarbar${browserName}`);
  await page1.getByLabel("Confirm password").fill("foobarbar");
  await page1.getByRole("button", { name: "Create user" }).click();

  await page1.goto("/login");
  await page1.getByLabel("Username").fill(`foobar${browserName}`);
  await page1.getByLabel("Password").fill(`foobarbar${browserName}`);
  await page1.getByRole("button", { name: "Login" }).click();
});
