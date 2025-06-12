import { screenshotBuilder, setup, updateFeatureCreationDate } from "./utils";
import {
  testBuilder,
  testFeature,
  testTenant,
  testProject,
} from "../testBuilder";

export async function generate() {
  const { page, browser } = await setup(true);

  const screenshot = screenshotBuilder("stale-features")(page);

  const situation = await testBuilder()
    .withTenant(
      testTenant("bookstore").withProject(
        testProject("website").withFeature(
          testFeature("comments").withEnableStatus()
        )
      )
    )
    .build(page);

  await updateFeatureCreationDate(
    "bookstore",
    situation.featuresIds.get("comments")!,
    "2020-05-20 15:00:00+00"
  );

  await page.goto("/tenants/bookstore/projects/website");
  await page.getByLabel("comments is stale", { exact: true }).hover();
  await page.waitForTimeout(200);

  await screenshot("stale-indicator");

  return await browser.close();
}

generate();
