import { screenshotBuilder, setup, openActions } from "./utils";
import { testBuilder, testTenant, testProject } from "../testBuilder";

export async function generate() {
  const { page, browser } = await setup(true);
  const screenshot = screenshotBuilder("contextenv")(page);

  await testBuilder()
    .withTenant(testTenant("bookstore").withProject(testProject("website")))
    .build(page);

  await page.goto("http://localhost:3000/tenants/bookstore/projects/website");
  await page
    .getByRole("button", {
      name: "Create new feature",
    })
    .click();
  await page.getByLabel("Name").fill("comments");
  await screenshot("feature-form");
  await page.getByRole("button", { name: "Save" }).click();
  await screenshot("feature");
  await page.getByRole("link", { name: "Global contexts" }).click();
  await page.getByRole("button", { name: "Create new context" }).click();
  await page.getByPlaceholder("Context name").fill("QA");
  await screenshot("context-creation");
  await page.getByRole("button", { name: "Save" }).click();

  await page.getByRole("link", { name: "Projects" }).click();
  await page.getByRole("link", { name: "website" }).click();
  await openActions(page);
  await page.getByRole("link", { name: "Overloads" }).hover();
  await screenshot("feature-overloads");
  await page.getByRole("link", { name: "Overloads" }).click();
  await page.getByRole("button", { name: "Create new overload" }).click();
  await page.getByRole("combobox", { name: "Context" }).click();
  await page.getByText("QA", { exact: true }).click();
  await page.getByLabel("Enabled").check();
  await screenshot("overload-creation");
  await page.getByRole("button", { name: "Save" }).click();
  await page.getByRole("button", { name: "Close" }).click();
  await openActions(page);
  await page.getByRole("link", { name: "Test feature" }).hover();
  await screenshot("hover-test");
  await page.getByRole("link", { name: "Test feature" }).click();
  await page.getByRole("button", { name: "Test feature", exact: true }).click();
  await screenshot("prod-inactive");
  await page.getByRole("combobox", { name: "Context" }).click();
  await page.getByText("QA", { exact: true }).click();
  await page.getByRole("button", { name: "Test feature", exact: true }).click();
  await screenshot("qa-active");

  await page.getByRole("button", { name: "Close Test Feature" }).click();
  await openActions(page, "comments");
  await page.getByRole("link", { name: "Url" }).click();
  await page.getByRole("combobox", { name: "Context (optional)" }).click();
  await page.getByText("QA", { exact: true }).click();
  await screenshot("link-generator");

  await browser.close();
}

//generate();
