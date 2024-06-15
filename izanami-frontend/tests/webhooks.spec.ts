import { Page } from "@playwright/test";
import { test, expect } from "./izanami-test";
import {
  testBuilder,
  testFeature,
  testTenant,
  testWebhook,
  testproject,
} from "./testBuilder";

test.use({
  headless: true,
});

async function webhookAction(page: Page, name: string) {
  await page.getByLabel("actions").click();
  if (
    (await page.getByRole("link", { name: name, exact: true }).count()) === 0
  ) {
    await page.getByLabel("actions").click();
  }
  await page.getByRole("link", { name: name, exact: true }).click();
}

test.describe("Webhook screen should", () => {
  test("allow to create webhook", async ({ page, tenantName }) => {
    await testBuilder()
      .withTenant(
        testTenant(tenantName).withProject(
          testproject("project")
            .withFeature(testFeature("f1"))
            .withFeature(testFeature("f2"))
        )
      )
      .build(page);

    await page.goto(`/tenants/${tenantName}/webhooks`);
    await page.getByRole("button", { name: "Create new webhook" }).click();
    await page.getByLabel("Name*").fill("my-hook");
    await page.getByLabel("URL*").click();
    await page.getByLabel("URL*").fill("http://localhost:9999");
    await page.getByRole("combobox", { name: "Features (project)" }).click();
    await page.getByText("f1 (project)", { exact: true }).click();
    await page.getByRole("button", { name: "Save" }).click();
    await expect(
      page.getByRole("rowheader", { name: "my-hook" })
    ).toBeVisible();
    await page.getByRole("cell", { name: "Disabled" }).isVisible();
    await page.getByRole("cell", { name: "http://localhost:9999" }).isVisible();
    await page.getByRole("cell", { name: "f1 ( project)" }).isVisible();
  });

  test("allow to list webhooks", async ({ page, tenantName }) => {
    await testBuilder()
      .withTenant(
        testTenant(tenantName)
          .withProject(
            testproject("project")
              .withFeature(testFeature("f1"))
              .withFeature(testFeature("f2"))
          )
          .withWebhook(testWebhook("webhook1"))
          .withWebhook(testWebhook("webhook2"))
          .withWebhook(testWebhook("webhook3"))
      )
      .build(page);

    await page.goto(`/tenants/${tenantName}/webhooks`);
    await expect(
      page.getByRole("rowheader", { name: "webhook1" })
    ).toBeVisible();
    await expect(
      page.getByRole("rowheader", { name: "webhook2" })
    ).toBeVisible();
    await expect(
      page.getByRole("rowheader", { name: "webhook3" })
    ).toBeVisible();
  });

  test("allow to edit webhooks", async ({ page, tenantName }) => {
    await testBuilder()
      .withTenant(
        testTenant(tenantName)
          .withProject(
            testproject("project")
              .withFeature(testFeature("f1"))
              .withFeature(testFeature("f2"))
          )
          .withWebhook(testWebhook("webhook1"))
      )
      .build(page);

    await page.goto(`/tenants/${tenantName}/webhooks`);
    await webhookAction(page, "Edit");

    await page.getByLabel("Name*").fill("webhook2");
    await page.getByRole("button", { name: "Save" }).click();
    await expect(
      page.getByRole("rowheader", { name: "webhook2" })
    ).toBeVisible();
  });

  test("allow to delete webhook", async ({ page, tenantName }) => {
    await testBuilder()
      .withTenant(
        testTenant(tenantName)
          .withProject(
            testproject("project")
              .withFeature(testFeature("f1"))
              .withFeature(testFeature("f2"))
          )
          .withWebhook(testWebhook("webhook1"))
      )
      .build(page);

    await page.goto(`/tenants/${tenantName}/webhooks`);
    await webhookAction(page, "Delete");

    await page.getByRole("button", { name: "Confirm" }).click();

    await expect(
      page.getByRole("rowheader", { name: "webhook1" })
    ).toBeHidden();
  });
});
