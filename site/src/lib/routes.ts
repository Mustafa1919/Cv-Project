import type { Locale } from "./i18n.ts";

export type Page =
  | { readonly kind: "profile" }
  | { readonly kind: "projects" }
  | { readonly kind: "project"; readonly id: string }
  | { readonly kind: "privacy" }
  | { readonly kind: "notFound" }
  | { readonly kind: "pdf" };

function idSegment(id: string): string {
  if (
    id.length === 0 ||
    id === "." ||
    id === ".." ||
    /[/\\\u0000-\u001f\u007f]/u.test(id)
  ) {
    throw new Error(`Invalid project route id: ${JSON.stringify(id)}`);
  }
  return encodeURIComponent(id);
}

export function profilePath(locale: Locale): string {
  return locale === "tr" ? "/" : "/en/";
}

export function projectsPath(locale: Locale): string {
  return locale === "tr" ? "/projeler/" : "/en/projects/";
}

export function projectPath(locale: Locale, id: string): string {
  return `${projectsPath(locale)}${idSegment(id)}/`;
}

export function privacyPath(locale: Locale): string {
  return locale === "tr" ? "/gizlilik/" : "/en/privacy/";
}

export function notFoundPath(locale: Locale): string {
  return locale === "tr" ? "/404.html" : "/en/404.html";
}

export function pdfPath(locale: Locale): string {
  return locale === "tr" ? "/cv.pdf" : "/en/cv.pdf";
}

export function pagePath(page: Page, locale: Locale): string {
  switch (page.kind) {
    case "profile":
      return profilePath(locale);
    case "projects":
      return projectsPath(locale);
    case "project":
      return projectPath(locale, page.id);
    case "privacy":
      return privacyPath(locale);
    case "notFound":
      return notFoundPath(locale);
    case "pdf":
      return pdfPath(locale);
  }
}

export function alternate(page: Page, locale: Locale): string {
  return pagePath(page, locale === "tr" ? "en" : "tr");
}
