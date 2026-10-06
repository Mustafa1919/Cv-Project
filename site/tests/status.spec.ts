import { expect, test } from "@playwright/test";
import type { Page, Route } from "@playwright/test";
import {
  API_ORIGIN,
  SITE_ORIGIN,
  expectProfileContent,
  mockStatus,
  ui,
} from "./helpers.ts";

async function expectState(page: Page, state: "up" | "down"): Promise<void> {
  await expect(page.locator("[data-status-badge]")).toHaveAttribute(
    "data-state",
    state,
    { timeout: 6000 },
  );
  await expect(page.locator("[data-status-text]")).toHaveText(ui("tr")(`status.${state}`));
}

test("unreachable API does not affect content or create persistence", async ({ page, context }) => {
  await page.goto("/");
  await expectState(page, "down");
  await expectProfileContent(page, "tr");
  expect(await context.cookies()).toEqual([]);
  expect(await page.evaluate(() => Object.keys(localStorage))).toEqual([]);
});

test("healthy status is up", async ({ page }) => {
  await mockStatus(page, { status: "ok" });
  await page.goto("/");
  await expectState(page, "up");
});

test("degraded status is down", async ({ page }) => {
  await mockStatus(page, { status: "degraded" });
  await page.goto("/");
  await expectState(page, "down");
});

test("HTTP 503 is down", async ({ page }) => {
  await mockStatus(page, { status: 503 });
  await page.goto("/");
  await expectState(page, "down");
});

test("non-JSON HTTP 200 is down", async ({ page }) => {
  await page.route("**/v1/public/status", (route) =>
    route.fulfill({
      status: 200,
      contentType: "text/plain",
      headers: { "access-control-allow-origin": "*" },
      body: "not JSON",
    }),
  );
  await page.goto("/");
  await expectState(page, "down");
});

test("status is requested once per load without credentials", async ({ page, context }) => {
  await context.addCookies([
    { name: "e2e-api-cookie", value: "must-not-be-sent", url: API_ORIGIN },
  ]);
  const headers: Record<string, string>[] = [];
  await page.route("**/v1/public/status", async (route) => {
    headers.push(await route.request().allHeaders());
    expect(route.request().method()).toBe("GET");
    expect(new URL(route.request().url()).origin).toBe(API_ORIGIN);
    await route.fulfill({
      status: 200,
      contentType: "application/json",
      headers: { "access-control-allow-origin": "*" },
      body: JSON.stringify({ status: "ok" }),
    });
  });

  for (const expectedCount of [1, 2]) {
    if (expectedCount === 1) {
      await page.goto("/");
    } else {
      await page.reload();
    }
    await expectState(page, "up");
    await page.waitForTimeout(150);
    expect(headers).toHaveLength(expectedCount);
  }
  for (const header of headers) {
    expect(header["cookie"]).toBeUndefined();
  }
});

test("status resolution does not shift the main or badge", async ({ page }) => {
  let release: (() => void) | undefined;
  let received: (() => void) | undefined;
  const gate = new Promise<void>((resolve) => { release = resolve; });
  const requested = new Promise<void>((resolve) => { received = resolve; });

  await page.route("**/v1/public/status", async (route: Route) => {
    received?.();
    await gate;
    await route.fulfill({
      status: 200,
      contentType: "application/json",
      headers: { "access-control-allow-origin": "*" },
      body: JSON.stringify({ status: "degraded" }),
    });
  });

  try {
    await page.goto("/", { waitUntil: "domcontentloaded" });
    await requested;
    await page.evaluate(() => document.fonts.ready);
    const main = page.locator("main");
    const badge = page.locator("[data-status-badge]");
    await expect(badge).toHaveAttribute("data-state", "unknown");
    const before = {
      main: await main.boundingBox(),
      badge: await badge.boundingBox(),
    };
    release?.();
    await expectState(page, "down");
    const after = {
      main: await main.boundingBox(),
      badge: await badge.boundingBox(),
    };
    for (const key of ["main", "badge"] as const) {
      const first = before[key];
      const last = after[key];
      expect(first).not.toBeNull();
      expect(last).not.toBeNull();
      if (first !== null && last !== null) {
        expect(last.y).toBe(first.y);
        expect(last.height).toBe(first.height);
      }
    }
  } finally {
    release?.();
  }
});

test("JavaScript disabled preserves neutral status and content", async ({ browser }) => {
  const context = await browser.newContext({
    javaScriptEnabled: false,
    baseURL: SITE_ORIGIN,
  });
  try {
    const page = await context.newPage();
    const apiRequests: string[] = [];
    page.on("request", (request) => {
      if (new URL(request.url()).origin === API_ORIGIN) {
        apiRequests.push(request.url());
      }
    });
    await page.goto("/");
    await expect(page.locator("[data-status-badge]")).toHaveAttribute("data-state", "unknown");
    await expect(page.locator("[data-status-text]")).toHaveText(ui("tr")("status.neutral"));
    await expect(page.locator("[data-theme-toggle]")).toBeHidden();
    await expectProfileContent(page, "tr");
    expect(apiRequests).toEqual([]);
    expect(await context.cookies()).toEqual([]);
  } finally {
    await context.close();
  }
});
