import AxeBuilder from "@axe-core/playwright";
import { expect, test } from "@playwright/test";
import type { Page } from "@playwright/test";
import type { Locale } from "../src/lib/i18n.ts";
import { printPath, profilePath } from "../src/lib/routes.ts";
import {
  collectProblems,
  evidence,
  LOCALES,
  mockStatus,
  profile,
  SITE_ORIGIN,
  skills,
  ui,
  waitForStatus,
} from "./helpers.ts";
import { normalizeText } from "./pdf-helpers.ts";

const sectionKeys = [
  "profile.strongestEvidence",
  "profile.skills",
  "profile.experience",
  "profile.projects",
  "profile.education",
  "profile.certificates",
] as const;

function nonEmpty(value: string, label: string): string {
  const normalized = normalizeText(value);
  expect(normalized, `${label} must not be empty`).not.toBe("");
  return normalized;
}

function sectionLabels(locale: Locale): string[] {
  const t = ui(locale);
  return sectionKeys.map((key) => nonEmpty(t(key), key));
}

async function openPrint(page: Page, locale: Locale): Promise<void> {
  const response = await page.goto(printPath(locale), { waitUntil: "load" });
  expect(response, "The print page must return a response").not.toBeNull();
  if (response === null) {
    throw new Error("The print page returned no response.");
  }
  expect(response.status()).toBe(200);
  await page.evaluate(async () => {
    await document.fonts.ready;
  });
}

