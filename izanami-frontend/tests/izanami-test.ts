import { test as base } from "@playwright/test";

export async function tenantFromTitleAndBrowser(title, browser) {
  const browserName = (await browser.browserType()).name();
  const neutralizedTitle = title
    .replaceAll(" ", "-")
    .replaceAll(",", "-")
    .replaceAll("'", "");
  return `${browserName}-${neutralizedTitle}`.substring(0, 63);
}

export const test = base.extend<{ tenantName: string }>({
  tenantName: async ({ browser }, use, { title }) => {
    // Set up the fixture.
    const tenant = await tenantFromTitleAndBrowser(title, browser);
    // Use the fixture value in the test.
    await use(tenant);
  },
});
export { expect } from "@playwright/test";
