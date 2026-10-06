import type { SiteContent } from "./content.ts";
import type { Locale, UiKey } from "./i18n.ts";
import { profilePath, projectPath } from "./routes.ts";

type Evidence = SiteContent["evidence"]["items"][number];

export function evidenceLink(
  evidence: Evidence,
  locale: Locale,
  content: SiteContent,
): { href: string; external: boolean } | null {
  switch (evidence.type) {
    case "project":
      return {
        href: projectPath(locale, evidence.ref),
        external: false,
      };
    case "case-study": {
      const study = content.caseStudiesById.get(evidence.ref);
      if (study === undefined) {
        throw new Error(`Missing case study: ${evidence.ref}`);
      }
      return {
        href: `${projectPath(locale, study.project)}#${encodeURIComponent(study.id)}`,
        external: false,
      };
    }
    case "certificate": {
      const certificate = content.certificates.items.find(
        (item) => item.id === evidence.ref,
      );
      if (certificate === undefined) {
        throw new Error(`Missing certificate: ${evidence.ref}`);
      }
      return certificate.url === undefined
        ? {
            href: `${profilePath(locale)}#certificates`,
            external: false,
          }
        : {
            href: certificate.url,
            external: true,
          };
    }
    case "repository":
      return { href: evidence.url, external: true };
    case "decision-record":
    case "measurement-report":
      return null;
  }
}

export function evidenceTypeKey(type: Evidence["type"]): UiKey {
  switch (type) {
    case "project":
      return "evidence.project";
    case "case-study":
      return "evidence.caseStudy";
    case "certificate":
      return "evidence.certificate";
    case "decision-record":
      return "evidence.decisionRecord";
    case "measurement-report":
      return "evidence.measurementReport";
    case "repository":
      return "evidence.repository";
  }
}
