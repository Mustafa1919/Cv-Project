import { join } from "node:path";
import type { Issue } from "../issue.ts";
import { makeIssue } from "../issue.ts";
import type { ContentTree } from "../tree.ts";
import { isFile } from "../value.ts";

export function checkMetricSources(tree: ContentTree, contentDir: string): Issue[] {
  const issues: Issue[] = [];
  for (const entry of tree.caseStudies) {
    (entry.value.metrics ?? []).forEach((metric, index) => {
      if (
        "report" in metric.source &&
        !isFile(join(contentDir, "measurements", `${metric.source.report}.yaml`))
      ) {
        issues.push(makeIssue(
          "NUMERIC_CLAIM_WITHOUT_SOURCE",
          entry.file,
          `/metrics/${index}/source/report`,
          `Measurement report "${metric.source.report}" is missing`
        ));
      }
    });
  }
  return issues;
}
