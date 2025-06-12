import { screenshotBuilder, setup } from "./utils";
import {
  testBuilder,
  testFeature,
  testTenant,
  testProject,
} from "../testBuilder";

export async function generate() {
  const { page, browser } = await setup(true);

  const screenshotNonPageDependant = screenshotBuilder("user-invitation");
  const screenshot = screenshotNonPageDependant(page);

  await testBuilder()
    .withTenant(
      testTenant("bookstore").withProject(
        testProject("website")
          .withFeature(testFeature("comments"))
          .withFeature(testFeature("summer-sales"))
          .withFeature(testFeature("phone-support"))
          .withFeature(testFeature("new-layout"))
      )
    )
    .build(page);

  await page.goto("/");
  await page.getByRole("link", { name: "Users" }).click();
  await page.getByRole("button", { name: "Invite new user" }).click();
  await page.getByRole("button", { name: "Invite new user" }).hover();
  await screenshot("empty-invitation-form");
  await page.getByLabel("Email to invite").fill("test@foo.com");
  await page.getByRole("button", { name: "Add tenant right" }).click();
  await page.getByRole("combobox", { name: "Tenant" }).click();
  await page.getByRole("form").getByText("bookstore", { exact: true }).click();
  await page.getByRole("button", { name: "Add project rights" }).click();
  await page.getByRole("combobox", { name: "new project" }).click();
  await page.getByRole("form").getByText("website", { exact: true }).click();
  await page.getByRole("combobox", { name: "website right" }).click();
  await page.getByRole("form").getByText("Write", { exact: true }).click();
  const invitationButtonLocator = page.getByRole("button", {
    name: "Send invitation",
  });
  await invitationButtonLocator.scrollIntoViewIfNeeded();
  await invitationButtonLocator.hover();
  await screenshot("filled-invitation-form");
  await invitationButtonLocator.click();

  await page.getByRole("button", { name: "Copy" }).click();
  await screenshot("invitation-url");
  const url: string = await page.evaluate("navigator.clipboard.readText()");

  const newCtx = await browser.newContext();
  const newPage = await newCtx.newPage();

  await newPage.goto(url);

  await newPage.getByLabel("Username").fill(`foobar`);
  await newPage.getByLabel("Password*", { exact: true }).fill(`foobarbar`);
  await newPage.getByLabel("Confirm password").fill("foobarbar");
  const userCreationLocator = newPage.getByRole("button", {
    name: "Create user",
  });
  await userCreationLocator.hover();
  await screenshotNonPageDependant(newPage)("new-user-form");
  await userCreationLocator.click();

  await newPage.goto("/");
  await newPage.getByLabel("Username").fill(`foobar`);
  await newPage.getByLabel("Password").fill(`foobarbar`);
  //await screenshotNonPageDependant(newPage)("login-form");
  await newPage.getByRole("button", { name: "Login" }).click();
  //await screenshotNonPageDependant(newPage)("logged-in-user");

  await browser.close();
}

//generate();
