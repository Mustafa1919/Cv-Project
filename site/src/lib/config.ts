import { resolve } from "node:path";

export interface SiteConfig {
  readonly contentDir: string;
  readonly schemaDir: string;
  readonly siteOrigin: string;
  readonly apiOrigin: string;
  readonly noindex: boolean;
}

function readOrigin(variable: string, fallback: string): string {
  const value = process.env[variable] ?? fallback;

  try {
    const url = new URL(value);
    if (
      !["http:", "https:"].includes(url.protocol) ||
      url.username ||
      url.password ||
      url.pathname !== "/" ||
      url.search ||
      url.hash
    ) {
      throw new Error("Expected an HTTP or HTTPS origin.");
    }
    return url.origin;
  } catch {
    throw new Error(`${variable} must be an HTTP or HTTPS origin: ${value}`);
  }
}

// Injected by astro.config.mjs; import.meta.url points into the build output here.
const siteDir = __VITRIN_SITE_DIR__;

export const siteConfig: SiteConfig = Object.freeze({
  contentDir: resolve(
    siteDir,
    process.env["VITRIN_CONTENT_DIR"] ?? "sample-content",
  ),
  schemaDir: resolve(siteDir, "../content/schema"),
  siteOrigin: readOrigin("VITRIN_SITE_ORIGIN", "http://localhost:4173"),
  apiOrigin: readOrigin("PUBLIC_VITRIN_API_ORIGIN", "http://localhost:8080"),
  noindex: process.env["VITRIN_NOINDEX"] === "1",
});
