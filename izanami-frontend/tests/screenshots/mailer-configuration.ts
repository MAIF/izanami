import { screenshotBuilder, setup } from "./utils";

export async function generate() {
  const { page, browser } = await setup(true);

  const screenshot = screenshotBuilder("mailer-configuration")(page);

  await page.getByRole("link", { name: "Global settings" }).click();
  await page.getByRole("combobox", { name: "Mail provider" }).click();
  await page.getByRole("combobox", { name: "Mail provider" }).click();
  await page.getByText("MailJet", { exact: true }).click();
  await page.getByLabel("API key").fill("XXXXX");
  await page.getByLabel("Secret", { exact: true }).fill("XXXXX");
  await page.getByLabel("Origin email").click();
  await page.getByLabel("Origin email").fill("izanami@myorg.com");

  await screenshot("configuration-screen");

  await page.getByRole("combobox", { name: "Invitation mode" }).click();
  await screenshot("invitation-method");

  await browser.close();
}

//generate();
