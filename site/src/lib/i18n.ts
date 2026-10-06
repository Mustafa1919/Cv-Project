import type { Profile } from "vitrin-content-tool/src/generated/profile.ts";
import type { UiDictionary } from "vitrin-content-tool/src/generated/ui.ts";
import { loadContent } from "./content.ts";

export type Locale = "tr" | "en";

export const UI_KEYS = [
  "site.title",
  "site.descriptionProfile",
  "site.descriptionProjects",
  "site.descriptionProject",
  "site.descriptionPrivacy",
  "site.descriptionNotFound",
  "nav.profile",
  "nav.projects",
  "nav.privacy",
  "nav.mainLabel",
  "nav.skipLink",
  "language.switchLabel",
  "language.tr",
  "language.en",
  "theme.toggle",
  "theme.light",
  "theme.dark",
  "actions.pdf",
  "profile.strongestEvidence",
  "profile.skills",
  "profile.experience",
  "profile.projects",
  "profile.education",
  "profile.certificates",
  "profile.skillsNote",
  "table.skill",
  "table.years",
  "table.evidence",
  "evidence.project",
  "evidence.caseStudy",
  "evidence.certificate",
  "evidence.decisionRecord",
  "evidence.measurementReport",
  "evidence.repository",
  "dates.present",
  "certificates.inProgress",
  "projects.heading",
  "projects.intro",
  "caseStudy.problem",
  "caseStudy.decision",
  "caseStudy.cost",
  "caseStudy.result",
  "caseStudy.metrics",
  "caseStudy.source",
  "caseStudy.measurementReport",
  "caseStudy.repository",
  "caseStudy.backToProjects",
  "caseStudy.noCaseStudy",
  "status.neutral",
  "status.up",
  "status.down",
  "footer.staticNotice",
  "privacy.title",
  "privacy.noCookies",
  "privacy.liveCookies",
  "privacy.rateLimiting",
  "privacy.localAssets",
  "privacy.statusRequest",
  "notFound.title",
  "notFound.text",
  "notFound.back",
  "print.title",
  "print.description",
  "print.site",
  "print.years",
] as const;

export type UiKey = (typeof UI_KEYS)[number];

function findLeaf(dictionary: UiDictionary, key: string): string | undefined {
  let value: string | UiDictionary = dictionary;

  for (const segment of key.split(".")) {
    if (
      typeof value === "string" ||
      !Object.prototype.hasOwnProperty.call(value, segment)
    ) {
      return undefined;
    }

    const next: string | UiDictionary | undefined = value[segment];
    if (next === undefined) {
      return undefined;
    }
    value = next;
  }

  return typeof value === "string" ? value : undefined;
}

const dictionaries = loadContent().ui;
const translations: Record<Locale, Map<UiKey, string>> = {
  tr: new Map(),
  en: new Map(),
};
const missingKeys: string[] = [];

for (const locale of ["tr", "en"] as const) {
  for (const key of UI_KEYS) {
    const value = findLeaf(dictionaries[locale], key);
    if (value === undefined) {
      missingKeys.push(`${locale}:${key}`);
    } else {
      translations[locale].set(key, value);
    }
  }
}

if (missingKeys.length > 0) {
  throw new Error(`Missing UI string leaves:\n${missingKeys.join("\n")}`);
}

export function translator(locale: Locale) {
  return function t(
    key: UiKey,
    params?: Record<string, string | number>,
  ): string {
    const template = translations[locale].get(key);
    if (template === undefined) {
      throw new Error(`Missing UI string leaf: ${locale}:${key}`);
    }

    return template.replace(/\{([a-zA-Z][a-zA-Z0-9]*)\}/g, (match: string, name: string) => {
      if (
        params === undefined ||
        !Object.prototype.hasOwnProperty.call(params, name)
      ) {
        throw new Error(`Missing UI parameter: ${locale}:${key}:${name}`);
      }

      const value = params[name];
      return value === undefined ? match : String(value);
    });
  };
}

export function pick(text: Profile["targetRole"], locale: Locale): string {
  return text[locale];
}
