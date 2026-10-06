import { PDFDict, PDFDocument, PDFName } from "@cantoo/pdf-lib";

export interface PdfFont {
  readonly subtype: string;
  readonly baseFont: string | undefined;
}

export interface PdfDescriptor {
  readonly fontName: string | undefined;
  readonly embedded: boolean;
}

export interface PdfFonts {
  readonly fonts: readonly PdfFont[];
  readonly descriptors: readonly PdfDescriptor[];
}

function normalizedName(
  dictionary: PDFDict,
  key: string,
): string | undefined {
  const value = dictionary.get(PDFName.of(key));
  if (value === undefined) {
    return undefined;
  }

  return String(value).replace(/^\//, "").replace(/^[A-Z]{6}\+/, "");
}

export async function inspectFonts(bytes: Uint8Array): Promise<PdfFonts> {
  const document = await PDFDocument.load(bytes, { updateMetadata: false });
  const fonts: PdfFont[] = [];
  const descriptors: PdfDescriptor[] = [];

  for (const [, object] of document.context.enumerateIndirectObjects()) {
    if (!(object instanceof PDFDict)) {
      continue;
    }

    const type = String(object.get(PDFName.of("Type")));

    if (type === "/Font") {
      const subtype = normalizedName(object, "Subtype");
      if (subtype === undefined || subtype.length === 0) {
        throw new Error("PDF inspection: font dictionary has no Subtype.");
      }

      fonts.push({
        subtype,
        baseFont: normalizedName(object, "BaseFont"),
      });
    }

    if (type === "/FontDescriptor") {
      descriptors.push({
        fontName: normalizedName(object, "FontName"),
        embedded: ["FontFile", "FontFile2", "FontFile3"].some((key) =>
          object.has(PDFName.of(key)),
        ),
      });
    }
  }

  return { fonts, descriptors };
}
