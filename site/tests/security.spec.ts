import { expect, test } from "@playwright/test";
import {
  API_ORIGIN,
  PAGES,
  SITE_ORIGIN,
  collectProblems,
  mockStatus,
  waitForStatus,
} from "./helpers.ts";
import { profilePath, projectPath } from "../src/lib/routes.ts";

for (const path of [profilePath("tr"), projectPath("en", "queue-service")]) {
  test(`security response headers ${path}`, async ({ request }) => {
    const response = await request.get(path);
    expect(response.status()).toBe(200);
    const headers = response.headers();
    const csp = headers["content-security-policy"] ?? "";
    for (const directive of [
      "default-src 'none'",
      "style-src 'self'",
      "frame-ancestors 'none'",
      `connect-src ${API_ORIGIN}`,
    ]) {
      expect(csp).toContain(directive);
    }
    const script = csp.split(";").find((part) => part.trim().startsWith("script-src "));
    expect(script).toBeDefined();
    expect(script).toContain("'self'");
    expect(script).not.toContain("'unsafe-inline'");
    expect(script).not.toContain("'unsafe-eval'");
    expect(headers["strict-transport-security"]).toContain("max-age=31536000");
    expect(headers["strict-transport-security"]).toContain("includeSubDomains");
    expect(headers["x-content-type-options"]).toBe("nosniff");
    expect(headers["referrer-policy"]).toBe("strict-origin-when-cross-origin");
    expect(headers["permissions-policy"]).toContain("camera=()");
    expect(headers["permissions-policy"]).toContain("microphone=()");
    expect(headers["x-frame-options"]).toBe("DENY");
  });
}

test("hashed assets have immutable caching", async ({ page, request }) => {
  const response = await request.get("/");
  expect(response.status()).toBe(200);
  const assets = await page.evaluate((html) => {
    const document = new DOMParser().parseFromString(html, "text/html");
    return Array.from(document.querySelectorAll("link[href], script[src]"))
      .map((element) => element.getAttribute("href") ?? element.getAttribute("src") ?? "")
      .filter((url) => url.startsWith("/_astro/"));
  }, await response.text());
  expect(assets.length).toBeGreaterThan(0);
  const asset = assets[0];
  if (asset === undefined) {
    throw new Error("No hashed asset was found.");
  }
  const result = await request.get(asset);
  expect(result.status()).toBe(200);
  expect(result.headers()["cache-control"]).toBe("public, max-age=31536000, immutable");
});

for (const entry of PAGES) {
  test(`no CSP or runtime problems ${entry.path}`, async ({ page }) => {
    const problems = collectProblems(page);
    const cspMessages: string[] = [];
    page.on("console", (message) => {
      if (/Content Security Policy/i.test(message.text())) {
        cspMessages.push(message.text());
      }
    });
    await page.addInitScript(() => {
      window.__vitrinCspViolations = [];
      document.addEventListener("securitypolicyviolation", (event) => {
        window.__vitrinCspViolations?.push(
          `${event.violatedDirective}: ${event.blockedURI}`,
        );
      });
    });
    await mockStatus(page, { status: "ok" });
    await page.goto(entry.path);
    await waitForStatus(page);
    await page.evaluate(() => document.fonts.ready);
    await page.waitForTimeout(100);
    expect(cspMessages).toEqual([]);
    expect(await page.evaluate(() => window.__vitrinCspViolations ?? [])).toEqual([]);
    expect(problems).toEqual([]);
  });

  test(`network origin allowlist ${entry.path}`, async ({ page }) => {
    const urls: string[] = [];
    page.on("request", (request) => { urls.push(request.url()); });
    await mockStatus(page, { status: "ok" });
    await page.goto(entry.path);
    await waitForStatus(page);
    await page.evaluate(() => document.fonts.ready);
    expect(urls.length).toBeGreaterThan(0);
    for (const url of urls) {
      expect([SITE_ORIGIN, API_ORIGIN], url).toContain(new URL(url).origin);
    }
  });
}

test("private headers, traversal, and unsupported methods are rejected", async ({ request }) => {
  const privateFile = await request.get("/_headers");
  expect(privateFile.status()).toBe(404);
  for (const path of ["/..%2f..%2fpackage.json", "/%2e%2e/package.json"]) {
    const response = await request.get(path);
    expect([400, 403, 404], path).toContain(response.status());
    expect(response.headers()["content-type"] ?? "").not.toContain("application/json");
    expect(await response.text()).not.toMatch(/"name"\s*:\s*"vitrin-site"/);
  }
  const post = await request.post("/");
  expect(post.status()).toBe(405);
  expect(post.headers()["allow"]).toBe("GET, HEAD");
});

test("HTML sources have no inline CSS or event handlers", async ({ page, request }) => {
  for (const entry of PAGES) {
    const response = await request.get(entry.path);
    expect(response.status()).toBe(200);
    const html = await response.text();
    // Parse tags first: Markdown examples and script strings are not attributes.
    const tags = await page.evaluate((source) => {
      const document = new DOMParser().parseFromString(source, "text/html");
      return Array.from(document.querySelectorAll("*")).map((element) => ({
        name: element.localName,
        attributes: Array.from(element.attributes).map(
          (attribute) => `${attribute.name}=${JSON.stringify(attribute.value)}`,
        ),
      }));
    }, html);
    for (const tag of tags) {
      expect(`<${tag.name}`, entry.path).not.toMatch(/^<style(?:\s|$)/i);
      for (const attribute of tag.attributes) {
        expect(attribute, entry.path).not.toMatch(/^style\s*=/i);
        expect(attribute, entry.path).not.toMatch(/^on[^\s=]*\s*=/i);
      }
    }
  }
});
