import { fileURLToPath } from "node:url";
import { defineConfig } from "astro/config";

const variable = "VITRIN_SITE_ORIGIN";
const value = process.env[variable] ?? "http://localhost:4173";
let site;

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
  site = url.origin;
} catch {
  throw new Error(`${variable} must be an HTTP or HTTPS origin: ${value}`);
}

export default defineConfig({
  site,
  output: "static",
  trailingSlash: "always",
  build: {
    format: "preserve",
    inlineStylesheets: "never",
  },
  i18n: {
    locales: ["tr", "en"],
    defaultLocale: "tr",
    routing: {
      prefixDefaultLocale: false,
    },
  },
  vite: {
    define: {
      // Bundled build code cannot find the source tree through import.meta.url.
      __VITRIN_SITE_DIR__: JSON.stringify(fileURLToPath(new URL(".", import.meta.url))),
    },
  },
  devToolbar: {
    enabled: false,
  },
});
