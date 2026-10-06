import { analyzeContent } from "./analyze.ts";
import type { CheckOptions } from "./check.ts";
import type { Issue } from "./issue.ts";
import { compareText, deduplicateIssues, makeIssue, sortIssues } from "./issue.ts";
import { LOCK_FILE_NAME, staleTranslationIssue, writeLock } from "./lock.ts";

export interface ApproveOptions extends CheckOptions {
  readonly acceptOneSided?: readonly string[];
}

export interface ApproveResult {
  readonly written: boolean;
  readonly errors: readonly Issue[];
  readonly added: readonly string[];
  readonly updated: readonly string[];
  readonly acceptedOneSided: readonly string[];
  readonly removed: readonly string[];
}

export function approveContent(options: ApproveOptions): ApproveResult {
  const analysis = analyzeContent(options.contentDir, options.schemaDir);
  if (analysis.blockingErrors.length > 0) {
    return {
      written: false,
      errors: analysis.blockingErrors,
      added: [],
      updated: [],
      acceptedOneSided: [],
      removed: []
    };
  }

  const changes = analysis.lockChanges;
  if (changes === null) {
    throw new Error("Cannot approve content without a valid lock analysis");
  }

  const acceptedKeys = new Set(options.acceptOneSided ?? []);
  const oneSided = new Map(
    changes.changes
      .filter((change) => change.kind === "one-sided")
      .map((change) => [change.unit.key, change] as const)
  );
  const errors: Issue[] = [];

  for (const [key, change] of oneSided) {
    if (!acceptedKeys.has(key)) {
      errors.push(staleTranslationIssue(change));
    }
  }

  for (const key of acceptedKeys) {
    if (!oneSided.has(key)) {
      const unit = changes.changes.find((change) => change.unit.key === key)?.unit;
      errors.push(makeIssue(
        "LOCK_OUT_OF_DATE",
        unit?.file ?? LOCK_FILE_NAME,
        key,
        "not a one-sided change"
      ));
    }
  }

  const added = changes.changes
    .filter((change) => change.kind === "added")
    .map((change) => change.unit.key)
    .sort(compareText);
  const updated = changes.changes
    .filter((change) => change.kind === "updated")
    .map((change) => change.unit.key)
    .sort(compareText);
  const acceptedOneSided = [...oneSided.keys()]
    .filter((key) => acceptedKeys.has(key))
    .sort(compareText);
  const removed = [...changes.removed].sort(compareText);

  if (errors.length > 0) {
    return {
      written: false,
      errors: sortIssues(deduplicateIssues(errors)),
      added,
      updated,
      acceptedOneSided,
      removed
    };
  }

  writeLock(options.contentDir, changes);
  return {
    written: true,
    errors: [],
    added,
    updated,
    acceptedOneSided,
    removed
  };
}
