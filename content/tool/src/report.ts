import type { ApproveResult } from "./approve.ts";
import type { CheckResult } from "./check.ts";
import type { Issue } from "./issue.ts";
import { sortIssues } from "./issue.ts";

function issueLine(issue: Issue): string {
  const path = issue.path === "" ? "" : ` ${issue.path}`;
  return `${issue.severity} ${issue.code} ${issue.file}${path}: ${issue.message}`;
}

export function checkText(result: CheckResult): string {
  const lines = sortIssues([...result.errors, ...result.warnings]).map(issueLine);
  lines.push(`${result.errors.length} errors, ${result.warnings.length} warnings`);
  return `${lines.join("\n")}\n`;
}

export function approveText(result: ApproveResult): string {
  const lines = result.errors.map(issueLine);
  lines.push(
    result.written
      ? `${result.added.length} added, ${result.updated.length} updated, ${result.acceptedOneSided.length} accepted, ${result.removed.length} removed`
      : "lock not written"
  );
  return `${lines.join("\n")}\n`;
}
