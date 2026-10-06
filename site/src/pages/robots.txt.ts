import type { APIRoute } from "astro";
import { siteConfig } from "../lib/config.ts";

export const GET: APIRoute = () => {
  const text = siteConfig.noindex
    ? "User-agent: *\nDisallow: /\n"
    : `User-agent: *\nAllow: /\nSitemap: ${siteConfig.siteOrigin}/sitemap.xml\n`;

  return new Response(text, {
    headers: {
      "Content-Type": "text/plain; charset=utf-8",
    },
  });
};
