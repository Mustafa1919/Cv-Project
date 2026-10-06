import { expect, test } from "@playwright/test";
import {
  LOCALES,
  PAGES,
  PUBLIC_PAGES,
  SITE_ORIGIN,
  mockStatus,
  profile,
  ui,
} from "./helpers.ts";
import {
  alternate,
  pdfPath,
  profilePath,
  projectPath,
  projectsPath,
} from "../src/lib/routes.ts";

for (const entry of PUBLIC_PAGES) {
  test(`language switch round trip ${entry.path}`, async ({ page }) => {
    await mockStatus(page, { status: "ok" });
    await page.goto(entry.path);
    const other = entry.locale === "tr" ? "en" : "tr";
    const switchLink = page.locator(`header .tools a[hreflang="${other}"]`);
    await expect(switchLink).toHaveAttribute("lang", other);
    const targetName = ui(other)(`language.${other}`);
    await expect(switchLink).toHaveAttribute(
      "aria-label",
      ui(entry.locale)("language.switchLabel").replace("{language}", targetName),
    );
    await switchLink.click();
    expect(new URL(page.url()).pathname).toBe(alternate(entry.page, entry.locale));
    await expect(page.locator("html")).toHaveAttribute("lang", other);
    await page.locator(`header .tools a[hreflang="${entry.locale}"]`).click();
    expect(new URL(page.url()).pathname).toBe(entry.path);
  });
}

test("browser language does not redirect the default locale", async ({ browser }) => {
  const context = await browser.newContext({ locale: "en-US", baseURL: SITE_ORIGIN });
  try {
    const page = await context.newPage();
    await mockStatus(page, { status: "ok" });
    await page.goto("/");
    expect(new URL(page.url()).pathname).toBe("/");
    await expect(page.locator("html")).toHaveAttribute("lang", "tr");
  } finally {
    await context.close();
  }
});

for (const entry of PAGES.filter((item) =>
  item.kind === "profile" ||
  item.kind === "projects" ||
  (item.page.kind === "project" && item.page.id === "queue-service")
)) {
  test(`header navigation state ${entry.path}`, async ({ page }) => {
    await mockStatus(page, { status: "ok" });
    await page.goto(entry.path);
    const t = ui(entry.locale);
    const nav = page.getByRole("navigation", { name: t("nav.mainLabel") });
    await expect(nav.locator("a")).toHaveText([t("nav.profile"), t("nav.projects")]);
    const profileLink = nav.locator("a").nth(0);
    const projectsLink = nav.locator("a").nth(1);
    await expect(profileLink).toHaveAttribute("href", profilePath(entry.locale));
    await expect(projectsLink).toHaveAttribute("href", projectsPath(entry.locale));
    if (entry.kind === "profile") {
      await expect(profileLink).toHaveAttribute("aria-current", "page");
      expect(await projectsLink.getAttribute("aria-current")).toBeNull();
    } else {
      expect(await profileLink.getAttribute("aria-current")).toBeNull();
      await expect(projectsLink).toHaveAttribute(
        "aria-current",
        entry.kind === "projects" ? "page" : "true",
      );
    }
  });
}

for (const locale of LOCALES) {
  test(`PDF links remain visible ${locale}`, async ({ page }) => {
    await mockStatus(page, { status: "ok" });
    for (const width of [1280, 320]) {
      await page.setViewportSize({ width, height: 900 });
      await page.goto(profilePath(locale));
      const links = page.locator(`a[href="${pdfPath(locale)}"]`);
      await expect(links).toHaveCount(2);
      for (const link of await links.all()) {
        await expect(link).toHaveText(ui(locale)("actions.pdf"));
        await expect(link).toBeVisible();
        const box = await link.boundingBox();
        expect(box).not.toBeNull();
        if (box !== null) {
          expect(box.x).toBeGreaterThanOrEqual(0);
          expect(box.x + box.width).toBeLessThanOrEqual(width);
        }
      }
    }
  });
}

test("breadth-first crawl validates local links and fragments", async ({ page, request }) => {
  const queue = [profilePath("tr"), profilePath("en")];
  const visited = new Set<string>();
  const documents = new Map<string, { links: string[]; ids: string[] }>();
  const pdfPaths = new Set(LOCALES.map(pdfPath));

  async function documentAt(path: string) {
    const cached = documents.get(path);
    if (cached !== undefined) {
      return cached;
    }
    const response = await request.get(path);
    expect(response.status(), path).toBe(200);
    const html = await response.text();
    const parsed = await page.evaluate((source) => {
      const document = new DOMParser().parseFromString(source, "text/html");
      return {
        links: Array.from(document.querySelectorAll("a[href]")).map(
          (link) => link.getAttribute("href") ?? "",
        ),
        ids: Array.from(document.querySelectorAll("[id]")).map((element) => element.id),
      };
    }, html);
    documents.set(path, parsed);
    return parsed;
  }

  while (queue.length > 0) {
    const path = queue.shift();
    if (path === undefined || visited.has(path)) {
      continue;
    }
    visited.add(path);
    const document = await documentAt(path);
    for (const href of document.links) {
      const target = new URL(href, SITE_ORIGIN + path);
      if (target.origin !== SITE_ORIGIN) {
        continue;
      }
      if (pdfPaths.has(target.pathname)) {
        // Not an HTML document: check that it is served, do not parse it.
        if (!visited.has(target.pathname)) {
          visited.add(target.pathname);
          const pdf = await request.get(target.pathname);
          expect(pdf.status(), target.pathname).toBe(200);
          expect(pdf.headers()["content-type"], target.pathname).toBe("application/pdf");
        }
        continue;
      }
      const targetPath = target.pathname + target.search;
      const targetDocument = await documentAt(targetPath);
      if (target.hash !== "") {
        expect(
          targetDocument.ids,
          `${path} -> ${target.href}`,
        ).toContain(decodeURIComponent(target.hash.slice(1)));
      }
      if (!visited.has(targetPath)) {
        queue.push(targetPath);
      }
    }
  }

  for (const entry of PUBLIC_PAGES) {
    expect(visited, `Unreachable page: ${entry.path}`).toContain(entry.path);
  }
  for (const path of pdfPaths) {
    expect(visited, `Unreachable PDF: ${path}`).toContain(path);
  }
});

test("external profile and repository links use noopener", async ({ page }) => {
  await mockStatus(page, { status: "ok" });
  await page.goto(profilePath("tr"));
  for (const item of profile().links ?? []) {
    const link = page.locator("main .actions a").filter({ hasText: item.label.tr });
    await expect(link).toHaveAttribute("href", item.url);
    expect((await link.getAttribute("rel"))?.split(/\s+/)).toContain("noopener");
  }
  await page.goto(projectPath("tr", "queue-service"));
  const repository = page.getByRole("link", {
    name: ui("tr")("caseStudy.repository"),
    exact: true,
  });
  await expect(repository).toHaveCount(1);
  expect((await repository.getAttribute("rel"))?.split(/\s+/)).toContain("noopener");
});
