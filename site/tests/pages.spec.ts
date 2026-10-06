import { expect, test } from "@playwright/test";
import {
  LOCALES,
  PAGES,
  evidence,
  expectProfileContent,
  mockStatus,
  profile,
  skills,
  ui,
} from "./helpers.ts";
import { notFoundPath, profilePath, projectPath } from "../src/lib/routes.ts";

for (const entry of PAGES) {
  test(`page structure ${entry.path}`, async ({ page }) => {
    await mockStatus(page, { status: "ok" });
    const response = await page.goto(entry.path);
    expect(response?.status()).toBe(200);
    await expect(page.locator("html")).toHaveAttribute("lang", entry.locale);
    await expect(page.locator("h1")).toHaveCount(1);
    await expect(page.locator("main#main")).toHaveCount(1);
    await expect(page).toHaveTitle(new RegExp(
      profile().name.replace(/[.*+?^${}()|[\]\\]/g, "\\$&"),
    ));
    expect((await page.title()).trim()).not.toBe("");
  });
}

for (const locale of LOCALES) {
  test(`profile content ${locale}`, async ({ page }) => {
    await mockStatus(page, { status: "ok" });
    await page.goto(profilePath(locale));
    await expectProfileContent(page, locale);
    await expect(page.locator("ol.proofs > li")).toHaveCount(profile().highlights.length);
    const rows = page.locator("#skills + .note + .table-wrap tbody tr");
    await expect(rows).toHaveCount(skills.items.length);

    for (const [index, skill] of skills.items.entries()) {
      const row = rows.nth(index);
      await expect(row.locator("td").nth(0)).toHaveText(skill.name);
      expect(skill.evidence.length).toBeGreaterThan(0);
      const titles = skill.evidence.map((id) => {
        const item = evidence.items.find((candidate) => candidate.id === id);
        if (item === undefined) {
          throw new Error(`Missing sample evidence: ${id}`);
        }
        return item.title[locale];
      });
      await expect(row.locator("td").nth(2)).toHaveText(titles.join(", "));
      for (const id of skill.evidence) {
        const item = evidence.items.find((candidate) => candidate.id === id);
        if (item?.type === "decision-record" || item?.type === "measurement-report") {
          await expect(row.locator("a").filter({ hasText: item.title[locale] })).toHaveCount(0);
        }
      }
    }
  });

  test(`case study content ${locale}`, async ({ page }) => {
    await mockStatus(page, { status: "ok" });
    await page.goto(projectPath(locale, "queue-service"));
    const study = page.locator('[id="queue-rewrite"]');
    await expect(study).toHaveCount(1);
    const t = ui(locale);
    await expect(study.locator("h3")).toHaveText([
      t("caseStudy.problem"),
      t("caseStudy.decision"),
      t("caseStudy.cost"),
      t("caseStudy.result"),
      t("caseStudy.metrics"),
    ]);
    await expect(study.locator("dl.metrics > div")).toHaveCount(2);
    await expect(study.locator(".prose")).toHaveCount(4);
  });
}

test("project without a case study", async ({ page }) => {
  await mockStatus(page, { status: "ok" });
  await page.goto(projectPath("tr", "content-workbench"));
  await expect(page.locator("main .note")).toHaveText(ui("tr")("caseStudy.noCaseStudy"));
});

for (const locale of LOCALES) {
  test(`localized unknown URL ${locale}`, async ({ page }) => {
    await mockStatus(page, { status: "ok" });
    const missingPath = locale === "tr"
      ? "/missing-e2e-page/"
      : "/en/missing-e2e-page/";
    const response = await page.goto(missingPath);
    expect(response?.status()).toBe(404);
    await expect(page.locator("h1")).toHaveText(ui(locale)("notFound.title"));
    await expect(page.locator("html")).toHaveAttribute("lang", locale);
    expect(new URL(page.url()).pathname).not.toBe(notFoundPath(locale));
  });
}

test("directory route redirects to its trailing slash", async ({ request }) => {
  const response = await request.get("/projeler", { maxRedirects: 0 });
  expect(response.status()).toBe(308);
  expect(response.headers()["location"]).toBe("/projeler/");
});
