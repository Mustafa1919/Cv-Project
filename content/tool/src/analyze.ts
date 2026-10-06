import type { Issue } from "./issue.ts";
import { deduplicateIssues, sortIssues } from "./issue.ts";
import { loadContent } from "./load.ts";
import type { LockChanges } from "./lock.ts";
import { checkLockChanges, classifyUnits, readLock } from "./lock.ts";
import { buildTree } from "./tree.ts";
import { collectUnits } from "./units.ts";
import { checkUiKeys, checkUiPlaceholders, flattenUi } from "./ui.ts";
import { checkDuplicateIds } from "./rules/duplicates.ts";
import { checkCaseStudyFilenames } from "./rules/filenames.ts";
import { checkUnknownReferences } from "./rules/references.ts";
import { checkEvidenceTargets } from "./rules/evidence.ts";
import { checkMetricSources } from "./rules/sources.ts";
import { checkPeriods } from "./rules/periods.ts";
import { checkNumbersInFreeText } from "./rules/free-text.ts";

export interface Analysis {
  readonly errors: readonly Issue[];
  readonly warnings: readonly Issue[];
  readonly blockingErrors: readonly Issue[];
  readonly lockChanges: LockChanges | null;
}

export function analyzeContent(contentDir: string, schemaDir: string): Analysis {
  const loaded = loadContent(contentDir, schemaDir);
  if (loaded.errors.length > 0) {
    const errors = sortIssues(loaded.errors);
    return {
      errors,
      warnings: [],
      blockingErrors: errors,
      lockChanges: null
    };
  }

  const tree = buildTree(loaded.files);
  const trLeaves = flattenUi(tree.uiTr);
  const enLeaves = flattenUi(tree.uiEn);
  const semanticIssues = [
    ...checkDuplicateIds(tree),
    ...checkCaseStudyFilenames(tree),
    ...checkUnknownReferences(tree),
    ...checkEvidenceTargets(tree, contentDir),
    ...checkMetricSources(tree, contentDir),
    ...checkPeriods(tree),
    ...checkUiKeys(trLeaves, enLeaves),
    ...checkUiPlaceholders(trLeaves, enLeaves),
    ...checkNumbersInFreeText(tree)
  ];

  const blockingErrors = semanticIssues.filter((issue) => issue.severity === "error");
  const warnings = semanticIssues.filter((issue) => issue.severity === "warning");
  const units = collectUnits(loaded.files, tree.uiTr, tree.uiEn);
  const lockResult = readLock(contentDir);

  if (!lockResult.valid) {
    const errors = sortIssues(deduplicateIssues([...blockingErrors, lockResult.issue]));
    return {
      errors,
      warnings: sortIssues(deduplicateIssues(warnings)),
      blockingErrors: errors,
      lockChanges: null
    };
  }

  const lockChanges = classifyUnits(units, lockResult.lock);
  return {
    errors: sortIssues(deduplicateIssues([
      ...blockingErrors,
      ...checkLockChanges(lockChanges)
    ])),
    warnings: sortIssues(deduplicateIssues(warnings)),
    blockingErrors: sortIssues(deduplicateIssues(blockingErrors)),
    lockChanges
  };
}
