import { readFileSync, readdirSync } from "node:fs";
import { join } from "node:path";
import { parse } from "yaml";
import { checkContent } from "vitrin-content-tool/src/check.ts";
import type { Profile } from "vitrin-content-tool/src/generated/profile.ts";
import type { SkillClaims } from "vitrin-content-tool/src/generated/skills.ts";
import type { EvidenceList } from "vitrin-content-tool/src/generated/evidence.ts";
import type { ExperienceList } from "vitrin-content-tool/src/generated/experience.ts";
import type { EducationList } from "vitrin-content-tool/src/generated/education.ts";
import type { CertificateList } from "vitrin-content-tool/src/generated/certificates.ts";
import type { ProjectList } from "vitrin-content-tool/src/generated/projects.ts";
import type { CaseStudy } from "vitrin-content-tool/src/generated/case-study.ts";
import type { UiDictionary } from "vitrin-content-tool/src/generated/ui.ts";
import { siteConfig } from "./config.ts";

export interface SiteContent {
  readonly profile: Profile;
  readonly skills: SkillClaims;
  readonly evidence: EvidenceList;
  readonly experience: ExperienceList;
  readonly education: EducationList;
  readonly certificates: CertificateList;
  readonly projects: ProjectList;
  readonly caseStudies: readonly CaseStudy[];
  readonly ui: {
    readonly tr: UiDictionary;
    readonly en: UiDictionary;
  };
  readonly evidenceById: ReadonlyMap<string, EvidenceList["items"][number]>;
  readonly projectsById: ReadonlyMap<string, ProjectList["items"][number]>;
  readonly caseStudiesById: ReadonlyMap<string, CaseStudy>;
  readonly caseStudiesByProjectId: ReadonlyMap<string, readonly CaseStudy[]>;
}

let cachedContent: SiteContent | undefined;

function readYaml<T>(relativePath: string): T {
  const text = readFileSync(join(siteConfig.contentDir, relativePath), "utf8");
  const value: unknown = parse(text, {
    version: "1.2",
    schema: "core",
    uniqueKeys: true,
    strict: true,
  });

  // The content tool validates these exact files before this typed boundary.
  return value as T;
}

export function loadContent(): SiteContent {
  if (cachedContent !== undefined) {
    return cachedContent;
  }

  const result = checkContent({
    contentDir: siteConfig.contentDir,
    schemaDir: siteConfig.schemaDir,
  });

  if (result.errors.length > 0) {
    throw new Error(
      [
        "Content validation failed:",
        ...result.errors.map(
          (issue) =>
            `${issue.code} ${issue.file} ${issue.path}: ${issue.message}`,
        ),
      ].join("\n"),
    );
  }

  for (const issue of result.warnings) {
    console.warn(
      `${issue.code} ${issue.file} ${issue.path}: ${issue.message}`,
    );
  }

  const profile = readYaml<Profile>("profile.yaml");
  const skills = readYaml<SkillClaims>("skills.yaml");
  const evidence = readYaml<EvidenceList>("evidence.yaml");
  const experience = readYaml<ExperienceList>("experience.yaml");
  const education = readYaml<EducationList>("education.yaml");
  const certificates = readYaml<CertificateList>("certificates.yaml");
  const projects = readYaml<ProjectList>("projects.yaml");

  const caseStudyDir = join(siteConfig.contentDir, "case-studies");
  let caseStudyFiles: string[];

  try {
    caseStudyFiles = readdirSync(caseStudyDir, { withFileTypes: true })
      .filter((entry) => entry.isFile() && entry.name.endsWith(".yaml"))
      .map((entry) => entry.name)
      .sort();
  } catch (error: unknown) {
    if (
      error instanceof Error &&
      "code" in error &&
      error.code === "ENOENT"
    ) {
      caseStudyFiles = [];
    } else {
      throw error;
    }
  }

  const caseStudies = caseStudyFiles.map((file) =>
    readYaml<CaseStudy>(join("case-studies", file)),
  );
  const caseStudiesByProjectId = new Map<string, CaseStudy[]>();

  for (const study of caseStudies) {
    const group = caseStudiesByProjectId.get(study.project);
    if (group === undefined) {
      caseStudiesByProjectId.set(study.project, [study]);
    } else {
      group.push(study);
    }
  }

  cachedContent = {
    profile,
    skills,
    evidence,
    experience,
    education,
    certificates,
    projects,
    caseStudies,
    ui: {
      tr: readYaml<UiDictionary>(join("ui", "tr.yaml")),
      en: readYaml<UiDictionary>(join("ui", "en.yaml")),
    },
    evidenceById: new Map(evidence.items.map((item) => [item.id, item])),
    projectsById: new Map(projects.items.map((item) => [item.id, item])),
    caseStudiesById: new Map(caseStudies.map((item) => [item.id, item])),
    caseStudiesByProjectId,
  };

  return cachedContent;
}
