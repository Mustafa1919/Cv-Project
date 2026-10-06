import type { Profile } from "./generated/profile.ts";
import type { SkillClaims } from "./generated/skills.ts";
import type { EvidenceList } from "./generated/evidence.ts";
import type { ExperienceList } from "./generated/experience.ts";
import type { EducationList } from "./generated/education.ts";
import type { CertificateList } from "./generated/certificates.ts";
import type { ProjectList } from "./generated/projects.ts";
import type { CaseStudy } from "./generated/case-study.ts";
import type { UiDictionary } from "./generated/ui.ts";
import type { ParsedFile } from "./load.ts";

export interface CaseStudyFile {
  readonly file: string;
  readonly value: CaseStudy;
}

export interface ContentTree {
  readonly profile: Profile;
  readonly skills: SkillClaims;
  readonly evidence: EvidenceList;
  readonly experience: ExperienceList;
  readonly education: EducationList;
  readonly certificates: CertificateList;
  readonly projects: ProjectList;
  readonly caseStudies: readonly CaseStudyFile[];
  readonly uiTr: UiDictionary;
  readonly uiEn: UiDictionary;
}

function valueFor(files: readonly ParsedFile[], name: string): unknown {
  const entry = files.find((file) => file.file === name);
  if (entry === undefined) {
    throw new Error(`Validated content is missing ${name}`);
  }
  return entry.value;
}

export function buildTree(files: readonly ParsedFile[]): ContentTree {
  return {
    profile: valueFor(files, "profile.yaml") as Profile,
    skills: valueFor(files, "skills.yaml") as SkillClaims,
    evidence: valueFor(files, "evidence.yaml") as EvidenceList,
    experience: valueFor(files, "experience.yaml") as ExperienceList,
    education: valueFor(files, "education.yaml") as EducationList,
    certificates: valueFor(files, "certificates.yaml") as CertificateList,
    projects: valueFor(files, "projects.yaml") as ProjectList,
    caseStudies: files
      .filter((file) => file.file.startsWith("case-studies/"))
      .map((file) => ({ file: file.file, value: file.value as CaseStudy })),
    uiTr: valueFor(files, "ui/tr.yaml") as UiDictionary,
    uiEn: valueFor(files, "ui/en.yaml") as UiDictionary
  };
}
