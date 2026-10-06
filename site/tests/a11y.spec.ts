import { expect, test } from "@playwright/test";
import AxeBuilder from "@axe-core/playwright";
import { PAGES, mockStatus, ui, waitForStatus } from "./helpers.ts";

for (const entry of PAGES) {
  for (const theme of ["light", "dark"] as const) {
    test(`WCAG AA ${entry.path} ${theme}`, async ({ page }) => {
      await page.emulateMedia({ colorScheme: theme });
      await mockStatus(page, { status: "ok" });
      await page.goto(entry.path);
      await waitForStatus(page);
      await page.evaluate(() => document.fonts.ready);
      const results = await new AxeBuilder({ page })
        .withTags(["wcag2a", "wcag2aa", "wcag21a", "wcag21aa"])
        .analyze();
      const details = results.violations.map((violation) => ({
        id: violation.id,
        targets: violation.nodes.map((node) => node.target),
      }));
      expect(results.violations, JSON.stringify(details, null, 2)).toEqual([]);
    });
  }
}

test("keyboard skip link is first and focuses main", async ({ page }) => {
  await mockStatus(page, { status: "ok" });
  await page.goto("/");
  await page.keyboard.press("Tab");
  const skipLink = page.getByRole("link", { name: ui("tr")("nav.skipLink"), exact: true });
  await expect(skipLink).toBeFocused();
  await expect(skipLink).toBeVisible();
  const box = await skipLink.boundingBox();
  expect(box).not.toBeNull();
  if (box !== null) {
    expect(box.y).toBeGreaterThanOrEqual(0);
    expect(box.x).toBeGreaterThanOrEqual(0);
  }
  await page.keyboard.press("Enter");
  await expect(page.locator("main#main")).toBeFocused();
});