for (const locale of LOCALES) {
  test.describe(`Print HTML (${locale})`, () => {
    test("renders localized identity, summary and metadata without availability", async ({ page }) => {
      const content = profile();
      const t = ui(locale);
      const name = nonEmpty(content.name, "Profile name");
      const role = nonEmpty(content.targetRole[locale], "Target role");
      const location = nonEmpty(content.location[locale], "Location");
      const workPreference = nonEmpty(content.workPreference[locale], "Work preference");
      const summary = nonEmpty(content.summary[locale], "Summary");
      const titleTemplate = nonEmpty(t("print.title"), "Print title template");
      expect(titleTemplate).toContain("{name}");
      const title = titleTemplate.replaceAll("{name}", content.name);

      await openPrint(page, locale);

      await expect(page.locator("html")).toHaveAttribute("lang", locale);
      await expect(page).toHaveTitle(title);
      await expect(page.locator("h1")).toHaveCount(1);
      await expect(page.locator("h1")).toHaveText(name);
      await expect(page.locator(".cv-role")).toHaveText(role);
      await expect(page.locator(".cv-meta")).toHaveText(`${location} · ${workPreference}`);
      await expect(page.locator(".cv-summary")).toHaveText(summary);

      if (content.availability !== undefined) {
        const availability = nonEmpty(content.availability[locale], "Availability");
        expect(normalizeText(await page.locator("main").innerText())).not.toContain(availability);
      }
    });

    test("is noindex in HTML and HTTP and canonicalizes to the public profile", async ({ page }) => {
      const response = await page.goto(printPath(locale), { waitUntil: "load" });
      expect(response).not.toBeNull();
      if (response === null) {
        throw new Error("The print page returned no response.");
      }
      expect(response.status()).toBe(200);

      await expect(page.locator('meta[name="robots"]')).toHaveCount(1);
      await expect(page.locator('meta[name="robots"]')).toHaveAttribute("content", "noindex");
      expect((await response.allHeaders())["x-robots-tag"]).toBe("noindex");
      await expect(page.locator('link[rel="canonical"]')).toHaveCount(1);
      await expect(page.locator('link[rel="canonical"]')).toHaveAttribute(
        "href",
        SITE_ORIGIN + profilePath(locale),
      );
    });

    test("has no scripts or inline styles and loads only built stylesheets without errors", async ({ page }) => {
      const problems = collectProblems(page);
      await openPrint(page, locale);

      await expect(page.locator("script")).toHaveCount(0);
      await expect(page.locator("style")).toHaveCount(0);
      await expect(page.locator("[style]")).toHaveCount(0);

      const stylesheetHrefs = await page
        .locator('link[rel="stylesheet"]')
        .evaluateAll((elements) =>
          elements.map((element) => element.getAttribute("href")),
        );

      expect(stylesheetHrefs.length, "Print CSS must actually be loaded").toBeGreaterThan(0);
      for (const href of stylesheetHrefs) {
        expect(href, "A stylesheet must have a non-empty href").toBeTruthy();
        expect(href).toMatch(/^\/_astro\//u);
      }

      expect(problems, problems.join("\n")).toEqual([]);
    });

    test("prints the site address and every profile link as visible URL text", async ({ page }) => {
      await openPrint(page, locale);
      const content = profile();
      const siteUrl = SITE_ORIGIN + profilePath(locale);
      const siteLabel = nonEmpty(ui(locale)("print.site"), "Site label");

      await expect(page.locator(".cv-site")).toHaveText(`${siteLabel}: ${siteUrl}`);
      await expect(page.locator(".cv-site a")).toHaveCount(1);
      await expect(page.locator(".cv-site a")).toBeVisible();
      await expect(page.locator(".cv-site a")).toHaveText(siteUrl);
      await expect(page.locator(".cv-site a")).toHaveAttribute("href", siteUrl);

      const links = content.links ?? [];
      const items = page.locator(".cv-links li");
      await expect(items).toHaveCount(links.length);
      await expect(page.locator("main a[href]")).toHaveCount(1 + links.length);

      for (const [index, link] of links.entries()) {
        const label = nonEmpty(link.label[locale], `Link ${link.id} label`);
        nonEmpty(link.url, `Link ${link.id} URL`);
        const item = items.nth(index);
        await expect(item).toHaveText(`${label}: ${link.url}`);
        await expect(item.locator("a")).toHaveCount(1);
        await expect(item.locator("a")).toBeVisible();
        await expect(item.locator("a")).toHaveText(link.url);
        await expect(item.locator("a")).toHaveAttribute("href", link.url);
      }
    });

    test("renders all six visible section headings in content order", async ({ page }) => {
      await openPrint(page, locale);
      const headings = page.locator("main section > h2");
      const expected = sectionLabels(locale);

      await expect(headings).toHaveCount(expected.length);
      expect((await headings.allTextContents()).map(normalizeText)).toEqual(expected);

      for (const heading of await headings.all()) {
        await expect(heading).toBeVisible();
      }
    });

    test("renders every skill with localized years in content order", async ({ page }) => {
      expect(skills.items.length, "Sample skills must not be empty").toBeGreaterThan(0);
      const template = nonEmpty(ui(locale)("print.years"), "Years template");
      expect(template).toContain("{years}");

      await openPrint(page, locale);
      const items = page.locator(".cv-skills li");
      await expect(items).toHaveCount(skills.items.length);

      for (const [index, skill] of skills.items.entries()) {
        const name = nonEmpty(skill.name, `Skill ${skill.id} name`);
        const years = nonEmpty(
          template.replaceAll("{years}", String(skill.years)),
          `Skill ${skill.id} years`,
        );
        await expect(items.nth(index)).toHaveText(`${name} ${years}`);
        await expect(items.nth(index).locator(".cv-years")).toHaveText(years);
      }
    });

    test("renders every evidence title and highlight in content order", async ({ page }) => {
      const highlights = profile().highlights;
      expect(highlights.length, "Sample highlights must not be empty").toBeGreaterThan(0);

      await openPrint(page, locale);
      const items = page.locator(".cv-evidence li");
      await expect(items).toHaveCount(highlights.length);

      for (const [index, highlight] of highlights.entries()) {
        const matchingEvidence = evidence.items.find(
          (item) => item.id === highlight.evidence,
        );
        expect(
          matchingEvidence,
          `Evidence ${highlight.evidence} must resolve`,
        ).toBeDefined();
        if (matchingEvidence === undefined) {
          throw new Error(`Unresolved sample evidence: ${highlight.evidence}`);
        }

        const title = nonEmpty(
          matchingEvidence.title[locale],
          `Evidence ${matchingEvidence.id} title`,
        );
        const text = nonEmpty(highlight.text[locale], "Highlight text");
        const item = items.nth(index);
        await expect(item.locator("strong")).toHaveText(title);
        expect(normalizeText(await item.innerText())).toContain(`${title} — ${text}`);
      }
    });

    test("keeps visual order equal to DOM reading order in print media", async ({ page }) => {
      await page.setViewportSize({ width: 794, height: 1123 });
      await page.emulateMedia({ media: "print" });
      await openPrint(page, locale);

      const elements = page.locator(
        "main h1, main .cv-role, main .cv-meta, main .cv-site, main .cv-summary, main section > h2",
      );
      await expect(elements).toHaveCount(11);

      const texts = (await elements.allTextContents()).map(normalizeText);
      const content = profile();
      expect(texts).toEqual([
        nonEmpty(content.name, "Name"),
        nonEmpty(content.targetRole[locale], "Role"),
        `${nonEmpty(content.location[locale], "Location")} · ${nonEmpty(content.workPreference[locale], "Work preference")}`,
        `${nonEmpty(ui(locale)("print.site"), "Site label")}: ${SITE_ORIGIN + profilePath(locale)}`,
        nonEmpty(content.summary[locale], "Summary"),
        ...sectionLabels(locale),
      ]);

      let previousTop = Number.NEGATIVE_INFINITY;
      for (const element of await elements.all()) {
        const text = nonEmpty(await element.innerText(), "Reading-order element");
        await expect(element).toBeVisible();
        const box = await element.boundingBox();
        expect(box, `Missing bounding box for ${JSON.stringify(text)}`).not.toBeNull();
        if (box === null) {
          throw new Error(`Missing bounding box for ${JSON.stringify(text)}`);
        }
        expect(
          box.y,
          `Top edge of ${JSON.stringify(text)} must not precede the previous element`,
        ).toBeGreaterThanOrEqual(previousTop);
        previousTop = box.y;
      }
    });

    test("does not alter extracted text through spacing, transforms, positioning or generated content", async ({ page }) => {
      await page.setViewportSize({ width: 794, height: 1123 });
      await page.emulateMedia({ media: "print" });
      await openPrint(page, locale);

      const result = await page.evaluate(() => {
        const elements = Array.from(document.querySelectorAll("main, main *"));
        const offenses: string[] = [];

        for (const element of elements) {
          const selector =
            element.tagName.toLowerCase() +
            (element.id === "" ? "" : `#${element.id}`) +
            Array.from(element.classList, (name) => `.${name}`).join("");
          const style = getComputedStyle(element);

          if (style.letterSpacing !== "normal" && style.letterSpacing !== "0px") {
            offenses.push(`${selector}: letter-spacing=${style.letterSpacing}`);
          }
          if (style.textTransform !== "none") {
            offenses.push(`${selector}: text-transform=${style.textTransform}`);
          }
          if (style.cssFloat !== "none") {
            offenses.push(`${selector}: float=${style.cssFloat}`);
          }
          if (style.position !== "static") {
            offenses.push(`${selector}: position=${style.position}`);
          }

          for (const pseudo of ["::before", "::after"]) {
            const content = getComputedStyle(element, pseudo).content;
            if (content !== "none" && content !== "normal") {
              offenses.push(`${selector}${pseudo}: content=${content}`);
            }
          }
        }

        return { count: elements.length, offenses };
      });

      expect(result.count, "Typography inspection must inspect actual elements").toBeGreaterThan(0);
      expect(result.offenses, result.offenses.join("\n")).toEqual([]);
    });

    test("uses loaded declared static font families for every direct text node", async ({ page }) => {
      await page.emulateMedia({ media: "print" });
      await openPrint(page, locale);

      const result = await page.evaluate(() => {
        let checked = 0;
        const offenses: string[] = [];

        for (const element of document.querySelectorAll("main, main *")) {
          const text = Array.from(element.childNodes)
            .filter((node) => node.nodeType === Node.TEXT_NODE)
            .map((node) => node.textContent ?? "")
            .join("")
            .trim();

          if (text === "") {
            continue;
          }

          checked += 1;
          const selector =
            element.tagName.toLowerCase() +
            (element.id === "" ? "" : `#${element.id}`) +
            Array.from(element.classList, (name) => `.${name}`).join("");
          const style = getComputedStyle(element);
          const firstFamily = (style.fontFamily.split(",")[0] ?? "")
            .trim()
            .replace(/^["']|["']$/gu, "");

          if (firstFamily !== "Source Serif 4" && firstFamily !== "IBM Plex Mono") {
            offenses.push(`${selector}: unexpected first font family ${style.fontFamily}`);
            continue;
          }

          // document.fonts.check() is too strict here: "ı" is in the unicode-range of both the
          // latin and latin-ext faces, and the browser only loads the one it needs. The PDF font
          // test is what catches a system-font fallback; this only requires a loaded face.
          const faces = Array.from(document.fonts).filter(
            (face) =>
              face.family.replace(/^["']|["']$/gu, "") === firstFamily &&
              face.weight === style.fontWeight &&
              face.style === style.fontStyle,
          );
          const font = `${style.fontStyle} ${style.fontWeight} "${firstFamily}"`;
          if (!faces.some((face) => face.status === "loaded")) {
            offenses.push(`${selector}: no loaded face for ${JSON.stringify(text)} (${font})`);
          }
          if (faces.some((face) => face.status === "error")) {
            offenses.push(`${selector}: a face failed to load (${font})`);
          }
        }

        return { checked, offenses };
      });

      expect(result.checked, "Font inspection must inspect non-empty direct text").toBeGreaterThan(0);
      expect(result.offenses, result.offenses.join("\n")).toEqual([]);
    });

    for (const width of [794, 320]) {
      test(`has no horizontal overflow at ${width}px`, async ({ page }) => {
        await page.setViewportSize({ width, height: 1123 });
        await openPrint(page, locale);
        await expect(page.locator("main")).toBeVisible();

        const dimensions = await page.evaluate(() => ({
          scrollWidth: document.documentElement.scrollWidth,
          clientWidth: document.documentElement.clientWidth,
        }));

        expect(dimensions.clientWidth).toBe(width);
        expect(dimensions.scrollWidth).toBeLessThanOrEqual(dimensions.clientWidth);
      });
    }

    test("has no WCAG A or AA accessibility violations", async ({ page }) => {
      await openPrint(page, locale);
      await expect(page.locator("main")).toBeVisible();

      const results = await new AxeBuilder({ page })
        .withTags(["wcag2a", "wcag2aa", "wcag21a", "wcag21aa"])
        .analyze();

      expect(
        results.violations,
        JSON.stringify(results.violations, null, 2),
      ).toEqual([]);
    });
  });
}

test("the public sitemap excludes both print routes", async ({ request, page }) => {
  const response = await request.get("/sitemap.xml");
  expect(response.status()).toBe(200);
  const xml = nonEmpty(await response.text(), "Sitemap XML");

  const locations = await page.evaluate((source) => {
    const document = new DOMParser().parseFromString(source, "application/xml");
    if (document.querySelector("parsererror") !== null) {
      throw new Error("Sitemap is not valid XML.");
    }
    return Array.from(document.getElementsByTagName("loc"), (element) =>
      (element.textContent ?? "").trim(),
    );
  }, xml);

  expect(locations.length, "Sitemap must contain public URLs").toBeGreaterThan(0);

  for (const location of locations) {
    nonEmpty(location, "Sitemap location");
    const path = new URL(location).pathname;
    for (const locale of LOCALES) {
      expect(path).not.toBe(printPath(locale));
    }
  }
});

test("neither public profile links to either print route", async ({ page }) => {
  await mockStatus(page, { status: "ok" });

  for (const locale of LOCALES) {
    const response = await page.goto(profilePath(locale), { waitUntil: "load" });
    expect(response?.status()).toBe(200);
    await waitForStatus(page);
    await expect(page.locator("h1")).toHaveText(nonEmpty(profile().name, "Profile name"));

    const hrefs = await page.locator("a[href]").evaluateAll((elements) =>
      elements.map((element) => element.getAttribute("href") ?? ""),
    );
    expect(hrefs.length, "The profile must contain links to inspect").toBeGreaterThan(0);

    for (const href of hrefs) {
      const url = new URL(href, SITE_ORIGIN + profilePath(locale));
      for (const printLocale of LOCALES) {
        expect(
          url.pathname,
          `Profile ${profilePath(locale)} must not link to ${printPath(printLocale)} via ${JSON.stringify(href)}`,
        ).not.toBe(printPath(printLocale));
      }
    }
  }
});
