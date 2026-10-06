import type { Issue } from "../issue.ts";
import { makeIssue } from "../issue.ts";
import type { ContentTree } from "../tree.ts";

export function checkNumbersInFreeText(tree: ContentTree): Issue[] {
  const issues: Issue[] = [];
  for (const entry of tree.caseStudies) {
    for (const section of ["problem", "decision", "cost", "result"] as const) {
      for (const language of ["tr", "en"] as const) {
        const numbers = entry.value[section][language].match(/\d+(?:[.,]\d+)*/g);
        if (numbers !== null) {
          issues.push(makeIssue(
            "NUMBER_IN_FREE_TEXT",
            entry.file,
            `/${section}/${language}`,
            `Numbers found in free text: ${numbers.join(", ")}; put numeric claims in metrics`,
            "warning"
          ));
        }
      }
    }
  }
  return issues;
}
