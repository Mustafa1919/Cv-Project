import type { Issue } from "../issue.ts";
import { makeIssue } from "../issue.ts";
import type { ContentTree } from "../tree.ts";

export function checkCaseStudyFilenames(tree: ContentTree): Issue[] {
  const issues: Issue[] = [];
  for (const entry of tree.caseStudies) {
    const expected = entry.file.slice("case-studies/".length, -".yaml".length);
    if (entry.value.id !== expected) {
      issues.push(makeIssue(
        "ID_FILENAME_MISMATCH",
        entry.file,
        "/id",
        `Case study id "${entry.value.id}" does not match filename "${expected}"`
      ));
    }
  }
  return issues;
}
