import { join } from "node:path";
import type { EvidenceList } from "../generated/evidence.ts";
import type { Issue } from "../issue.ts";
import { makeIssue } from "../issue.ts";
import type { ContentTree } from "../tree.ts";
import { isFile } from "../value.ts";

type Evidence = EvidenceList["items"][number];

function resolves(
  evidence: Evidence,
  tree: ContentTree,
  contentDir: string
): boolean {
  switch (evidence.type) {
    case "repository":
      return true;
    case "project":
      return tree.projects.items.some((item) => item.id === evidence.ref);
    case "case-study":
      return tree.caseStudies.some((entry) => entry.value.id === evidence.ref);
    case "certificate":
      return tree.certificates.items.some((item) => item.id === evidence.ref);
    case "decision-record":
      return isFile(join(contentDir, "decisions", `${evidence.ref}.yaml`));
    case "measurement-report":
      return isFile(join(contentDir, "measurements", `${evidence.ref}.yaml`));
  }
}

export function checkEvidenceTargets(tree: ContentTree, contentDir: string): Issue[] {
  const issues: Issue[] = [];
  tree.evidence.items.forEach((item, index) => {
    if (item.type !== "repository" && !resolves(item, tree, contentDir)) {
      issues.push(makeIssue(
        "EVIDENCE_TARGET_UNRESOLVED",
        "evidence.yaml",
        `/items/${index}/ref`,
        `Unresolved ${item.type} target "${item.ref}"`
      ));
    }
  });
  return issues;
}
