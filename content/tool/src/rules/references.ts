import type { Issue } from "../issue.ts";
import { makeIssue } from "../issue.ts";
import type { ContentTree } from "../tree.ts";

function checkReferences(
  file: string,
  path: string,
  references: readonly string[],
  targets: ReadonlySet<string>,
  kind: string
): Issue[] {
  const issues: Issue[] = [];
  references.forEach((reference, index) => {
    if (!targets.has(reference)) {
      issues.push(makeIssue(
        "UNKNOWN_REFERENCE",
        file,
        `${path}/${index}`,
        `Unknown ${kind} id "${reference}"`
      ));
    }
  });
  return issues;
}

export function checkUnknownReferences(tree: ContentTree): Issue[] {
  const evidenceIds = new Set(tree.evidence.items.map((item) => item.id));
  const skillIds = new Set(tree.skills.items.map((item) => item.id));
  const projectIds = new Set(tree.projects.items.map((item) => item.id));
  const issues: Issue[] = [];

  tree.skills.items.forEach((item, index) => {
    issues.push(...checkReferences(
      "skills.yaml",
      `/items/${index}/evidence`,
      item.evidence,
      evidenceIds,
      "evidence"
    ));
  });

  tree.profile.highlights.forEach((item, index) => {
    if (!evidenceIds.has(item.evidence)) {
      issues.push(makeIssue(
        "UNKNOWN_REFERENCE",
        "profile.yaml",
        `/highlights/${index}/evidence`,
        `Unknown evidence id "${item.evidence}"`
      ));
    }
  });

  tree.experience.items.forEach((item, index) => {
    issues.push(...checkReferences(
      "experience.yaml",
      `/items/${index}/skills`,
      item.skills ?? [],
      skillIds,
      "skill"
    ));
  });

  tree.projects.items.forEach((item, index) => {
    issues.push(...checkReferences(
      "projects.yaml",
      `/items/${index}/skills`,
      item.skills ?? [],
      skillIds,
      "skill"
    ));
  });

  for (const entry of tree.caseStudies) {
    if (!projectIds.has(entry.value.project)) {
      issues.push(makeIssue(
        "UNKNOWN_REFERENCE",
        entry.file,
        "/project",
        `Unknown project id "${entry.value.project}"`
      ));
    }
  }

  return issues;
}
