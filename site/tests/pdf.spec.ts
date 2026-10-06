import { expect, test } from "@playwright/test";
import type { APIRequestContext, Page } from "@playwright/test";
import type { Locale } from "../src/lib/i18n.ts";
import { pdfPath, printPath, profilePath } from "../src/lib/routes.ts";
import { inspectFonts } from "../scripts/pdf-inspect.ts";
import {
  LOCALES,
  mockStatus,
  profile,
  SITE_ORIGIN,
  ui,
  waitForStatus,
} from "./helpers.ts";
import { expectInOrder, normalizeText, readPdf } from "./pdf-helpers.ts";

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

function expectPdfContains(text: string, expected: string, label: string): void {
  const needle = nonEmpty(expected, label);
  expect(normalizeText(text), `PDF must contain ${label}: ${JSON.stringify(needle)}`).toContain(needle);
}

async function fetchPdf(
  request: APIRequestContext,
  locale: Locale,
): Promise<Uint8Array> {
  const response = await request.get(pdfPath(locale));
  expect(response.status(), `${pdfPath(locale)} must exist`).toBe(200);
  expect(response.headers()["content-type"]).toMatch(/^application\/pdf(?:;|$)/iu);

  const bytes = await response.body();
  expect(bytes.byteLength, "PDF must contain more than its signature").toBeGreaterThan(5);
  expect(Array.from(bytes.subarray(0, 5))).toEqual([0x25, 0x50, 0x44, 0x46, 0x2d]);

  return bytes;
}

async function openPrint(page: Page, locale: Locale): Promise<void> {
  const response = await page.goto(printPath(locale), { waitUntil: "load" });
  expect(response?.status()).toBe(200);
  await expect(page.locator("main")).toBeVisible();
}

for (const locale of LOCALES) {
  test.describe(`PDF (${locale})`, () => {
    test("is served successfully as a PDF with a valid signature", async ({ request }) => {
      await fetchPdf(request, locale);
    });

    test("S0-52: contains exactly one page", async ({ request }) => {
      const bytes = await fetchPdf(request, locale);
      const pdf = await readPdf(bytes);
      expect(pdf.pages).toBe(1);
      nonEmpty(pdf.text, "Extracted PDF text");
    });

    test("has metadata matching the rendered print page", async ({ request, page }) => {
      const bytes = await fetchPdf(request, locale);
      const pdf = await readPdf(bytes);
      await openPrint(page, locale);

      const metadata = await page.evaluate(() => ({
        title: document.title,
        subject:
          document
            .querySelector('meta[name="description"]')
            ?.getAttribute("content") ?? "",
      }));

      nonEmpty(metadata.title, "Print document title");
      nonEmpty(metadata.subject, "Print document description");
      nonEmpty(profile().name, "Profile name");

      expect(pdf.info["Title"]).toBe(metadata.title);
      expect(pdf.info["Author"]).toBe(profile().name);
      expect(pdf.info["Language"]).toBe(locale);
      expect(pdf.info["Subject"]).toBe(metadata.subject);
      expect(pdf.info["Creator"]).toBe("vitrin");
    });

    test("S0-52: uses only embedded static résumé fonts", async ({ request }) => {
      const bytes = await fetchPdf(request, locale);
      const result = await inspectFonts(bytes);

      expect(result.fonts.length, "PDF must contain font dictionaries").toBeGreaterThan(0);
      for (const font of result.fonts) {
        expect(font.subtype, "Font subtype must not be empty").not.toBe("");
        expect(font.subtype, `Unexpected Type3 font: ${font.baseFont ?? "unnamed"}`).not.toBe("Type3");
        expect(["Type0", "CIDFontType2"]).toContain(font.subtype);

        expect(font.baseFont, "Every font must have a BaseFont name").toBeDefined();
        if (font.baseFont === undefined) {
          throw new Error("PDF font has no BaseFont name.");
        }
        nonEmpty(font.baseFont, "BaseFont name");
        expect(font.baseFont).toMatch(/^(?:SourceSerif4|IBMPlexMono)/u);
      }

      expect(
        result.descriptors.length,
        "PDF must contain font descriptors",
      ).toBeGreaterThan(0);
      for (const descriptor of result.descriptors) {
        expect(
          descriptor.embedded,
          `Font ${descriptor.fontName ?? "unnamed"} must have an embedded font program`,
        ).toBe(true);
      }
    });

    test("S0-52: extracts identity, site, summary and section headings in reading order", async ({ request }) => {
      const bytes = await fetchPdf(request, locale);
      const pdf = await readPdf(bytes);
      const content = profile();
      const summary = nonEmpty(content.summary[locale], "Summary");
      const headings = sectionKeys.map((key) => nonEmpty(ui(locale)(key), key));

      expectInOrder(pdf.text, [
        nonEmpty(content.name, "Profile name"),
        nonEmpty(content.targetRole[locale], "Target role"),
        SITE_ORIGIN + profilePath(locale),
        summary.slice(0, 40),
        ...headings,
      ]);
    });

    test("S0-53: agrees with the public profile and print experience text", async ({ request, page }) => {
      const bytes = await fetchPdf(request, locale);
      const pdf = await readPdf(bytes);

      await mockStatus(page, { status: "ok" });
      const response = await page.goto(profilePath(locale), { waitUntil: "load" });
      expect(response?.status()).toBe(200);
      await waitForStatus(page);

      await expect(page.locator("h1")).toHaveCount(1);
      await expect(page.locator(".role")).toHaveCount(1);
      await expect(page.locator(".lead")).toHaveCount(1);

      for (const [selector, label] of [
        ["h1", "Public profile name"],
        [".role", "Public profile role"],
        [".lead", "Public profile summary"],
      ] as const) {
        expectPdfContains(pdf.text, await page.locator(selector).innerText(), label);
      }

      const rows = page.locator('section[aria-labelledby="experience"] dl.exp > dd');
      const rowCount = await rows.count();
      expect(rowCount, "The public profile must have experience rows").toBeGreaterThan(0);
      await expect(page.locator('section[aria-labelledby="experience"] dl.exp > dt')).toHaveCount(rowCount);

      for (let index = 0; index < rowCount; index += 1) {
        const row = rows.nth(index);
        await expect(row.locator("h3")).toHaveCount(1);
        await expect(row.locator(".prose")).toHaveCount(1);

        const organization = row.locator(":scope > span").first();
        await expect(organization).toHaveCount(1);

        const period = await row.evaluate((element) => {
          const previous = element.previousElementSibling;
          if (!(previous instanceof HTMLElement) || previous.tagName !== "DT") {
            throw new Error("An experience dd must immediately follow its period dt.");
          }
          return previous.innerText;
        });

        expectPdfContains(
          pdf.text,
          await row.locator("h3").innerText(),
          `Public experience ${index + 1} title`,
        );
        expectPdfContains(
          pdf.text,
          await organization.innerText(),
          `Public experience ${index + 1} organization`,
        );
        expectPdfContains(pdf.text, period, `Public experience ${index + 1} period`);
        expectPdfContains(
          pdf.text,
          await row.locator(".prose").innerText(),
          `Public experience ${index + 1} description`,
        );
      }

      await openPrint(page, locale);
      const printRows = page.locator(".cv-item");
      await expect(printRows).toHaveCount(rowCount);

      for (let index = 0; index < rowCount; index += 1) {
        const row = printRows.nth(index);
        for (const [selector, label] of [
          ["h3", "title"],
          [".cv-item-meta", "metadata"],
          [".cv-prose", "description"],
        ] as const) {
          await expect(row.locator(selector)).toHaveCount(1);
          expectPdfContains(
            pdf.text,
            await row.locator(selector).innerText(),
            `Print experience ${index + 1} ${label}`,
          );
        }
      }
    });

    test("excludes availability, status, theme and skip-link UI", async ({ request }) => {
      const bytes = await fetchPdf(request, locale);
      const pdf = await readPdf(bytes);
      nonEmpty(pdf.text, "Extracted PDF text");

      const content = profile();
      if (content.availability !== undefined) {
        const availability = nonEmpty(content.availability[locale], "Availability");
        expect(pdf.text, "Availability must not be printed").not.toContain(availability);
      }

      const t = ui(locale);
      for (const key of [
        "status.neutral",
        "status.up",
        "status.down",
        "theme.toggle",
        "nav.skipLink",
      ]) {
        const label = nonEmpty(t(key), key);
        expect(pdf.text, `PDF must not contain ${key}: ${JSON.stringify(label)}`).not.toContain(label);
      }
    });

    test("includes every printed link target as extractable URL text", async ({ request, page }) => {
      const bytes = await fetchPdf(request, locale);
      const pdf = await readPdf(bytes);
      await openPrint(page, locale);

      const links = await page.locator("main a[href]").evaluateAll((elements) =>
        elements.map((element) => ({
          href: element.getAttribute("href") ?? "",
          text: element.textContent ?? "",
        })),
      );
      expect(links.length, "Print page must contain link targets to inspect").toBeGreaterThan(0);

      for (const link of links) {
        const href = nonEmpty(link.href, "Printed link target");
        expect(nonEmpty(link.text, "Printed link text")).toBe(href);
        expectPdfContains(pdf.text, href, "Printed URL");
      }
    });
  });
}

