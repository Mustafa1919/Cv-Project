import type { ParsedFile } from "./load.ts";
import type { UiDictionary } from "./generated/ui.ts";
import { compareText } from "./issue.ts";
import { flattenUi } from "./ui.ts";
import { isRecord, pointerSegment } from "./value.ts";

export interface Unit {
  readonly key: string;
  readonly file: string;
  readonly tr: string;
  readonly en: string;
}

export function collectUnits(
  files: readonly ParsedFile[],
  uiTr: UiDictionary,
  uiEn: UiDictionary
): Unit[] {
  const units: Unit[] = [];

  function walk(file: string, value: unknown, path: string): void {
    if (Array.isArray(value)) {
      const entries: readonly unknown[] = value;
      entries.forEach((entry, index) => {
        const segment = isRecord(entry) && typeof entry.id === "string"
          ? entry.id
          : String(index);
        walk(file, entry, `${path}/${pointerSegment(segment)}`);
      });
      return;
    }

    if (!isRecord(value)) {
      return;
    }

    const keys = Object.keys(value);
    if (
      keys.length === 2 &&
      Object.hasOwn(value, "tr") &&
      Object.hasOwn(value, "en") &&
      typeof value.tr === "string" &&
      typeof value.en === "string"
    ) {
      units.push({
        key: `${file}#${path}`,
        file,
        tr: value.tr,
        en: value.en
      });
      return;
    }

    for (const [name, child] of Object.entries(value)) {
      walk(file, child, `${path}/${pointerSegment(name)}`);
    }
  }

  for (const entry of files) {
    if (!entry.file.startsWith("ui/")) {
      walk(entry.file, entry.value, "");
    }
  }

  const trLeaves = flattenUi(uiTr);
  const enLeaves = flattenUi(uiEn);
  for (const [path, tr] of trLeaves) {
    const en = enLeaves.get(path);
    if (en !== undefined) {
      units.push({ key: `ui#/${path}`, file: "ui/tr.yaml", tr, en });
    }
  }

  return units.sort((left, right) => compareText(left.key, right.key));
}
