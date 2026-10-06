import type { UiDictionary } from "./generated/ui.ts";
import type { Issue } from "./issue.ts";
import { compareText, makeIssue } from "./issue.ts";

export function flattenUi(dictionary: UiDictionary): Map<string, string> {
  const leaves = new Map<string, string>();

  function visit(value: UiDictionary, prefix: string): void {
    for (const [name, child] of Object.entries(value)) {
      const path = prefix === "" ? name : `${prefix}.${name}`;
      if (typeof child === "string") {
        leaves.set(path, child);
      } else {
        visit(child, path);
      }
    }
  }

  visit(dictionary, "");
  return leaves;
}

export function checkUiKeys(
  tr: ReadonlyMap<string, string>,
  en: ReadonlyMap<string, string>
): Issue[] {
  const issues: Issue[] = [];
  const keys = [...new Set([...tr.keys(), ...en.keys()])].sort(compareText);
  for (const key of keys) {
    if (!tr.has(key)) {
      issues.push(makeIssue(
        "UI_KEY_MISMATCH",
        "ui/tr.yaml",
        key,
        "Leaf is missing from the Turkish dictionary"
      ));
    } else if (!en.has(key)) {
      issues.push(makeIssue(
        "UI_KEY_MISMATCH",
        "ui/en.yaml",
        key,
        "Leaf is missing from the English dictionary"
      ));
    }
  }
  return issues;
}

function placeholders(text: string): string[] {
  return [...new Set(
    [...text.matchAll(/\{([a-zA-Z][a-zA-Z0-9]*)\}/g)]
      .map((match) => match[1])
      .filter((name): name is string => name !== undefined)
  )].sort(compareText);
}

export function checkUiPlaceholders(
  tr: ReadonlyMap<string, string>,
  en: ReadonlyMap<string, string>
): Issue[] {
  const issues: Issue[] = [];
  for (const [key, trText] of tr) {
    const enText = en.get(key);
    if (enText === undefined) {
      continue;
    }
    const trNames = placeholders(trText);
    const enNames = placeholders(enText);
    if (JSON.stringify(trNames) !== JSON.stringify(enNames)) {
      issues.push(makeIssue(
        "UI_PLACEHOLDER_MISMATCH",
        "ui/en.yaml",
        key,
        `Placeholder sets differ: tr [${trNames.join(", ")}], en [${enNames.join(", ")}]`
      ));
    }
  }
  return issues;
}