test("S0-52: preserves all expected Turkish characters without replacement or private-use glyphs", async ({ request }) => {
  const content = profile();
  const expected = [
    nonEmpty(content.name, "Turkish profile name"),
    nonEmpty(content.targetRole.tr, "Turkish target role"),
    nonEmpty(content.location.tr, "Turkish location"),
    nonEmpty(content.summary.tr, "Turkish summary"),
  ];
  const combined = expected.join(" ");

  for (const character of ["ğ", "ş", "ı", "İ", "ö", "ü", "ç"]) {
    expect(
      combined,
      `Sample expectations must exercise Turkish character ${character}`,
    ).toContain(character);
  }

  const bytes = await fetchPdf(request, "tr");
  const pdf = await readPdf(bytes);

  for (const text of expected) {
    expectPdfContains(pdf.text, text, "Expected Turkish content");
  }

  expect(pdf.text).not.toContain("\uFFFD");
  expect(pdf.text).not.toMatch(/[\uE000-\uF8FF]/u);
});

test("the localized PDFs differ and contain their respective target roles", async ({ request }) => {
  const turkishBytes = await fetchPdf(request, "tr");
  const englishBytes = await fetchPdf(request, "en");

  expect(
    Buffer.from(turkishBytes).equals(Buffer.from(englishBytes)),
    "Turkish and English PDFs must not be byte-identical",
  ).toBe(false);

  const turkish = await readPdf(turkishBytes);
  const english = await readPdf(englishBytes);
  const content = profile();

  const turkishRole = nonEmpty(content.targetRole.tr, "Turkish target role");
  const englishRole = nonEmpty(content.targetRole.en, "English target role");
  expect(turkishRole, "Sample roles must exercise localization").not.toBe(englishRole);

  expectPdfContains(turkish.text, turkishRole, "Turkish target role");
  expectPdfContains(english.text, englishRole, "English target role");
});
