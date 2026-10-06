import { createHash } from "node:crypto";
import { readdir, readFile, writeFile } from "node:fs/promises";
import { join, relative } from "node:path";
import { fileURLToPath } from "node:url";

const distDir = fileURLToPath(new URL("../dist/", import.meta.url));

interface Attribute {
  readonly name: string;
  readonly value: string;
}

function apiOrigin(): string {
  const variable = "PUBLIC_VITRIN_API_ORIGIN";
  const value = process.env[variable] ?? "http://localhost:8080";

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

async function htmlFiles(directory: string): Promise<string[]> {
  const files: string[] = [];
  const entries = await readdir(directory, { withFileTypes: true });
  entries.sort((left, right) => left.name.localeCompare(right.name, "en"));

  for (const entry of entries) {
    const path = join(directory, entry.name);
    if (entry.isDirectory()) {
      files.push(...await htmlFiles(path));
    } else if (entry.isFile() && entry.name.endsWith(".html")) {
      files.push(path);
    }
  }
  return files;
}

function attributes(source: string): Attribute[] {
  const result: Attribute[] = [];
  const pattern = /([^\s=/>]+)(?:\s*=\s*(?:"([^"]*)"|'([^']*)'|([^\s"'=<>`]+)))?/g;

  for (const match of source.matchAll(pattern)) {
    const name = match[1];
    if (name !== undefined) {
      result.push({
        name: name.toLowerCase(),
        value: match[2] ?? match[3] ?? match[4] ?? "",
      });
    }
  }
  return result;
}

function decodeAttribute(value: string): string {
  const named: Readonly<Record<string, string>> = {
    amp: "&",
    colon: ":",
    Tab: "\t",
    NewLine: "\n",
    quot: '"',
    apos: "'",
    lt: "<",
    gt: ">",
  };

  return value.replace(
    /&#(?:x([0-9a-f]+)|([0-9]+));?|&([a-z]+);/gi,
    (match: string, hex: string | undefined, decimal: string | undefined, name: string | undefined) => {
      if (hex !== undefined || decimal !== undefined) {
        const code = hex !== undefined
          ? Number.parseInt(hex, 16)
          : Number.parseInt(decimal ?? "", 10);
        return code > 0 && code <= 0x10ffff
          ? String.fromCodePoint(code)
          : "\uFFFD";
      }
      return name === undefined ? match : named[name] ?? match;
    },
  );
}

function inspectHtml(
  html: string,
  filename: string,
  hashes: Set<string>,
): void {
  // Assumption: Astro emits ordinary HTML with quoted or unquoted attributes.
  // Comments and raw-text elements are skipped, so script strings are not tags.
  const tags = /<!--[\s\S]*?-->|<![^>]*>|<\/?[a-z][a-z0-9:-]*(?:\s+(?:"[^"]*"|'[^']*'|[^'">])*)?\s*\/?>/gi;
  let match: RegExpExecArray | null;

  function fail(message: string): never {
    throw new Error(`${filename}: ${message}`);
  }

  while ((match = tags.exec(html)) !== null) {
    const tag = match[0];
    if (tag.startsWith("<!--") || tag.startsWith("<!") || tag.startsWith("</")) {
      continue;
    }

    const opening = /^<([a-z][a-z0-9:-]*)/i.exec(tag);
    const tagName = opening?.[1]?.toLowerCase();
    if (tagName === undefined || opening === null) {
      continue;
    }

    const attrs = attributes(
      tag.slice(opening[0].length).replace(/\/?>$/, ""),
    );

    if (tagName === "style") {
      fail("A <style> element is forbidden.");
    }

    for (const attribute of attrs) {
      if (attribute.name === "style") {
        fail("An inline style attribute is forbidden.");
      }
      if (/^on/i.test(attribute.name)) {
        fail(`An inline event handler is forbidden: ${attribute.name}`);
      }
      const value = decodeAttribute(attribute.value)
        .replace(/[\u0000-\u0020\u007f]/g, "");
      if (/^javascript:/i.test(value)) {
        fail(`A javascript: URL is forbidden: ${attribute.name}`);
      }
    }

    if (
      tagName === "script" ||
      tagName === "textarea" ||
      tagName === "title"
    ) {
      const closing = new RegExp(`</${tagName}\\s*>`, "gi");
      closing.lastIndex = tags.lastIndex;
      const end = closing.exec(html);
      if (end === null) {
        fail(`An unclosed <${tagName}> element was found.`);
      }

      if (
        tagName === "script" &&
        !attrs.some((attribute) => attribute.name === "src")
      ) {
        const script = html.slice(tags.lastIndex, end.index);
        const digest = createHash("sha256")
          .update(script, "utf8")
          .digest("base64");
        hashes.add(`'sha256-${digest}'`);
      }

      tags.lastIndex = closing.lastIndex;
    }
  }
}

async function main(): Promise<void> {
  const origin = apiOrigin();
  const hashes = new Set<string>();
  const files = await htmlFiles(distDir);

  for (const file of files) {
    inspectHtml(
      await readFile(file, "utf8"),
      relative(distDir, file),
      hashes,
    );
  }

  const scriptSources = ["'self'", ...Array.from(hashes).sort()].join(" ");
  const csp = [
    "default-src 'none'",
    `script-src ${scriptSources}`,
    "style-src 'self'",
    "font-src 'self'",
    "img-src 'self'",
    `connect-src ${origin}`,
    "manifest-src 'self'",
    "base-uri 'none'",
    "form-action 'none'",
    "frame-ancestors 'none'",
  ].join("; ");

  const headers = [
    "/*",
    `  Content-Security-Policy: ${csp}`,
    "  Strict-Transport-Security: max-age=31536000; includeSubDomains",
    "  X-Content-Type-Options: nosniff",
    "  Referrer-Policy: strict-origin-when-cross-origin",
    "  Permissions-Policy: accelerometer=(), camera=(), geolocation=(), gyroscope=(), magnetometer=(), microphone=(), payment=(), usb=()",
    "  X-Frame-Options: DENY",
    "  Cross-Origin-Opener-Policy: same-origin",
    "",
    "/_astro/*",
    "  Cache-Control: public, max-age=31536000, immutable",
    "",
    // The print pages only exist to be turned into the PDF files.
    "/cv/",
    "  X-Robots-Tag: noindex",
    "",
    "/en/cv/",
    "  X-Robots-Tag: noindex",
    "",
  ].join("\n");

  await writeFile(join(distDir, "_headers"), headers, "utf8");
}

main().catch((error: unknown) => {
  console.error(error instanceof Error ? error.message : String(error));
  process.exitCode = 1;
});
