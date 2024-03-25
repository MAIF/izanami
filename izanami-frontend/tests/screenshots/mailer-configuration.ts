import { screenshotBuilder, setup } from "./utils";

export async function generate() {
  const { page, browser } = await setup(true);

  const screenshot = screenshotBuilder("mailer-configuration")(page);

  await page.getByRole("link", { name: "Global settings" }).click();
  await page.getByText("Mail providerConsole (None)").click();
  await page.getByText("MailJet", { exact: true }).click();
  await page.getByRole("button", { name: "Show / hide configuration" }).click();
  await page.getByLabel("API key").click();
  await page.getByLabel("API key").fill("XXXXX");
  await page.getByLabel("Secret").click();
  await page.getByLabel("Secret").fill("XXXXX");
  await page.getByLabel("Origin email").click();
  await page.getByLabel("Origin email").fill("izanami@myorg.com");

  await screenshot("configuration-screen");

  await page.getByRole("combobox", { name: "Invitation method" }).click();
  await screenshot("invitation-method");

  await browser.close();
}

//generate();
