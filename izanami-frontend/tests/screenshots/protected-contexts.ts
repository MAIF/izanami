import { screenshotBuilder, setup } from "./utils";
import {
  testBuilder,
  testFeature,
  testTenant,
  testProject,
  testGlobalContext,
} from "../testBuilder";
import { expect } from "@playwright/test";

export async function generate() {
  const { page, browser } = await setup(true);
  const screenshot = screenshotBuilder("protected-context")(page);
  await testBuilder()
    .withTenant(
      testTenant("bookstore")
        .withProject(
          testProject("website")
            .withFeature(testFeature("comments"))
            .withFeature(testFeature("summer-sales"))
            .withFeature(testFeature("phone-support"))
            .withFeature(testFeature("new-layout"))
        )
        .withContext(testGlobalContext("prod"))
        .withContext(testGlobalContext("dev"))
    )
    .build(page);

  await page.goto("http://localhost:3000/tenants/bookstore/contexts");
  await page.getByRole("button", { name: "actions" }).first().click();
  await page.getByRole("link", { name: "Protect context" }).hover();
  await screenshot("protect-context");
  await page.getByRole("link", { name: "Protect context" }).click();
  await page.getByRole("button", { name: "Confirm" }).click();

  await page.getByRole("button", { name: "actions" }).first().click();
  await page.getByRole("link", { name: "Unprotect context" }).hover();
  await screenshot("unprotect-context");
}
