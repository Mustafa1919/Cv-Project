import { expect, test } from "@playwright/test";
import {
  LOCALES,
  PAGES,
  PUBLIC_PAGES,
  SITE_ORIGIN,
  mockStatus,
} from "./helpers.ts";
import { pagePath } from "../src/lib/routes.ts";

for (const entry of PUBLIC_PAGES) {
  test(`localized metadata ${entry.path}`, async ({ page, request }) => {
    await mockStatus(page, { status: "ok" });
    await page.goto(entry.path);
    const url = SITE_ORIGIN + entry.path;
    await expect(page.locator('link[rel="canonical"]')).toHaveAttribute("href", url);
    const alternates = page.locator('link[rel="alternate"][hreflang]');
    await expect(alternates).toHaveCount(3);
    for (const language of LOCALES) {
      await expect(
        alternates.filter({ has: page.locator(`:scope[hreflang="${language}"]`) }),
      ).toHaveCount(1);
      await expect(page.locator(`link[rel="alternate"][hreflang="${language}"]`))
        .toHaveAttribute("href", SITE_ORIGIN + pagePath(entry.page, language));
    }
    await expect(page.locator('link[rel="alternate"][hreflang="x-default"]'))
      .toHaveAttribute("href", SITE_ORIGIN + pagePath(entry.page, "tr"));

    for (const property of ["og:title", "og:description", "og:url", "og:locale"]) {
      const meta = page.locator(`meta[property="${property}"]`);
      await expect(meta).toHaveCount(1);
      expect((await meta.getAttribute("content"))?.trim()).toBeTruthy();
    }
    await expect(page.locator('meta[property="og:title"]'))
      .toHaveAttribute("content", await page.title());
    await expect(page.locator('meta[property="og:url"]')).toHaveAttribute("content", url);
    await expect(page.locator('meta[property="og:locale"]'))
      .toHaveAttribute("content", entry.locale === "tr" ? "tr_TR" : "en_US");

    const description = await page.locator('meta[name="description"]').getAttribute("content");
    expect(description?.trim()).toBeTruthy();
    const other = entry.locale === "tr" ? "en" : "tr";
    const response = await request.get(pagePath(entry.page, other));
    expect(response.status()).toBe(200);
    const otherDescription = await page.evaluate((html) =>
      new DOMParser().parseFromString(html, "text/html")
        .querySelector('meta[name="description"]')?.getAttribute("content"),
    await response.text());
    expect(otherDescription?.trim()).toBeTruthy();
    expect(otherDescription).not.toBe(description);
  });
}

for (const entry of PAGES.filter((item) => item.kind === "notFound")) {
  test(`not-found metadata ${entry.path}`, async ({ page }) => {
    await mockStatus(page, { status: "ok" });
    await page.goto(entry.path);
    await expect(page.locator('meta[name="robots"]')).toHaveAttribute("content", "noindex");
    await expect(page.locator('link[rel="canonical"]')).toHaveCount(0);
  });
}

test("robots and sitemap describe exactly the public HTML pages", async ({ page, request }) => {
  const robots = await request.get("/robots.txt");
  expect(robots.status()).toBe(200);
  expect(robots.headers()["content-type"]).toContain("text/plain");
  expect(await robots.text()).toBe(
    `User-agent: *\nAllow: /\nSitemap: ${SITE_ORIGIN}/sitemap.xml\n`,
  );

  const sitemap = await request.get("/sitemap.xml");
  expect(sitemap.status()).toBe(200);
  expect(sitemap.headers()["content-type"]).toContain("application/xml");
  const parsed = await page.evaluate((xml) => {
    const document = new DOMParser().parseFromString(xml, "application/xml");
    return {
      invalid: document.getElementsByTagName("parsererror").length > 0,
      entries: Array.from(document.getElementsByTagName("url")).map((url) => ({
        loc: url.getElementsByTagName("loc")[0]?.textContent ?? "",
        alternates: Array.from(
          url.getElementsByTagNameNS("http://www.w3.org/1999/xhtml", "link"),
        ).map((link) => ({
          rel: link.getAttribute("rel"),
          language: link.getAttribute("hreflang"),
          href: link.getAttribute("href"),
        })),
      })),
    };
  }, await sitemap.text());
  expect(parsed.invalid).toBe(false);
  expect(parsed.entries.map((entry) => entry.loc).sort()).toEqual(
    PUBLIC_PAGES.map((entry) => SITE_ORIGIN + entry.path).sort(),
  );
  expect(parsed.entries.some((entry) => entry.loc.endsWith(".pdf"))).toBe(false);
  for (const item of parsed.entries) {
    const entry = PUBLIC_PAGES.find((candidate) => SITE_ORIGIN + candidate.path === item.loc);
    expect(entry).toBeDefined();
    if (entry === undefined) {
      throw new Error(`Unexpected sitemap URL: ${item.loc}`);
    }
    expect(item.alternates).toHaveLength(3);
    for (const language of ["tr", "en", "x-default"] as const) {
      expect(item.alternates).toContainEqual({
        rel: "alternate",
        language,
        href: SITE_ORIGIN + pagePath(entry.page, language === "x-default" ? "tr" : language),
      });
    }
  }
});

test("fonts are self-hosted and preloaded", async ({ page }) => {
  const fonts: string[] = [];
  page.on("request", (request) => {
    if (request.resourceType() === "font" || /\.(?:woff2?|ttf|otf)(?:\?|$)/i.test(request.url())) {
      fonts.push(request.url());
    }
  });
  await mockStatus(page, { status: "ok" });
  await page.goto("/");
  await page.evaluate(() => document.fonts.ready);
  expect(fonts.length).toBeGreaterThan(0);
  for (const url of fonts) {
    expect(new URL(url).origin).toBe(SITE_ORIGIN);
  }
  const preloads = page.locator('link[rel="preload"][as="font"]');
  await expect(preloads).toHaveCount(2);
  for (const preload of await preloads.all()) {
    const href = await preload.getAttribute("href");
    expect(href).not.toBeNull();
    expect(new URL(href ?? "", SITE_ORIGIN).origin).toBe(SITE_ORIGIN);
    await expect(preload).toHaveAttribute("type", "font/woff2");
    expect(await preload.getAttribute("crossorigin")).not.toBeNull();
  }
});
