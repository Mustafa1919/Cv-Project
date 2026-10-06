import { readFileSync } from "node:fs";
import { fileURLToPath } from "node:url";
import { parse } from "yaml";
import { expect } from "@playwright/test";
import type { Page as BrowserPage } from "@playwright/test";
import type { Profile } from "vitrin-content-tool/src/generated/profile.ts";
import type { SkillClaims } from "vitrin-content-tool/src/generated/skills.ts";
import type { EvidenceList } from "vitrin-content-tool/src/generated/evidence.ts";
import type { ProjectList } from "vitrin-content-tool/src/generated/projects.ts";
import type { UiDictionary } from "vitrin-content-tool/src/generated/ui.ts";
import type { Locale } from "../src/lib/i18n.ts";
import { pagePath } from "../src/lib/routes.ts";
import type { Page } from "../src/lib/routes.ts";

export const SITE_ORIGIN = "http://127.0.0.1:4173";
export const API_ORIGIN = "http://127.0.0.1:4174";

function sample<T>(path: string): T {
  const filename = fileURLToPath(new URL(`../sample-content/${path}`, import.meta.url));
  const value: unknown = parse(readFileSync(filename, "utf8"), {
    version: "1.2",
    schema: "core",
    uniqueKeys: true,
    strict: true,
  });
  // The runner builds and validates these files before starting the tests.
  return value as T;
}

const owner = sample<Profile>("profile.yaml");
export const skills = sample<SkillClaims>("skills.yaml");
export const evidence = sample<EvidenceList>("evidence.yaml");
export const projects = sample<ProjectList>("projects.yaml");
const dictionaries: Record<Locale, UiDictionary> = {
  tr: sample<UiDictionary>("ui/tr.yaml"),
  en: sample<UiDictionary>("ui/en.yaml"),
};

export function profile(): Profile {
  return owner;
}

export function ui(locale: Locale): (key: string) => string {
  return (key) => {
    let current: string | UiDictionary = dictionaries[locale];
    for (const part of key.split(".")) {
      if (
        typeof current === "string" ||
        !Object.prototype.hasOwnProperty.call(current, part)
      ) {
        throw new Error(`Missing sample UI key: ${locale}:${key}`);
      }
      const next: string | UiDictionary | undefined = current[part];
      if (next === undefined) {
        throw new Error(`Missing sample UI key: ${locale}:${key}`);
      }
      current = next;
    }
    if (typeof current !== "string") {
      throw new Error(`Sample UI key is not a string: ${locale}:${key}`);
    }
    return current;
  };
}

export interface TestPage {
  readonly path: string;
  readonly kind: Page["kind"];
  readonly locale: Locale;
  readonly page: Page;
}

const routePages: readonly Page[] = [
  { kind: "profile" },
  { kind: "projects" },
  ...projects.items.map(
    (project): Page => ({ kind: "project", id: project.id }),
  ),
  { kind: "privacy" },
  { kind: "notFound" },
];

export const LOCALES: readonly Locale[] = ["tr", "en"];
export const PAGES: readonly TestPage[] = LOCALES.flatMap((locale) =>
  routePages.map((page) => ({
    path: pagePath(page, locale),
    kind: page.kind,
    locale,
    page,
  })),
);
export const PUBLIC_PAGES = PAGES.filter((page) => page.kind !== "notFound");

export const PROFILE_SECTION_KEYS = [
  "profile.strongestEvidence",
  "profile.skills",
  "profile.experience",
  "profile.projects",
  "profile.education",
  "profile.certificates",
] as const;

export async function expectProfileContent(
  page: BrowserPage,
  locale: Locale,
): Promise<void> {
  await expect(page.locator("h1")).toHaveText(owner.name);
  await expect(page.locator(".role")).toHaveText(owner.targetRole[locale]);
  await expect(page.locator(".role")).toBeVisible();
  await expect(page.locator(".lead")).toHaveText(owner.summary[locale]);
  await expect(page.locator(".lead")).toBeVisible();
  await expect(page.locator("main > section > h2")).toHaveText(
    PROFILE_SECTION_KEYS.map(ui(locale)),
  );
  for (const heading of await page.locator("main > section > h2").all()) {
    await expect(heading).toBeVisible();
  }
}

type StatusMock =
  | "fail"
  | { readonly status: number }
  | { readonly status: string; readonly [key: string]: unknown };

export async function mockStatus(
  page: BrowserPage,
  body: StatusMock,
): Promise<void> {
  await page.route("**/v1/public/status", async (route) => {
    if (body === "fail") {
      await route.abort("failed");
      return;
    }
    const httpStatus = typeof body.status === "number" ? body.status : 200;
    await route.fulfill({
      status: httpStatus,
      contentType: "application/json",
      headers: { "access-control-allow-origin": "*" },
      body: JSON.stringify(
        typeof body.status === "number" ? { status: "ok" } : body,
      ),
    });
  });
}

export async function waitForStatus(page: BrowserPage): Promise<void> {
  await expect(page.locator("[data-status-badge]")).not.toHaveAttribute(
    "data-state",
    "unknown",
    { timeout: 6000 },
  );
}

function originOf(url: string): string | undefined {
  try {
    return new URL(url).origin;
  } catch {
    return undefined;
  }
}

export function collectProblems(page: BrowserPage): string[] {
  const problems: string[] = [];

  page.on("console", (message) => {
    if (message.type() !== "error") {
      return;
    }
    const failedApiRequest =
      originOf(message.location().url) === API_ORIGIN &&
      /Failed to load resource|net::ERR_/i.test(message.text());
    if (!failedApiRequest) {
      problems.push(`console: ${message.text()}`);
    }
  });
  page.on("pageerror", (error) => {
    problems.push(`pageerror: ${error.message}`);
  });
  page.on("requestfailed", (request) => {
    const origin = originOf(request.url());
    if (origin === API_ORIGIN) {
      return;
    }
    if (origin === SITE_ORIGIN) {
      problems.push(
        `requestfailed: ${request.url()} ${request.failure()?.errorText ?? ""}`,
      );
    }
  });

  return problems;
}

declare global {
  interface Window {
    __vitrinCspViolations?: string[];
  }
}
