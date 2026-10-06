import { expect, test } from "@playwright/test";
import type { Page } from "@playwright/test";
import { mockStatus } from "./helpers.ts";

async function expectBackground(page: Page, color: string): Promise<void> {
  await expect(page.locator("body")).toHaveCSS("background-color", color);
}

test("system dark applies without a theme attribute", async ({ page }) => {
  await page.emulateMedia({ colorScheme: "dark" });
  await mockStatus(page, { status: "ok" });
  await page.goto("/");
  expect(await page.locator("html").getAttribute("data-theme")).toBeNull();
  await expectBackground(page, "rgb(20, 19, 15)");
});

test("system light applies without a theme attribute", async ({ page }) => {
  await page.emulateMedia({ colorScheme: "light" });
  await mockStatus(page, { status: "ok" });
  await page.goto("/");
  expect(await page.locator("html").getAttribute("data-theme")).toBeNull();
  await expectBackground(page, "rgb(250, 248, 243)");
});

test("stored dark choice is applied before DOMContentLoaded", async ({ page, context }) => {
  await page.emulateMedia({ colorScheme: "light" });
  await context.addInitScript(() => {
    localStorage.setItem("vitrin-theme", "dark");
    document.addEventListener("DOMContentLoaded", () => {
      document.documentElement.setAttribute(
        "data-test-theme-at-dom",
        document.documentElement.getAttribute("data-theme") ?? "",
      );
    }, { once: true });
  });
  await mockStatus(page, { status: "ok" });
  await page.goto("/", { waitUntil: "domcontentloaded" });
  await expect(page.locator("html")).toHaveAttribute("data-test-theme-at-dom", "dark");
  await expect(page.locator("html")).toHaveAttribute("data-theme", "dark");
  await expectBackground(page, "rgb(20, 19, 15)");
});

test("theme toggle updates its state, persists, and survives reload", async ({ page }) => {
  await page.emulateMedia({ colorScheme: "light" });
  await mockStatus(page, { status: "ok" });
  await page.goto("/");
  const button = page.locator("[data-theme-toggle]");
  await expect(button).toBeVisible();
  await expect(button).toHaveAttribute("aria-pressed", "false");
  await button.click();
  await expect(page.locator("html")).toHaveAttribute("data-theme", "dark");
  await expect(button).toHaveAttribute("aria-pressed", "true");
  expect(await page.evaluate(() => localStorage.getItem("vitrin-theme"))).toBe("dark");
  await expectBackground(page, "rgb(20, 19, 15)");

  await page.reload();
  await expect(page.locator("html")).toHaveAttribute("data-theme", "dark");
  await expect(button).toHaveAttribute("aria-pressed", "true");
  await button.click();
  await expect(page.locator("html")).toHaveAttribute("data-theme", "light");
  await expect(button).toHaveAttribute("aria-pressed", "false");
  expect(await page.evaluate(() => localStorage.getItem("vitrin-theme"))).toBe("light");
  await expectBackground(page, "rgb(250, 248, 243)");
});

test("invalid stored theme is ignored", async ({ page, context }) => {
  await page.emulateMedia({ colorScheme: "light" });
  await context.addInitScript(() => {
    localStorage.setItem("vitrin-theme", "invalid-e2e-theme");
  });
  await mockStatus(page, { status: "ok" });
  await page.goto("/");
  expect(await page.locator("html").getAttribute("data-theme")).toBeNull();
  await expectBackground(page, "rgb(250, 248, 243)");
  await expect(page.locator("[data-theme-toggle]")).toHaveAttribute("aria-pressed", "false");
});
