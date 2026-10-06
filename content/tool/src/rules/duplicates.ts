import type { Issue } from "../issue.ts";
import { makeIssue } from "../issue.ts";
import type { ContentTree } from "../tree.ts";

function checkIds(
  file: string,
  path: string,
  items: readonly { readonly id: string }[]
): Issue[] {
  const seen = new Set<string>();
  const issues: Issue[] = [];
  items.forEach((item, index) => {
    if (seen.has(item.id)) {
      issues.push(makeIssue(
        "DUPLICATE_ID",
        file,
        `${path}/${index}/id`,
        `Duplicate id "${item.id}"`
      ));
    }
    seen.add(item.id);
  });
  return issues;
}

export function checkDuplicateIds(tree: ContentTree): Issue[] {
  const issues: Issue[] = [];
  const lists = [
    { file: "skills.yaml", items: tree.skills.items },
    { file: "evidence.yaml", items: tree.evidence.items },
    { file: "experience.yaml", items: tree.experience.items },
    { file: "education.yaml", items: tree.education.items },
    { file: "certificates.yaml", items: tree.certificates.items },
    { file: "projects.yaml", items: tree.projects.items }
  ];
  for (const list of lists) {
    issues.push(...checkIds(list.file, "/items", list.items));
  }
  issues.push(...checkIds("profile.yaml", "/links", tree.profile.links ?? []));

  const seenCases = new Set<string>();
  for (const entry of tree.caseStudies) {
    issues.push(...checkIds(entry.file, "/metrics", entry.value.metrics ?? []));
    if (seenCases.has(entry.value.id)) {
      issues.push(makeIssue(
        "DUPLICATE_ID",
        entry.file,
        "/id",
        `Duplicate case study id "${entry.value.id}"`
      ));
    }
    seenCases.add(entry.value.id);
  }
  return issues;
}
