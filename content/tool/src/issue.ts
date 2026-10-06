export type IssueCode =
  | "YAML_PARSE"
  | "MISSING_FILE"
  | "SCHEMA"
  | "FORBIDDEN_FIELD"
  | "MISSING_TRANSLATION"
  | "SKILL_WITHOUT_EVIDENCE"
  | "CASE_STUDY_SECTION_MISSING"
  | "NUMERIC_CLAIM_WITHOUT_SOURCE"
  | "DUPLICATE_ID"
  | "ID_FILENAME_MISMATCH"
  | "UNKNOWN_REFERENCE"
  | "EVIDENCE_TARGET_UNRESOLVED"
  | "INVALID_PERIOD"
  | "UI_KEY_MISMATCH"
  | "UI_PLACEHOLDER_MISMATCH"
  | "STALE_TRANSLATION"
  | "LOCK_OUT_OF_DATE"
  | "NUMBER_IN_FREE_TEXT";

export type Severity = "error" | "warning";

export interface Issue {
  readonly code: IssueCode;
  readonly severity: Severity;
  readonly file: string;
  readonly path: string;
  readonly message: string;
}

export function makeIssue(
  code: IssueCode,
  file: string,
  path: string,
  message: string,
  severity: Severity = "error"
): Issue {
  return { code, severity, file, path, message };
}

export function compareText(left: string, right: string): number {
  return left < right ? -1 : left > right ? 1 : 0;
}

export function sortIssues(issues: readonly Issue[]): Issue[] {
  return [...issues].sort((left, right) =>
    compareText(left.file, right.file) ||
    compareText(left.path, right.path) ||
    compareText(left.code, right.code) ||
    compareText(left.message, right.message)
  );
}

export function deduplicateIssues(issues: readonly Issue[]): Issue[] {
  const seen = new Set<string>();
  return issues.filter((issue) => {
    const key = JSON.stringify([
      issue.code,
      issue.file,
      issue.path,
      issue.message
    ]);
    if (seen.has(key)) {
      return false;
    }
    seen.add(key);
    return true;
  });
}
