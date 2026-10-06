import { readFileSync, writeFileSync } from "node:fs";
import { join } from "node:path";
import { hashText } from "./hash.ts";
import type { Issue } from "./issue.ts";
import { compareText, makeIssue } from "./issue.ts";
import type { Unit } from "./units.ts";
import { errorMessage, isRecord } from "./value.ts";

export const LOCK_FILE_NAME = "i18n.lock";

export interface LockFile {
  readonly version: 1;
  readonly units: Readonly<Record<string, {
    readonly tr: string;
    readonly en: string;
  }>>;
}

export type LockReadResult =
  | { readonly valid: true; readonly lock: LockFile }
  | { readonly valid: false; readonly issue: Issue };

export type UnitChangeKind = "unchanged" | "added" | "updated" | "one-sided";

export interface UnitChange {
  readonly unit: Unit;
  readonly kind: UnitChangeKind;
  readonly tr: string;
  readonly en: string;
  readonly changedLanguage: "tr" | "en" | null;
}

export interface LockChanges {
  readonly changes: readonly UnitChange[];
  readonly removed: readonly string[];
}

function parseLock(value: unknown): LockFile | undefined {
  if (
    !isRecord(value) ||
    Object.keys(value).length !== 2 ||
    !Object.hasOwn(value, "version") ||
    !Object.hasOwn(value, "units") ||
    value.version !== 1 ||
    !isRecord(value.units)
  ) {
    return undefined;
  }

  const entries: [string, { tr: string; en: string }][] = [];
  for (const [key, hashes] of Object.entries(value.units)) {
    if (
      !isRecord(hashes) ||
      Object.keys(hashes).length !== 2 ||
      !Object.hasOwn(hashes, "tr") ||
      !Object.hasOwn(hashes, "en") ||
      typeof hashes.tr !== "string" ||
      typeof hashes.en !== "string" ||
      !/^[a-f0-9]{64}$/.test(hashes.tr) ||
      !/^[a-f0-9]{64}$/.test(hashes.en)
    ) {
      return undefined;
    }
    entries.push([key, { tr: hashes.tr, en: hashes.en }]);
  }

  return { version: 1, units: Object.fromEntries(entries) };
}

export function readLock(contentDir: string): LockReadResult {
  let text: string;
  try {
    text = readFileSync(join(contentDir, LOCK_FILE_NAME), "utf8");
  } catch (error: unknown) {
    if (isRecord(error) && error.code === "ENOENT") {
      return { valid: true, lock: { version: 1, units: {} } };
    }
    return {
      valid: false,
      issue: makeIssue(
        "LOCK_OUT_OF_DATE",
        LOCK_FILE_NAME,
        "",
        `Cannot read lock file: ${errorMessage(error)}`
      )
    };
  }

  try {
    const value: unknown = JSON.parse(text);
    const lock = parseLock(value);
    if (lock === undefined) {
      return {
        valid: false,
        issue: makeIssue(
          "LOCK_OUT_OF_DATE",
          LOCK_FILE_NAME,
          "",
          "Lock file has an invalid shape or version"
        )
      };
    }
    return { valid: true, lock };
  } catch (error: unknown) {
    return {
      valid: false,
      issue: makeIssue(
        "LOCK_OUT_OF_DATE",
        LOCK_FILE_NAME,
        "",
        `Cannot parse lock file: ${errorMessage(error)}`
      )
    };
  }
}

export function classifyUnits(units: readonly Unit[], lock: LockFile): LockChanges {
  const currentKeys = new Set(units.map((unit) => unit.key));
  const changes = units.map((unit): UnitChange => {
    const tr = hashText(unit.tr);
    const en = hashText(unit.en);
    const previous = Object.hasOwn(lock.units, unit.key)
      ? lock.units[unit.key]
      : undefined;

    if (previous === undefined) {
      return { unit, kind: "added", tr, en, changedLanguage: null };
    }

    const trChanged = previous.tr !== tr;
    const enChanged = previous.en !== en;
    if (trChanged && enChanged) {
      return { unit, kind: "updated", tr, en, changedLanguage: null };
    }
    if (trChanged || enChanged) {
      return {
        unit,
        kind: "one-sided",
        tr,
        en,
        changedLanguage: trChanged ? "tr" : "en"
      };
    }
    return { unit, kind: "unchanged", tr, en, changedLanguage: null };
  });

  return {
    changes,
    removed: Object.keys(lock.units)
      .filter((key) => !currentKeys.has(key))
      .sort(compareText)
  };
}

export function staleTranslationIssue(change: UnitChange): Issue {
  const changed = change.changedLanguage === "tr" ? "tr" : "en";
  const leftBehind = changed === "tr" ? "en" : "tr";
  return makeIssue(
    "STALE_TRANSLATION",
    change.unit.file,
    change.unit.key,
    `${changed} changed; ${leftBehind} was left behind`
  );
}

export function checkLockChanges(changes: LockChanges): Issue[] {
  const issues: Issue[] = [];
  for (const change of changes.changes) {
    if (change.kind === "added") {
      issues.push(makeIssue(
        "LOCK_OUT_OF_DATE",
        change.unit.file,
        change.unit.key,
        "new unit, run approve"
      ));
    } else if (change.kind === "updated") {
      issues.push(makeIssue(
        "LOCK_OUT_OF_DATE",
        change.unit.file,
        change.unit.key,
        "both languages changed, run approve"
      ));
    } else if (change.kind === "one-sided") {
      issues.push(staleTranslationIssue(change));
    }
  }
  for (const key of changes.removed) {
    issues.push(makeIssue(
      "LOCK_OUT_OF_DATE",
      LOCK_FILE_NAME,
      key,
      "removed unit, run approve"
    ));
  }
  return issues;
}

export function writeLock(contentDir: string, changes: LockChanges): void {
  const entries = [...changes.changes]
    .sort((left, right) => compareText(left.unit.key, right.unit.key))
    .map((change): [string, { tr: string; en: string }] => [
      change.unit.key,
      { tr: change.tr, en: change.en }
    ]);
  const lock: LockFile = { version: 1, units: Object.fromEntries(entries) };
  writeFileSync(
    join(contentDir, LOCK_FILE_NAME),
    `${JSON.stringify(lock, null, 2)}\n`,
    "utf8"
  );
}
