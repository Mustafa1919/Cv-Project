import { expect, test } from "@playwright/test";
import { PAGES, mockStatus, waitForStatus } from "./helpers.ts";

for (const entry of PAGES) {
  for (const width of [320, 360, 768, 1280]) {
    test(`no page overflow ${entry.path} ${width}`, async ({ page }) => {
      await page.setViewportSize({ width, height: 900 });
      await mockStatus(page, { status: "ok" });
      await page.goto(entry.path);
      await waitForStatus(page);
      await page.evaluate(() => document.fonts.ready);
      const dimensions = await page.evaluate(() => ({
        scroll: document.documentElement.scrollWidth,
        client: document.documentElement.clientWidth,
      }));
      expect(dimensions.scroll).toBeLessThanOrEqual(dimensions.client);
    });
  }
}

test("skills table scrolls locally at 320 pixels", async ({ page }) => {
  await page.setViewportSize({ width: 320, height: 900 });
  await mockStatus(page, { status: "ok" });
  await page.goto("/");
  await page.evaluate(() => document.fonts.ready);
  const container = page.locator(".table-wrap");
  const dimensions = await container.evaluate((element) => ({
    scroll: element.scrollWidth,
    client: element.clientWidth,
    overflow: getComputedStyle(element).overflowX,
  }));
  expect(dimensions.scroll).toBeGreaterThan(dimensions.client);
  expect(["auto", "scroll"]).toContain(dimensions.overflow);
  await container.evaluate((element) => { element.scrollLeft = element.scrollWidth; });
  expect(await container.evaluate((element) => element.scrollLeft)).toBeGreaterThan(0);
  const pageDimensions = await page.evaluate(() => ({
    scroll: document.documentElement.scrollWidth,
    client: document.documentElement.clientWidth,
  }));
  expect(pageDimensions.scroll).toBeLessThanOrEqual(pageDimensions.client);
});
