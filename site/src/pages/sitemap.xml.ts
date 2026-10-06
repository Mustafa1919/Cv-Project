import type { APIRoute } from "astro";
import { siteConfig } from "../lib/config.ts";
import { loadContent } from "../lib/content.ts";
import type { Locale } from "../lib/i18n.ts";
import { pagePath } from "../lib/routes.ts";
import type { Page } from "../lib/routes.ts";

function escapeXml(value: string): string {
  return value
    .replace(/&/g, "&amp;")
    .replace(/</g, "&lt;")
    .replace(/>/g, "&gt;")
    .replace(/"/g, "&quot;")
    .replace(/'/g, "&apos;");
}

export const GET: APIRoute = () => {
  const content = loadContent();
  const pages: Page[] = [
    { kind: "profile" },
    { kind: "projects" },
    ...content.projects.items.map(
      (project): Page => ({ kind: "project", id: project.id }),
    ),
    { kind: "privacy" },
  ];
  const locales: readonly Locale[] = ["tr", "en"];

  function absolutePath(page: Page, locale: Locale): string {
    return escapeXml(siteConfig.siteOrigin + pagePath(page, locale));
  }

  const urls = pages.flatMap((page) =>
    locales.map((locale) => {
      const alternatives = locales.map(
        (language) =>
          `    <xhtml:link rel="alternate" hreflang="${language}" href="${absolutePath(page, language)}" />`,
      );
      alternatives.push(
        `    <xhtml:link rel="alternate" hreflang="x-default" href="${absolutePath(page, "tr")}" />`,
      );
      return [
        "  <url>",
        `    <loc>${absolutePath(page, locale)}</loc>`,
        ...alternatives,
        "  </url>",
      ].join("\n");
    }),
  );

  const xml = [
    '<?xml version="1.0" encoding="UTF-8"?>',
    '<urlset xmlns="http://www.sitemaps.org/schemas/sitemap/0.9" xmlns:xhtml="http://www.w3.org/1999/xhtml">',
    ...urls,
    "</urlset>",
    "",
  ].join("\n");

  return new Response(xml, {
    headers: {
      "Content-Type": "application/xml; charset=utf-8",
    },
  });
};
