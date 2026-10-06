import type { Issue } from "../issue.ts";
import { makeIssue } from "../issue.ts";
import type { ContentTree } from "../tree.ts";

export function checkPeriods(tree: ContentTree): Issue[] {
  const issues: Issue[] = [];
  tree.experience.items.forEach((item, index) => {
    if (item.end !== undefined && item.end < item.start) {
      issues.push(makeIssue(
        "INVALID_PERIOD",
        "experience.yaml",
        `/items/${index}/end`,
        `End "${item.end}" is earlier than start "${item.start}"`
      ));
    }
  });
  tree.education.items.forEach((item, index) => {
    if (item.endYear !== undefined && item.endYear < item.startYear) {
      issues.push(makeIssue(
        "INVALID_PERIOD",
        "education.yaml",
        `/items/${index}/endYear`,
        `End year ${item.endYear} is earlier than start year ${item.startYear}`
      ));
    }
  });
  return issues;
}
