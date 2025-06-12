import { Page } from "@playwright/test";
import { test, expect } from "./izanami-test";
import { testBuilder, testTenant, testProject } from "./testBuilder";
import { PASSWORD } from "./utils";

test.use({
  headless: true,
});

async function createProject(page: Page, name: string) {
  await page.getByRole("button", { name: "Create new project" }).click();
  await page.getByLabel("Project name").fill(name);
  await page.getByRole("button", { name: "Save" }).click();
  await page.waitForURL(/.*\/tenants\/.*\/projects\/.*/);
}

test.describe("Tenant screen should", () => {
  test("allow to create project", async ({ page, tenantName }) => {
    const situation = await testBuilder()
      .withTenant(testTenant(tenantName))
      .build(page);
    await page.goto(`/tenants/${tenantName}`);
    await createProject(page, "foo");
    await expect(page).toHaveURL(`/tenants/${tenantName}/projects/foo`);
  });

  test("list extisting projects", async ({ page, tenantName }) => {
    const situation = await testBuilder()
      .withTenant(testTenant(tenantName))
      .build(page);
    await page.goto(`/tenants/${tenantName}`);
    const projects = ["project1", "project2", "project3"];

    for (const project of projects) {
      await page.goto(`/tenants/${tenantName}`);
      await createProject(page, project);
    }
    await page.goto(`/tenants/${tenantName}`);

    for (const project of projects) {
      await expect(
        page.getByRole("link", {
          name: project,
        })
      ).toBeVisible();
    }
  });
});

test.describe("Project setting screen should", () => {
  test("allow to delete project", async ({ page, tenantName }) => {
    const situation = await testBuilder()
      .withTenant(testTenant(tenantName).withProject(testProject("project")))
      .build(page);
    await page.goto(`/tenants/${tenantName}/projects/project`);
    await page.getByLabel("Project settings").click();
    await page.getByRole("button", { name: "Delete project" }).click();
    await page.getByRole("textbox", { name: "Confirmation" }).fill("project");
    await page.getByRole("button", { name: "Confirm" }).click();
    await expect(page).toHaveURL(`/tenants/${tenantName}`);
    await expect(page.getByRole("link", { name: "foo" })).toHaveCount(0);
  });
});
