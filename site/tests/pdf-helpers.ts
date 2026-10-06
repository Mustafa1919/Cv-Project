import { expect } from "@playwright/test";
import { getDocument } from "pdfjs-dist/legacy/build/pdf.mjs";

export interface PdfText {
  readonly pages: number;
  readonly text: string;
  readonly info: Readonly<Record<string, unknown>>;
}

export function normalizeText(value: string): string {
  return value.normalize("NFC").replace(/\s+/gu, " ").trim();
}

export async function readPdf(bytes: Uint8Array): Promise<PdfText> {
  // pdf.js transfers its input buffer; never give it the caller's buffer.
  const task = getDocument({ data: new Uint8Array(bytes) });

  try {
    const document = await task.promise;
    const pageTexts: string[] = [];

    for (let pageNumber = 1; pageNumber <= document.numPages; pageNumber += 1) {
      const page = await document.getPage(pageNumber);
      const content = await page.getTextContent();
      let text = "";

      for (const item of content.items) {
        if ("str" in item) {
          text += item.str;
          if (item.hasEOL) {
            text += " ";
          }
        }
      }

      pageTexts.push(text);
    }

    const metadata = await document.getMetadata();
    if (
      metadata.info === null ||
      typeof metadata.info !== "object" ||
      Array.isArray(metadata.info)
    ) {
      throw new Error("PDF metadata info must be an object.");
    }

    return {
      pages: document.numPages,
      text: normalizeText(pageTexts.join(" ")),
      info: { ...(metadata.info as Record<string, unknown>) },
    };
  } finally {
    await task.destroy();
  }
}

export function expectInOrder(
  haystack: string,
  needles: readonly string[],
): void {
  const text = normalizeText(haystack);
  expect(text, "The extracted PDF text must not be empty").not.toBe("");
  expect(needles.length, "The ordered expectations must not be empty").toBeGreaterThan(0);

  let cursor = 0;

  for (const rawNeedle of needles) {
    const needle = normalizeText(rawNeedle);
    expect(
      needle,
      `The ordered needle ${JSON.stringify(rawNeedle)} must not be empty`,
    ).not.toBe("");

    const index = text.indexOf(needle, cursor);
    expect(
      index,
      `Missing or misplaced needle ${JSON.stringify(needle)} after offset ${cursor}`,
    ).toBeGreaterThanOrEqual(cursor);

    cursor = index + needle.length;
  }
}
